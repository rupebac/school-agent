# Setup and operations

## Fresh installation

Use Docker Engine with Compose v2. From the repository root:

```bash
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
```

Fill the blank settings before starting containers:

| Setting | Purpose |
|---|---|
| `POSTGRES_PASSWORD` | Explicit database password, at least 12 characters |
| `SCHOOLAGENT_MASTER_KEY` | Persistent base64-encoded 32-byte vault key |
| `SCHOOLAGENT_PUBLIC_URL` | Application origin; local Compose: `http://127.0.0.1:4200` |
| `SCHOOLAGENT_INITIAL_USERNAME` | Administrator login for a fresh database |
| `SCHOOLAGENT_INITIAL_PASSWORD` | Administrator password, at least 12 characters |

Generate the vault key once with `openssl rand -base64 32`. Keep it securely with
private backups and preserve it across upgrades: it encrypts integration credentials.
There is no default login or sample family. Placeholder secrets fail validation.
Bootstrap creates one administrator and no children; it does not reset existing accounts.

```bash
cd deploy
docker compose up -d --build
```

Open `http://127.0.0.1:4200`, sign in with your configured administrator, and add
children and parent accounts under **Settings → Family**. Optional integrations
remain unconnected until configured. AI extraction needs a configured model and key.

Compose binds its published ports to loopback. For remote use, configure a reverse
proxy with HTTPS and the actual public origin; HTTPS is also needed for mobile PWA
installation outside localhost. Keep database volumes, `deploy/api-data`, the vault
key and your private configuration in your backup plan. Check `/actuator/health` on
the API after an upgrade. See [AGENTS.md](../AGENTS.md) for deployment safeguards.

## School connections

In **Settings → School**, select the child and open **School portal**. Choose
Elternportal or Schulmanager Online; each child has one provider. Enter the portal
credentials. Schulmanager offers **Find children in this account** so you can select
the matching pupil. The initial import establishes a quiet baseline for notifications.
Later syncs deduplicate unchanged letters and native records.

Class exams, forgotten homework/material reports and payments depend on school
modules and permissions. Their individual status appears in Settings; an unavailable
optional section does not make the letters connection unhealthy. Verify class details
before using the exam feed. See [PROGRESS.md](../PROGRESS.md) for validation limits.

## Google mail and calendar

An administrator can configure the family's Google OAuth web client in
**Settings → Family → Google app setup**. Enable the Gmail and Google Calendar APIs
in your Google Cloud project, configure the consent screen and allowed users, and
create a **Web application** OAuth client. Copy the displayed callback into that
client's **Authorized redirect URIs** exactly. See Google's
[web-server OAuth guide](https://developers.google.com/identity/protocols/oauth2/web-server).

Enter the client ID and secret in the app. Credentials are encrypted in the vault;
the API returns only the client ID and whether a secret exists. A blank secret keeps
the existing one for the same client ID. Saving applies immediately and overrides
`SCHOOLAGENT_GOOGLE_CLIENT_ID` / `SCHOOLAGENT_GOOGLE_CLIENT_SECRET`; existing environment
configuration remains the fallback until settings are saved. The screen identifies
which source is active. Client credentials alone do not connect any Google account,
and they do not reveal which person owns the Google Cloud project.

Changing the client ID requires a new secret and explicit confirmation: all local
Google connections become inactive and each child needs to reconnect. Old refresh
tokens cannot be reused with the new client, even after a restart or switching back.
Rotating the secret for the same client preserves refresh tokens and clears the use
of cached access tokens. Pending consent flows must restart after a credential change.

The displayed callback is read-only and comes from server configuration. Compose
defaults to `${SCHOOLAGENT_PUBLIC_URL}/api/google/callback`; the standalone backend
defaults to `http://localhost:8080/api/google/callback`. An operator can override
`SCHOOLAGENT_GOOGLE_REDIRECT_URI` if needed. A `redirect_uri_mismatch` must be resolved
by registering that exact address on the matching Google client. Saving app settings
does not update Google Cloud. OAuth testing-mode token lifetimes may require
reconnecting; consult Google's current deployment guidance.

Connect Google for each child through **Settings → School → Google**. In **School
calendar**, select or create the dedicated calendar and configure sharing. The
calendar is a projection of internal school events; the app database remains the
source of truth. Do not regenerate the vault key when connecting another account.

After a child connects, upcoming events already stored in the app are automatically
projected into that child's dedicated school calendar. A background job checks every
minute, in batches of 50 per child; failed projections retry after five minutes.
Past events and school reports are excluded. Repeated checks reuse the existing
projection rather than inserting another event. Calendar diagnostics record safe
counts and outcomes in the same 60-day journal as school and notification work.

Set `SCHOOLAGENT_GMAIL_POLLING=true` to enable scheduled ingestion. The default
query is `in:inbox`; set `SCHOOLAGENT_GMAIL_QUERY` if forwarding rules use a dedicated
label or archive messages. Configure school sender domains in the child's email
settings. Forwarding metadata helps identify original senders; repeated and forwarded
messages are deduplicated within each child's mailbox. Reply drafts are copied by a
parent and sent manually; School Agent does not send emails or submit school forms.

## WhatsApp and AI

**Settings → Family** holds shared WhatsApp and AI configuration. Recipients and
notification preferences belong to each parent; messages identify the child.
WhatsApp requires your Meta business configuration, webhook and approved templates.
The repository does not supply tokens or automatically publish templates. Unavailable
templates do not trigger substitute messages.

Shared WhatsApp configuration and diagnostics require an administrator account.
Configure the Meta **App Secret** as well as the webhook verification token:
incoming POST deliveries are rejected when no signing secret is configured or the
`X-Hub-Signature-256` signature does not match. The verification token is used only
for Meta's initial GET challenge.

AI models are configurable by role. The default Z.ai integration uses the
Anthropic-compatible `https://api.z.ai/api/anthropic` endpoint; OpenAI-compatible
providers are also supported. Store keys in private configuration or the encrypted
vault. Persisted translations, summaries and cached model results avoid repeating
unchanged work. Connecting an AI provider sends relevant source content to that
provider; choose one appropriate for your family's data.

## Optional browser worker

Browser investigation is disabled unless `SCHOOLAGENT_BROWSER_WORKER_URL` is set.
When running the optional Compose worker, set it to `http://playwright-worker:8100`
in the private environment file and start it with `docker compose --profile browser up -d playwright-worker`. Do not publish its
port to the Internet. The worker permits public HTTP(S) destinations on ports 80/443,
blocks private destination addresses and non-read requests, and bounds response
sizes and concurrent browser work. Elternportal requests use the same destination policy, including authenticated
redirects and downloads. Schulmanager restricts production API/download hosts.

The Compose worker enables Chromium sandboxing and restricts capabilities, writable
storage, memory, CPU and process count. Its host must support unprivileged user
namespaces; sandbox initialization failure does not fall back to an unsafe mode.
See [worker isolation](../playwright-worker/SECURITY.md). The Playwright package and
container image must use matching versions.

## Account changes

Changing an account's password or role invalidates its existing authenticated
sessions on their next request. Password changes, administrative password resets,
role changes and account deletion revoke remembered-login tokens.

## Diagnostic history and log storage

Integration diagnostics stay in PostgreSQL for **60 days**. Cleanup runs at startup
and daily at 03:40 Europe/Berlin; it deletes only `DIAGNOSTIC_…` entries, preserving
ordinary case/audit history. Standard audit listings exclude these diagnostic entries.
Unchanged school-feed sets get one daily checkpoint; set changes are recorded immediately,
including removal and reappearance. Repeated unchanged/skipped records and scheduler
gate decisions are limited to one entry per day. Individual reminder runs and delivery
transitions remain traceable.

The Compose stack uses Docker's `local` logging driver, automatic compression and
**three 5 MB files per service**: about **60 MB total before compression** across
the four services, plus logging metadata/buffers. These console files rotate by size
and can cover less than 60 days; the database is the retained diagnostic history.
Applying the logging settings requires recreating existing containers during an
authorized deployment. Do not remove database/document volumes to apply logging changes.

An operator can inspect diagnostic history through a private database session:

```sql
SELECT created_at, action, detail
FROM audit_entry
WHERE action LIKE 'DIAGNOSTIC_%'
  AND created_at >= now() - interval '7 days'
ORDER BY created_at;
```

Follow a sync's `run`, a snapshot's `recordRefs`, and the linked `recordRef`/`caseRef`
to distinguish an absent record from `BEFORE_WINDOW`, `INVALID_FEED`, `FAILED` or
an applied record. Only a successful complete snapshot supports a claim of absence;
an unavailable/failed section does not. Snapshot history starts with deployment and
cannot establish when an older exam first appeared. Schulmanager observations cover
the provider's requested date window; they do not prove what existed outside it.
Opaque references are SHA-256 of `diagnostic:` followed by the internal identifier;
they can be correlated privately without printing raw identifiers in logs.

For reminders, `NO_ITEMS`, `DISABLED`, `NO_NUMBER`, `WAITING_FOR_TIME`, `WINDOW_PASSED`
and `DUPLICATE` explain why no new notification was queued. `PLAN_EXAM` records
individual exam eligibility, including muted/closed/archived/low-priority cases.
WhatsApp `QUEUED`, `SENDING`, `ACCEPTED`, `DELIVERED`, `READ`, `RETRY`, `UNKNOWN`
and `FAILED` distinguish preparation and transport outcomes; acceptance alone is
not delivery. Traces contain fixed reason codes, counts, timestamps and opaque
references, never message bodies, school names, pupil IDs, recipient numbers or secrets.
If diagnostic persistence fails, the console reports the failure category; ingestion
and notification work continue. Diagnostics remain private operational data.

## Existing installations after the publication cleanup

The historical bootstrap migrations V2, V7, V8 and V9 were anonymized; their checksums
changed. Before deploying this release to an existing database, back up the database
and keep the running image. Review that **only these four checksums** differ, then
run Flyway `repair` with this release's migrations and your private database settings.
The repair updates migration metadata; it does not rerun seeds, rename accounts,
remove children or change vault values. Do not disable migration validation, blindly
repair other mismatches, or restart an old image after repairing for a new release.
Fresh databases need no repair. Existing installations must also check that their
database password is explicit and meets validation; changing an environment variable
does not rotate the password of an existing PostgreSQL role. Keep the old checksum metadata with the private
rollback backup so an old-image rollback can restore it.

## Local development

Use Java 21, Docker for integration tests, and Node >=22.22.3. Supply explicit
database, vault and bootstrap credentials to the backend. Development PostgreSQL
uses loopback port 15432; no usable credentials are checked into the repository.

```bash
./backend/mvnw -f backend/pom.xml spring-boot:run
```

In a separate terminal:

```bash
cd frontend
npm ci
npm start
```

The frontend development server proxies API requests to the backend. To explore
only the UI without a database or accounts, use the fictional demo in the
[README](../README.md#try-the-ui-without-accounts).

## Recovery and guarded releases

For an existing installation, `deploy/push.sh` requires
`SCHOOLAGENT_REMOTE_BACKUP_DIR`, an absolute private directory outside the checkout,
in addition to the SSH destination and origin. It snapshots the live deployment
before replacing source. The server-side `release.sh` builds selected services,
requires a recovery point and verifies API health (including Flyway startup) before
reopening the web service. Startup or health failure restores the snapshot and
previous images. A failed release returns a failing exit status even after rollback.
Fresh installation still uses the initial Compose setup above.

`backup-full.sh` requires `SCHOOLAGENT_BACKUP_DIR`. It briefly stops running app
services to capture a consistent database and document set, includes `.env` (and
therefore the vault key), captures image/configuration references and archives the
application images. Snapshot files are private and checksummed. Image archives are
shared between snapshots by image ID. Copy the **whole backup root**, including
`images/`, to separately protected storage for host-loss recovery.

The default full-snapshot retention is 14 days, configurable through
`SCHOOLAGENT_BACKUP_RETAIN_DAYS` (1–365); the two newest snapshots are always kept.
Unused rollback tags and image archives are removed with expired snapshots.
The older `backup.sh` remains a database-only utility and is not a full recovery point.

To restore an existing installation, run `deploy/restore.sh` with the absolute
snapshot directory and `--replace-data`, using the Docker permissions of the
deployment operator. Use sudo when restoring files owned by the application user.
It verifies checksums, reloads missing application images from the archive, stops
application services, preserves displaced files, restores PostgreSQL and documents
with their original ownership, and starts the previous images. Verify
`/actuator/health` before reopening external traffic. For a replacement host, prepare
Docker/PostgreSQL with the backed-up private configuration first; never generate a
replacement vault key for an existing backup.

The repository tests these operations with generated credentials and isolated
containers through `python3 scripts/test-recovery.py`. Run
`python3 scripts/test-release.py` to verify release failure gates without deploying.

## Calendar switching and inbound replies

Changing a child's Google account pauses projection until calendar setup succeeds.
The destination is tracked separately from the event ID. Events are reconciled into
the new school calendar; existing copies in the old calendar are retained as history.
Reconnecting to the same calendar preserves event IDs. Retried creates use a stable
ID and verify ownership before updating an existing remote event.

Signed WhatsApp messages are durably queued before acknowledgment. Duplicate
provider deliveries do not repeat processing. Preparation and definitive retryable
send failures retry up to five attempts. An uncertain send remains `UNKNOWN` for
operator review; automatically repeating it could send the same reply twice.
Administrators can inspect queue counts at `/api/whatsapp/inbound-status`.
Completed message content is cleared; other queued content expires after seven days
and deduplication records after 60 days.
