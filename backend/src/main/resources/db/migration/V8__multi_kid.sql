-- V8: multi-kid support — kids are first-class, accounts are assigned to kids,
-- and connector configs become child-scoped.

CREATE TABLE account_kid (
    account_id uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    child_id   uuid NOT NULL REFERENCES child (id) ON DELETE CASCADE,
    PRIMARY KEY (account_id, child_id)
);

-- Existing accounts get access to the legacy child, if that child exists.
INSERT INTO account_kid (account_id, child_id)
SELECT u.id, c.id FROM app_user u CROSS JOIN child c
WHERE c.id = '00000000-0000-0000-0000-000000000003'
ON CONFLICT DO NOTHING;

-- Portal config becomes per-child: preserve the legacy connection scope.
INSERT INTO setting (key, value_json, updated_at)
SELECT 'elternportal.' || '00000000-0000-0000-0000-000000000003' || '.url', value_json, now()
FROM setting WHERE key = 'elternportal.url'
ON CONFLICT (key) DO NOTHING;

INSERT INTO secret_ref (key, cipher_text, updated_at)
SELECT 'elternportal.' || '00000000-0000-0000-0000-000000000003' || '.username', cipher_text, now()
FROM secret_ref WHERE key = 'elternportal.username'
ON CONFLICT (key) DO NOTHING;

INSERT INTO secret_ref (key, cipher_text, updated_at)
SELECT 'elternportal.' || '00000000-0000-0000-0000-000000000003' || '.password', cipher_text, now()
FROM secret_ref WHERE key = 'elternportal.password'
ON CONFLICT (key) DO NOTHING;

-- Google refresh token becomes per-kid as well.
INSERT INTO secret_ref (key, cipher_text, updated_at)
SELECT 'google.' || '00000000-0000-0000-0000-000000000003' || '.refresh-token', cipher_text, now()
FROM secret_ref WHERE key = 'google.refresh-token'
ON CONFLICT (key) DO NOTHING;

-- A display color per kid for the UI badges.
ALTER TABLE child ADD COLUMN IF NOT EXISTS color varchar(16) DEFAULT '#2563eb';

ALTER TABLE school_event ADD COLUMN IF NOT EXISTS child_id uuid REFERENCES child (id);
