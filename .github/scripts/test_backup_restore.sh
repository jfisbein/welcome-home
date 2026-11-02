#!/bin/sh
# Tries production's backup and restore for real: the stack in deploy/, its backup.sh and its restore.sh, on a copy
# of the stack with throwaway secrets. Takes the image to run as the app, and needs the keysets in secrets/
# (./gradlew generateKeysets). Run it from the repository's root: .github/scripts/test_backup_restore.sh <image>
set -eu

image="$1"
repository="$(pwd)"
work="$(mktemp -d)"
export COMPOSE_PROJECT_NAME=welcome-home-restore-test

cleanup() {
    status=$?
    cd "$work/stack" && {
        [ "$status" -eq 0 ] || docker compose logs --tail=50 db app backup
        docker compose down --volumes --remove-orphans
    }
    rm -rf "$work"
    exit "$status"
}
trap cleanup EXIT

sql() {
    docker compose exec -T db psql --username=postgres --dbname=welcome_home -v ON_ERROR_STOP=1 -tA -c "$1"
}

# As a role over the network, as the app and the backup tool connect, not as the superuser.
sql_as() {
    docker compose exec -T -e PGPASSWORD="$(cat "secrets/$1_password")" db \
        psql --host=127.0.0.1 --username="$1" --dbname=welcome_home -v ON_ERROR_STOP=1 -tA -c "$2"
}

expect() {
    if [ "$2" != "$3" ]; then
        echo "FAILED: $1: expected '$3', got '$2'" >&2
        exit 1
    fi
    echo "ok: $1"
}

cp -R deploy "$work/stack"
cd "$work/stack"

mkdir secrets backups
chmod 777 backups
for name in postgres_password migrator_password app_user_password backup_reader_password; do
    openssl rand -hex 24 | tr -d '\n' > "secrets/$name"
done
cp "$repository/secrets/aead-keyset.json" "$repository/secrets/mac-keyset.json" secrets/
for name in tunnel_token smtp_username smtp_password nuki-oauth-client-id nuki-oauth-client-secret; do
    : > "secrets/$name"
done
chmod 444 secrets/*
cat > compose.override.yaml <<EOF
services:
  app:
    image: $image
EOF

docker compose up --detach --wait db app backup

owner="$(sql "INSERT INTO owner (email) VALUES ('alice@example.com') RETURNING id" | head -1)"
lock="$(sql "INSERT INTO lock (owner_id, nuki_smartlock_id, name, time_zone)
             VALUES ('$owner', 17, 'Casa', 'Europe/Madrid') RETURNING id" | head -1)"
sql "INSERT INTO access_link (owner_id, lock_id, label, valid_from, valid_until)
     VALUES ('$owner', '$lock', 'Ana', now(), now() + interval '1 day')" > /dev/null

docker compose exec -T backup /backup/backup.sh now
dump="$(docker compose exec -T backup sh -c 'ls -t /backups/*.dump | head -1' | tr -d '\r')"
# The dump is only for its owner, the postgres user: here, a copy that this script may read.
docker compose exec -T backup cat "$dump" > "$work/copy.dump"

sql "DELETE FROM owner" > /dev/null
expect "the data is gone before restoring" "$(sql 'SELECT count(*) FROM access_link')" 0

./restore.sh "$work/copy.dump"

expect "restored the link" "$(sql 'SELECT label FROM access_link')" Ana
expect "the app's role sees nothing without an owner" "$(sql_as app_user 'SELECT count(*) FROM access_link')" 0
expect "the app's role sees its owner's rows" \
    "$(sql_as app_user "BEGIN; SELECT set_config('app.owner_id', '$owner', true); SELECT count(*) FROM access_link; COMMIT" \
        | sed -n 3p)" 1
expect "the backup role reads every row" "$(sql_as backup_reader 'SELECT count(*) FROM access_link')" 1
if sql_as backup_reader "DELETE FROM access_link" > /dev/null 2>&1; then
    echo "FAILED: the backup role could delete rows" >&2
    exit 1
fi
echo "ok: the backup role cannot write"

# With set -e, a dump that fails ends the test.
docker compose exec -T backup /backup/backup.sh now

echo "Backup and restore work"
