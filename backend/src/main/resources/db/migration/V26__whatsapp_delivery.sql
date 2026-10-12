-- Legacy SENT rows are historical API results: do not replay them or invent delivery receipts.
ALTER TABLE notification ADD COLUMN template_payload text;
ALTER TABLE notification ADD COLUMN message_id varchar(512);
ALTER TABLE notification ADD COLUMN attempt_count integer NOT NULL DEFAULT 0;
ALTER TABLE notification ADD COLUMN next_attempt_at timestamptz;
ALTER TABLE notification ADD COLUMN expires_at timestamptz;
ALTER TABLE notification ADD COLUMN last_error text;
CREATE INDEX notification_retry ON notification(next_attempt_at) WHERE template_payload IS NOT NULL;
CREATE UNIQUE INDEX notification_message_id ON notification(message_id) WHERE message_id IS NOT NULL;
CREATE TABLE whatsapp_receipt (
    message_id varchar(512) PRIMARY KEY,
    status varchar(32) NOT NULL,
    error_code integer,
    error text,
    updated_at timestamptz NOT NULL
);
