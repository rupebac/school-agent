-- V15: persistent source translation cache (was in-memory, lost on restart).

CREATE TABLE source_translation (
    source_id       uuid NOT NULL REFERENCES source (id) ON DELETE CASCADE,
    language        varchar(8) NOT NULL,
    content_hash    text NOT NULL,
    translated_text text NOT NULL,
    created_at      timestamptz NOT NULL,
    PRIMARY KEY (source_id, language, content_hash)
);
