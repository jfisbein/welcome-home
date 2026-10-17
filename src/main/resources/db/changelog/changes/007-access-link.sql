--liquibase formatted sql

--changeset welcome-home:007-access-link
-- No token is stored: the link is {id}.{mac}, with mac = HMAC(key, id || token_version).
-- Links are never deleted: they are revoked with revoked_at.
-- The state (PENDING, ACTIVE, EXPIRED, REVOKED) is computed from the dates and revoked_at.
CREATE TABLE access_link (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id      uuid        NOT NULL REFERENCES owner (id) ON DELETE CASCADE,
    lock_id       uuid        NOT NULL,
    label         text        NOT NULL,
    token_version integer     NOT NULL DEFAULT 1,
    valid_from    timestamptz NOT NULL,
    valid_until   timestamptz NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    revoked_at    timestamptz,
    UNIQUE (owner_id, id),
    FOREIGN KEY (owner_id, lock_id) REFERENCES lock (owner_id, id) ON DELETE CASCADE,
    CHECK (valid_until > valid_from)
);

CREATE INDEX access_link_lock_idx ON access_link (owner_id, lock_id);

ALTER TABLE access_link ENABLE ROW LEVEL SECURITY;
CREATE POLICY owner_isolation ON access_link
    USING (owner_id = current_owner_id())
    WITH CHECK (owner_id = current_owner_id());

GRANT SELECT, INSERT, UPDATE ON access_link TO app_user;
