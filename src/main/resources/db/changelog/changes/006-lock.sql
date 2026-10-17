--liquibase formatted sql

--changeset welcome-home:006-lock
-- Locks are never deleted: a lock that disappears from the Nuki account gets active = false.
-- connection_id becomes NULL when the owner disconnects Nuki.
-- Foreign keys include owner_id because foreign key checks skip Row-Level Security:
-- this way a row can only reference rows of the same owner.
CREATE TABLE lock (
    id                uuid    PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id          uuid    NOT NULL REFERENCES owner (id) ON DELETE CASCADE,
    connection_id     uuid,
    nuki_smartlock_id bigint  NOT NULL,
    name              text    NOT NULL,
    time_zone         text    NOT NULL,
    active            boolean NOT NULL DEFAULT true,
    UNIQUE (owner_id, id),
    UNIQUE (owner_id, nuki_smartlock_id),
    FOREIGN KEY (owner_id, connection_id) REFERENCES nuki_connection (owner_id, id) ON DELETE SET NULL (connection_id)
);

ALTER TABLE lock ENABLE ROW LEVEL SECURITY;
CREATE POLICY owner_isolation ON lock
    USING (owner_id = current_owner_id())
    WITH CHECK (owner_id = current_owner_id());

GRANT SELECT, INSERT, UPDATE ON lock TO app_user;
