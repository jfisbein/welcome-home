#!/bin/sh
# Replaces the database with a dump from ./backups: ./restore.sh backups/welcome_home-20261018-040000.dump
# The roles must exist: on a new server, start db first so that db-init creates them.
# The dump brings the database back with its owner, grants and Liquibase history; the app migrates it on start,
# and the backup service then dumps the restored database.
set -eu

if [ $# -ne 1 ] || [ ! -f "$1" ]; then
    echo "Usage: $0 <dump file>" >&2
    exit 1
fi

dump="$(realpath "$1")"
cd "$(dirname "$0")"
# Nothing may be connected while the database is dropped.
docker compose stop app backup
docker compose up --detach --wait db
docker compose exec --no-TTY db \
    pg_restore --username=postgres --dbname=postgres --clean --if-exists --create --exit-on-error < "$dump"
docker compose up --detach --wait app backup
echo "Restored $dump"
