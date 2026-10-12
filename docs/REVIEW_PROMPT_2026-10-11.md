# Review prompt — school-agent changes, 11 October 2026

Review today's changes in `your checkout` independently. This
is a review request only: **do not edit code, commit, push, deploy, access production
credentials, contact school systems, send notifications, or publish WhatsApp
templates**. Local tests and read-only repository inspection are allowed. Do not
delegate the review unless the user separately asks for it.

Read `PROGRESS.md` first, then `AGENTS.md`, `docs/ARCHITECTURE.md`,
`docs/FRONTEND.md`, and the relevant parts of `docs/SPEC.md`. Treat documentation
and the author's test results as claims to verify against the implementation.

## Scope and product intent

Review the current implementation in the single-commit release snapshot. Focus
on form-link resolution and native school records, including sync health, identity,
quiet baselines, parser validation, persistent wording, outbox retries and case history.

The application helps a family understand school communications, deadlines and
events for multiple children. Keep it simple, reusable across families and schools,
and coherent with the existing case/event model. Do not suggest assignments,
partial task completion, per-child hardcoded rules, or broad unrelated redesigns.

Today's requested changes:

1. Fix misleading **Open form** buttons that led to a school homepage. Resolve
   portal tasks to the correct child's connection, preserve genuine specific form
   URLs, and avoid presenting letterhead websites as forms.
2. Read Elternportal **Service → Schulaufgaben** for the selected child's actual
   class, without importing other classes from **Allgemeine Termine**. Represent
   exams as important cases/events, with an independent calendar filter. Support
   the equivalent Schulmanager exam feed when accessible.
3. Read **Vergessene Hausaufgaben / Vergessene Materialien** during normal sync.
   Create important dated reports when entries exist. Try the corresponding
   Schulmanager classbook feed if the school exposes it.
4. Read Elternportal **Meldungen → Zahlungen**, and Schulmanager unpaid invoices
   when available, as important actionable payment cases. Do not invent deadlines
   or treat an entry's disappearance as proof of payment.
5. Reuse existing approved WhatsApp templates and normal notification machinery.
   No new templates, fallback layouts, manual replays, or production test messages.
6. Persist results so unchanged syncs and page loads do not repeat AI work,
   duplicate cases/events, reset Done/read state, or resend notifications.

Production deployment and school accounts are private and outside the scope of
this public review brief. Use synthetic fixtures; do not request or embed live data.

## Review priorities

- **Child isolation and connector contracts:** Follow selection from configured
  child name/class through the authenticated account's student identity to every
  feed. Check ambiguity, siblings, class changes, reconnects, multiple children
  sharing credentials, and errors. Verify that only the dedicated class exam
  timetable is authoritative. Account navigation must never submit school data.
- **Parsing and availability:** Distinguish a confirmed empty list from denied,
  disabled, expired-login, partial or unfamiliar responses. Check strict date/time
  parsing, native IDs, identity stability when rows move, and the representative
  populated fixtures. Assess whether endpoint parameters match the connector's
  conventions and whether unfamiliar layouts fail visibly rather than inventing
  or deleting records. No family/school/class IDs belong in application logic.
- **Persistence and lifecycle:** Trace V28, record keys/namespaces/content hashes,
  transactions, locks, case transitions, provenance, and audit history. Check
  concurrency, interrupted syncs, replay after a crash, unchanged polling,
  completion/read overrides, genuine updates, and event identity. Withdrawal is
  allowed only for future exams missing from a complete verified snapshot; denied
  or failed feeds must not erase data. Reports/payments must not auto-complete
  merely because they stop appearing.
- **AI and date roles:** Native exam/report dates must remain authoritative.
  Payment due dates need a real explicit deadline rather than a report/creation
  date or unsupported model guess. Check source grounding, cached language work,
  retry behavior, prompt robustness and unnecessary requests. Exams and forgotten
  reports should not invent parent tasks, study instructions or materials to bring.
- **WhatsApp and calendar side effects:** Trace notification eligibility, durable
  pending preparation, recipient/child scope, deduplication, and retries through
  the existing system. Historical school reports may be announced when received
  but must not become tomorrow appointments. Exams should participate in normal
  future reminders. Check Google calendar updates/removals and failure recovery.
  Confirm no template publication or fallback was added.
- **UI and links:** Verify the independent Exams filter across the month grid,
  selected-day agenda and coming-up list. Check mobile clarity, high importance,
  existing child colours, Done visibility and current settings conventions.
  Section status must distinguish inaccessible from empty. Portal links should
  belong to the right child/provider and say Open Elternportal/Open Schulmanager
  where appropriate; inbox and detail must agree. Check source badges and existing
  English/Spanish strings without turning this into a language-system redesign.
- **Regression and maintainability:** Trace regular letter sync and existing
  controller/API behavior. Review error boundaries so one unsupported optional
  section does not prevent letters or other accessible feeds from working. Look
  for unnecessary abstractions, per-record exceptions, performance problems,
  migration risks and genuinely missing tests.

Start with `ActionLinks.kt`, `SchoolRecords.kt`, `SchoolConnector.kt`,
`ElternportalRecordsParser.kt`, `ElternportalSchoolConnector.kt`,
`HttpElternportalClient.kt`, `SchulmanagerRecords.kt`, `SchulmanagerClient.kt`,
`SchoolRecordIngestionService.kt`, `SchoolRecordInterpreter.kt`,
`SchoolIngestionService.kt`, `SchoolConnectionService.kt`, the V28 migration,
`NotificationService.kt`, `BriefingService.kt`, and the changed Angular calendar,
settings and link presentation. Read relevant callers and existing services rather
than reviewing the new files in isolation.

## Evidence and limitations

- Before review fixes, the author ran **328 backend tests** and **77 frontend tests**, all passing. Latest validation counts are in `PROGRESS.md`.
  The frontend production build passed with the existing initial-bundle warning
  of approximately 511/500 kB. Verify coverage rather than relying on totals.
- Read-only portal inspection found five class-specific exams for the configured
  Elternportal child. Forgotten homework/materials and payment pages were empty.
  Their populated fixtures are representative, not captured live populated pages.
- The configured Schulmanager account has exam access but no exam entries;
  classbook access is denied and invoicing is disabled. This distinguishes
  availability from a bug, but does not prove the populated feeds end to end.
- No native production import, notification delivery test, or repeated live native
  sync was performed. These remain future release validation, after review.

Backend checks: `./backend/mvnw -f backend/pom.xml -q test` (Docker is needed for
Testcontainers). Frontend checks: from `frontend`, use Node at
`$HOME/.local/opt/node-v24.21.0-linux-x64/bin`, then
`npm test -- --watch=false` and `npm run build`. Do not use deployment scripts as
validation. Do not print secrets or private school content in the review.

## Expected result

Lead with concrete findings, ordered by severity. For each finding give the
file/line, triggering scenario, user impact and smallest coherent fix. Clearly
distinguish a confirmed defect, a plausible risk needing evidence, and an
acknowledged live-verification gap. Include missing tests that would catch an
actual failure, not tests that merely duplicate implementation.

Then give a brief assessment of architectural fit, UI consistency and readiness
for release, with any blockers and the live checks still required. If you find no
blocking defects, say so without implying that empty live feeds establish complete
compatibility. Avoid speculative findings, cosmetic nitpicks and broad rewrites.
Do not make fixes or deploy; stop after the review so the user can compare reviews.
