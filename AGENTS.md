# AGENTS.md — coding agent instructions

## Orientation

Read `PROGRESS.md` first. Product requirements are in `docs/SPEC.md`, architecture
in `docs/ARCHITECTURE.md`, and frontend conventions in `docs/FRONTEND.md`.

This repository is prepared for public distribution. It contains a Kotlin/Spring
Boot backend, Angular frontend, PostgreSQL, school portal connectors, Gmail/Calendar,
WhatsApp and configurable AI extraction. Deployments and family data are private.

## Privacy and secrets — applies to every change

- Never commit real credentials, API/OAuth tokens, vault/master keys, cookies, private
  keys, passwords, environment files, database exports, runtime documents or logs.
- Never commit real family names, email addresses, phone numbers, child/student IDs,
  school/class combinations, letters, screenshots, case links, or operational records.
  Documentation, fixtures, mockups, defaults, comments, commit messages and PRs are
  subject to the same rule as code. Use clearly fictional data and reserved domains
  (`example.org`, `school.example`, `.test`) for examples; never copy live responses.
- Do not add production domains, LAN addresses, hostnames, operator usernames,
  home-directory paths or backup locations to tracked files. Private operator notes
  belong in ignored `DEPLOYMENT.local.md` or outside the repository. Consult those
  notes for an authorized deployment; do not paste them into public output/docs.
- Secrets are operator-supplied configuration. Blank templates are allowed; usable
  default secrets and real default accounts are not. Validate required configuration
  and fail with an actionable message that identifies the setting, never its value.
  Integrations may stay disabled/unconnected until explicitly configured.
- Never print secrets or personal data in tool output, CI reports or error messages.
  Redact scanner findings and summarize rule/file/line metadata, not matched values.
  Keep private scan reports and pre-cleanup Git backups outside this checkout.
- Use a GitHub noreply email and a public-safe contributor identity for Git metadata;
  do not publish an operator’s private email or family identity as the commit author.
- `.gitignore` does not protect already tracked content or historical commits.
  Run `python3 scripts/check-public-content.py --staged` and Gitleaks before commits;
  inspect the staged diff, including docs/fixtures/assets. Install the local hook with
  `git config core.hooksPath .githooks`. CI repeats privacy and secret checks.
- Keep privacy checks enabled. Do not blanket-allowlist a finding to get CI green.
  Review narrow exceptions for actual public third-party metadata or explicit test
  credentials; never exempt real deployment credentials or family information.
- History rewrites do not erase GitHub caches, old clones, forks or hidden references.
  Keep a repository private until any sensitive historical copies are dealt with.
  Do not change GitHub visibility, delete the repository or contact third parties
  without a user instruction for that action.

## Deployment

- Use `deploy/docker-compose.yml`, with an ignored mode-600 `deploy/.env` populated
  from the blank `.env.example`. Preserve the existing vault key and persistent data.
- `deploy/push.sh` requires explicit SSH host/user, remote directory and application
  origin. Use SSH keys and interactive sudo; never pass passwords in command text.
- Only deploy when authorized. Build tested code, back up first, preserve rollback
  images, then verify migrations and `/actuator/health`. A timed-out HTTP sync may
  continue on the server; check its result rather than repeatedly starting new work.
- Publication cleanup sanitized historical bootstrap migrations. For an existing
  installation, follow the reviewed checksum-repair procedure in README before its
  next deployment. Do not disable Flyway validation or auto-repair unknown changes.

## Development and verification

- Backend: `./backend/mvnw -f backend/pom.xml test`; integration tests need Docker.
- Frontend: Node >=22.22.3; `cd frontend && npm test -- --watch=false`.
- Production frontend build: `cd frontend && npm run build`.
- Run both test suites before committing code changes. Use only synthetic test data.
- Development PostgreSQL listens on loopback port 15432. Supply explicit database,
  vault and bootstrap credentials; none are checked into the repository.
- User-facing copy supports English/Spanish. Never hardcode family names in UI text.
- Keep ingestion idempotent. Case mutations use the state machine, history and audit.
  Human actors use a generic label; personal read/acknowledgment state uses user IDs.
- External school/email write actions remain forbidden in v1. Existing WhatsApp
  notifications and authorized Calendar projections follow their established policy.
- Z.ai uses the Anthropic-compatible `https://api.z.ai/api/anthropic` endpoint for
  coding-plan quota; do not silently switch it to the pay-as-you-go endpoint.
