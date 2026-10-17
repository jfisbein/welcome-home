--liquibase formatted sql

--changeset welcome-home:005-nuki-connection
-- One Nuki connection per owner. The API token is encrypted with AEAD, using owner_id as associated data.
CREATE TABLE nuki_connection (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id            uuid        NOT NULL UNIQUE REFERENCES owner (id) ON DELETE CASCADE,
    api_token_encrypted bytea       NOT NULL,
    status              text        NOT NULL CHECK (status IN ('OK', 'DISCONNECTED')),
    last_checked_at     timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id)
);

ALTER TABLE nuki_connection ENABLE ROW LEVEL SECURITY;
CREATE POLICY owner_isolation ON nuki_connection
    USING (owner_id = current_owner_id())
    WITH CHECK (owner_id = current_owner_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON nuki_connection TO app_user;
