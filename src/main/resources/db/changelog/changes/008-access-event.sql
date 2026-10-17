--liquibase formatted sql

--changeset welcome-home:008-access-event
-- Append-only: events are never updated, only purged after the retention period.
CREATE TABLE access_event (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id      uuid        NOT NULL REFERENCES owner (id) ON DELETE CASCADE,
    link_id       uuid        NOT NULL,
    lock_id       uuid        NOT NULL,
    occurred_at   timestamptz NOT NULL DEFAULT now(),
    result        text        NOT NULL CHECK (result IN ('OK', 'DENIED', 'ERROR')),
    reason        text,
    ip            inet,
    user_agent    text,
    nuki_response jsonb,
    FOREIGN KEY (owner_id, link_id) REFERENCES access_link (owner_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, lock_id) REFERENCES lock (owner_id, id) ON DELETE CASCADE
);

CREATE INDEX access_event_lock_idx ON access_event (owner_id, lock_id, occurred_at DESC);
CREATE INDEX access_event_link_idx ON access_event (owner_id, link_id, occurred_at DESC);

ALTER TABLE access_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY owner_isolation ON access_event
    USING (owner_id = current_owner_id())
    WITH CHECK (owner_id = current_owner_id());

GRANT SELECT, INSERT, DELETE ON access_event TO app_user;
