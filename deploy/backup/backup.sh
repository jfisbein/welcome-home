#!/bin/sh
# Dumps the database to /backups when it starts and every day at $BACKUP_TIME, and deletes the dumps older
# than $BACKUP_RETENTION_DAYS. "backup.sh now" takes one dump and exits.
# Each dump is a pg_dump custom archive: restore.sh, next to compose.yaml, restores one.
set -eu

BACKUP_TIME="${BACKUP_TIME:-04:00}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
PGPASSWORD="$(cat "$PGPASSWORD_FILE")"
export PGPASSWORD

# Dumps hold personal data: only their owner can read them.
umask 077

backup() {
    name="/backups/welcome_home-$(date +%Y%m%d-%H%M%S).dump"
    # Written under another name first, so a copy taken meanwhile never sees half a dump. Errors are returned
    # explicitly: set -e does not apply to a function called as "backup || ...".
    if ! pg_dump --format=custom --file="$name.partial" "$PGDATABASE"; then
        rm -f "$name.partial"
        return 1
    fi
    mv "$name.partial" "$name"
    echo "Backed up to $name"
    # -mtime +N matches files at least N + 1 days old.
    find /backups -name 'welcome_home-*.dump' -mtime +"$((BACKUP_RETENTION_DAYS - 1))" -print -delete
}

seconds_until_next_backup() {
    now=$(date +%s)
    next=$(date -d "today $BACKUP_TIME" +%s)
    if [ "$next" -le "$now" ]; then
        next=$(date -d "tomorrow $BACKUP_TIME" +%s)
    fi
    echo $((next - now))
}

backup
if [ "${1:-}" = now ]; then
    exit 0
fi
while true; do
    sleep "$(seconds_until_next_backup)"
    # A failed dump is logged, the container's health check reports it, and tomorrow's dump tries again.
    backup || echo "Backup failed" >&2
done
