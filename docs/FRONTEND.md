# Frontend Design — school-web

Technical design for the Angular control center. Requirements come from the
spec (docs/SPEC.md §27–28); this document adds the decisions needed to build
it. Implementation is milestone **M8** (see PROGRESS.md), unless pulled
forward deliberately.

---

## 1. Role

The frontend is the **system control center** (spec §27): what needs attention,
what was processed, how the system is configured and how healthy it is.
WhatsApp remains the day-to-day conversational UI — the web app is for
oversight, bulk review and configuration, not chat.

## 2. Stack decisions

| Decision | Choice | Rationale |
|---|---|---|
| Framework | Angular 22, standalone components, signals (scaffold exists) | spec requirement; modern default |
| Styling | Plain CSS + custom properties now; Angular Material only if a component is genuinely cheaper than CSS | family tool, not a design showcase |
| State | Services with signals; no NgRx | two users, small data volume |
| HTTP | Angular `HttpClient` + dev proxy to API | no CORS in dev or prod (same origin via proxy) |
| Charts/calendar libs | none in v1 (hand-rolled agenda list; month grid later) | calendar views read from our own `/api/events`, not a lib's data model |

## 3. Authentication (important design decision)

The backend uses HTTP Basic (v1). Browsers answer a `401 +
WWW-Authenticate: Basic` with the **native popup dialog**, which is ugly and
un-stylable. Therefore:

- Backend change (M8 prerequisite): omit `WWW-Authenticate` on `WWW-Authenticate`-worthy
  401s for `/api/**` requests that carry `Accept: application/json` or the
  `X-Requested-With: XMLHttpRequest` header → no native popup.
- Frontend: `/login` route with username+password form; credentials are
  base64'd and kept in a signal-backed auth service (memory +
  sessionStorage). Every API request gets `Authorization: Basic …` via an
  interceptor. A 401 anywhere → redirect to `/login`.
- Logout drops the stored header. Session expiry = next 401.
- Later (not v1): upgrade to form login + server session or tokens without
  breaking this flow — the interceptor is the only place credentials are used.

## 4. Routes and components

```
/login                 LoginComponent
/                      InboxPage                 one lane per child (or “Together”), chips: All | Needs us | Coming | Info | Done
/cases/:id             CaseDetailPage            child route of the inbox: beside the lanes (wide), drawer (tablet), full screen (phone)
/calendar              CalendarPage              month grid coloured per child + agenda split by child (lazy)
/settings              SettingsPage              School / You / Family (lazy)
/audit                 AuditPage                 chronological system-action trail (lazy)
/search                SearchPage                (lazy)
```

Old `/actions` and `/cases` redirect to the inbox.

Shell (redesign “E”, mockups in `docs/design-mockups/`): a dark icon rail on
desktop/tablet — pages at the top (Inbox with needs-us badge, Calendar), then
the child filter (both / one child), and at the bottom the actions that open
over the current page (Snap a letter, Ask the assistant), Audit, Settings, a
connector-health dot and the account menu. Phones get a top bar (child chips,
search, account) and a bottom tab bar with a raised camera button. The top
search box lives in the shell; a connector problem still shows as a strip
(spec §30: “no new mail” ≠ “haven't checked in 12h”).

Inbox lanes group rows into Overdue / Due within 7 days (Berlin calendar days)
/ Later / No date / Just info / Done, sorted by date then importance. Rows
without a child are shown as “Both kids”. Importance is five bars coloured by
level (score 1–5).

The Inbox has independent Info, Done and Archived visibility toggles. Info defaults
on; completed/cancelled cases, personally read information, and expired items are
hidden until their toggle is enabled. The minimum importance filter selects a score
and above, applying to active and history items; selecting it again clears it.
There are no All / Needs us / Coming category filters. Controls are retained in
the URL and local storage, and apply equally to child lanes and the combined list.
Archived items retain their case state, open tasks, documents and direct URLs.

`CaseSummaryDto.relevance` / `CaseDetailDto.relevance` contain a shared, persisted
visibility projection: expiry instant, assessment, confidence, reason, fallback weeks and archived
flag. Background AI assesses the final relevant calendar day from original school
documents; only confidence ≥0.8 is accepted. Unknown periods use the shared configured fallback (default four weeks)
from the latest school-message date (original sent date preferred over import date),
never ending before a known occurrence/deadline. Estimated end days are inclusive
in Europe/Berlin. New facts invalidate old assessments; read marks do not. Reads
never generate estimates. A detail disclosure explains the expiry and uncertainty.
Expiry also suppresses scheduled plans and queued case notifications; it does not
complete work. The calendar's history option can still display archived matters.

Inbox row copy has separate roles: a short subject title, an optional sentence of
additional context, and a concise parent-action label. `CaseSummaryDto.inboxCopy`
provides the persisted projection (`title`, nullable `description`, `actions` keyed
by action ID). A missing projection falls back to original text; a null description
inside a valid projection means show no description. Detail views retain full text.
Generation is background-only and content-versioned; list reads never call AI.

Optional and Overdue are status chips, not repeated in generated titles, descriptions
or action labels. Meaningful eligibility conditions remain. Done/Mark as read/Undo
controls sit below the importance bars, separate from the lower metadata.

Dates appear only in the right-hand block above the importance bars, with
Today/Tomorrow/Yesterday or “In N days” through day 7, otherwise weekday/date.
Keep event time, without “Due” or “Event” prefixes. Show both days if the deadline
and occasion differ; otherwise show the day once. Location and Sent/Received/Added
source metadata remain below the text. Dates used for AI copy and display both use
Europe/Berlin, including UTC midnight crossings.

## 5. API contract

Implemented today (backend `de.schoolagent.api`):

| Method | Path | Returns |
|---|---|---|
| GET | `/api/cases?status=A,B` | `CaseSummaryDto[]` (id, title, summary, category, status, importance, investigationComplete, openedAt, closedAt, updatedAt, openActions) |
| GET | `/api/cases/{id}` | `CaseDetailDto` — case fields + sources, evidence, actions, requirements, events, externalForms, changes, statusHistory, investigations |

Unknown status filter → 400. Unknown id → 404.

**Planned (build in lockstep with M2+ backend work, do not invent client-side):**

| Method | Path | Milestone |
|---|---|---|
| GET | `/api/events?from&to` | M8 (calendar view) |
| GET | `/api/actions?state=…` | M8 (actions view; list-only first) |
| PUT | `/api/cases/{id}/complete` | Inbox Done (shared across parents) |
| PUT | `/api/actions/{id}/assign` | Inbox Take / Unassign |
| PUT | `/api/actions/{id}/complete` | Inbox Done when the card has an action |
| GET | `/api/settings`, PUT `/api/settings/{key}` | M8 (Google/AI/automation tabs) |
| GET | `/api/audit?caseId&from&to` | M8 |
| GET | `/api/health/connectors` | M8 (health strip; actuator is not enough for the strip) |
| POST | `/api/ingest/manual` (upload/URL) | later (spec §42) |

## 6. Case detail — the provenance screen (spec §29)

The single most important screen. Layout:

1. Header: title, status badge, importance, confidence, category, child.
2. Summary (agent-written) + investigation state (`investigationComplete=false`
   shows the stored reason — spec §18's "do not silently conclude").
3. Tabs or sections:
   - **Actions** (parent obligations, assignee, due, deep link to external form)
   - **Jamie needs** (child requirements with dates)
   - **Events** (projected calendar events, status incl. CANCELLED/MOVED)
   - **Sources** (type, title, sender, date, URL, fetch status)
   - **Evidence** (kind, subject, confidence, source link)
   - **Changes** (the delta log — what moved/cancelled/changed and when)
   - **History** (status timeline incl. actor and reason)
4. Every item that came from a source links to it; sources link out to
   originals where a URL exists. "The AI must not become an opaque authority."

## 7. Section ↔ spec §28 mapping

| Spec §28 section | v1 scope | Blocked on |
|---|---|---|
| Dashboard | urgent actions + upcoming deadlines/events from `/api/cases` data; connector strip once `/api/health/connectors` exists | health endpoint |
| Calendar/Agenda | agenda list (chronological events from cases) | `/api/events` |
| Actions | read-only grouping by state; assign/complete once PATCH exists | write API |
| Cases/Inbox | **fully buildable now** with existing endpoints | — |
| Settings | structure + disabled inputs | settings endpoints |
| Audit | — | `/api/audit` |

Meaning: login + cases list + case detail + dashboard shell are buildable
**today** against the running backend; the rest lands with the backend
endpoints above.

## 8. Non-goals (v1)

- No push/live updates (poll on focus; SSE later if it matters).
- No i18n machinery (UI language: English with German domain terms, like the spec).
- No mobile-specific design beyond responsive CSS; WhatsApp is the mobile UX.
- No optimistic UI mutations — refresh after write ops.

## 9. Dev setup

```jsonc
// frontend/proxy.conf.json (to add)
{ "/api": { "target": "http://127.0.0.1:8080", "secure": false } }
```

`npm start` serves on :4200 and proxies `/api` to the backend; production
deployment (deploy/docker-compose.yml) serves the built bundle behind the
same origin as the API.

Family → Automatic archive configures the fallback through `/api/inbox/settings`
(GET authenticated, PUT admin; 1–52 weeks). Updates apply to existing uncertain
projections without new AI work; confident estimates remain unchanged. Inbox
visibility toggles use short labels and pressed states. Info controls informational
content across active/history rows. Done and Archived include history alongside
active items, retaining URL/storage flags. Expired read items appear once in
Archived. Old history links migrate to these toggles. Each section has an accessible
minus/plus button; collapsed cards remain mounted. Collapse choices are stored per
child/group, with separate shared and combined-list scopes. Minimum importance is
remembered and applied locally without fetching content or AI work.

Native exam dates use an independent **Exams** filter in Calendar. **Events** controls
other events and obligations; both filters apply to the month grid, selected-day agenda
and coming-up lists. Exams retain child colours and priority labels. School portal settings
show each child's section availability and last fetched record counts; denied/disabled
sections are distinct from confirmed empty lists. Section error/unavailable reasons are
visible text rather than hover-only hints. Sync count/failure messages use the current
English/Spanish pack. Native payments retain their portal provider metadata and link to
the original portal, even after another connection is configured. Action navigation reads
public connection metadata once per child per response. Crawled form inventory opens
its verified containing page with **Open page**, never the submission endpoint; older
inventory without a verified page remains an unlinked **Detected form**.
