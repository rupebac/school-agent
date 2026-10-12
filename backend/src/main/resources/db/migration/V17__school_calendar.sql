-- V17: Bavarian school calendar (holidays, vacations, bridge days).
-- Entries with child_id = NULL apply to all kids; kid-specific entries
-- override for schools with different calendars.

CREATE TABLE school_calendar_entry (
    id uuid PRIMARY KEY,
    child_id uuid REFERENCES child (id) ON DELETE CASCADE,
    name text NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    entry_type varchar(32) NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX idx_school_calendar_dates ON school_calendar_entry (start_date, end_date);
