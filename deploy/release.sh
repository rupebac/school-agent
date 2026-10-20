#!/usr/bin/env bash
# Run on the server with Docker permissions, after transferring reviewed source.
set -euo pipefail
umask 077
cd "$(dirname "$0")"
exec 9>.release.lock
export SCHOOLAGENT_RELEASE_LOCKED=1
flock -n 9 || { echo 'Another release is active'; exit 1; }
SERVICES=("$@")
((${#SERVICES[@]})) || SERVICES=(school-api school-web)
for service in "${SERVICES[@]}"; do
  [[ "$service" == school-api || "$service" == school-web || "$service" == playwright-worker ]] || { echo 'Invalid release service'; exit 2; }
done
# Build first: a build failure leaves the running deployment untouched.
BUILD_FLAGS=()
[[ "${SCHOOLAGENT_NO_CACHE:-false}" != true ]] || BUILD_FLAGS+=(--no-cache)
docker compose build "${BUILD_FLAGS[@]}" "${SERVICES[@]}"
if [[ -f .prepared-recovery-point ]]; then
  SNAPSHOT="$(cat .prepared-recovery-point)"
  (cd "$SNAPSHOT" && sha256sum --check --status SHA256SUMS)
  rm .prepared-recovery-point
else
  SNAPSHOT="$(./backup-full.sh)"
fi
printf '%s\n' "$SNAPSHOT" > .last-recovery-point
rollback() {
  echo 'Release failed; restoring database, documents, configuration and previous images.' >&2
  ./restore.sh "$SNAPSHOT" --replace-data
}
# Keep public UI traffic stopped until API startup/migrations have passed health checks.
WEB_WAS_RUNNING="$(docker compose ps --status running -q school-web)"
docker compose stop school-web >/dev/null
BACKEND_SERVICES=()
DEPLOY_WEB=false
for service in "${SERVICES[@]}"; do
  if [[ "$service" == school-web ]]; then DEPLOY_WEB=true; else BACKEND_SERVICES+=("$service"); fi
done
if ((${#BACKEND_SERVICES[@]})) && ! docker compose up -d --no-build "${BACKEND_SERVICES[@]}"; then rollback; exit 1; fi
for attempt in $(seq 1 45); do
  if curl -fsS --max-time 5 http://127.0.0.1:8080/actuator/health 2>/dev/null | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("status")=="UP" else 1)' 2>/dev/null; then
    if [[ "$DEPLOY_WEB" == true ]]; then
      if ! docker compose up -d --no-build school-web; then rollback; exit 1; fi
    elif [[ -n "$WEB_WAS_RUNNING" ]]; then
      if ! docker compose start school-web; then rollback; exit 1; fi
    fi
    echo 'Release healthy; Flyway validation and migrations completed at startup.'
    exit 0
  fi
  sleep 2
done
rollback
exit 1
