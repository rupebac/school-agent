CREATE TABLE ai_response_cache (
    id varchar(64) PRIMARY KEY,
    response text NOT NULL,
    created_at timestamptz NOT NULL
);
