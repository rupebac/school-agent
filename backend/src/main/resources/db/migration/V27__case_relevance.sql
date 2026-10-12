-- Visibility projection only: never completes, cancels or deletes school work.
CREATE TABLE case_relevance (
    case_id uuid PRIMARY KEY REFERENCES school_case(id) ON DELETE CASCADE,
    content_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    assessment varchar(32) NOT NULL,
    confidence double precision,
    reason text NOT NULL,
    created_at timestamptz NOT NULL
);
