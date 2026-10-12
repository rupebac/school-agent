-- Deliveries retain both parents' provenance without duplicating a source/case.
CREATE TABLE email_delivery (
    id uuid PRIMARY KEY,
    child_id uuid NOT NULL REFERENCES child(id),
    gmail_id text NOT NULL,
    source_id uuid NOT NULL REFERENCES source(id),
    original_message_id text,
    fingerprint text,
    gmail_thread_id text,
    reference_ids text NOT NULL DEFAULT '',
    forwarded_by text,
    received_at timestamptz NOT NULL,
    UNIQUE(child_id, gmail_id)
);
CREATE INDEX email_delivery_source_idx ON email_delivery(source_id);
