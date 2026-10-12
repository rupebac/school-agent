# School Agent — UI and behavior proposal

Status: approved by the family and implemented locally on 28 September 2026.
See `PROGRESS.md` for verification and deployment status.
Date: 28 September 2026.
Implementation refinement: Info only and Mute WhatsApp are in a button-triggered
More menu in the detail header; primary actions remain Done or Mark as read.
More stays beside the primary action, with a 16px gap and space above the row.
Calendar navigation uses arrow icons around the month name, with explicit
Previous month/Next month tooltips and accessible labels; Today remains text.
Readability refinement: Info uses a blue-tinted card and a labeled read/unread badge;
action cards use a Needs action badge and a solid Done button. Read/completed states
retain full text opacity. Secondary text, control borders and focus rings are darker.
Baseline: `2827f4b`, pushed to the private GitHub repository before design work.

Open `design/school-agent.html` for an interactive, self-contained mockup. All
content is illustrative. Sofia is a fictional second child. Changing the viewer
or clicking controls only changes in-memory demo state; nothing is sent or saved.

## Product direction

One shared family inbox, one card per school matter, a useful calendar, and easy
access to what the school actually said. Parents should understand the consequence
without interpreting internal workflow states.

Keep the user's constraints:

- No assignments, ownership workflow, progress tracking, or partial completion.
- Actionable matter: one shared **Done** for the whole matter, with **Undo**.
- Information matter: **Mark as read**, separately for each parent.
- Keep the month calendar available, initially expanded on desktop and collapsed on phones; remember its visibility.
- Keep All as the initial filter and remember the parent's last selection.
- Keep quiet notification behavior and the existing three notification toggles.
- English remains the default, with the existing per-account Spanish option.

The earlier proposals for assignment, “Ready,” packing checkboxes, and intermediate
completion states are explicitly dropped. Preparation items are readable information
inside their matter, without a second completion workflow.

## 1. Inbox and visual hierarchy

Desktop: matter list on the left, a persistent calendar and selected-day agenda on
the right. Mobile: a single column, with a compact calendar disclosure above the
list. The mockup demonstrates this responsive arrangement.

Top-level controls:

- Family scope: **All children / Jamie / [second child]**.
- Search within that scope, with an explicit way to search all children.
- Add a letter/photo; choose a child if All is selected and the family has several.
- Account menu for personal settings. School connections remain under Settings.

The opening line is factual: “2 need attention · 3 unread updates.” A quiet status
line shows when school sources were successfully checked. Partial coverage says so;
“nothing new” must not disguise a failed or incomplete sync.

Keep **All / Needs us / Coming / Info / Done** as views of the same matters.
Info is ordered unread first; an optional Unread-only filter is local to that view.
Counts follow the selected children and viewer.

Card hierarchy:

1. Child name and origin (Email / Elternportal / Upload).
2. Short, descriptive title.
3. One or two sentences explaining the consequence.
4. A clearly named date: **Reply by Thursday** versus **Event on Monday**.
5. One primary state control: Done or Mark as read.

Important changes appear near the top of their card: “Departure moved: 08:00 →
07:30.” Source details, extra dates, and the full explanation remain one click away.
Numeric scores may remain in details; the main card communicates the reason for
priority. An information item can still be urgent, for example a school closure.

Visual direction: warm neutral background, white surfaces, dark ink, restrained
teal accent, clear spacing, fewer nested cards. Amber signals a change or uncertainty;
red is reserved for immediate consequences. Child colors identify children and are
always accompanied by names, so they do not compete with urgency or depend on color
recognition. Controls should have comfortable touch targets and visible keyboard focus.

## 2. Calendar

| Content | Month cell | Hover / keyboard focus / click |
|---|---|---|
| Vacation or holiday | Subtle diagonal shading and inset edge; no repeated holiday names | Name, date range, and affected children |
| Score 0–3 matter | Small marker in the child's color; group by matter | Full title, child, time, and origin |
| Score 4–5 matter | One short label, roughly 2–3 meaningful words, up to two lines | Full title and why it matters |
| Multiple important matters | Highest-priority label plus a visible additional-item count | All matters for that day |
| Today / selected date | Separate outline / filled selection treatment | Explicit date label |

Keep cell heights stable; do not shrink text until it fits. Labels should preserve
meaning (“Return consent,” “Early pickup”), use ellipsis as a last resort, and be
stored separately from the full title. Their generation must not invent new facts.
Small screens use at most one label and a count. A marker is not an extra copy of a
matter already represented by a label.

Clicking opens the day agenda immediately below the calendar. Hover/focus gives a
preview, but no information is available only on hover. Escape dismisses a preview.
The selected-day panel stays put while the pointer moves away.

One row per matter per day; show its event time, deadline, and preparation text
together. Multi-day trips span the relevant dates. Cancelled or moved events have a
clear explanation in history, not a mysterious disappearing dot.

Read information stays on the calendar. Shared Done preserves the family's current
meaning of clearing an actionable matter from active views; Done/All provide access
to it. Changing an inbox filter should not silently alter calendar meaning: calendar
visibility of finished matters gets its own explicit Show finished option.

In All children, child-specific days off identify which child is off in the day panel;
the app must not label the whole family “No school” when only one child's school is off.

## 3. Information, personal reading, and shared completion

Treat classification, personal reading, and shared completion as separate concepts.
Do not derive Info purely from a low importance score or from an empty parent-task list:
child preparation can also make a matter actionable.

| Operation | Effect |
|---|---|
| Mark as read | Records the current matter revision as read for this parent only |
| Mark unread | Removes that parent's read mark; other parents are unaffected |
| Done | Completes an actionable matter for the family in one operation |
| Undo Done | Restores the previous shared state through the existing state machine |
| Information only toggle | Shared correction of classification; does not mark it read |
| Mute | Changes notifications only; does not complete or reclassify the matter |

The detail page has a labeled **Information only** switch and a short explanation:
“No action or preparation needed.” In Info mode, the primary control is Mark as read.
Turning it off returns the matter to Needs us, even if the classifier failed to extract
a concrete action. Do not invent a “Read this letter” task to achieve that.

Turning Info on excludes erroneous generated obligations from active tasks and reminders,
but retains their provenance and previous state so the correction can be reversed. A
manual correction takes precedence over routine reprocessing. A genuinely conflicting
new source is surfaced for review, rather than silently overriding the parent.

New substantive information (deadline, required preparation, cancellation, new source
with a consequence) makes an Info matter unread again. Retranslation or formatting
changes do not. Store a semantic revision/read marker on the server, not just localStorage.
Opening a card alone does not mark it read. No read receipt is sent to the school.

Do not automatically reinterpret every historically completed case during migration.
Preserve existing shared Done state and introduce the new behavior for active Info items.

## 4. Details and the document reader

Detail order: conclusion → important change → what the family needs to do/know →
date/location → sources → technical history. Show incomplete investigation at the
top when it affects the conclusion, rather than hiding it in processing history.

Provide three reading levels:

- **Card description:** the existing short conclusion, one or two sentences.
- **Detailed summary:** usually 150–300 words, shorter for short letters. Structured
  into useful headings such as What this means, Dates, What you need, and Other details.
- **Full translation:** faithful document text, with the original always available.

The detailed summary consolidates the matter's sources, identifies corrections, and
links its claims to the relevant documents. Preserve deadlines, amounts, conditions,
optional-versus-required distinctions, and unresolved ambiguity. Remove greetings,
repeated school headers, postal addresses, signatures, and repetitive explanatory prose
unless they affect a decision. Do not add empty headings or fill to a word target.

For full reading, rejoin PDF line wraps without flattening paragraphs, preserve lists
and headings, use a comfortable reading width and line spacing, and separate source
metadata from the article. Render a small safe structured format; never insert arbitrary
source/model HTML. Structured tables need a readable fallback instead of merged text.
Allow selecting the document when a matter contains several sources.

Generate the extended summary on demand and cache by source versions, language, and
summary format version. A failed summary leaves the source readable with Retry.
Clearly mark partial OCR/extraction. The current service limits translation input to
16,000 characters and scanned reading to four pages; chunking or explicit partial-text
disclosure is needed before presenting either as “the whole document.”

## 5. Gmail and source provenance

Recommended setup: connect each child's Google account under that child's School
settings. Forward school correspondence to the corresponding account. If “all email”
literally includes non-school mail, provide a mailbox query/label and school relevance
filter rather than turning unrelated messages into school matters. Gmail supports
forwarding selected messages with filters:
https://support.google.com/mail/answer/10957?hl=en

For a two-child family, route known school senders to the relevant child. If one mailbox
will receive both children's mail, that is a separate intake mode: resolve by reliable
school/class context and ask when ambiguous. Do not silently assume every message in
Jamie's mailbox belongs to Jamie while presenting it as a family-wide intake account.

Show Email on the matter even when most extracted facts came from an attached PDF.
Detail example: “Email · Ms Weber · forwarded by Morgan · received 28 September.”
Only show an original sender/date when recoverable with reasonable confidence; always
retain the actual received envelope and source. Forwarding should not overwrite the
history of when the school originally sent a letter.

Source rules:

- Store raw MIME, attachments, and extracted links with their relationship intact.
- Support normal auto-forwarding, inline forwarding, and attached `.eml` messages.
- Scope Gmail IDs, cursors, thread IDs, and deduplication to the connected mailbox/child.
- Handle pagination and late-arriving/overlapping polling windows without losing mail.
- Preserve original message identity where available, but do not merge unrelated
  forwards just because they share a subject or attachment filename.
- Deduplicate a portal notification only when its referenced content is actually covered
  by the portal connector. A blanket sender-domain skip can hide messages/surveys that
  the current Elternbrief-only connector never fetches.
- Keep both Email and Elternportal provenance when two sources become one matter.
- Shared document bytes may be deduplicated; children's obligations and calendars stay distinct.

Google's API returns `nextPageToken`; the current client only reads its first page:
https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/list

Google settings should show the actual connected mailbox, child, polling state, last
successful check, and a short forwarding setup guide. A connection and a working poll
are distinct states. Connecting still requires the family's OAuth consent; this proposal
does not access or change an external mailbox.

## 6. WhatsApp

Use the same matter title, classification, and dates as the website. Short bold title,
one useful paragraph, separate date/deadline lines, and one link. At most one urgency
symbol. Do not put internal score numbers in messages.

Illustrative actionable message:

```text
*Jamie · Schullandheim consent*

Return the signed consent form by Thursday, 1 October.
The trip is on Monday, 5 October.

*You need to:* sign and return the form.

Details & letter: [matter link]
```

Illustrative Info update:

```text
*Sofia · Parents' evening moved*

Thursday, 8 October at 18:00, instead of Tuesday.
The room is unchanged. No reply needed.

Details & letter: [matter link]
```

Use WhatsApp's supported single-asterisk bold syntax, short paragraphs, and plain URLs:
https://faq.whatsapp.com/539178204879377/

Keep the existing notification frequency choices and score thresholds unless separately
changed. New Info read marks must not generate messages. A substantive correction can
still notify a parent who read the earlier version, according to the existing policy.

Weekly: group by day, with child names on each matter when viewing a family. Skip empty
day rows; collapse a whole vacation week into a short note. Preserve essential kit within
its matter line instead of dropping it to remove duplicate event rows. No assignments
or completion checklists. Evening: retain the current quiet policy for high-priority
items; do not reintroduce generic nagging.

The current daily/weekly builder hardcodes English; it should honor each recipient's
language consistently. Notification deduplication should include meaningful revisions:
today's key uses score, action count, and earliest action deadline, so a changed event
time alone can be suppressed. Fix content correctness alongside typography.

## 7. Two children as a normal workflow

| Area | Family behavior |
|---|---|
| Inbox | All children by default, named child pills on cards, scoped filters/counts |
| Calendar | Shared view with child identity; selecting a child scopes all marks and days off |
| Search/chat | Explicit child/family context; no hidden mismatch with the visible selector |
| Detail links | Show the actual child's name even if the prior inbox filter differed |
| Upload | Choose a child when ambiguous; never default silently to the first child |
| School settings | Separate connection context per child; switching it does not change inbox scope |
| Family settings | Children, schools/classes, and which parent accounts can access each child |
| Personal settings | Language, own read marks, and notification times/toggles |

One family WhatsApp conversation per parent is sufficient. Shared transport credentials
should be configured once where possible, with routing independent of which child was
last selected in Settings. If different senders are retained, make their routing explicit
and pick a valid sender for that recipient. Notification content remains child-scoped.

For the same school-wide notice affecting both children, preserve each child's relevance
and calendar. The family presentation can group a genuinely shared event with both names;
matching titles alone must never cause a cross-child case merge.

## 8. Findings in the current implementation

These are code-review findings, not claims that failures were reproduced in production.

| File | Finding | Proposed work |
|---|---|---|
| `scheduling/GmailPollJob.kt` | Checks/polls the legacy null-child connection | Iterate configured child mailboxes |
| `ingestion/GmailIngestionService.kt` | Shared cursor and unscoped Gmail message/attachment dedup keys | Per-mailbox state, migration compatibility, forwarding fixtures |
| `connectors/gmail/GmailClient.kt` | First page only; response deserialization uses Jackson-2 JsonNode in Boot 4 | Pagination and live-compatible HTTP decoding, verified with fake HTTP tests |
| `api/OverviewControllers.kt`, `api/SearchController.kt`, `api/CasesController.kt` | Several list/detail/mutation routes do not enforce child scope | Consistent server-side access checks and scoped queries |
| `reasoning/CaseReasoningService.kt` | Merge candidates include other children's cases | Filter candidates and reject cross-child targets at apply time |
| `pages/inbox/inbox.ts` | Empty child case list falls back to all events; action/requirement marks are unscoped | Filter every calendar input consistently, including empty/loading states |
| `pages/settings/settings.ts`, `layout/shell.ts` | Settings and inbox share selection; header changes do not reload settings connectors | Separate contexts and cancel/discard stale connector responses |
| `api/SourceReadingService.kt` | Flat translated text, capped input/pages | Structured reading, detailed summary, explicit completeness |
| `whatsapp/NotificationService.kt` | “Info” inferred from parent tasks alone; revision missing from dedup key | Authoritative classification, child requirements, meaningful change notifications |
| `api/FeedbackController.kt` | “Not important” can cancel a case; Wrong date only records a rule | Distinct notification preference and factual correction workflows |

Paths in this table are relative to `backend/src/main/kotlin/de/schoolagent` or
`frontend/src/app`, as appropriate.

## 9. Implementation sequence after design review

1. Introduce shared Info classification/override, personal read revisions, and one shared
   Done operation. Add isolation, reversibility, and reprocessing tests.
2. Fix child scoping and mail ingestion before connecting real Google mail. Test two
   mailboxes with colliding message IDs, pagination, forwarded messages, mixed sources,
   different access rights, and an empty child inbox.
3. Implement calendar/card layout and simple detail actions; check desktop, mobile,
   keyboard navigation, overflow, and empty/error/loading states.
4. Add structured document reading and on-demand detailed summaries, with source links,
   caching, partial-document handling, and AI-unavailable behavior.
5. Update WhatsApp formatting, localized briefings, and change deduplication. Compare
   examples for one/two children and Info/actionable matters without sending live messages.
6. Run the full backend/frontend suites and build, update the living progress ledger,
   and commit the reviewed implementation. Deployment is a separate step.

Useful follow-ups from the earlier review: exact source references, visible investigation
gaps, correction flows that really repair facts, AI regression cases, and a verified
backup/restore covering both documents and the encrypted vault. These should not expand
the everyday UI into a task-management application.

## Acceptance examples

- Morgan marks a newsletter read; Casey still sees it unread. The event remains on the calendar.
- Morgan marks a form matter Done; it is done for both parents in one operation.
- A school changes an already-read event time; it appears as an unread update with the delta.
- A parent toggles Info on; it stays Info after reprocessing and no task reminder remains active.
- Switching Jamie → Sofia never shows Jamie's calendar marks, even when Sofia has no cases.
- A 4–5 score deadline has a short cell label; vacation days have shading and accessible details.
- Two mailboxes with the same Gmail ID both ingest their own messages without mixing children.
- A forwarded PDF appears as an Email matter with its attachment and recoverable sender history.
- A translation/summary failure leaves the original readable and makes incomplete coverage explicit.

## Mockup boundaries

The prototype demonstrates calendar selection, high-priority labels, vacation shading,
child filters, viewer switching, personal read state, shared Done/Undo, Info correction,
three reader levels, Settings context, and WhatsApp examples. It has no backend, real
AI, live connectors, persistent data, search implementation, or sending capability.
Reloading resets all sample changes. It is a review artifact, not a production patch.
