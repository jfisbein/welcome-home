#!/bin/sh
# Creates the database roles on the first start of an empty Postgres:
#   migrator: owns the database and schema; Liquibase runs as this role.
#   app_user: the application; no ownership, no BYPASSRLS. Its table grants come from Liquibase.
#   backup_reader: an off-site backup tool's, such as Databasus; reads every row and writes nothing. Only when its
#   password is given.
# Passwords come from the environment in development, and from secret files (*_FILE) in production.
set -e

migrator_password="${MIGRATOR_PASSWORD:-$(cat "$MIGRATOR_PASSWORD_FILE")}"
app_user_password="${APP_USER_PASSWORD:-$(cat "$APP_USER_PASSWORD_FILE")}"
backup_reader_password="${BACKUP_READER_PASSWORD:-}"
if [ -z "$backup_reader_password" ] && [ -n "${BACKUP_READER_PASSWORD_FILE:-}" ]; then
    backup_reader_password="$(cat "$BACKUP_READER_PASSWORD_FILE")"
fi

psql -v ON_ERROR_STOP=1 \
     -v db="$POSTGRES_DB" \
     -v migrator_password="$migrator_password" \
     -v app_user_password="$app_user_password" \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'EOSQL'
CREATE ROLE migrator LOGIN PASSWORD :'migrator_password';
CREATE ROLE app_user LOGIN PASSWORD :'app_user_password';

ALTER DATABASE :"db" OWNER TO migrator;
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO app_user;

ALTER SCHEMA public OWNER TO migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO app_user;
EOSQL

# BYPASSRLS, as Row-Level Security would hide every row from it. Dumps then carry its CONNECT grant, so restoring
# one with --create needs the role: on a new server this script creates it before restore.sh runs.
if [ -n "$backup_reader_password" ]; then
    psql -v ON_ERROR_STOP=1 \
         -v db="$POSTGRES_DB" \
         -v backup_reader_password="$backup_reader_password" \
         --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'EOSQL'
CREATE ROLE backup_reader LOGIN BYPASSRLS PASSWORD :'backup_reader_password';
GRANT CONNECT ON DATABASE :"db" TO backup_reader;
GRANT pg_read_all_data TO backup_reader;
EOSQL
fi
