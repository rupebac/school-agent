-- V19: per-account toggle for instant "new letter" WhatsApp pings (default on).

ALTER TABLE app_user ADD COLUMN instant_enabled boolean NOT NULL DEFAULT true;
