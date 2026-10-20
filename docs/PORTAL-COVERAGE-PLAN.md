# Portal coverage and reconciliation plan

Status: implementation and validation on 2026-10-20. Sections below preserve the
original design and acceptance criteria; the delivery record here describes what
the implementation actually supports. Provider documentation alone does not establish
the private response contract or visibility for a particular account.

## Implementation status

Both connectors share notice discovery, complete manifests, immutable source versions,
current evidence, explicit correction operations, linked response tasks and family
completion guards. Migration V34 is additive. New native feeds reuse the existing
record ingestion, enrichment, projection and notification machinery.

The operator explicitly authorized connected-account messages to be shared with
everyone who can access the child, and PDF downloads that automatically acknowledge
receipt. Both permissions are saved per connection and reset on account replacement.
Survey answers, consent submissions, payments and outgoing school messages remain
manual.

| Capability | Elternportal | Schulmanager |
|---|---|---|
| Same-ID content refresh and history | Enabled with PDF receipt permission | Detail and attachment reads; no confirm calls |
| Receipt status | Listing receipt flag | Student receipt timestamp |
| Form response state | Unknown: no verified response contract | Pending/partial/submitted/closed, question IDs/choices and deadline |
| Payment observations | Existing request table; unverified settlement stays unknown | Full parent invoice history plus unpaid widget; explicit settlement and exact minor-unit amounts |
| Additional communications | Noticeboard HTML and verified PDF/image attachment links | Incoming Messenger messages on a verified single-child account |
| Homework | Populated classbook day/subject rows, rolling seven days | Parent homework feed grouped by day/subject |
| Timetable | Verified class substitution rows | Personal lesson exceptions and explicitly observed restoration |

Elternportal populated homework, substitution headers, noticeboard structure and
document routes were inspected with authorized read-only listing requests. Schulmanager
contracts were inspected in its public web client and tested with synthetic API
fixtures. Authorized live reads verified `letters/get-letter-for-reading` with nested
student identities and `messenger/get-messages-by-subscription` with string subscription
IDs; numeric subscription IDs produce a provider error. Receipt metadata comes from
the child-scoped letter list when the reading endpoint omits it. The generated Sent
header is excluded from material-change detection to preserve legacy Done/read state.
Response deadlines persist as ISO timestamps independently of MVC JSON configuration.
Noticeboard attachment media types are verified from their bytes; scanned image
announcements are retained alongside PDFs. The tested homework endpoint currently
returns a provider error for the connected account; its feed reports failure and preserves existing records. Real account
availability remains independently reported by each feed.
No live answers or consent choices were submitted during discovery.

Acquisition checks at most 30 new items and 10 existing items per notice feed per
poll, with a two-minute feed and four-minute provider session budget. Existing content
checks rotate after 30 minutes; backlog and last successful content check are exposed.
Messenger covers the latest 50 conversations and at most five pages each. It does not
claim full archival coverage or infer deletion from absence. Multi-child Messenger is
unavailable until explicit attribution is verified. Elternportal teacher-contact
navigation is not treated as an incoming inbox.

Timetable entries are date/period change notices, not lesson appointments or inferred
arrival/departure instructions. They remain in the inbox and relevant WhatsApp plans,
without separate entries in either calendar. V35 schedules removal of their existing
app-owned Google projections through the normal retryable worker. Actual school events
continue to update or cancel their existing Calendar IDs.
Schulmanager normal observations can restore a previously imported exception;
Elternportal missing rows cannot prove restoration. Homework publication dates never
become deadlines. Undated assignments appear in daily plans and child requirements,
without immediate per-assignment WhatsApp messages or invented calendar events.

The first full manifest of an adopted legacy letter and the first observation of its
school response state establish quiet baselines. Instant notices share date/relevance
checks at composition and delivery: expired events, overdue-only duties and archived
letters do not replay as new school changes. Future cancellations remain eligible;
these notification checks never complete or delete the underlying family duties.

Material updates preserve completed unrelated tasks and existing event/Calendar IDs.
Unknown or unreadable corrections retain old duties with visible pending review.
Cosmetic short-PDF regeneration preserves Done/read identity. For larger PDFs the
comparison is conservative. Downloads retain originals up to 16 MiB per part and
32 MiB per complete notice; extraction/rendering remains capped at 8 MiB. Larger
originals use byte identity without parsing. Current original/OCR inputs and operation quotes are
validated before correction application; oversized or unresolved inputs stay in review.
School status and prior documents are inspectable in the bilingual detail view.

Operational boundaries: session advisory locks release on connection failure; no
expiring lease can admit overlapping provider context. New document acquisition and
correction model calls happen outside database row locks. The older first-import
investigation/reasoning pipeline retains its existing transaction model. The document
store reserves 256 MiB of free space; original versions are retained, not log-rotated.
Abandoned-blob garbage collection, archival Messenger pagination, separate homework
filters, automatic response deadline escalation and shorter school-hours polling
remain follow-ups. Existing 60-day diagnostics and bounded compressed service logs
remain in force.

Regression coverage includes real PostgreSQL A→B→A revisions, cosmetic updates,
incomplete manifests, child isolation, receipt versus response, corrected event IDs
and completed consent, model outage, partial/reversed payments, changed completed
homework, stale completion, optional-feed isolation and current-change notifications.
Release status and final check results are maintained in `PROGRESS.md`.

## 1. Outcome and priorities

School Agent should notice a changed school obligation, explain the change, and
keep the same matter and calendar entry current. It should distinguish a family's
local completion from the school's acknowledgment, response and payment records.
Additional feeds should enter that same reliable process.

Implement in this order:

1. Verify connector capabilities and read side effects; specify fixture contracts.
2. Correct same-ID letter updates, evidence supersession and obligation reconciliation.
3. Track school responses and payment status separately from local completion.
4. Add direct messages and noticeboards with explicit audience/access handling.
5. Add timetable exceptions, then actual homework assignments.

Steps 1–3 are the first release series. Later feeds are separate vertical slices,
enabled per provider only after their discovery and acceptance gates pass.
No rewrite into microservices, new queue infrastructure, automatic school replies,
survey submissions, payment execution or appointment booking is required.

## 2. Findings and evidence

Repository paths below are relative to `backend/src/main/kotlin/de/schoolagent/`.

| Finding | Source evidence | Consequence |
|---|---|---|
| Imported letters are never revisited | `ingestion/SchoolIngestionService.kt`: `syncOnce`, `alreadyImported` | Changed PDF/body, added attachment or changed response deadline under the same notice ID can be missed. |
| Re-fetching alone is insufficient | `ingestion/InvestigationService.kt` reads all fetched case sources; `reasoning/CaseReasoningService.kt` reads all case evidence | Historical facts need explicit supersession before a correction can be interpreted reliably. |
| Existing actions are often skipped by title/key | `reasoning/CaseReasoningService.kt`: action loop in `reason` | A corrected deadline on the same action can remain unchanged. Requirement replacement/removal also needs explicit operations. Existing event moves provide only part of the solution. |
| Source changes affect local read/Done revisions | `domain/service/FamilyInboxService.kt`: `revision`, `complete`, `undo`; `whatsapp/WhatsappDoneService.kt` | New document versions must not make cosmetic changes reopen work, invalidate Undo unnecessarily or let old buttons complete new duties. |
| Changed native payments reopen work | `ingestion/SchoolRecordIngestionService.kt`: `apply`, `applyPayment` | Any changed record resets the payment action to OPEN, including text reflecting payment progress. |
| School response state is lost | `connectors/schulmanager/SchulmanagerClient.kt`: `notices`, `documents` | Student statuses filter recipients; question text/deadline become text. They are not maintained as typed obligations/status. |
| Payments are an incomplete view | `connectors/schulmanager/SchulmanagerRecords.kt`: unpaid widget | Absence cannot establish settlement. Amount paid is text, not a reconciled payment state. |
| Additional feeds are absent | `connectors/school/SchoolRecords.kt`, both provider adapters | Native feeds cover exams, forgotten homework/material reports and payment requests. Messenger, noticeboard, timetable changes and homework assignments need dedicated adapters. |

Existing strengths to preserve: child verification, serialized account context,
complete-snapshot-only exam withdrawals, quiet first snapshots, independent optional
feed health, native-effect retries, calendar identity, audit history, bilingual UI,
and diagnostics with 60-day retention.

### Provider evidence and unresolved behavior

- Elternportal documents class timetables/substitutions, homework in its classbook,
  noticeboards, surveys and teacher communications. It also describes automatic
  receipt confirmation when a document is opened. Whether the current PDF download
  triggers that confirmation has **not** been established.
  [Elternportal features](https://www.artsoftandmore.com/schulen/elternportal/)
- Schulmanager describes receipt confirmation on opening ordinary letters; survey
  letters require answers, separately per child. Its UI behavior does not prove
  whether our detail-fetch calls have the same effect. Email alerts for Messenger
  can be delayed and suppressed, so Gmail is not a complete substitute.
  [Schulmanager parent FAQ](https://blog.schulmanager-online.de/faq2)
- Schulmanager Messenger is a separate communication module, including attachments.
  [Messenger](https://www.schulmanager-online.de/module.nachrichten.html)
- Schulmanager homework visibility depends on school configuration. Homework
  discovery must cover **both** providers; it is distinct from forgotten-work reports.
  [Homework visibility](https://schulmanager.zammad.com/help/de-de/2-klassenbuch/25-konnen-eltern-schuler-die-hausaufgaben-sehen)

These are feature descriptions, not supported API contracts. Do not invent endpoint
names, status fields, pagination semantics or deletion guarantees from them.

## 3. Discovery gate: establish what can safely be observed

Create a capability inventory per connection, student scope and feed. Each entry
records support, school/account visibility, read-side-effect classification,
pagination and coverage semantics, stable identity, change markers, last successful
check, failure reason and parser version.

Represent these concerns separately:

- Support: implemented, not implemented, unavailable to this account, unknown.
- Sync health: never checked, current, stale, failed.
- Coverage: complete for a specified scope/window, partial, unknown.
- Read behavior: verified observation-only, known acknowledgment side effect, unknown.

An empty result is not proof of support or completeness. Expired authentication,
HTML login pages, unexpected schemas and partial pagination cannot clear prior data.
Keep existing exams on their established contract while expanding coverage metadata.

For each proposed adapter:

1. Inspect available documentation and client behavior without executing confirmations.
2. Establish account/student/audience ownership, stable IDs, paging limits and detail
   fetch semantics. Record endpoint methods and response contracts privately.
3. Verify fetch side effects in a controlled test account or from sufficient protocol
   evidence. A GET can mutate receipt state; a POST can be a read query.
4. Build handwritten synthetic fixtures for normal, empty, denied, malformed, changed,
   paginated and sibling responses. Do not commit captures with replaced names:
   hidden IDs, signed URLs and embedded metadata can still identify a family.

Do not test acknowledgment behavior on a live unread letter simply to find out.
If no observation-only path exists, use a metadata-only adapter and explain the
limitation. Any broader policy allowing automatic acknowledgment requires a separate
user decision. Do not expand current document reads to more items until this gate
is resolved; a confirmed existing side effect needs an explicit remediation decision.

Deliverable: a provider/feature matrix with verified contracts and a reason for each
unsupported or gated feature. Missing access blocks that adapter, not the common
reconciliation work using synthetic contracts.

## 4. Data and processing design

### Stable items, immutable versions and current evidence

Add a small common acquisition layer for changing portal objects; keep feature-specific
payloads typed. Avoid forcing every feed into the current exam-oriented `SchoolRecord`.

- **School item:** opaque local ID, namespace, kind, native ID, case relation,
  latest observed revision, currently applied revision, last seen/check timestamps.
  Unique `(namespace, kind, nativeId)`.
- **Namespace:** provider, verified institution/origin, connection identity and child/
  student scope. Use an opaque connection identity, not a password or personal name.
  Credential rotation preserves identity; replacing the school/account/student does not
  silently adopt old objects. Explicitly map legacy identities during migration.
- **Item revision:** item ID, monotonic sequence, provider change marker when reliable,
  raw manifest hash, normalized content hash, observed timestamp, typed metadata,
  completeness, processing status and retry state. Unique `(itemId, sequence)`.
- **Revision parts:** stable provider part ID, role, content-addressed blob reference,
  immutable `Source` reference and membership in the revision. Preserve original
  filenames privately; signed URLs/session values never become identity or content.
- **Effect work:** durable work keyed by item/revision/effect kind, including extraction,
  case application, notification decision and calendar projection. Extend established
  pending-effect patterns; do not add an external broker.

Reuse unchanged Source/blob references across revisions. A replaced part gets a new
immutable Source; removed parts remain available in history but leave the current
manifest. A source can belong to several successive manifests when unchanged.
Do not impose global uniqueness on content hashes: A → B → A is a real later revision,
not a duplicate of the first observation.

Active-evidence queries select the applied manifest for each item, plus applicable
independent sources such as email. Extraction, reasoning, source previews, translation,
retrieval and provenance must share that selection rule. Superseded evidence remains
inspectable with its historical version; it cannot silently compete with current facts.
Disagreeing independent sources remain a conflict to review, not an automatic deletion.

Existing `Source` IDs and case links survive migration. Current V24 already removed
the original `(source_type, external_id)` uniqueness constraint; do not design against
V1 alone. Case merge operations must also move/resolve item relations, revision work
and obligation provenance, or future updates would target a cancelled duplicate case.

### Polling and revision application

1. Discover item metadata and page coverage within a request/time/byte budget.
2. Refresh details when a trusted change marker changes, an item is new, a previous
   fetch failed, or its verification interval expires.
3. Where no reliable marker exists, use bounded rotating detail checks. Start with
   active obligations and recently published letters; schedule older items separately.
   Expose actual last-content-check time and backlog. A list check alone is not a
   claim that every PDF is current.
4. Acquire a complete candidate manifest with existing download/extraction limits.
   One failed attachment leaves the prior applied revision in place and marks the
   candidate incomplete. Known changed-but-unreadable content becomes visible as
   “Update could not be checked”; it must not look current.
5. Extract and compare outside long database transactions. Before applying, lock the
   case/item and recheck connection generation, candidate sequence and case fact
   revision. Discard stale results and retry against the current state.
6. Apply changes, history and effect intent atomically. Resume effects independently
   after restarts; notification enqueue uses a unique key and never acknowledges work
   for a superseded revision.

Use bounded DB-backed leases for cross-process sync ownership in addition to existing
in-process account locks. Release/expire leases on crashes; define one lock ordering.
Do not hold DB row locks during provider requests or AI calls.

No global “delete everything absent” reconciler. Withdrawal requires a feature-specific,
verified complete snapshot for its exact audience/window or an explicit tombstone.
An inaccessible item is inaccessible, not cancelled.

### Material changes and local completion

Keep three version concepts:

- Acquisition revision: original bytes/manifest changed.
- Processing revision: an extraction/reasoning job's exact input generation.
- Case fact revision: accepted user-relevant facts or obligations changed.

Raw PDF metadata, signed URL rotation, attachment ordering, translation or generated
wording alone must not create a new case fact revision. Material changes include new
or changed duties, required response choices, deadlines, amounts, event times,
locations, cancellations and meaningful informational content.

Unknown/ambiguous changes require review; do not label them cosmetic. While a candidate
may change obligations, flag pending review and prevent an old Done button from
completing it. Retain the previous accepted facts, clearly marked as awaiting an update.

Introduce explicit proposed operations with target IDs and evidence references:
`ADD`, `UPDATE`, `CANCEL`, `REOPEN`. Validate scope, prior revision and source support
before application. Native facts are deterministic; AI may propose operations for
unstructured letters within the same validator.

Matching order: provider obligation ID, explicit provenance mapping, then a reviewed
semantic match. Titles/date alone are not identity. Ambiguity goes to NEEDS_REVIEW.
Missing text or an attachment disappearing does not by itself cancel an obligation;
require explicit cancellation or a verified complete replacement contract.

Preserve stable action/event/requirement IDs and Calendar event IDs. Change a deadline
on the existing action. Cancel a withdrawn requirement with history. Keep completed
unaffected actions completed; reopen only new or materially changed duties that
require renewed family attention. A corrected event time can update Calendar and
notify without reopening an already submitted consent.

`FamilyInboxService.revision`, read marks, completion snapshots, Undo, relevance/copy
caches, notification deduplication and WhatsApp Done must adopt the same fact revision
and pending-review rules. Seed compatibility with existing revisions so migration
alone does not mark everything unread or invalidate every prior button. Prevent stale
web completion as well as stale WhatsApp completion; pass expected fact revision on
web writes. Record why completion was preserved or reopened.

## 5. Feature behavior

### School acknowledgment and survey responses

Persist acknowledgment and response separately, scoped to the provider's actual
recipient unit (child/account/guardian), with observed time and source revision.
Suggested states:

- Receipt: not required, pending, acknowledged, unknown.
- Response: not required, pending, partial, submitted, closed, unknown.

Store required question IDs, type/choices, deadline and submitted-status metadata
when the verified API exposes them. Do not infer consent answers from receipt, local
Done or a parent's read mark. Avoid retaining answer contents when status is sufficient.
Closing a deadline is not evidence of an answer; an unanswered closed survey remains
a visible missed/closed obligation with contact-school guidance.

Show “Done in School Agent” and “School response pending/submitted/unknown” separately.
Opening the portal is a user action. School Agent never submits the response.
An observed submission completes only its linked response duty, not unrelated tasks.
Retain who/what completed it in audit as provider observation versus manual completion.

Local Done on a still-pending response is “handled locally; awaiting school confirmation”.
Do not reopen it on every unchanged poll or repeat the same WhatsApp. Keep a visible
pending-verification badge and use at most one deadline-escalation per response revision
when the school still explicitly reports pending. Unknown/stale state gets a freshness
notice, not a claim that the family failed to answer.

### Payments

Use a typed payment observation: invoice identity, currency, total, paid amount,
remaining amount, due date, explicit provider status and observed time. Parse amounts
with decimal/minor-unit arithmetic, never floating point or AI-derived balances.
Validate currency and totals; unsupported refund/credit semantics remain unknown.

Track outstanding, partial, settled, cancelled and unknown independently of local
Done. A decreasing balance updates progress without reopening the same manually
completed duty. An increased obligation or explicitly reversed settlement requires
review. A verified settled/cancelled observation resolves only the linked payment task
and removes its upcoming payment reminder/projection; preserve history.

Keep using the unpaid widget as a partial observation until a verified detail/full
invoice path exists. A disappearing invoice becomes unconfirmed/stale, never paid.
Do not promise full payment reconciliation for a provider lacking status access.

### Direct messages and noticeboards

Adapt Schulmanager Messenger and exposed Elternportal messages/noticeboards using
stable conversation/message/post IDs, edit/tombstone handling and durable pagination
checkpoints. Commit a checkpoint only after its items/work are durable; use overlapping
reads to handle delayed delivery without duplicates. Bound attachment acquisition.

Map explicit replies to the existing conversation/case where appropriate. A thread
can contain multiple school matters; do not force unrelated obligations into one case.
Same text arriving via letter/email/message is correlation evidence, not permission
to merge across children or discard the only delivery containing attachments.

Define visibility before enabling: a message addressed privately to one guardian
must not automatically be shared with all users who can access the child. If the
current family access model cannot represent the provider audience, add recipient
ACLs throughout APIs/search/WhatsApp/Calendar or leave that message class gated.
Ambiguous child attribution stays in a restricted review queue.

Urgent actionable changes use existing notification policy; routine conversation
and general noticeboard posts default to digest/silent. No teacher-message sending.

### Timetable and substitutions

Use authoritative lesson occurrence identity, original/current date-period, subject,
room, status and explicit clock times where available. Compare exceptions against
the verified baseline for the selected pupil/group, not only a class label.

First release shows changes: cancelled lessons, later start, earlier finish and
room/location changes. Do not fill Google Calendar with every routine lesson by
default. Calculate arrival/departure only when the day's complete personal schedule,
period times and supervision semantics support it. Otherwise report the affected
lesson without inventing when the child should leave home/school.

Represent cancellation and reinstatement explicitly. Keep exam reconciliation separate.
Reuse stable projection IDs. Notify once per meaningful exception revision, coalescing
related changes; a withdrawal must not disappear silently from Calendar.

### Homework assignments

Discover actual assignments for both providers where visible. Persist assignment ID,
subject, description, issued date, explicit due date and attachments. Map actionable
assignments to child requirements, with a compact homework view/filter.

No due date means undated, not “tomorrow”. Classbook entry date is not automatically
the submission deadline. Local completed homework and school submission status are
separate if the latter exists. Keep forgotten-work/material reports as historical
reports. Assignments default to agenda/digest; avoid one immediate WhatsApp per task.

### Deferred coverage

General school dates, absences/justifications, grades, booked appointments and download
libraries are later candidates, not part of the initial five slices. Discover their
availability without importing every school-wide event or turning every historical
record into a task. Do not silently substitute these for the core reliability work.

## 6. UI, scheduling, diagnostics and storage

- Add case-detail school-status and update-history sections, current/prior document
  versions, changed-field summaries, pending-review state and precise source links.
  English/Spanish copy must distinguish “unknown” from “not required”.
- Expand School settings with per-feed coverage and freshness: supported but disabled,
  inaccessible, not implemented, never checked, last successful list/content check,
  next attempt and backlog. Optional failure must not make letters appear disconnected.
- Keep the current 30-minute poll initially. After measuring provider behavior,
  propose a configurable 5–10-minute school-hours schedule only for urgent message/
  substitution feeds; use jitter, request budgets, Retry-After and exponential backoff.
  No real-time guarantee. Berlin timezone/DST and holidays must not suppress an
  explicitly published urgent change.
- Add opaque item/revision/run references and fixed reason codes for fetched,
  unchanged, incomplete, superseded, applied, completion preserved/reopened,
  notification suppressed/enqueued and projection retried. Do not log document text,
  recipients, questions/answers, balances, tokens or signed URLs.
- Preserve 60-day diagnostic retention and existing bounded container logs. Store
  only changed document content in the content-addressed store. Retain source versions
  needed by case/audit history; do not treat them as disposable diagnostic logs.
  Budget pending downloads and temporary files; garbage-collect abandoned blobs only
  after a grace period and a reference check across all sources/revisions/backups.
  Measure disk growth in shadow operation and block feed expansion if capacity is
  insufficient. Low disk must fail a fetch visibly without discarding current facts.

## 7. Delivery slices and acceptance gates

### A. Capability contracts and regression fixtures

Implement per-feed metadata and contract tests; resolve read-side-effect questions.
Validate child/sibling isolation, empty versus denied versus partial results, pagination
and no confirmation/submission calls using a fake provider server that records requests.
Exit: each enabled path has a documented evidence-backed contract and synthetic fixtures.

### B. Versioned letters and explicit corrections

Add additive migrations, legacy mapping, item/revision/manifest storage, current-evidence
queries and durable work. Then add explicit operation application and fact revisions
as one usable vertical slice. Wire reader, completion, notification and Calendar behavior.

Acceptance examples:

- Same notice ID, deadline changes from 12 to 15 November: one case, same action ID,
  corrected deadline, old source accessible, one eligible delta notification.
- Corrected PDF plus added attachment: one committed complete revision; retry after
  one failed attachment neither drops old evidence nor creates duplicate cases.
- Removed duty with explicit cancellation: old requirement stops reminders and retains
  provenance. A missing attachment alone does not cancel it.
- Cosmetic PDF regeneration or attachment reordering: no new alert/read reset/reopen.
- A → B → A, edits during AI work, concurrent manual/poll sync, restart between each
  processing stage: final state reflects the latest accepted revision exactly once.
- Completed consent plus corrected trip time preserves consent completion; a new
  required consent question invalidates old completion buttons and requires review.
- Existing merged cases, legacy sources, translations, search and cross-source evidence
  continue to resolve to the right child and current matter.

Exit: these cases pass against real PostgreSQL and fake provider/Calendar/WhatsApp
transports; no historical facts influence current reasoning by accident.

### C. Responses and payment lifecycle

Ship typed observations and linked duty reconciliation provider by provider.
Acceptance: per-child surveys, partial/submitted/closed states, status from another
authorized guardian, school receipt without survey answer, local Done with remote
pending, stale status, partial payment, settlement, cancellation, balance increase,
refund/unknown formats and an invoice absent from a partial list.
Exit: no false “submitted/paid”, no periodic reopening, bounded reminder escalation,
and bilingual detail/inbox behavior explaining local versus school status.

### D. Messages and noticeboards

Ship one provider path at a time after audience gates. Test edits, deleted messages,
pagination overlap, attachment failure, ambiguous child, private guardian audience,
revoked access, multi-topic threads and email duplication. Include notification and
Calendar access controls, not only the page API.
Exit: no message loss or audience expansion; historical baseline remains quiet.

### E. Timetable changes and homework

Ship timetable exceptions first; homework independently afterward. Test lesson moves,
cancel/reinstate, incomplete days, unknown period times, pupil groups, DST/year rollover,
late corrections, duplicate events, homework without deadlines and edited assignments.
Exit: no invented times/deadlines, no routine timetable flood, no mixing homework tasks
with historical forgotten-work reports.

## 8. Migration, validation and release

Use new forward-only Flyway migrations; allocate versions when implementation starts.
Do not alter deployed migration checksums. Backfill in resumable batches with stable
legacy mappings, preserving case/source/event IDs, access, assignments, read state,
Done snapshots and provider connection boundaries. Existing ambiguous records require
review rather than guessed namespaces or case relationships.

Separate initial backfill from subsequent observed changes. Initial ingestion stays
quiet and shows an in-app coverage summary; do not send years of newly discovered
history. Newly observed corrections after the baseline use normal delta policy.
Urgent unresolved items discovered at baseline remain visible even though import
notifications are suppressed.

Run a bounded shadow period for each adapter: acquire/compare without changing cases
or dispatching notifications, then reconcile a controlled sample against the portal.
Shadow mode is permitted only for verified observation-only requests; it does not
neutralize provider read side effects. Promote each adapter independently with
pending-effect flags and a disable switch that preserves imported state.

For code releases run backend and frontend suites, production frontend build, privacy/
secret checks and targeted real-PostgreSQL concurrency/migration tests. Add browser
coverage for update history, local/remote statuses, stale data, mobile and Spanish.
Verify bounded attachment processing, request counts, AI calls, queue delay and disk
growth under synthetic backfill volume. Existing exam, Gmail, Calendar and WhatsApp
regressions remain release gates.

Before authorized deployment: full recovery backup, preserve vault/data and rollback
images, review migration compatibility, verify health/migrations and effect queues.
Disabling a new feed stops new acquisition and its unapplied effects. Code rollback
is safe only with an explicitly tested compatible schema; otherwise use the reviewed
full recovery procedure. Never run old writers against an incompatible revision model.

## 9. Implementation starting point

Begin with slice A and the same-ID letter correction fixtures from B. The first useful
release must demonstrate a corrected deadline on the existing case/action, reliable
Done behavior and one delta notification. Do not enable additional high-volume feeds
before that path works.

Outstanding discovery decisions are exact provider status/pagination fields,
observation-only download paths, personal versus shared message audiences, full invoice
status availability, and pupil-specific timetable/homework visibility. These are
evidence gates, not reasons to guess contracts or block unrelated implementation.
