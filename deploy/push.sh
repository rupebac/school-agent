#!/usr/bin/env bash
# Deploy tested source using explicit operator configuration and SSH keys.
set -euo pipefail

DEPLOY_HOST="${SCHOOLAGENT_HOST:?Set the SSH host}"
DEPLOY_USER="${SCHOOLAGENT_USER:?Set the SSH user}"
DEPLOY_DIR="${SCHOOLAGENT_REMOTE_DIR:?Set the absolute remote project directory}"
PUBLIC_ORIGIN="${SCHOOLAGENT_PUBLIC_URL:?Set the application origin}"
[[ "$DEPLOY_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9.:_-]*$ ]] || { echo 'Invalid SSH host'; exit 1; }
[[ "$DEPLOY_USER" =~ ^[a-zA-Z_][a-zA-Z0-9_-]*$ ]] || { echo 'Invalid SSH user'; exit 1; }
[[ "$DEPLOY_DIR" = /* && "$DEPLOY_DIR" != *$'\n'* ]] || { echo 'Use an absolute remote directory'; exit 1; }
[[ "$PUBLIC_ORIGIN" =~ ^https?:// ]] || { echo 'Application origin must use HTTP or HTTPS'; exit 1; }
BACKUP_ROOT="${SCHOOLAGENT_REMOTE_BACKUP_DIR:?Set an absolute private remote backup directory}"
[[ "$BACKUP_ROOT" = /* && "$BACKUP_ROOT" != *$'\n'* ]] || { echo 'Use an absolute backup directory'; exit 1; }
printf -v BACKUP_ROOT_QUOTED '%q' "$BACKUP_ROOT"
DESTINATION="$DEPLOY_USER@$DEPLOY_HOST"
printf -v REMOTE_DIR_QUOTED '%q' "$DEPLOY_DIR"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
SERVICES='school-api school-web'
NO_CACHE=''
for arg in "$@"; do
  case "$arg" in
    --api-only) SERVICES='school-api' ;;
    --web-only) SERVICES='school-web' ;;
    --worker) SERVICES="$SERVICES playwright-worker" ;;
    --no-cache) NO_CACHE='--no-cache' ;;
    --force) ;; # Compatibility: selected services are always built.
    *) echo "Unknown option: $arg"; exit 1 ;;
  esac
done

# Install only recovery helpers first; snapshot the live configuration before replacing source.
scp "$SCRIPT_DIR/backup-full.sh" "$SCRIPT_DIR/restore.sh" "$SCRIPT_DIR/release.sh" "$DESTINATION:$DEPLOY_DIR/deploy/"
ssh -tt "$DESTINATION" "cd $REMOTE_DIR_QUOTED/deploy && sudo env SCHOOLAGENT_BACKUP_DIR=$BACKUP_ROOT_QUOTED bash -c 'umask 077; ./backup-full.sh > .prepared-recovery-point'"

rsync -az --delete --exclude=node_modules --exclude=dist --exclude=target \
  --exclude=/deploy/backups/ --exclude=data --exclude=.angular --exclude='*.log' --exclude=.git \
  --exclude=.env --exclude='.env.*' --exclude='*.local.*' --exclude=api-data \
  --exclude='.restore-previous.*' --exclude='.release.lock' --exclude='.last-recovery-point' --exclude='.prepared-recovery-point' \
  "$PROJECT_ROOT/backend" "$PROJECT_ROOT/frontend" "$PROJECT_ROOT/deploy" \
  "$PROJECT_ROOT/playwright-worker" "$PROJECT_ROOT/docs" \
  "$PROJECT_ROOT/PROGRESS.md" "$PROJECT_ROOT/README.md" "$PROJECT_ROOT/AGENTS.md" \
  "$DESTINATION:$DEPLOY_DIR/"

# sudo reads from the interactive terminal; no password in argv, logs or environment.
ssh -tt "$DESTINATION" "cd $REMOTE_DIR_QUOTED/deploy && sudo env SCHOOLAGENT_BACKUP_DIR=$BACKUP_ROOT_QUOTED SCHOOLAGENT_NO_CACHE=${NO_CACHE:+true} ./release.sh $SERVICES"
for attempt in $(seq 1 30); do
  if curl -fsS --max-time 5 "${PUBLIC_ORIGIN%/}/actuator/health"; then
    printf '\nDeployed and healthy.\n'
    exit 0
  fi
  sleep 2
done
echo 'Health check failed; inspect API logs on the configured host.'
exit 1
