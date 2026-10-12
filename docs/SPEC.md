> Public specification: all named people and sample records below are fictional.
> Deployment credentials and real family/school information are intentionally excluded.

# School Administration Agent

## 1. Context

We are a family living in Germany. Our son Jamie attends a Gymnasium in Bavaria.

The school generates a very large amount of communication through several channels, primarily:

* Elternportal
* Email
* PDF attachments
* Links to school webpages
* Links to external websites
* Registration pages and forms
* Notices about activities, events, materials, timetable changes, trips, payments, etc.

The communication volume is high enough that manually reading and processing everything is becoming a significant waste of time.

The problem is not simply the number of emails.

The more important problem is that a small amount of relevant information is buried inside a large amount of low-value communication.

Examples include:

* newsletters with no consequences
* descriptions of activities that already happened
* messages addressed mainly to pupils
* generic school information
* announcements that require no action
* long Elternbriefe containing only one relevant deadline
* PDFs linking to another webpage containing the actual instructions
* messages that modify or correct information sent previously

At the same time, some messages are genuinely important:

* bring a particular item tomorrow
* register for an activity
* fill out a form
* sign a consent
* pay something
* remember an event
* attend an Elternabend
* note a timetable change
* respond before a deadline
* prepare something for Jamie
* react to a cancellation or date change

The existing communication channels provide information, but they do not manage the resulting obligations.

We want to build a system that does.

---

# 2. Product ambition

The objective is NOT:

> Summarize school emails.

The objective is:

> Build a private school-administration agent that autonomously reads, investigates, understands and organizes school communications, while interrupting the parents only when something actually matters.

The system should eventually make it unnecessary for Morgan and Casey to routinely read school emails or browse Elternportal themselves.

The desired experience is:

> Nothing important from school should escape us, and almost nothing unimportant should interrupt us.

The system should understand the difference between:

* information
* an event
* an obligation
* a deadline
* something Jamie has to do
* something the parents have to do
* something one parent has already handled
* an update to previously known information
* irrelevant noise

---

# 3. Users

Primary users:

* Morgan
* Casey

Child:

* Jamie

The system is specifically designed around managing Jamie's school communication.

It should nevertheless be architected so that another child could eventually be added without redesigning the core domain.

---

# 4. Central Google account

Jamie already has a Gmail account that he currently does not use for anything significant.

We can reuse this account as the central Google identity for the school system.

It can own:

* the central Gmail inbox for school communication
* a dedicated Google Calendar
* possibly some school-related Google Drive storage later

Create a calendar:

> Jamie – Schule

Share it with:

* Morgan
* Casey

Both parents should have permission to see and preferably edit the calendar.

Google Calendar is a projection of our internal state.

It is NOT the authoritative datastore.

Our application database remains the source of truth.

---

# 5. Email ingestion strategy

School-related email received by Morgan or Casey can be automatically forwarded to Jamie's Gmail.

Examples of Gmail rules may include:

* school domains
* particular teachers
* Elternportal notification emails
* other known school communication addresses

Jamie's Gmail therefore becomes the central school mailbox.

The application reads it using the Gmail API and OAuth.

Do not store the Google password.

Use normal Google OAuth with refresh tokens stored securely on the backend.

Possible future behavior:

* forward
* label as school
* optionally archive the original from Morgan/Casey's inbox

But email is only one input.

---

# 6. Elternportal integration

Elternportal should also be accessed directly.

We do not want to depend only on its notification emails.

The application should periodically retrieve whatever can reasonably be retrieved from Elternportal, including where supported:

* Elternbriefe
* messages
* announcements
* appointments
* polls
* notices
* timetable-related information
* attachments
* other relevant portal objects

Where a direct client/library or HTTP integration exists, prefer that over browser automation.

Use Playwright only when needed.

Elternportal notification emails can still be useful as:

* triggers
* fallback signals
* redundancy

The ingestion system must deduplicate:

> "New Elternbrief available"

against the actual Elternbrief retrieved directly from the portal.

---

# 7. Fundamental domain concept: Case

The fundamental unit of the system is NOT an email.

It is a:

> Case

A case represents one real school matter.

Example:

> Schullandheim October 12–14

A single case may contain:

* one Elternportal message
* one email
* two PDFs
* a school webpage
* an external registration website
* a form
* a later correction email
* one calendar event
* three child requirements
* one parent task
* several reminders

All these objects belong to the same case.

This is essential because school communication is often incremental.

Example:

Monday:

> Wandertag is October 10.

Wednesday:

> Children should bring sports shoes.

Friday:

> Correction: Wandertag moved to October 12.

These must update ONE case.

The system should NOT create three unrelated summaries.

---

# 8. Example case lifecycle

Possible case states:

* NEW
* INGESTING
* INVESTIGATING
* READY_FOR_REASONING
* ACTION_REQUIRED
* WAITING_FOR_PARENT
* INFORMATION_ONLY
* COMPLETED
* CANCELLED
* FAILED
* NEEDS_REVIEW

Exact state machine can be refined during implementation.

Important requirement:

Case history must be retained.

---

# 9. Investigation philosophy

The system should investigate communications before summarizing them.

For example:

Email says:

> Information about Medienprojekt is attached.

PDF says:

> Registration information is available at example.org/projekt.

Website says:

> Registration closes September 30.

Registration page says:

* parental consent required
* headphones needed
* Google account required

The system should not stop at the email.

It should autonomously follow the information chain:

Email
→ PDF
→ website
→ relevant subpage
→ form

Only after relevant evidence is gathered should the system determine the implications.

---

# 10. Crawling and investigation

Do NOT make the LLM responsible for basic crawling.

Use deterministic software whenever possible.

Examples:

* HTTP GET
* HTML parsing
* link extraction
* MIME parsing
* attachment downloading
* PDF download
* hashing
* duplicate detection
* canonical URL handling
* detecting redirects
* checking page status
* storing retrieved source metadata

The AI should be used for semantic decisions.

Examples:

* Is this link relevant?
* Does this page contain actionable information?
* Which facts matter?
* Is this an update to an existing case?
* Does this imply an obligation?
* Should we investigate another referenced page?

---

# 11. Browser / Playwright

Use Playwright as a secondary investigation mechanism.

Prefer:

HTTP + parser

over:

real browser

when possible.

Playwright becomes useful when:

* content is generated with JavaScript
* login is required
* a page needs interaction
* a download is hidden behind a button
* a form must be inspected
* a portal page cannot easily be accessed with HTTP
* authenticated session state is needed

Playwright should run in a separate worker/container.

For v1, browser automation is primarily READ-ONLY.

It should inspect interfaces.

It should NOT autonomously submit external forms.

---

# 12. External forms — v1 policy

Any consequential interaction with an external system should be escalated to the parents.

Examples:

* registration forms
* parental consent
* trip confirmation
* appointment booking
* teacher communication
* upload
* payment
* Elternportal confirmation
* external website registration

The agent MAY:

* open the form
* inspect all fields
* understand what the form is asking
* identify the deadline
* recommend likely answers
* identify information it does not know
* provide the direct form URL

The agent MUST NOT in v1:

* submit the form
* accept conditions
* send a teacher message
* make a payment
* make a booking
* commit an external action

Example WhatsApp message:

> 🔴 Schullandheim — Action required
>
> Deadline: 29 September
>
> The school needs confirmation of Jamie's participation.
>
> Recommended answers:
>
> Participation: Yes
>
> Emergency contact: Morgan
>
> Vegetarian meal: No
>
> Photo permission: requires your decision
>
> Nothing needs to be uploaded.
>
> [Open form]

This is the intended automation boundary for v1.

---

# 13. AI architecture

Use MODEL ROUTING.

Do not use the strongest/most expensive model for every operation.

Use logical model roles.

For example:

## Extraction model

Used for:

* classification
* basic summarization
* structured fact extraction
* relevance decisions
* link relevance
* dates
* people
* amounts
* requirements
* basic document understanding

This should normally be a cheap/fast model.

## Reasoning model

Used for:

* case-level interpretation
* resolving conflicting information
* understanding implications
* matching new information to old cases
* determining parent vs child obligations
* interpreting nuanced language
* detecting corrections
* deciding whether information materially changed
* handling ambiguous communications

Use a stronger reasoning model.

## Vision/document model

May be separate if useful.

Used when:

* PDF visual layout matters
* scanned image
* image attachment
* table
* screenshot
* visually structured notice

## Fallback model

Optional.

Can be used if another model fails or returns low confidence.

---

# 14. Runtime model routing concept

Do not hard-code model names throughout application code.

Use something like:

ModelRouter.extract(...)
ModelRouter.reason(...)
ModelRouter.vision(...)
ModelRouter.fallback(...)

Model configuration should be editable from the frontend.

Example Settings:

Extraction model:
[ configurable ]

Reasoning model:
[ configurable ]

Vision model:
[ configurable ]

Fallback:
[ configurable ]

Reasoning effort:
[ configurable by role ]

---

# 15. Suggested runtime reasoning strategy

Routine extraction:

cheap model
low/no reasoning where supported

Normal case interpretation:

strong model
medium reasoning

Ambiguous/consequential case:

strong model
high reasoning

Potential escalation conditions:

* form found
* deadline found
* consent found
* payment found
* schedule change
* cancellation
* correction to previous communication
* contradiction with existing case
* low confidence
* multiple related messages
* unusual requirement
* missing evidence
* authenticated external source

Avoid expensive high reasoning where the item is obviously irrelevant.

Example:

> Thank you to everybody who participated in our summer celebration.

Cheap model determines:

* no future event
* no action
* no relevant obligation

Case can end without expensive reasoning.

---

# 16. Distilled evidence

Do not necessarily send all raw crawled content to the expensive reasoning model.

The extraction stage should produce structured evidence.

Example:

Source: schullandheim.pdf

Facts:

* trip October 12–14
* parental confirmation required
* deadline September 29
* sleeping bag required

Source: registration website

Facts:

* participation yes/no required
* emergency contact required
* dietary preference requested
* photo permission requested

The reasoning model receives the distilled evidence.

It should still be able to request the original source if needed.

This controls cost and keeps prompts focused.

---

# 17. Investigation limits

Crawling should be bounded.

Configurable values:

* maximum link depth
* maximum pages per case
* maximum document size
* maximum AI calls per case
* maximum token usage
* optionally maximum estimated cost per case

The system should follow semantically relevant links, not crawl arbitrary websites.

Relevant examples:

* Anmeldung
* Teilnahme
* Termine
* Elterninformation
* Kosten
* Material
* FAQ
* Einverständnis
* Downloads
* Form
* Registration

Usually ignore:

* Impressum
* unrelated news
* advertising
* generic navigation
* social media
* irrelevant footer links

---

# 18. Investigation completeness

A case should have a notion of:

investigationComplete

If false, store why.

Examples:

* referenced PDF could not be downloaded
* external site unavailable
* login needed
* relevant page unclear
* model believes another source must be inspected

Do not silently produce a definitive conclusion when evidence is missing.

Notify parents where appropriate.

Example:

> ⚠️ I found a registration link in the Elternbrief but could not access the page. Please check it manually.

---

# 19. Derived case information

The reasoning phase should produce structured results.

Possible concepts:

* parentActionRequired
* childActionRequired
* deadline
* event
* childRequirement
* parentRequirement
* payment
* consent
* form
* scheduleChange
* cancellation
* exam/test
* informationalNotice
* importance
* confidence
* summary

Prefer domain objects over free text.

---

# 20. Parent actions

Examples:

* fill form
* sign form
* confirm participation
* pay fee
* attend event
* book appointment
* send item
* respond
* provide information

Action fields may include:

* id
* caseId
* title
* description
* dueAt
* assignedTo
* status
* source
* externalUrl
* recommendedInput
* confidence
* createdAt
* completedAt

Statuses could include:

* OPEN
* ASSIGNED
* IN_PROGRESS
* COMPLETED
* CANCELLED
* OVERDUE

---

# 21. Child requirements

Separate parent actions from things Jamie must do.

Examples:

* bring sports shoes
* bring €5
* bring instrument
* prepare presentation
* wear sports clothes
* arrive at different time

These may generate reminders but are not necessarily parent tasks.

---

# 22. Calendar events

Examples:

* Wandertag
* Elternabend
* Sportfest
* school trip
* concert
* exam
* registration deadline
* school closure
* timetable exception

Calendar events should be generated automatically where confidence is sufficient.

Use Jamie's shared Google Calendar:

Jamie – Schule

Store Google event ID in the internal database.

If underlying school information changes:

update the existing calendar event.

Do not create duplicates.

---

# 23. Updates and corrections

This is one of the most important requirements.

The agent must understand communications such as:

> Correction to the information from Monday...

> Please note the new time...

> The event has been postponed...

> The previous consent remains valid...

New evidence may mutate existing state.

Example internal mutations:

MOVE event Oct 10 → Oct 12

ADD requirement sports shoes

REMOVE payment obligation

CHANGE deadline Sep 29 → Oct 2

CANCEL event

The WhatsApp notification should emphasize the delta.

Example:

> 🟠 Update: Sportfest moved from Tuesday to Thursday. The required sports clothes remain unchanged.

---

# 24. Parent ownership

Morgan and Casey share the system.

Tasks need ownership.

Example:

Casey:

> I'll handle the Schullandheim form.

Backend:

assignedTo = Casey
status = IN_PROGRESS

Then Morgan asking:

> What's still pending?

can receive:

> Schullandheim is being handled by Casey. Medienprojekt registration is still unassigned.

This prevents duplicated effort.

---

# 25. WhatsApp

WhatsApp is the primary day-to-day interaction channel.

Assume initially two independent 1:1 conversations:

Morgan ↔ School Agent

Casey ↔ School Agent

Both operate against the same backend state.

Do not make WhatsApp itself the source of truth.

The application database owns the shared state.

Examples of natural interactions:

> Anything pending?

> What's happening next week?

> What do I need to do for the Schullandheim?

> I'll handle this.

> Mark this done.

> Why do we need to bring €5?

> Show me the original source.

Important notifications should be pushed automatically.

Less important information should preferably be summarized in a digest.

---

# 26. Notification levels

At least distinguish:

## Immediate

Examples:

* parent action required
* deadline approaching
* cancellation
* important timetable change
* child must bring something tomorrow
* payment required
* system unable to inspect important source

## Digest

Examples:

* general useful information
* new non-urgent event
* updates without immediate consequences

## Silent

Examples:

* generic newsletter
* retrospective report
* irrelevant publicity
* activity with no future consequence

The goal is not to reproduce the school's notification volume through WhatsApp.

The agent should actively suppress noise.

---

# 27. Frontend

A frontend is required.

Recommended:

Angular

Backend:

Kotlin + Spring Boot

Database:

PostgreSQL

The frontend acts as the system control center.

WhatsApp remains the conversational UI.

---

# 28. Frontend sections

## Dashboard

Main question:

> What currently needs our attention?

Show:

* urgent actions
* upcoming deadlines
* upcoming events
* notable recent changes
* connector health

Avoid clutter.

---

## Calendar / Agenda

Views:

* month
* week
* agenda

Types may include:

* school event
* deadline
* exam
* parent event
* item to bring
* payment
* timetable change
* music/event activity

Clicking an entry opens the related Case.

---

## Actions

Show:

* overdue
* due soon
* assigned to Morgan
* assigned to Casey
* unassigned
* completed

Allow:

* assign
* mark done
* reopen
* open external form
* inspect case

---

## Cases / Inbox

Show everything the agent processed.

Example:

21 Sep — Medienprojekt — Action required

21 Sep — School newsletter — Ignored

20 Sep — Sportfest — Event

19 Sep — Timetable update — Calendar changed

Clicking a case shows:

* summary
* state
* confidence
* actions
* events
* source chain
* investigation history
* external references
* changes from previous state

---

## Settings

Areas:

Google

* OAuth status
* Gmail connection
* calendar selection
* shared users
* last successful sync

Elternportal

* portal URL
* username/connection status
* last successful sync
* reconnect
* test connection

WhatsApp

* integration status
* parent numbers
* notification preferences
* daily digest time

AI

* extraction model
* reasoning model
* vision model
* reasoning effort
* confidence thresholds
* crawl limits
* escalation rules
* maximum budget limits

Automation

* investigation: automatic
* calendar creation: automatic
* task creation: automatic
* form submission: disabled
* teacher messages: disabled
* payments: disabled

Family policy

Examples:

* exams → calendar only
* payment → notify immediately
* photo consent → always ask
* music events → always show
* newsletters → ignore unless actionable

---

## Audit

Show important system actions.

Example:

11:32 New Elternbrief detected

11:32 Downloaded attachment

11:33 Visited external page

11:33 Detected deadline

11:34 Created task

11:34 Created calendar event

11:35 WhatsApp sent to parents

12:04 Casey marked task complete

This is important for trust and debugging.

---

# 29. Source provenance

Every conclusion should be traceable.

Example:

Case:

Schullandheim

Sources:

1. Elternportal message
2. Elternbrief.pdf, page 2
3. school website
4. registration page

The user should be able to inspect the original material.

The AI must not become an opaque authority.

---

# 30. Health monitoring

The system must distinguish:

> No new messages.

from:

> I haven't successfully checked Elternportal for 12 hours.

Track connector health:

* Gmail
* Elternportal
* Google Calendar
* WhatsApp
* Playwright worker
* AI provider

Frontend example:

Gmail ✅ checked 2 min ago

Elternportal ✅ checked 6 min ago

Calendar ✅ connected

WhatsApp ✅ connected

Browser worker ✅ healthy

If something stops working for a meaningful period, notify parents.

---

# 31. Security

Credentials must remain server-side.

Never expose secret values back to the frontend after saving.

Store securely:

* Google refresh tokens
* Elternportal credentials/session
* WhatsApp access tokens
* OpenAI API key
* encryption keys

Prefer secret references / encryption at rest.

The model should never receive credentials.

Instead the model receives tools such as:

getPortalMessage(id)

fetchPage(url)

readDocument(id)

It should not receive passwords or OAuth tokens.

---

# 32. Prompt injection / untrusted content

External websites, PDFs and messages are untrusted data.

A webpage may contain text such as:

> Ignore previous instructions...

That text must never be treated as agent policy.

External content may provide facts.

It may NOT:

* redefine system instructions
* grant permissions
* expose credentials
* authorize submissions
* cause arbitrary tool calls

Enforce safety in application code, not only prompts.

---

# 33. Data retention

We probably do not need to permanently retain every irrelevant newsletter.

Persist:

* source IDs
* hashes
* important documents
* structured extracted facts
* cases
* events
* tasks
* actions
* audit logs

Retention for raw unimportant content can be configurable.

Critical data should be backed up.

---

# 34. Family memory

Maintain structured context.

Examples:

Jamie:

* school
* class
* teachers
* known recurring activities

Parents:

* Morgan
* Casey

Preferences:

* communication rules
* consent preferences where explicitly configured
* notification preferences

Do NOT let the AI silently invent persistent personal facts.

Structured data should be inspectable and editable.

---

# 35. Feedback / learning

Users must be able to correct the agent.

Examples:

> This is irrelevant.

> You missed an action.

> This should not have generated a WhatsApp.

> Always tell us about this type of thing.

Frontend controls could include:

* Wrong classification
* Not important
* Missing action
* Wrong event
* Wrong deadline

Initially this should update explicit rules/preferences.

Do not require model fine-tuning.

---

# 36. Recommended technical architecture

Possible deployment:

Docker Compose

Services:

school-api
school-web
postgres
playwright-worker

Optional later:

redis
queue worker
object storage

Primary backend:

Kotlin
Spring Boot

Frontend:

Angular

Database:

PostgreSQL

Browser worker:

Playwright

AI:

OpenAI API through a ModelRouter abstraction

---

# 37. Suggested backend modules

Potential modules/packages:

auth

gmail

google-calendar

elternportal

ingestion

cases

documents

crawler

browser

ai

reasoning

actions

notifications

whatsapp

audit

settings

health

security

Do not over-split into microservices initially.

Prefer a modular monolith plus isolated Playwright worker.

---

# 38. Idempotency

Everything must be idempotent.

Repeated polling of Gmail or Elternportal must not create duplicate cases.

Repeated reasoning must not duplicate tasks.

Repeated calendar synchronization must not create duplicate events.

Use:

* source IDs
* external IDs
* hashes
* deduplication keys
* stable case relations

---

# 39. Scheduling

Use background scheduling for:

* Gmail polling if push integration is not initially used
* Elternportal polling
* deadline reminders
* daily digest
* health checks
* retrying failed investigations

Eventually Google push notifications/webhooks may be preferable, but polling is acceptable for MVP.

---

# 40. Error and retry model

Classify failures.

Examples:

TRANSIENT

* network failure
* temporary API failure

AUTHENTICATION

* token expired
* portal login failed

CONTENT

* unsupported document
* unreadable PDF

INVESTIGATION

* missing page
* blocked site
* ambiguity

AI

* malformed structured output
* timeout
* low confidence

Retries should depend on category.

Do not endlessly retry non-transient errors.

---

# 41. Cost monitoring

Store AI usage per case.

Track at minimum:

* model
* input tokens
* output tokens
* reasoning effort if available
* number of calls
* estimated cost

Frontend should eventually allow basic cost visibility.

Possible settings:

maximum pages per case

maximum AI calls per case

maximum estimated cost per case

Cheap extraction should eliminate most irrelevant content before expensive reasoning is used.

---

# 42. Manual ingestion

Allow manual upload / URL submission in the frontend eventually.

For example:

Morgan drags in a PDF the school sent through another channel.

The system creates a case and processes it through the same pipeline.

Useful but not necessarily first MVP milestone.

---

# 43. Search / conversational retrieval

WhatsApp and perhaps the frontend should eventually allow questions across historical state.

Examples:

> When is the Elternabend?

> What did they say about the Schullandheim?

> Do we still need to pay anything?

> What should Jamie bring Friday?

Answers should come from structured state + source evidence.

Do not invent information.

---

# 44. Important design principle

The system should optimize for:

> parent attention

not:

> maximum information extraction

A perfectly functioning system may process six school communications in one day and send Morgan and Casey zero messages.

That is success.

---

# 45. MVP boundary

For v1, INCLUDE:

* Gmail OAuth
* Gmail ingestion
* Google Calendar integration
* shared Jamie – Schule calendar
* direct Elternportal ingestion where practical
* case engine
* deduplication
* PDF/document ingestion
* external link investigation
* basic HTTP crawler
* Playwright read-only investigation
* configurable cheap extraction model
* configurable strong reasoning model
* actions
* calendar events
* deadline handling
* parent assignment
* WhatsApp integration
* frontend dashboard
* frontend actions
* frontend cases
* frontend settings
* frontend audit
* connector health
* retries
* source provenance

DO NOT initially implement:

* automatic form submission
* automatic registration
* sending teacher messages
* payments
* automatic appointment booking
* autonomous portal changes
* arbitrary computer-use behavior
* complicated Google Tasks integration
* complex Drive filing
* model fine-tuning

---

# 46. Suggested implementation phases

## Phase 0 — Repository and infrastructure

Create:

* backend
* frontend
* database
* migrations
* Docker environment
* configuration system
* secret handling
* basic authentication for Morgan/Casey
* tests and CI baseline

---

## Phase 1 — Domain model

Implement:

* Case
* Source
* Investigation
* Evidence
* Action
* Event
* ChildRequirement
* ExternalForm
* Notification
* AuditEntry
* ConnectorHealth

Implement case lifecycle.

Implement migrations.

Implement CRUD/service tests.

---

## Phase 2 — Google integration

Implement:

* Google OAuth
* Jamie Gmail access
* email ingestion
* attachments
* Google Calendar
* creation/update/delete synchronization

Ensure idempotency.

---

## Phase 3 — Investigation pipeline

Implement:

* document storage
* PDF extraction
* HTML extraction
* URL discovery
* crawling
* canonicalization
* deduplication
* evidence generation

Initially use deterministic fixtures/tests.

---

## Phase 4 — Model Router

Implement abstraction:

ExtractionModel
ReasoningModel
VisionModel

Configuration stored in DB/settings.

Require structured JSON/schema outputs.

Add usage/cost logging.

---

## Phase 5 — Case reasoning

Implement:

new source
→ extract evidence
→ identify/attach case
→ decide whether investigation continues
→ case reasoning
→ state mutation

Support:

* new actions
* events
* deadlines
* child requirements
* changes
* cancellations

---

## Phase 6 — Elternportal

Implement direct access.

Start read-only.

Retrieve available objects and attachments.

Map them into Sources.

Deduplicate against Gmail notifications.

---

## Phase 7 — Playwright worker

Create separate browser worker.

Implement:

* authenticated session
* navigate
* inspect page
* retrieve DOM/text
* inspect forms
* download files

No external submission.

---

## Phase 8 — Frontend

Create:

Dashboard

Calendar/Agenda

Actions

Cases

Settings

Audit

Health status

Prioritize usefulness over visual complexity.

---

## Phase 9 — WhatsApp

Implement:

* Morgan
* Casey
* inbound webhook
* outbound notifications
* shared backend state
* ownership commands
* task completion
* case questions
* deep links to frontend/source

---

## Phase 10 — Reliability

Implement:

* retries
* connector monitoring
* auth-expiry detection
* failed investigation handling
* alerting
* deduplication stress tests
* concurrent processing
* idempotency tests

---

## Phase 11 — Feedback/policy

Implement:

* irrelevant
* wrong classification
* missing action
* notification preferences
* family rules

Use these rules as context for future reasoning.

---

# 47. Development principles for the coding agent

The implementation agent should work autonomously where requirements are sufficiently clear.

It should:

* maintain a TODO/progress file
* implement vertically usable slices
* write tests
* run tests before moving on
* use migrations
* avoid premature microservices
* favor explicit domain models over generic JSON blobs
* favor deterministic logic over AI where deterministic logic suffices
* isolate external integrations behind interfaces
* keep model configuration abstract
* make operations idempotent
* preserve an audit trail
* never implement external write actions in v1 unless explicitly instructed later

When an architectural choice is minor and reversible, choose a sensible option and continue.

Do not block implementation by asking unnecessary questions.

If a requirement is genuinely ambiguous and materially affects architecture, document alternatives and ask.

---

# 48. Suggested coding-agent model

For implementation in Codex:

Primary recommendation:

GPT-6 Sol
High reasoning

Use for:

* architecture
* domain design
* major features
* integrations
* long autonomous implementation

Medium reasoning is sufficient for:

* straightforward CRUD
* DTOs
* basic tests
* mechanical refactoring

xHigh can be used selectively for:

* difficult architecture review
* complicated concurrency/idempotency issue
* difficult OAuth problem
* difficult Playwright issue
* difficult debugging

Do not waste xHigh reasoning on routine implementation.

---

# 49. First objective for the implementation agent

Do not immediately start by implementing WhatsApp or Playwright.

First produce:

1. refined architecture
2. domain model
3. case state machine
4. major interfaces
5. database schema proposal
6. repository structure
7. implementation milestones
8. unresolved technical risks

Then begin implementation once those foundations are internally consistent.

The most important architectural questions are:

* how sources become cases
* how cases evolve over time
* how new evidence mutates state
* how idempotency works
* how actions/events are derived
* how AI reasoning is isolated from deterministic infrastructure
* how evidence/provenance is retained

---

# 50. Definition of success

The project succeeds when Morgan and Casey can largely stop reading raw school communication.

A successful workflow looks like:

School sends several emails, portal messages, PDFs and links.

The system silently investigates them.

Most disappear because they have no consequence.

One creates:

> 🔴 Jamie — action required
>
> Medienprojekt registration closes Thursday.
>
> I checked the Elternbrief, attached PDF and registration website.
>
> You need to:
>
> * confirm participation
> * answer photo-consent question
>
> Recommended answers:
>
> * Participation: Yes
> * Photo consent: your decision
>
> Jamie needs headphones on October 6.
>
> Event added to calendar.
>
> [Open form]

Morgan replies:

> I'll do it.

The task becomes assigned to Morgan.

Casey no longer needs to think about it.

The deadline is tracked.

The event appears in the shared calendar.

The original sources remain available for verification.

Everything else stays quiet.

That is the product we want to build.
