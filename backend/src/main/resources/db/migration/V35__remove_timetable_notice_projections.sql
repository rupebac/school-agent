-- Change notices remain in the family inbox and WhatsApp. They are not lessons.
-- Let the normal Calendar worker remove only the app-owned Google projections,
-- preserving IDs until successful deletion so failures remain retryable.
UPDATE school_event
SET google_sync_status = CASE WHEN google_event_id IS NULL THEN 'NOT_APPLICABLE' ELSE 'PENDING' END,
    updated_at = now()
WHERE event_type = 'TIMETABLE_CHANGE';
