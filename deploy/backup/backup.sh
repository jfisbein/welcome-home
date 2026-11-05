#!/bin/sh
# Dumps the database to /backups when it starts and every day at $BACKUP_TIME, and deletes the dumps older
# than $BACKUP_RETENTION_DAYS. "backup.sh now" takes one dump and exits.
# Each dump is a pg_dump custom archive: restore.sh, next to compose.yaml, restores one.
set -eu

BACKUP_TIME="${BACKUP_TIME:-04:00}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
# Compared with the clock as date prints it: "4:00" would never match.
case "$BACKUP_TIME" in
    [0-2][0-9]:[0-5][0-9]) ;;
    *) echo "BACKUP_TIME must be HH:MM, such as 04:00: $BACKUP_TIME" >&2; exit 1 ;;
esac
PGPASSWORD="$(cat "$PGPASSWORD_FILE")"
export PGPASSWORD

# Dumps hold personal data: only their owner can read them.
umask 077

# If ./backups did not exist, Docker created it for root: say how to fix that rather than fail in pg_dump.
if [ ! -w /backups ]; then
    echo "/backups is not writable by uid $(id -u): run 'chown $(id -u):$(id -g) backups' next to compose.yaml" >&2
    exit 1
fi

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

backup
if [ "${1:-}" = now ]; then
    exit 0
fi
# The clock is read twice a minute rather than slept on until the next dump: the change to and from summer time
# then moves nothing, and no date arithmetic is needed, which BusyBox's date in the Alpine image cannot do.
# The dump at start counts as the day's when it was taken at $BACKUP_TIME.
dumped_on=""
if [ "$(date +%H:%M)" = "$BACKUP_TIME" ]; then
    dumped_on=$(date +%F)
fi
while true; do
    sleep 30
    if [ "$(date +%H:%M)" = "$BACKUP_TIME" ] && [ "$(date +%F)" != "$dumped_on" ]; then
        dumped_on=$(date +%F)
        # A failed dump is logged, the container's health check reports it, and tomorrow's dump tries again.
        backup || echo "Backup failed" >&2
    fi
done
