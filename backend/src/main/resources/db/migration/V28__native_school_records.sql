CREATE TABLE school_record (
    record_key varchar(100) PRIMARY KEY,
    namespace varchar(64) NOT NULL,
    child_id uuid NOT NULL REFERENCES child(id) ON DELETE CASCADE,
    kind varchar(40) NOT NULL,
    native_id text NOT NULL,
    record_date date,
    content_hash varchar(64) NOT NULL,
    payload text NOT NULL,
    case_id uuid NOT NULL REFERENCES school_case(id) ON DELETE CASCADE,
    source_id uuid NOT NULL REFERENCES source(id) ON DELETE CASCADE,
    active boolean NOT NULL DEFAULT true,
    notification_pending boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL
);
CREATE INDEX school_record_snapshot ON school_record(namespace, kind, active);
