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

rsync -az --delete --exclude=node_modules --exclude=dist --exclude=target \
  --exclude=data --exclude=.angular --exclude='*.log' --exclude=.git \
  --exclude=.env --exclude='.env.*' --exclude='*.local.*' --exclude=api-data \
  "$PROJECT_ROOT/backend" "$PROJECT_ROOT/frontend" "$PROJECT_ROOT/deploy" \
  "$PROJECT_ROOT/playwright-worker" "$PROJECT_ROOT/docs" \
  "$PROJECT_ROOT/PROGRESS.md" "$PROJECT_ROOT/README.md" "$PROJECT_ROOT/AGENTS.md" \
  "$DESTINATION:$DEPLOY_DIR/"

# sudo reads from the interactive terminal; no password in argv, logs or environment.
ssh -tt "$DESTINATION" "cd $REMOTE_DIR_QUOTED/deploy && sudo docker compose --progress plain build $NO_CACHE $SERVICES && sudo docker compose --progress plain up -d --no-build $SERVICES"
for attempt in $(seq 1 30); do
  if curl -fsS --max-time 5 "${PUBLIC_ORIGIN%/}/actuator/health"; then
    printf '\nDeployed and healthy.\n'
    exit 0
  fi
  sleep 2
done
echo 'Health check failed; inspect API logs on the configured host.'
exit 1
