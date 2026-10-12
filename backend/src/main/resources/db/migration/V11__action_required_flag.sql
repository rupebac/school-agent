ALTER TABLE parent_action
    ADD COLUMN required boolean NOT NULL DEFAULT true;

ALTER TABLE child_requirement
    ADD COLUMN required boolean NOT NULL DEFAULT true;
