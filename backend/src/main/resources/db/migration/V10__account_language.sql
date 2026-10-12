-- V10: per-account UI/WhatsApp language (en | es).

ALTER TABLE app_user ADD COLUMN language varchar(8) NOT NULL DEFAULT 'en';
