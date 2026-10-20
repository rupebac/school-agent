#!/usr/bin/env bash
# Explicit operator action; restores a verified snapshot and preserves displaced files.
set -euo pipefail
umask 077
[[ $# == 2 && "$2" == --replace-data ]] || { echo 'Usage: restore.sh ABSOLUTE_SNAPSHOT_DIR --replace-data'; exit 2; }
SNAPSHOT="$1"
[[ "$SNAPSHOT" = /* ]] || { echo 'Use an absolute snapshot directory'; exit 2; }
cd "$(dirname "$0")"
if [[ "${SCHOOLAGENT_RELEASE_LOCKED:-}" != 1 ]]; then
  exec 9>.release.lock
  flock -n 9 || { echo 'Deployment or backup is active'; exit 1; }
fi
(cd "$SNAPSHOT" && sha256sum --check --status SHA256SUMS)
# Refuse archives with paths or links that can escape the deployment directory.
python3 - "$SNAPSHOT/files.tar.gz" <<'PY'
import os,pathlib,sys,tarfile
with tarfile.open(sys.argv[1]) as archive:
    for member in archive:
        path=pathlib.PurePosixPath(member.name)
        if path.is_absolute() or '..' in path.parts or member.issym() or member.islnk() or not (member.isfile() or member.isdir()):
            raise SystemExit('Unsafe backup archive')
        if os.geteuid()!=0 and member.uid!=os.geteuid():
            raise SystemExit('Run restore with sudo to preserve document ownership')
        if path.parts[0] not in ('.env','api-data','docker-compose.yml','seccomp_profile.json'):
            raise SystemExit('Unexpected backup member')
PY
# Validate the dump and rollback images before stopping or replacing anything.
docker compose exec -T postgres pg_restore --list < "$SNAPSHOT/database.dump" >/dev/null
python3 - "$SNAPSHOT" <<'PY'
import gzip,hashlib,json,pathlib,re,subprocess,sys
root=pathlib.Path(sys.argv[1]); archives=json.loads((root/'image-archives.json').read_text())
for tag,entry in archives.items():
    if not re.fullmatch(r'[a-f0-9]{64}\.tar\.gz',entry['file']) or not re.fullmatch(r'sha256:[a-f0-9]{64}',entry['image']):
        raise SystemExit('Invalid image archive reference')
    archive=root.parent/'images'/entry['file']
    with archive.open('rb') as stream:
        if hashlib.file_digest(stream,'sha256').hexdigest()!=entry['sha256']: raise SystemExit('Image archive checksum failed')
    if subprocess.run(['docker','image','inspect',entry['image']],capture_output=True).returncode:
        with gzip.open(archive,'rb') as stream, subprocess.Popen(['docker','image','load'],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL) as load:
            import shutil
            shutil.copyfileobj(stream,load.stdin); load.stdin.close()
            if load.wait(): raise SystemExit('Could not restore rollback image')
    subprocess.run(['docker','tag',entry['image'],tag],check=True)
PY
docker compose stop school-api school-web playwright-worker >/dev/null
DISPLACED="$(mktemp -d .restore-previous.XXXXXXXX)"
for item in .env api-data docker-compose.yml seccomp_profile.json; do
  if [[ -e "$item" ]]; then mv -- "$item" "$DISPLACED/"; fi
done
tar -xzf "$SNAPSHOT/files.tar.gz" --same-owner
chmod 600 .env
docker compose exec -T postgres pg_restore -U schoolagent -d schoolagent --clean --if-exists --exit-on-error < "$SNAPSHOT/database.dump"
mapfile -t RESTORE_SERVICES < <(python3 - "$SNAPSHOT/rollback.json" <<'PYRUN'
import json,sys
for service in json.load(open(sys.argv[1])).get('x-schoolagent-running-services',['school-api','school-web']):
    if service not in ('school-api','school-web','playwright-worker'): raise SystemExit('Invalid snapshot service')
    print(service)
PYRUN
)
if ((${#RESTORE_SERVICES[@]})); then
  docker compose -f docker-compose.yml -f "$SNAPSHOT/rollback.json" up -d --no-build "${RESTORE_SERVICES[@]}"
fi
# Image availability and schema validation are checked by the restored application startup.
echo 'Snapshot restored. Previous files retained; verify /actuator/health before reopening access.'
