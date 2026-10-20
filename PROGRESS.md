# PROGRESS — School Administration Agent

Read this living status file before making changes. This public ledger contains
implementation state only; private deployment notes and family records stay outside Git.

## Current state

WhatsApp Done buttons implemented on 2026-10-20: existing individual action-letter
templates gain a localized quick reply alongside Open letter, without new template
names. Buttons bind to the original recipient and message, recheck access and the
school revision, and reuse shared family completion with history and audit. Duplicate
taps are idempotent; changed matters require review. Information notices and plans
keep their existing controls. Template sync can edit mismatched utility layouts in
place and waits for matching Meta approval. Validation: 446 backend and 87 frontend
tests, plus production frontend build, pass. Backend/frontend deployed on 2026-10-20;
public health and frontend fingerprint verified. Existing action templates were
updated in place and submitted for Meta approval; affected sends wait for approval.

Local review and follow-up on 2026-10-20: [source and regression audit](docs/REVIEW-2026-10-20.md)
records the initial fixes and corrections for all nine follow-up findings: connector
network restrictions, Calendar target reconciliation/stable IDs, durable inbound
WhatsApp, guarded deployment/full recovery, audited requirement completion, bounded
document/Gmail processing, sandboxed browser worker, functional CI and bilingual UI.
The backend and frontend fixes were deployed on 2026-10-20 with migrations V31–V33.
Full recovery backup, migration checksums, running images, public health, frontend
fingerprint and bounded log rotation were verified. The optional browser worker
remains inactive; its updated source and sandbox policy are available for activation.

Validation: 432 backend tests, 87 frontend tests, worker policy/isolation checks,
real PostgreSQL recovery and release failure-gate checks pass. The frontend builds
at 466.64 kB, below its initial-bundle warning budget. Real-backend shared-browser
checks cover login, persistent child creation, Calendar, Spanish settings and mobile
layout. Live provider behavior and production load remain separate validation.

The application supports multiple children and two school providers (one provider
per child), shared family AI/WhatsApp configuration, parent-specific read marks and
notification preferences, email ingestion, document reading and a filtered calendar.

- School letters and attachments are deduplicated, extracted and reasoned into cases,
  family actions, child requirements and events. Translation/model responses persist.
- School email handles forwarding metadata, sender domains, duplicate messages and
  threads. Reply drafts can be copied manually; the agent does not send email.
- Inbox filters include information, done, archive and severity; date sections collapse.
  Undated/information relevance expires with an AI-supported estimate or configurable
  retention. Original cases remain accessible after hiding them from the active inbox.
- Calendar provides dates, high-importance labels, holiday shading and an Exams filter.
  iOS/Android PWA support and persisted child/navigation selection are implemented.
- Private integration diagnostics retain 60 days of school-feed snapshots, skipped
  record reasons, reminder decisions and WhatsApp queue/delivery transitions. Snapshots
  use opaque references and daily checkpoints plus immediate set changes, including
  records discarded as historical. Normal audit history is kept separate from diagnostic
  noise. Docker logs rotate at 5 MB × 3 files per service with compression; diagnostics
  survive restarts in PostgreSQL. Deployed on 2026-10-20; rotation is verified on
  the running services. Validation: 386 backend tests (including PostgreSQL
  integration tests), 84 frontend tests,
  Compose logging checks and privacy/secret scans pass.
- Administrators can now manage the shared Google OAuth client in Family settings.
  Client credentials are encrypted, secrets are never returned to the browser, and
  existing environment configuration remains the fallback. The bilingual setup guide
  displays the exact server callback to copy into Google Cloud and distinguishes app
  registration from each child's mailbox connection. Client replacement requires
  confirmation and reconnecting all accounts; same-client secret rotation preserves
  refresh tokens. Pending consent and cached access cannot use outdated credentials.
  Deployed on 2026-10-20; live health and the new frontend are verified.
  Validation: 386 backend tests, 84 frontend tests,
  production frontend build, desktop/mobile browser checks and privacy/secret scans
  pass. The build retains the existing initial-bundle warning (515.38/500 kB).
- Google Calendar now backfills pending upcoming events after a child's connection,
  without requiring another school import. A bounded per-child job runs every minute,
  waits for the dedicated school calendar, and retries failed projections after five
  minutes. Row locks and status rechecks prevent racing ingestion/backfill inserts;
  cancelled events, historical events and school reports are excluded. Calendar API
  timestamps use RFC 3339 offsets. Typed calendar diagnostics retain 60 days and API
  failures omit provider response bodies. Validation: 394 backend tests, 84 frontend
  tests and the production frontend build pass. Deployed on 2026-10-20; live
  health, frontend version and successful initial Calendar backfill are verified.
- WhatsApp daily/weekly plans reuse the existing template catalog, identify each child,
  avoid duplicating related bullets, and limit tomorrow's agenda to tomorrow's items.
- Native school feeds support class-scoped exams, forgotten homework/material reports
  and payments. Provider student identity is separate from class guards. Unsupported
  optional sections do not block letters or connection health. First snapshots are quiet.
- Native records persist independent enrichment/calendar/notification flags and retry
  failed effects. Empty/invalid feeds cannot withdraw valid future exams. School-provided
  dates stay authoritative. Historical reports are excluded from Google Calendar.
- Wording versions repair older generated text once without reopening Done, changing
  dates/source identity, or resending notifications. Unexplained timetable codes remain
  in source documents and are excluded from exam translation input.
- Authentication, per-child access, encrypted vault storage, case history/audit and
  schema migrations are implemented. Required deployment secrets have no usable defaults;
  fresh databases require an explicitly configured administrator and start without children.

## Publication preparation

- Family/deployment details have been replaced by fictional examples or removed from
  docs, fixtures, UI copy, templates and configuration. Historical operator notes and
  the original Git history are backed up privately outside this repository.
- AGENTS.md now requires privacy review of code, docs, examples, defaults and history.
  A staged-content hook and CI scan check for credentials and accidental private data.
- Publication changes include sanitized bootstrap migrations. Existing installations
  must review/repair only those known checksums before deploying, without changing
  their actual accounts, school connections or family data. See the setup guide linked from README.
- Validation: **366 backend tests and 79 frontend tests**, no failures/errors/skips.
  Production frontend build passes with the existing 511.50/500 kB bundle warning.
  Production-bootstrap tests confirm one explicitly configured administrator and zero
  default children; legacy human actor labels remain readable without rewriting history.
- Tracked/staged privacy review and redacted secret scans pass. Historical credential
  scan found only Docker-tag false positives; private identifiers in historical docs and
  fixtures required cleanup. Runtime files, private notes and backup bundles are excluded.
- The release is prepared as one root commit, with a private external backup of the
  original history. Repository visibility remains PRIVATE. A Git history rewrite alone
  cannot purge hosting caches; resolve those before changing visibility. This cleanup
  was deployed to an existing installation on 2026-10-20 after verifying and
  updating only the reviewed V2/V7/V8/V9 checksum metadata. Private database/file
  backups and rollback images were retained; the vault key and data were preserved.

## Project presentation

- GitHub README explains the family inbox, document reader, calendar, integrations,
  mobile PWA and self-hosted setup, with eight screenshots using fictional data only.
- The presentation prominently identifies Elternportal and Schulmanager Online as
  the core school connections, with one provider per child and optional email support.
- A loopback-only read-only UI demo provides reproducible screenshot fixtures without
  a database, secrets or external requests. Required production configuration remains blank.
- Detailed setup and the existing-installation checksum procedure are in docs/SETUP.md.

## Remaining validation and improvements

- Populated school payment/report layouts need verification against authorized live
  records; empty feeds and denial responses do not prove compatibility with all layouts.
- Live Google insert/backfill behavior is verified with an authorized connected calendar.
  Live update/delete behavior still needs verification; retry paths, child isolation,
  duplicate prevention and report exclusions are covered by automated tests.
- Cross-source exam matching needs reliable identity; do not merge exams by date alone.
- Parentheses or school reference codes must not become invented teacher/track meanings.
- Preserve all privacy checks as integrations and example fixtures evolve.
