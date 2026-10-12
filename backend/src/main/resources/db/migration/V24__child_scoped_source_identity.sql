-- Provider/mailbox IDs can legitimately repeat for siblings. The unique dedup_key
-- already includes child/provider scope and remains the ingestion invariant.
ALTER TABLE source DROP CONSTRAINT uq_source_type_external;
CREATE INDEX source_type_external_idx ON source(source_type, external_id);
