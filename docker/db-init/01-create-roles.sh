#!/bin/sh
# Creates the database roles on the first start of an empty Postgres:
#   migrator: owns the database and schema; Liquibase runs as this role.
#   app_user: the application; no ownership, no BYPASSRLS. Its table grants come from Liquibase.
set -e

psql -v ON_ERROR_STOP=1 \
     -v db="$POSTGRES_DB" \
     -v migrator_password="$MIGRATOR_PASSWORD" \
     -v app_user_password="$APP_USER_PASSWORD" \
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
