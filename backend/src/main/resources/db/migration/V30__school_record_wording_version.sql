-- Reinterpret old generated copy once without treating unchanged school facts as new records.
ALTER TABLE school_record ADD COLUMN wording_version integer NOT NULL DEFAULT 0;
