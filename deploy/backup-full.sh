#!/usr/bin/env bash
# Consistent database, document, configuration and image-reference recovery point.
set -euo pipefail
umask 077
cd "$(dirname "$0")"
if [[ "${SCHOOLAGENT_RELEASE_LOCKED:-}" != 1 ]]; then
  exec 9>.release.lock
  flock -n 9 || { echo 'Deployment or recovery is active'; exit 1; }
fi
BACKUP_ROOT="${SCHOOLAGENT_BACKUP_DIR:?Set a private backup directory outside the checkout}"
[[ "$BACKUP_ROOT" = /* ]] || { echo 'Backup directory must be absolute'; exit 1; }
[[ -f .env ]] || { echo 'Private deployment environment is required'; exit 1; }
python3 - "$BACKUP_ROOT" <<'PYROOT'
import pathlib,sys
root=pathlib.Path(sys.argv[1]).resolve()
if root.is_relative_to(pathlib.Path.cwd().parent.resolve()):
    raise SystemExit('Backups must be outside the checkout')
PYROOT
mkdir -p "$BACKUP_ROOT"
chmod 700 "$BACKUP_ROOT"
PENDING="$(mktemp -d "$BACKUP_ROOT/.pending.XXXXXXXX")"
RUNNING=()
for service in school-api school-web playwright-worker; do
  if [[ -n "$(docker compose ps --status running -q "$service")" ]]; then RUNNING+=("$service"); fi
done
cleanup() {
  local code=$?
  if ((${#RUNNING[@]})); then docker compose start "${RUNNING[@]}" >/dev/null || code=1; fi
  if [[ -n "$PENDING" ]]; then rm -rf -- "$PENDING"; fi
  exit "$code"
}
trap cleanup EXIT
if ((${#RUNNING[@]})); then docker compose stop "${RUNNING[@]}" >/dev/null; fi
mkdir -p api-data
docker compose exec -T postgres pg_dump -U schoolagent -d schoolagent -Fc > "$PENDING/database.dump"
docker compose exec -T postgres pg_restore --list < "$PENDING/database.dump" >/dev/null
CONFIG_FILES=(.env api-data docker-compose.yml)
[[ ! -f seccomp_profile.json ]] || CONFIG_FILES+=(seccomp_profile.json)
tar -czf "$PENDING/files.tar.gz" "${CONFIG_FILES[@]}"
# Pin the current images so subsequent builds and normal image pruning retain rollback.
python3 - "$PENDING" "${RUNNING[@]}" <<'PY'
import datetime, gzip, hashlib, json, pathlib, shutil, subprocess, sys
root=pathlib.Path(sys.argv[1]); services={}; archives={}
cache=root.parent/'images'; cache.mkdir(exist_ok=True); cache.chmod(0o700)
stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d%H%M%S%f')
for service in ('school-api','school-web','playwright-worker'):
    ids=subprocess.check_output(['docker','compose','ps','-aq',service],text=True).split()
    if not ids: continue
    image=subprocess.check_output(['docker','inspect','--format','{{.Image}}',ids[0]],text=True).strip()
    tag=f'school-agent-rollback:{stamp}-{service}'
    subprocess.run(['docker','tag',image,tag],check=True)
    services[service]={'image':tag,'pull_policy':'never'}
    target=cache/(image.removeprefix('sha256:')+'.tar.gz')
    if not target.exists():
        pending=target.with_suffix('.pending')
        try:
            with subprocess.Popen(['docker','image','save',image],stdout=subprocess.PIPE) as process:
                with gzip.open(pending,'wb',compresslevel=1) as compressed: shutil.copyfileobj(process.stdout,compressed)
                if process.wait(): raise RuntimeError('Could not archive rollback image')
            pending.chmod(0o600); pending.replace(target)
        finally: pending.unlink(missing_ok=True)
    with target.open('rb') as stream: digest=hashlib.file_digest(stream,'sha256').hexdigest()
    archives[tag]={'image':image,'file':target.name,'sha256':digest}
(root/'image-archives.json').write_text(json.dumps(archives,indent=2))
(root/'rollback.json').write_text(json.dumps({'services':services,'x-schoolagent-running-services':sys.argv[2:]},indent=2))
PY
(cd "$PENDING" && sha256sum database.dump files.tar.gz rollback.json image-archives.json > SHA256SUMS)
chmod 600 "$PENDING"/*
TARGET="$BACKUP_ROOT/snapshot-$(date -u +%Y%m%dT%H%M%S)-${PENDING##*.}"
mv -- "$PENDING" "$TARGET"
PENDING=''
printf '%s\n' "$TARGET"
# Retention removes a snapshot and its image tags together; always keep two recent points.
python3 - "$BACKUP_ROOT" "${SCHOOLAGENT_BACKUP_RETAIN_DAYS:-14}" <<'PYPRUNE'
import json,pathlib,shutil,subprocess,sys,time
root=pathlib.Path(sys.argv[1]); days=int(sys.argv[2])
if not 1 <= days <= 365: raise SystemExit('Retention must be 1–365 days')
points=sorted((p for p in root.glob('snapshot-*') if p.is_dir() and not p.is_symlink()),key=lambda p:p.stat().st_mtime,reverse=True)
for point in points[2:]:
    if point.stat().st_mtime >= time.time()-days*86400: continue
    try:
        tags=[s['image'] for s in json.loads((point/'rollback.json').read_text())['services'].values()]
        if any(not t.startswith('school-agent-rollback:') for t in tags): continue
        if any(subprocess.run(['docker','image','rm',tag],capture_output=True).returncode for tag in tags): continue
        shutil.rmtree(point)
    except (OSError,ValueError,KeyError): continue
# Shared image archives are retained while any snapshot references them.
try:
    used={entry['file'] for point in root.glob('snapshot-*') for entry in json.loads((point/'image-archives.json').read_text()).values()}
    for image in (root/'images').glob('*.tar.gz'):
        if image.name not in used and not image.is_symlink(): image.unlink()
except (OSError,ValueError,KeyError): pass
PYPRUNE
