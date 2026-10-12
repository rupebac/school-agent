-- V4: setting values are plain strings (urls, ids, timestamps, numbers),
-- not JSON documents — jsonb rejected them with "invalid input syntax".

ALTER TABLE setting ALTER COLUMN value_json TYPE text;
