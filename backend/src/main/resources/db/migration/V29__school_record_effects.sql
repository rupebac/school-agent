ALTER TABLE school_record ADD COLUMN projection_pending boolean NOT NULL DEFAULT true;
ALTER TABLE school_record ADD COLUMN wording_pending boolean NOT NULL DEFAULT false;
CREATE INDEX school_record_pending_effects ON school_record(updated_at)
    WHERE notification_pending OR projection_pending;
-- A form submission target is not the page a parent should open. Existing inventory stays unlinked until inspected again.
ALTER TABLE external_form ADD COLUMN page_url text;
ALTER TABLE parent_action ADD COLUMN portal_provider varchar(32);
