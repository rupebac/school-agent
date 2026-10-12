-- Derived display copy only: canonical facts, tasks and lifecycle remain unchanged.
CREATE TABLE inbox_copy (
    case_id uuid PRIMARY KEY REFERENCES school_case(id) ON DELETE CASCADE,
    content_hash varchar(64) NOT NULL,
    payload text NOT NULL,
    created_at timestamptz NOT NULL
);
