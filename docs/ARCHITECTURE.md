# School Administration Agent — Architecture

This document is the deliverable required by spec §49 before implementation starts.
It covers: refined architecture, domain model, case state machine, major interfaces,
database schema, repository structure, implementation milestones, and unresolved risks.

Source spec: `docs/SPEC.md` (pasted requirements document).

---

## 1. Refined architecture

### 1.1 Shape

A **modular monolith** (Kotlin + Spring Boot) plus one isolated **Playwright worker**
container. PostgreSQL is the single source of truth. Google Calendar and WhatsApp are
*projections* of internal state, never authoritative.

```
┌─────────────┐   ┌──────────────────────────────────────────────────┐
│  Angular     │   │                school-api (Kotlin)                │
│  school-web  │──▶│  api ─ auth ─ settings ─ health ─ audit           │
└─────────────┘   │                                                  │
                  │  ┌──────────── ingestion pipeline ─────────────┐  │
                  │  │ connectors → source dedup → extraction →     │  │
                  │  │ case resolution → investigation (crawler) →  │  │
                  │  │ case reasoning → mutation apply → projection │  │
                  │  └──────────────────────────────────────────────┘  │
                  │           │            │              │            │
                  └───────────┼────────────┼──────────────┼────────────┘
                              ▼            ▼              ▼
                        PostgreSQL     Gmail API     Google Calendar
                        (truth)        Elternportal  WhatsApp Gateway
                                        HTTP crawler      │
                                   ┌───────────────┐      │
                                   │ playwright-   │◀─────┘ (only when HTTP
                                   │ worker (Node) │       parsing is not enough)
                                   └───────────────┘
```

### 1.2 Ingestion pipeline (how a source becomes state)

Each stage is idempotent and independently retryable. Stages communicate only through
the database, so a crash at any point loses nothing.

1. **Acquire** — a connector (`GmailConnector`, `ElternportalClient`, manual upload)
   produces a raw item and a deterministic `dedupKey` (`sourceType:externalId`).
   If the key exists, stop — this is a re-poll, not a new source.
2. **Store** — raw content (MIME, HTML, PDF bytes) written to the document store;
   SHA-256 content hash recorded. Identical content arriving through two channels
   (e.g. portal letter + notification email) collapses here.
3. **Extract (cheap model)** — classification, relevance, dates/amounts/people,
   link candidates. Output is structured `Evidence`, never free text. Obvious noise
   ("thank you for the summer celebration") ends here with `SILENT`, no reasoning model.
4. **Case resolution** — attach to an existing `Case` or create one:
   a. *Deterministic first*: connector-provided thread/topic IDs, known sender+subject
      patterns, an explicit `case.dedupKey`.
   b. *Reasoning model second*: given candidate open cases (title, summary, recent
      evidence) and the new evidence, choose `NEW_CASE | ATTACH(caseId) | UNSURE`.
      `UNSURE` → `NEEDS_REVIEW` (never guess).
5. **Investigate (deterministic + cheap model)** — bounded crawl following
   semantically relevant links (`Anmeldung`, `Termine`, `Kosten`, …); PDF parsing;
   optional read-only browser rendering via the worker. Each fetch is recorded as an
   `InvestigationStep`. Limits: max depth, pages, AI calls, tokens per case.
6. **Reason (strong model)** — receives *distilled evidence* plus the current case
   state, emits a **mutation set** (see §4): add actions/events/requirements, move
   dates, cancel, change deadlines. It can request original sources by id.
7. **Apply** — mutations are validated and committed transactionally; every mutation
   writes a `CaseChange` row (before/after). Dedup keys prevent re-application on
   re-runs. Projector updates Google Calendar; notifier decides
   IMMEDIATE / DIGEST / SILENT per family policy.

### Native school records

`SchoolSession` binds a fresh login to a verified child before reading letters or native
sections. `SchoolRecordFeed` distinguishes AVAILABLE (including a proven empty list),
UNAVAILABLE (disabled/denied), and FAILED (network/schema errors). Both providers
normalize exams, forgotten homework/material reports and payment requests. No class,
school or account IDs are embedded in application code.

`SchoolRecordIngestionService` persists child/account/student-scoped native identities and
content hashes. Unchanged successfully worded records preserve parent completion/read
state and make no AI calls. One cached language pass supplies English wording; native exam/report dates remain
authoritative. Unknown payment deadlines need literal source evidence. Sources and
case changes retain provenance and transitions write history/audit. Schema-validated
language answers are cached; invalid saved answers are evicted. During AI outages the
native facts remain visible with original wording and a persisted enrichment flag;
wording repair preserves Done/read state and never reannounces unchanged facts.
Generated wording has a persisted version: changes to the interpreter repair older
copy once through the same audited enrichment path, without changing source hashes
or native identity. Unexplained parenthetical timetable reference codes stay in the
original source and are excluded from translation input; exam titles contain only
the subject and assessment, without parenthetical interpretations.
Existing approved WhatsApp templates handle these cases.

Elternportal reads the dedicated class exam table, never the mixed general calendar.
Its calendar JSON IDs are positional, so rows without native IDs use a date/topic identity:
a moved exam withdraws the previous date and creates its replacement. Only a verified
complete class exam snapshot may withdraw future exams. Forgotten reports are historical
`SCHOOL_REPORT` events, not invented bring-item tasks or tomorrow reminders. Missing
payments/reports never imply completion or cancellation. Nonempty payment rows require
a stable native identifier; unfamiliar layouts fail visibly and await parser refinement.
Schulmanager uses student-filtered exam/classbook endpoints and sibling-filtered unpaid
invoices. Disabled modules and school-denied classbook access remain unavailable and do
not interrupt letters or other accessible sections. Letter last-sync/connection health
is independent of optional section failures; per-section status includes its last success.
The first successful snapshot of each section establishes a quiet baseline, even for
previously configured children. Later historical reports older than three days stay
quiet. Exams are requested from today forward; an empty Elternportal exam timetable
requires an explicit empty marker and table headers are ignored.

Elternportal name/native pupil ID establishes identity; the configured class only guards
the exam timetable. A class mismatch disables that feed without blocking letters or
changing record keys. Shared-account operations serialize viewing context, and the
returned letter page and document access are checked against the selected pupil.

The native record row is a durable side-effect outbox with separate notification and
calendar pending flags. Quiet-import suppression, enqueue and notification acknowledgement
commit atomically. Updates, withdrawals and deletion failures retry every five
minutes independently of school access, including inactive records. Projection errors
retain the Google ID and remain pending; school reports never create Google appointments.
Timetable changes use the existing information template with explicit cancellation/move
wording. The existing letter-score sweep preserves explicit native priorities by source
provenance. Status/reasons are visible per child in Settings, including on phones.

### 1.3 Deterministic vs AI responsibility split (spec §10)

| Deterministic code                       | AI (via ModelRouter)                    |
|------------------------------------------|-----------------------------------------|
| HTTP fetch, redirects, status codes      | is this link relevant?                  |
| MIME parse, attachment extraction        | does this page contain obligations?     |
| PDF text extraction, hashing             | which facts matter (→ Evidence)?        |
| URL canonicalization, dedup keys         | new case or update to existing?         |
| Crawl bounds, robots/budget enforcement  | corrections vs new information?         |
| DB writes, transactions, idempotency     | mutation set for state change           |
| Calendar/WhatsApp API calls              | notification level + message drafting   |

The AI never receives credentials and never performs I/O on external systems; it
emits *decisions* that deterministic code executes against an allow-listed tool set
(`fetchPage`, `getPortalMessage`, `readDocument` — spec §31).

### 1.4 Prompt-injection containment (spec §32)

All external text (mail bodies, PDFs, pages, portal messages) is stored and passed to
models as **quoted data inside a single dedicated field**, never concatenated into
system instructions. Model output must validate against a strict JSON schema;
anything failing validation is treated as an AI-category failure, not policy.
No tool exists that can write to external systems, so injected instructions have no
consequential capability to hijack. v1 additionally hard-disables external writes at
the automation-toggle level (spec §45).

---

## 2. Domain model

Entities (all UUID PKs, all tables carry `created_at`):

| Entity | Purpose | Key fields |
|---|---|---|
| `AppUser` | Morgan, Casey | name (unique), passwordHash, whatsappNumber |
| `Child` | Jamie (extensible to siblings) | name, school, className, gmailAddress, googleCalendarId |
| `SchoolCase` | one real school matter (spec §7) | childId, title, summary, category, status, importance, confidence, investigationComplete, investigationNotes, dedupKey (unique) |
| `CaseStatusEntry` | full case history (spec §8) | caseId, fromStatus, toStatus, reason, actor |
| `Source` | every acquired raw object | caseId?, sourceType, externalId, dedupKey (unique), contentHash, canonicalUrl, storageRef, fetchStatus |
| `Evidence` | distilled facts from one source | caseId, sourceId, kind, subject, payload (typed JSON), confidence, extractedBy, dedupKey |
| `Investigation` | one crawl session for a case | caseId, status, pagesVisited, aiCalls, limitsHit |
| `InvestigationStep` | per-action trace | investigationId, stepType, target, outcome, detail |
| `ParentAction` | what a parent must do | caseId, title, dueAt, assignedTo?, status, externalUrl, recommendedInput, dedupKey |
| `ChildRequirement` | what Jamie must do | caseId, description, relevantOn, status, dedupKey |
| `SchoolEvent` | calendar-projected event | caseId, eventType, start/end, status, googleEventId, googleSyncStatus, supersededBy, dedupKey |
| `CaseChange` | mutation log — the delta history | caseId, changeType (ADD/MOVE/REMOVE/CHANGE/CANCEL/REOPEN), targetType, targetId?, before, after, evidenceId? |
| `Notification` | every outbound message | caseId?, level, channel, recipient, status, dedupKey |
| `AuditEntry` | system action trail (spec §28) | caseId?, actor (SYSTEM/AI/MORGAN/CASEY), action, detail |
| `ConnectorHealth` | per-connector heartbeat | connector (pk), status, lastSuccessAt, lastFailureAt, consecutiveFailures |
| `ModelConfig` | role→model routing, editable | role (unique), provider, modelName, reasoningEffort |
| `AiUsage` | cost tracking (spec §41) | caseId?, role, model, inputTokens, outputTokens, calls, estimatedCost |
| `FamilyRule` | explicit policy/feedback (spec §35) | ruleKey, value, enabled |
| `Setting` | misc kv config | key (pk), value |
| `SecretRef` | encrypted secret storage | key (pk), cipherText |

Enums are stored as `text` (not DB enums) to keep migrations cheap.

---

## 3. Case state machine

```
                    ┌─────────────────────────────────────────┐
                    │          (new evidence arrives)         │
                    ▼                                         │
 NEW ─▶ INGESTING ─▶ INVESTIGATING ─▶ READY_FOR_REASONING ────┤
                    ▲      ▲                                     │
                    │      │ retry            ┌─────────────────┴───┐
                    │      └── FAILED ◀───────┤ reasoning outcome   │
                    │                          ▼                   ▼
                    │                    ACTION_REQUIRED    INFORMATION_ONLY
                    │                          │                   │
                    │                          ▼                   ▼
                    ├─────────────────── WAITING_FOR_PARENT      COMPLETED
                    │                          │
                    └──────────────────────────┘
  any state ─▶ NEEDS_REVIEW (ambiguity, low confidence, failed investigation)
  any state ─▶ CANCELLED (school cancelled the matter)
```

Transition rules (enforced in `CaseStateMachine`, pure Kotlin, unit-tested):

| From | To | Guard |
|---|---|---|
| NEW | INGESTING | raw source stored |
| INGESTING | INVESTIGATING | extraction produced link candidates or attachments |
| INGESTING | READY_FOR_REASONING | self-contained source, nothing to crawl |
| INVESTIGATING | READY_FOR_REASONING | investigation complete or limits reached |
| INVESTIGATING | FAILED | non-transient failure after retries (spec §40) |
| INVESTIGATING | NEEDS_REVIEW | important source unreachable (spec §18) |
| READY_FOR_REASONING | ACTION_REQUIRED | mutation set contains ≥1 open parent action |
| READY_FOR_REASONING | INFORMATION_ONLY | no parent action, possibly events/info |
| ACTION_REQUIRED | WAITING_FOR_PARENT | immediate notification sent |
| WAITING_FOR_PARENT | COMPLETED | all actions COMPLETED/CANCELLED |
| WAITING_FOR_PARENT | ACTION_REQUIRED | new action added |
| INFORMATION_ONLY | COMPLETED | event passed / archived |
| NEEDS_REVIEW | INVESTIGATING, COMPLETED | manual resolve via frontend |
| FAILED | INVESTIGATING | retry with backoff |
| COMPLETED, CANCELLED, INFORMATION_ONLY, ACTION_REQUIRED, WAITING_FOR_PARENT | INVESTIGATING | **reopen**: new source attached to this case |

Every transition writes `CaseStatusEntry`; state is never mutated without history.

---

## 4. How new evidence mutates state (spec §23)

The reasoning model outputs a **mutation set** — a typed list, validated against a
schema, e.g.:

```json
{ "mutations": [
  { "type": "MOVE_EVENT", "eventKey": "wandertag", "newStart": "2026-10-12T08:00" },
  { "type": "ADD_CHILD_REQUIREMENT", "description": "Sportschuhe", "relevantOn": "2026-10-12" },
  { "type": "CHANGE_DEADLINE", "actionKey": "schullandheim-consent", "newDueAt": "2026-10-02" },
  { "type": "CANCEL_EVENT", "eventKey": "sportfest-original" },
  { "type": "ADD_PARENT_ACTION", "title": "Confirm participation", "dueAt": "2026-09-29",
    "externalUrl": "https://...", "recommendedInput": { "participation": "yes" } }
] }
```

Application is deterministic: each mutation is resolved against current state by
`dedupKey`/`eventKey` (upsert semantics), the change is written to `CaseChange`
(before/after JSON), and dependent projections run (calendar upsert — never create
duplicate Google events; WhatsApp message emphasizes the **delta**, not the full state).

---

## 5. Major interfaces (ports)

Behind Spring interfaces so integrations stay mockable and replaceable:

```kotlin
interface MailConnector   { fun poll(): List<RawMail>; fun fetchAttachment(id: String): StoredDocument }
interface PortalClient    { fun listNewItems(): List<PortalItem>; fun fetch(item: PortalItem): StoredDocument }
interface Crawler         { fun fetch(url: URI): FetchedPage        // HTTP+parser path
                            fun relevantLinks(page: FetchedPage): List<URI> }
interface BrowserWorker   { fun render(url: URI): RenderedPage      // Playwright path, read-only
                            fun describeForm(url: URI): FormDescription }
interface ModelRouter     { fun extract(req: ExtractionRequest): ExtractionResult
                            fun reason(req: ReasoningRequest): MutationSet
                            fun vision(req: VisionRequest): ExtractionResult }
interface CalendarProjector { fun upsert(evt: SchoolEvent): SyncResult; fun delete(googleEventId: String) }
interface Notifier        { fun send(msg: OutboundMessage): SendResult }
interface DocumentStore   { fun put(content: ByteArray, meta: DocMeta): StoredDocument; fun get(ref: String): StoredDocument }
interface SecretVault     { fun store(key: String, value: String); fun read(key: String): String }
```

`ModelRouter` reads role→model mapping from `ModelConfig` (DB, frontend-editable,
spec §14) and logs every call to `AiUsage`.

---

## 6. Database schema

PostgreSQL; Flyway versioned migrations (`V1__…`, `V2__…`). Full DDL lives in
`backend/src/main/resources/db/migration/`. Summary of V1:

- **Identity**: `app_user`, `child`
- **Case core**: `school_case`, `case_status_entry`
- **Acquisition**: `source` (unique `dedup_key`; unique `(source_type, external_id)`)
- **Knowledge**: `evidence` (unique `dedup_key`), `investigation`, `investigation_step`
- **Obligations**: `parent_action` (unique `dedup_key`), `child_requirement`
- **Time**: `school_event` (unique `dedup_key`, unique nullable `google_event_id`)
- **History/projection**: `case_change`, `notification` (unique `dedup_key`)
- **Ops**: `audit_entry`, `connector_health`, `model_config`, `ai_usage`, `family_rule`, `setting`, `secret_ref`

Idempotency is enforced by unique constraints + upserts (`ON CONFLICT DO NOTHING`
semantics at service level), never by "hope the poll didn't rerun".

---

## 7. Repository structure

```
school-agent/
├── docs/                    # SPEC.md, ARCHITECTURE.md (this file)
├── PROGRESS.md              # living TODO/progress file (spec §47)
├── deploy/docker-compose.yml
├── backend/                 # Kotlin + Spring Boot modular monolith (Maven)
│   └── src/main/kotlin/de/schoolagent/
│       ├── common/          # ids, hashing, clock, json
│       ├── security/        # auth (basic for v1), users
│       ├── domain/          # entities, enums, CaseStateMachine, repositories
│       ├── ingestion/       # pipeline orchestration
│       ├── connectors/      # gmail/, elternportal/, calendar/
│       ├── crawler/         # HTTP crawler, link relevance
│       ├── browser/         # client to playwright-worker
│       ├── ai/              # ModelRouter, prompts, schema validation
│       ├── actions/, events/ # services, calendar projection
│       ├── notifications/   # whatsapp/, digest
│       ├── audit/, health/, settings/
│       └── api/             # REST controllers + DTOs
├── frontend/                # Angular control center
└── playwright-worker/       # Node/TS read-only browser service
```

---

## 8. Implementation milestones

The spec's phases (§46) map to milestones tracked in `PROGRESS.md`:

- **M0 (Phase 0)**: repo, Docker Compose, config, secrets baseline, CI-less test
  baseline (`mvn test` green), auth skeleton.
- **M1 (Phase 1)**: domain entities, state machine, V1 schema, repos + tests. ← current
- **M2 (Phase 2)**: Google OAuth, Gmail polling, attachments, Calendar projection.
- **M3 (Phase 3)**: document store, PDF/HTML extraction, crawler, dedup, evidence.
- **M4 (Phase 4)**: ModelRouter + DB-backed config + usage logging.
- **M5 (Phase 5)**: end-to-end case reasoning on fixtures (email → case → actions).
- **M6 (Phase 6)**: Elternportal client + dedup against Gmail notifications.
- **M7 (Phase 7)**: playwright-worker.
- **M8 (Phase 8)**: Angular frontend (Dashboard/Calendar/Actions/Cases/Settings/Audit).
- **M9 (Phase 9)**: WhatsApp in/out, ownership commands.
- **M10 (Phase 10)**: retries, health monitoring, idempotency stress tests.
- **M11 (Phase 11)**: feedback → family rules.

Each milestone ends with tests green and a PROGRESS.md update.

---

## 9. Unresolved technical risks

1. **Elternportal has no official API.** Scraping may break on portal updates; session
   lifetime unknown; ToS must be respected (read-only, personal account, low frequency).
   *Mitigation*: notification emails as trigger/fallback; Playwright fallback;
   connector health alerts (spec §30). **Decision needed before M6**: confirm the
   family's portal URL/instance and acceptable access pattern.
2. **WhatsApp channel.** Official Cloud API needs a Meta business account + public
   webhook HTTPS. For a home deployment this implies a tunnel (cloudflared) or hosted
   relay. **Decision needed before M9**: Cloud API vs. unofficial bridge (ToS risk).
3. **Google OAuth consent scope.** `gmail.readonly` + `calendar` are restricted/sensitive
   scopes. Unverified personal apps can use them in "testing" mode, but refresh tokens
   then expire every ~7 days; publishing "in production" unverified keeps them
   long-lived with a warning screen. Plan: production-unverified, family-only.
4. **Case clustering quality.** Matching a correction to the right case is the hardest
   AI task. *Mitigation*: deterministic keys first, conservative `UNSURE → NEEDS_REVIEW`,
   feedback rules (M11).
5. **Scanned/image PDFs** need OCR or vision-model treatment; cost/quality TBD at M3.
6. **Cost control.** Cheap-model gate before reasoning; per-case budgets; AiUsage
   monitoring. Thresholds tuned with real traffic after M5.
7. **German date/locale quirks** (dd.mm.yyyy, "nächste Woche", Bavarian school terms)
   need solid extraction prompts + explicit timezone handling (Europe/Berlin everywhere).
8. **Home-deployment availability.** Runs on a home server/NAS; if it is down, we may
   miss deadlines — hence connector health + "haven't checked portal in 12h" alerts are
   MVP (spec §30), and digest mentions staleness.


## School provider boundary (September 2026)

`SchoolConnector.connect` returns an isolated `SchoolSession` exposing school identity,
child-scoped notices and their documents. Elternportal and Schulmanager implementations
translate their own authentication and payloads into this contract. `SchoolIngestionService`
handles deduplication, complete document acquisition, transactional case creation,
provenance and the existing investigation/reasoning pipeline. Higher layers do not
call provider endpoints. Gmail remains a separate, complementary source.

Each child has exactly one selected provider. Settings uses `/api/school-connection`
for status/config, account child discovery, connection tests and sync. Schulmanager
requires explicit remote student/institution IDs and validates them against every
fresh login. Legacy Elternportal routes delegate to this same selection. Passwords
are vaulted; Schulmanager status returns only password presence. Imports never call
Schulmanager's separate `letters/confirm` endpoint or submit survey answers.

WhatsApp transport credentials and AI model configuration belong to the family.
Recipient access/preferences and message content are still scoped by child. V21
adopts the most recently saved WhatsApp account, including encrypted secrets, and
leaves obsolete child keys unused for rollback. Initial school-letter imports are
quiet; subsequent changes use the existing notification rules.


## Email identity and read-path performance (September 2026)

`EmailContent` is the single MIME/forward parser. `email_delivery` records each
child/mailbox delivery and maps duplicates to an existing source. Original message
IDs are strongest; inline-forward fingerprints require original date, sender,
subject, normalized body and attachment hashes. Parent forwarding envelopes and
CC recipient differences do not create extra tasks. References (including replies
arriving before their parent) and Gmail thread IDs group conversations; subject
alone is deliberately insufficient. A forward stripped of identity/reference/date
headers may remain separate. Automatic forwarding or attached `.eml` preserves the
most metadata. Per-child domain settings match sender/recipients after unwrapping;
Google's configured mailbox query remains the outer selection (default inbox).

Ordinary read endpoints do no model work or title-rewrite mutations. Background
case batches use one transaction per case, lock/recheck before processing, and
include reopened investigations. `ModelRouter` hashes the complete model request,
including prompt, model parameters and image data, and persists successful JSON
responses through `AiResponseCache` in independent short transactions. Matching
concurrent requests share one call in the app process; failed calls remain retryable.
There is no distributed single-flight guarantee across multiple API replicas.

The conversation reader requests AI output explicitly. Existing source translations
and versioned case summaries remain persisted. Drafts never enter Gmail's send API;
no additional write scope is requested. Generic cross-list obligation review returns
only existing IDs, validates the unchanged snapshot under lock, and moves verbatim
child details into the covering parent action using audited services. Distinct duties
remain separate; a cached empty result prevents repeated inference on unchanged lists.

## Installed web app

The manifest and home-screen icons support Android and iOS standalone launches. A
small service worker caches only the generic offline page (not API data or school
files). School content requires connectivity. `sw.js` and the manifest must bypass
immutable asset caching. The selected child stays in local storage; event IDs stay in
routes, login preserves the requested route, and the manifest launch restores the
last inbox/event URL. Browser installation conventions follow
[Chrome's install criteria](https://web.dev/articles/install-criteria) and
[WebKit's home-screen app behavior](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/).
