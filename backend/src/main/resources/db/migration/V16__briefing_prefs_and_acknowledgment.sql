-- V16: user briefing preferences + action acknowledgment tracking.

ALTER TABLE app_user ADD COLUMN briefing_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE app_user ADD COLUMN briefing_time varchar(5) NOT NULL DEFAULT '19:00';
ALTER TABLE app_user ADD COLUMN weekly_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE app_user ADD COLUMN weekly_time varchar(5) NOT NULL DEFAULT '10:00';

CREATE TABLE action_acknowledgment (
    action_id uuid NOT NULL REFERENCES parent_action (id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    acknowledged_at timestamptz NOT NULL,
    PRIMARY KEY (action_id, user_id)
);
