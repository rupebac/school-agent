ALTER TABLE school_case
    ADD COLUMN importance_score integer NOT NULL DEFAULT 0;

UPDATE school_case SET importance_score = CASE importance
    WHEN 'IMMEDIATE' THEN 5
    WHEN 'SILENT' THEN 1
    ELSE 3
END;
