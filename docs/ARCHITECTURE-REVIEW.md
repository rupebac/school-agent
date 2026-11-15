# Architecture review — November 2026

## Assessment

The modular monolith fits this product. Shared portal contracts, immutable source
history, audited case transitions, bounded document readers and a durable WhatsApp
outbox are useful boundaries worth preserving. Replacing these with microservices
would increase operational cost without resolving the actual consistency problems.

This review covered the package structure and critical paths for authentication,
child access, acquisition, interpretation, planning, delivery, calendar projection,
frontend state and release tooling. It is a source review with regression tests, not
an exhaustive security audit or a live exercise of every provider endpoint.

## Changes made

| Finding | Change and validation |
|---|---|
| Three briefing paths repeated eligibility and loaded cases before audience filtering | Added `BriefingCaseSelector`, used by daily/weekly text and scheduled plans. Added a regression proving inconsistent event child metadata cannot expose another child's case. Existing archive/account-notice/priority tests remain applicable. |
| Login caught every error as a wrong password, even after saving an authenticated context | Failed provisioning invalidates the session/context. Authentication outages return safe 503 responses; rejected credentials return 401. Tests cover persistent-login failure after context creation. |
| Calendar deep links were discarded after authentication | Added calendar to the internal return-route allowlist, retained external-URL rejection, and prevented duplicate in-flight submissions. Frontend tests exercise these paths and outage messaging. |
| Model caching depended on the MIME parser solely for hashing | Moved that call to the existing common SHA-256 primitive. Email identity reuses the same primitive; persisted hash values remain compatible. |
| Architecture and UI documentation described obsolete Basic-password browser storage and speculative pipeline guarantees | Replaced architecture prose with actual ownership, flows, consistency guarantees and limitations; corrected frontend authentication documentation. |

## Remaining design constraints

These are explicit follow-up boundaries, not claims of newly observed production failures.

| Priority | Area and evidence | Direction |
|---|---|---|
| High | `CaseReasoningService.reason` and `CalendarProjectionService` can hold transactions across model/provider calls | Separate snapshot/proposal acquisition from locked validation and apply, following `SchoolCorrectionService`. Preserve optimistic fingerprint checks, stable IDs and recovery intent. This requires concurrency/failure-injection tests, not a mechanical method extraction. |
| High | `WhatsappOutbox` rechecks case notices, but aggregate plans have no per-row revision references | Persist structured plan provenance and recompose or invalidate changed rows before first delivery. Retain immutable accepted messages and ambiguous-send protection. Do not infer dependencies by parsing rendered text. |
| Medium | Connector health is aggregated by connector type | Persist per-child/account health and derive the family banner from those rows; one child's successful refresh should not hide another's failure. |
| Medium | School-domain filtering deliberately accepts the configured inbox when the list is empty | Make onboarding's dedicated-inbox assumption explicit. A broad relevance classifier needs a reviewable quarantine, representative evaluation data and a recovery path before it may discard school mail. |
| Medium | `CaseReasoningService` combines preparation, interpretation, merge and apply; conversational handling is also large | Extract at those behavioural boundaries as transaction ownership is improved. Avoid splitting files merely by line count or introducing a universal mutation framework. |
| Medium | Some business dependencies remain nullable with defaults, chiefly to support older test construction | Make mandatory production collaborators required when their modules are changed; keep optionality only for real disabled capabilities. Test doubles should supply collaborators. |
| Medium | Jobs, several API list paths and relevance evaluation scan full tables | Introduce bounded repositories and pagination when retention/data volume warrants it, preserving complete-snapshot semantics for school withdrawals. Measure query counts before adding caches. |
| Medium | Package boundaries are conventions, not enforced modules; `FamilyInboxService` still imports an API exception and HTTP status types | Move domain failures to domain-owned exceptions with API-layer mapping, then add dependency rules. Provider adapters should remain unaware of case mutation/rendering, and common primitives must not depend on feature packages. |

The next substantial architecture change should address transaction ownership and
structured plan provenance together with their recovery tests. Current behaviour must
remain explicit: local Done, school receipts, personal read state, archived visibility
and remote delivery are separate state dimensions.
