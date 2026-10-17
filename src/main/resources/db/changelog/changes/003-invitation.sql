--liquibase formatted sql

--changeset welcome-home:003-invitation
-- No owner_id and no Row-Level Security: only the auth and admin modules use it.
-- created_by is NULL for the bootstrap admin invitation.
CREATE TABLE invitation (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash bytea       NOT NULL UNIQUE,
    note       text,
    created_by uuid        REFERENCES owner (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz
);

GRANT SELECT, INSERT, UPDATE ON invitation TO app_user;
