-- V7: account system — roles for admin/subaccounts, persistent login tokens.

ALTER TABLE app_user ADD COLUMN role varchar(32) NOT NULL DEFAULT 'PARENT';

-- The bootstrap account becomes the administrator (user management)
UPDATE app_user SET role = 'ADMIN' WHERE id = '00000000-0000-0000-0000-000000000001';

-- Spring Security remember-me tokens ("remember the login")
CREATE TABLE persistent_logins (
    username  varchar(64)  NOT NULL,
    series    varchar(64)  PRIMARY KEY,
    token     varchar(64)  NOT NULL,
    last_used timestamptz  NOT NULL
);
