CREATE TABLE whatsapp_inbound (
    id varchar(64) PRIMARY KEY,
    payload text,
    reply text,
    status varchar(24) NOT NULL DEFAULT 'QUEUED',
    lease_token varchar(36),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    last_error varchar(120)
);
CREATE INDEX whatsapp_inbound_pending ON whatsapp_inbound(next_attempt_at) WHERE next_attempt_at IS NOT NULL;
