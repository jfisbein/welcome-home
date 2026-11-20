#!/bin/sh
# Replaces the database with a dump from ./backups: ./restore.sh backups/welcome_home-20261018-040000.dump
# The roles must exist: on a new server, start db first so that db-init creates them.
# The dump brings back the schema with its owner, its grants, the data and Liquibase's history; the migrate service
# migrates it before the app starts again, and the backup service then dumps the restored database.
# What the database holds is dumped to ./backups first, so that the wrong file, or a restore that fails halfway,
# loses nothing: SKIP_DUMP=1 ./restore.sh <dump file> goes without it, for a database that cannot be dumped.
set -eu

if [ $# -ne 1 ] || [ ! -f "$1" ]; then
    echo "Usage: $0 <dump file>" >&2
    exit 1
fi

dump="$(realpath "$1")"
cd "$(dirname "$0")"
docker compose up --detach --wait db
# Before anything is stopped or dropped: a file that is no pg_dump archive ends here, with the stack as it was.
if ! docker compose exec --no-TTY db pg_restore --list < "$dump" > /dev/null; then
    echo "$dump is not a dump that pg_restore can read: nothing was changed" >&2
    exit 1
fi
# Nothing may be connected while the database is dropped: if something still is, such as an off-site backup under
# way, dropping fails and the script stops before changing anything.
docker compose stop app backup
if [ "${SKIP_DUMP:-}" != 1 ]; then
    exists="$(docker compose exec --no-TTY db psql --username=postgres --dbname=postgres --tuples-only --no-align \
        --command="SELECT count(*) FROM pg_database WHERE datname = 'welcome_home'")"
    if [ "$exists" = 1 ]; then
        # The backup service's own script and role, once: it prints where the dump is. If it fails, the script
        # stops here, with the database untouched; start app and backup again with docker compose up --detach.
        docker compose run --rm --no-deps backup now
    fi
fi
# The database is created here, not by the dump (pg_restore --create), which would give it back the collation it
# had: text is compared by PostgreSQL's own rules, by code point, whatever C library the image has, so that a
# change of image never leaves indexes that miss rows. The indexes are built as the data comes in. What db-init
# does to the database itself, its owner and who may connect, is done again, as the dump no longer brings it.
docker compose exec --no-TTY db psql --username=postgres --dbname=postgres -v ON_ERROR_STOP=1 --quiet <<'EOSQL'
DROP DATABASE IF EXISTS welcome_home;
CREATE DATABASE welcome_home OWNER migrator TEMPLATE template0
    LOCALE_PROVIDER builtin BUILTIN_LOCALE 'C.UTF-8';
REVOKE ALL ON DATABASE welcome_home FROM PUBLIC;
GRANT CONNECT ON DATABASE welcome_home TO app_user;
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'backup_reader') THEN
        GRANT CONNECT ON DATABASE welcome_home TO backup_reader;
    END IF;
END
$$;
EOSQL
docker compose exec --no-TTY db \
    pg_restore --username=postgres --dbname=welcome_home --clean --if-exists --exit-on-error < "$dump"
docker compose up --detach --wait app backup
echo "Restored $dump"
