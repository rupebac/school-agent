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

Create a Google OAuth web client with Gmail read-only and Calendar permissions.
Configure `SCHOOLAGENT_GOOGLE_CLIENT_ID` and `SCHOOLAGENT_GOOGLE_CLIENT_SECRET`
in the private environment. Register the exact callback used by your installation:
Compose defaults to `${SCHOOLAGENT_PUBLIC_URL}/api/google/callback`; the standalone
backend defaults to `http://localhost:8080/api/google/callback`. The consent screen
and allowed users must permit the accounts you connect. OAuth testing-mode token
lifetimes may require reconnecting; consult Google's current deployment guidance.

Connect Google for each child through **Settings → School → Google**. In **School
calendar**, select or create the dedicated calendar and configure sharing. The
calendar is a projection of internal school events; the app database remains the
source of truth. Do not regenerate the vault key when connecting another account.

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

AI models are configurable by role. The default Z.ai integration uses the
Anthropic-compatible `https://api.z.ai/api/anthropic` endpoint; OpenAI-compatible
providers are also supported. Store keys in private configuration or the encrypted
vault. Persisted translations, summaries and cached model results avoid repeating
unchanged work. Connecting an AI provider sends relevant source content to that
provider; choose one appropriate for your family's data.

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
