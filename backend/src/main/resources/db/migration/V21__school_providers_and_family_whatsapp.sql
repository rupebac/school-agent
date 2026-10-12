-- Keep the most recently saved WhatsApp account as the single family connection.
-- Copy the complete scope, including encrypted secrets; never decrypt in a migration.
-- Legacy child keys remain inert for rollback and are never read by the application.
CREATE TEMP TABLE whatsapp_family_scope ON COMMIT DROP AS
SELECT regexp_replace(key, 'token$', '') AS prefix
FROM secret_ref
WHERE key = 'whatsapp.token'
   OR key ~ '^whatsapp\.[0-9a-f-]{36}\.token$'
ORDER BY updated_at DESC, key
LIMIT 1;

INSERT INTO setting (key, value_json, updated_at)
SELECT 'whatsapp.' || substring(s.key FROM length(w.prefix) + 1), s.value_json, s.updated_at
FROM setting s CROSS JOIN whatsapp_family_scope w
WHERE s.key IN (w.prefix || 'api-url', w.prefix || 'phone-number-id', w.prefix || 'waba-id', w.prefix || 'frontend-url')
ON CONFLICT (key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at;

INSERT INTO secret_ref (key, cipher_text, updated_at)
SELECT 'whatsapp.' || substring(s.key FROM length(w.prefix) + 1), s.cipher_text, s.updated_at
FROM secret_ref s CROSS JOIN whatsapp_family_scope w
WHERE s.key IN (w.prefix || 'token', w.prefix || 'verify-token', w.prefix || 'app-secret')
ON CONFLICT (key) DO UPDATE SET cipher_text = excluded.cipher_text, updated_at = excluded.updated_at;

INSERT INTO connector_health (connector, status, updated_at)
VALUES ('SCHULMANAGER', 'UNKNOWN', now())
ON CONFLICT (connector) DO NOTHING;

-- Backfilled letters are available in the inbox and briefings without a burst of instant alerts.
ALTER TABLE school_case ADD COLUMN suppress_initial_notification boolean NOT NULL DEFAULT false;
