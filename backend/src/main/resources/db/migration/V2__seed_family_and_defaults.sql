-- V2: seed family users (password set on first boot from SCHOOLAGENT_INITIAL_PASSWORD),
-- connector health rows and disabled model config placeholders.

-- One pending administrator is configured explicitly at first boot. No family or child defaults.
INSERT INTO app_user (id, name, display_name, password_hash, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'bootstrap-admin', 'Administrator', 'PENDING', now(), now());

INSERT INTO connector_health (connector, status, updated_at)
VALUES
    ('GMAIL',        'UNKNOWN', now()),
    ('ELTERNPORTAL', 'UNKNOWN', now()),
    ('CALENDAR',     'UNKNOWN', now()),
    ('WHATSAPP',     'UNKNOWN', now()),
    ('PLAYWRIGHT',   'UNKNOWN', now()),
    ('AI',           'UNKNOWN', now());

INSERT INTO model_config (id, role, provider, model_name, enabled, updated_at)
VALUES
    ('00000000-0000-0000-0000-000000000011', 'EXTRACTION', 'openai', '', false, now()),
    ('00000000-0000-0000-0000-000000000012', 'REASONING',  'openai', '', false, now()),
    ('00000000-0000-0000-0000-000000000013', 'VISION',     'openai', '', false, now()),
    ('00000000-0000-0000-0000-000000000014', 'FALLBACK',   'openai', '', false, now());
