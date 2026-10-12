-- V9: WhatsApp provider credentials become per-kid (same pattern as V8 portal/Google).
-- Copy the legacy family configuration into the existing child scope.

INSERT INTO setting (key, value_json, updated_at)
SELECT 'whatsapp.00000000-0000-0000-0000-000000000003.api-url', value_json, now()
FROM setting WHERE key = 'whatsapp.api-url'
ON CONFLICT (key) DO NOTHING;

INSERT INTO setting (key, value_json, updated_at)
SELECT 'whatsapp.00000000-0000-0000-0000-000000000003.phone-number-id', value_json, now()
FROM setting WHERE key = 'whatsapp.phone-number-id'
ON CONFLICT (key) DO NOTHING;

INSERT INTO setting (key, value_json, updated_at)
SELECT 'whatsapp.00000000-0000-0000-0000-000000000003.waba-id', value_json, now()
FROM setting WHERE key = 'whatsapp.waba-id'
ON CONFLICT (key) DO NOTHING;

INSERT INTO setting (key, value_json, updated_at)
SELECT 'whatsapp.00000000-0000-0000-0000-000000000003.frontend-url', value_json, now()
FROM setting WHERE key = 'whatsapp.frontend-url'
ON CONFLICT (key) DO NOTHING;

INSERT INTO secret_ref (key, cipher_text, updated_at)
SELECT 'whatsapp.00000000-0000-0000-0000-000000000003.token', cipher_text, now()
FROM secret_ref WHERE key = 'whatsapp.token'
ON CONFLICT (key) DO NOTHING;

INSERT INTO secret_ref (key, cipher_text, updated_at)
SELECT 'whatsapp.00000000-0000-0000-0000-000000000003.verify-token', cipher_text, now()
FROM secret_ref WHERE key = 'whatsapp.verify-token'
ON CONFLICT (key) DO NOTHING;

INSERT INTO secret_ref (key, cipher_text, updated_at)
SELECT 'whatsapp.00000000-0000-0000-0000-000000000003.app-secret', cipher_text, now()
FROM secret_ref WHERE key = 'whatsapp.app-secret'
ON CONFLICT (key) DO NOTHING;
