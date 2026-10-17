--liquibase formatted sql

--changeset welcome-home:001-current-owner-id splitStatements:false
-- The owner whose rows the current transaction may use, set by OwnerTransactions.
-- NULL when no owner is set, so Row-Level Security policies fail closed.
CREATE FUNCTION current_owner_id() RETURNS uuid
    LANGUAGE sql
    STABLE
AS $$
    SELECT nullif(current_setting('app.owner_id', true), '')::uuid
$$;
