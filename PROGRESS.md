# PROGRESS — School Administration Agent

Read this living status file before making changes. This public ledger contains
implementation state only; private deployment notes and family records stay outside Git.

## Current state

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
  has not been deployed to an existing installation.

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
- Live Google insert/update/delete behavior requires an authorized connected calendar.
  Retry paths and report exclusions are covered by automated tests.
- Cross-source exam matching needs reliable identity; do not merge exams by date alone.
- Parentheses or school reference codes must not become invented teacher/track meanings.
- Preserve all privacy checks as integrations and example fixtures evolve.
