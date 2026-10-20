#!/usr/bin/env bash
# School Agent database backup (spec §33: critical data must be backed up).
# Run from cron, e.g. daily at 03:17:
#   17 3 * * *  /path/to/school-agent/deploy/backup.sh >> /var/log/schoolagent-backup.log 2>&1
set -euo pipefail
umask 077

BACKUP_DIR="${SCHOOLAGENT_BACKUP_DIR:-$HOME/schoolagent-backups}"
RETAIN_DAYS="${SCHOOLAGENT_BACKUP_RETAIN_DAYS:-14}"
CONTAINER="${SCHOOLAGENT_DB_CONTAINER:-deploy-postgres-1}"
DB_USER="${SCHOOLAGENT_DB_USER:-schoolagent}"
DB_NAME="${SCHOOLAGENT_DB_NAME:-schoolagent}"

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
TARGET="$BACKUP_DIR/schoolagent-$STAMP.sql.gz"

PENDING="$(mktemp "$BACKUP_DIR/.schoolagent-pending.XXXXXX")"
trap 'rm -f -- "$PENDING"' EXIT
docker exec "$CONTAINER" pg_dump -U "$DB_USER" "$DB_NAME" | gzip > "$PENDING"
gzip -t "$PENDING"
mv -- "$PENDING" "$TARGET"
echo "$(date -Is) backup written: $TARGET ($(du -h "$TARGET" | cut -f1))"

find "$BACKUP_DIR" -name 'schoolagent-*.sql.gz' -mtime "+$RETAIN_DAYS" -delete
echo "$(date -Is) retention: removed backups older than $RETAIN_DAYS days"
