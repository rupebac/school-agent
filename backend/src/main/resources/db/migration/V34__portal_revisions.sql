ALTER TABLE source ADD COLUMN superseded boolean NOT NULL DEFAULT false;
ALTER TABLE source ADD COLUMN school_item_key text;
ALTER TABLE source ADD COLUMN school_version integer;
ALTER TABLE source ADD COLUMN fact_hash text;
ALTER TABLE source ADD COLUMN revision_identity text;
ALTER TABLE source ADD COLUMN derived_from_sources text;
CREATE INDEX source_current_case_idx ON source(case_id) WHERE NOT superseded;
CREATE INDEX source_school_item_idx ON source(school_item_key);

ALTER TABLE school_case ADD COLUMN school_update_pending boolean NOT NULL DEFAULT false;
ALTER TABLE school_case ADD COLUMN school_previous_status varchar(32);
ALTER TABLE school_case ADD COLUMN school_notification_pending boolean NOT NULL DEFAULT false;

CREATE TABLE school_item (
    item_key text PRIMARY KEY,
    child_id uuid NOT NULL REFERENCES child(id),
    namespace text NOT NULL,
    native_id text NOT NULL,
    title text,
    source_id uuid NOT NULL REFERENCES source(id),
    version integer NOT NULL DEFAULT 1,
    manifest_hash text NOT NULL,
    fact_hash text NOT NULL,
    change_marker text,
    checked_at timestamptz NOT NULL,
    last_success_at timestamptz,
    failure_code text,
    school_status text,
    UNIQUE(namespace, native_id)
);
CREATE INDEX school_item_refresh_idx ON school_item(child_id, checked_at);
CREATE TABLE school_item_revision (
    id uuid PRIMARY KEY,
    item_key text NOT NULL REFERENCES school_item(item_key),
    version integer NOT NULL,
    manifest_hash text NOT NULL,
    fact_hash text NOT NULL,
    parts text NOT NULL,
    observed_at timestamptz NOT NULL,
    material boolean NOT NULL,
    UNIQUE(item_key, version)
);
