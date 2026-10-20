#!/usr/bin/env python3
"""Isolated Docker recovery smoke test. Uses generated credentials and synthetic data only."""
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import time

repo = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='school-recovery-test-') as temporary:
    root = Path(temporary)
    deploy = root / 'deploy'
    deploy.mkdir()
    backups = Path(tempfile.mkdtemp(prefix='school-recovery-backups-'))
    env = {**os.environ, 'COMPOSE_PROJECT_NAME': 'school-recovery-' + secrets.token_hex(4),
           'SCHOOLAGENT_BACKUP_DIR': str(backups)}
    for name in ('backup-full.sh', 'restore.sh'):
        shutil.copy2(repo / 'deploy' / name, deploy / name)
    password = secrets.token_urlsafe(24)
    (deploy / '.env').write_text('POSTGRES_PASSWORD=' + password + '\nSCHOOLAGENT_MASTER_KEY=' + base64.b64encode(os.urandom(32)).decode() + '\n')
    (deploy / '.env').chmod(0o600)
    (deploy / 'seccomp_profile.json').write_text('{"defaultAction":"SCMP_ACT_ERRNO"}')
    (deploy / 'api-data').mkdir()
    (deploy / 'api-data' / 'synthetic.txt').write_text('before')
    # Exercise the entrypoint's temporary socket-only server before final TCP startup.
    (deploy / 'initialization.sql').write_text(
        "SELECT pg_sleep(2); CREATE TABLE initialization_probe(value text); "
        "INSERT INTO initialization_probe VALUES ('ready');\n")
    (deploy / 'docker-compose.yml').write_text('''services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_USER: schoolagent
      POSTGRES_DB: schoolagent
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - pg:/var/lib/postgresql/data
      - ./initialization.sql:/docker-entrypoint-initdb.d/initialization.sql:ro
  school-api:
    image: postgres:17
    command: [sleep, infinity]
    stop_grace_period: 1s
  school-web:
    image: postgres:17
    command: [sleep, infinity]
    stop_grace_period: 1s
  playwright-worker:
    image: postgres:17
    command: [sleep, infinity]
    profiles: [browser]
volumes:
  pg:
''')
    def run(args, *, data=None, check=True, overrides=None):
        result = subprocess.run(args, cwd=deploy, env={**env, **(overrides or {})}, input=data,
                                text=True, capture_output=True, timeout=120)
        if check and result.returncode:
            raise AssertionError('Recovery command failed: ' + args[0])
        return result
    def sql(statement):
        return run(['docker','compose','exec','-T','postgres','psql','-U','schoolagent','-d','schoolagent','-At','-v','ON_ERROR_STOP=1'],data=statement).stdout.strip()
    tags = []
    try:
        run(['docker','compose','up','-d'])
        for _ in range(60):
            # The bootstrap server accepts Unix-socket connections before initialization
            # finishes. Only the final server listens on TCP.
            if run(['docker','compose','exec','-T','postgres','pg_isready','-h','127.0.0.1','-U','schoolagent','-d','schoolagent'],check=False).returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise AssertionError('Recovery fixture PostgreSQL did not finish initialization')
        assert sql('select value from initialization_probe') == 'ready'
        sql("create table recovery_probe(value text); insert into recovery_probe values ('before');")
        snapshot = Path(run(['./backup-full.sh']).stdout.strip())
        tags = [s['image'] for s in json.loads((snapshot / 'rollback.json').read_text())['services'].values()]
        assert (snapshot / 'database.dump').stat().st_mode & 0o777 == 0o600
        sql("update recovery_probe set value='after';")
        (deploy / 'api-data' / 'synthetic.txt').write_text('after')
        dump = (snapshot / 'database.dump').read_bytes()
        (snapshot / 'database.dump').write_bytes(dump + b'corrupt')
        assert run(['./restore.sh',str(snapshot),'--replace-data'],check=False).returncode != 0
        assert sql('select value from recovery_probe') == 'after'
        (snapshot / 'database.dump').write_bytes(dump)
        run(['./restore.sh',str(snapshot),'--replace-data'])
        assert sql('select value from recovery_probe') == 'before'
        assert (deploy / 'api-data' / 'synthetic.txt').read_text() == 'before'
        assert password in (deploy / '.env').read_text()
        assert (deploy / 'seccomp_profile.json').exists()
        assert len(list((backups / 'images').glob('*.tar.gz'))) == 1
        assert list(deploy.glob('.restore-previous.*'))
        # A failed database dump must restore running services and publish no partial snapshot.
        fakebin = root / 'bin'; fakebin.mkdir()
        docker = shutil.which('docker')
        import shlex
        (fakebin / 'docker').write_text('#!/bin/sh\ncase " $* " in *" pg_dump "*) exit 9;; esac\nexec ' + shlex.quote(docker) + ' "$@"\n')
        (fakebin / 'docker').chmod(0o700)
        assert run(['./backup-full.sh'],check=False,overrides={'PATH':str(fakebin)+os.pathsep+env['PATH']}).returncode != 0
        assert len(list(backups.glob('snapshot-*'))) == 1
        assert not list(backups.glob('.pending.*'))
        assert len(run(['docker','compose','ps','--status','running','-q']).stdout.splitlines()) == 3
        print('PASS: full recovery, archive permissions, checksum rejection, failure cleanup and service resumption')
    finally:
        run(['docker','compose','down','-v','--remove-orphans'],check=False)
        shutil.rmtree(backups, ignore_errors=True)
        for tag in tags:
            run(['docker','image','rm',tag],check=False)
