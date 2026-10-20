#!/usr/bin/env python3
"""Exercise deployment gates without SSH, live data, or external writes."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

class ReleaseTest(unittest.TestCase):
    def run_release(self, fail=''):
        with tempfile.TemporaryDirectory(prefix='school-release-test-') as temporary:
            root=Path(temporary); deploy=root/'deploy'; deploy.mkdir(); binary=root/'bin'; binary.mkdir()
            shutil.copy2(Path(__file__).resolve().parents[1]/'deploy/release.sh',deploy/'release.sh')
            scripts={
                binary/'docker': '''#!/bin/sh
printf 'docker %s\n' "$*" >> "$TRACE"
case "$*" in
  'compose build'*) [ "$FAIL" != build ] || exit 1;;
  'compose ps --status running -q school-web') echo web-id;;
  'compose up -d --no-build school-api') [ "$FAIL" != migration ] || exit 1;;
esac
''',
                binary/'curl': '''#!/bin/sh
echo health >> "$TRACE"
if [ "$FAIL" = health ]; then echo '{"status":"DOWN"}'; else echo '{"status":"UP"}'; fi
''',
                binary/'sleep':'#!/bin/sh\nexit 0\n',
                deploy/'backup-full.sh':'''#!/bin/sh
echo backup >> "$TRACE"
[ "$FAIL" != backup ] || exit 1
printf '%s\n' "$SNAPSHOT"
''',
                deploy/'restore.sh':'''#!/bin/sh
echo restore >> "$TRACE"
''',
            }
            for path,text in scripts.items(): path.write_text(text); path.chmod(0o700)
            trace=root/'trace'; snapshot=root/'snapshot'; snapshot.mkdir()
            env={**os.environ,'PATH':str(binary)+os.pathsep+os.environ['PATH'],'TRACE':str(trace),'FAIL':fail,'SNAPSHOT':str(snapshot)}
            result=subprocess.run(['./release.sh'],cwd=deploy,env=env,capture_output=True,text=True,timeout=20)
            return result.returncode,trace.read_text().splitlines()
    def test_build_and_backup_failures_do_not_replace_services(self):
        for failure in ('build','backup'):
            status,trace=self.run_release(failure)
            self.assertNotEqual(status,0)
            self.assertFalse(any('compose up' in line for line in trace))
            self.assertNotIn('restore',trace)
    def test_failed_migration_and_health_restore_the_snapshot(self):
        for failure in ('migration','health'):
            status,trace=self.run_release(failure)
            self.assertNotEqual(status,0)
            self.assertEqual(trace.count('restore'),1)
            self.assertNotIn('docker compose up -d --no-build school-web',trace)
    def test_web_reopens_only_after_healthy_api(self):
        status,trace=self.run_release()
        self.assertEqual(status,0)
        self.assertLess(trace.index('backup'),trace.index('docker compose up -d --no-build school-api'))
        self.assertLess(trace.index('health'),trace.index('docker compose up -d --no-build school-web'))
        self.assertNotIn('restore',trace)

if __name__=='__main__': unittest.main()
