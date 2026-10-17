--liquibase formatted sql

--changeset welcome-home:002-owner
-- No owner_id and no Row-Level Security: only the auth and admin modules use it.
CREATE TABLE owner (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    email      text        NOT NULL UNIQUE CHECK (email = lower(email)),
    is_admin   boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, DELETE ON owner TO app_user;
