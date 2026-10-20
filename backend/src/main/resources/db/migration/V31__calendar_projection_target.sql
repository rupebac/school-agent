-- Preserve the original target before allowing account/calendar reconciliation.
ALTER TABLE school_event ADD COLUMN google_calendar_id text;
UPDATE school_event e SET google_calendar_id = s.value_json
FROM school_case c, setting s
WHERE c.id = e.case_id AND e.google_event_id IS NOT NULL
AND s.key = CASE WHEN c.child_id IS NULL THEN 'google.calendar-id'
                ELSE 'google.' || c.child_id::text || '.calendar-id' END;

-- Google IDs are unique within a calendar, not across unrelated accounts/calendars.
ALTER TABLE school_event DROP CONSTRAINT school_event_google_event_id_key;
CREATE UNIQUE INDEX school_event_calendar_projection ON school_event(google_calendar_id, google_event_id);
