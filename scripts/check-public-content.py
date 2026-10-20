#!/usr/bin/env python3
"""Check tracked/new staged text without logging matched private values."""
import argparse
import ipaddress
import pathlib
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(['git', *args])


def findings(path, text):
    if path in {'backend/mvnw', 'backend/mvnw.cmd', 'frontend/package-lock.json'}:
        return []  # Public upstream metadata; Gitleaks still scans these files.
    result = []
    for number, line in enumerate(text.splitlines(), 1):
        for value in re.findall(r'\b(?:\d{1,3}\.){3}\d{1,3}\b', line):
            try:
                address = ipaddress.ip_address(value)
            except ValueError:
                continue
            if any(address in network for network in PRIVATE_NETWORKS) and not (
                path == 'scripts/check-public-content.py' and (line.startswith('PRIVATE_NETWORKS =') or line.startswith('RFC1918_FIXTURE_VALUES ='))
                or path in RFC1918_FIXTURE_FILES and value in RFC1918_FIXTURE_VALUES):
                result.append((number, 'private-network-address'))
        if re.search(r'/home/[A-Za-z][A-Za-z0-9_.-]*/|/Users/[A-Za-z][A-Za-z0-9_.-]*/', line):
            result.append((number, 'personal-home-path'))
        for email in re.findall(r'[\w.+-]+@([\w.-]+\.[A-Za-z]{2,})', line):
            if not reserved(email) and email not in {'users.noreply.github.com', 'group.calendar.google.com'}:
                result.append((number, 'non-example-email'))
        if re.search(r'(?:wabaId|phoneNumberId|phone_number_id)[\"\'\s:=]+[\"\']?\d{10,}', line):
            result.append((number, 'non-example-account-identifier'))
        # Reserved phone ranges for fictional examples; no real phone data in tracked text.
        for phone in re.findall(r'(?<![\w])\+\d[\d ()-]{8,}\d', line):
            digits = re.sub(r'\D', '', phone)
            if not (digits.startswith('120255501') or digits.startswith('447700900')):
                result.append((number, 'non-example-phone'))
    return result


def reserved(domain):
    return domain in {'example.com', 'example.org', 'example.net'} or domain.endswith(
        ('.example', '.test', '.invalid', '.example.com', '.example.org', '.example.net'))


# Public RFC 1918 block boundaries and synthetic boundary-test inputs only.
# This exception is limited to exact values in the SSRF policy and its tests;
# other addresses in those files, and these values anywhere else, still fail.
RFC1918_FIXTURE_FILES = {
    'playwright-worker/network-policy.js',
    'playwright-worker/network-policy.test.js',
    'backend/src/test/kotlin/de/schoolagent/crawler/PublicNetworkPolicyTest.kt',
}
RFC1918_FIXTURE_VALUES = {'10.0.0.0', '10.0.0.1', '172.16.0.0', '172.16.0.1', '192.168.0.0', '192.168.0.1', '192.168.1.1'}

PRIVATE_NETWORKS = tuple(ipaddress.ip_network(value) for value in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--staged', action='store_true', help='Check new staged blobs, including uncommitted additions')
    args = parser.parse_args()
    files = git('diff', '--cached', '--name-only', '--diff-filter=ACMR', '-z') if args.staged else git('ls-files', '-z')
    failures = []
    for name in files.decode().split('\0'):
        if not name:
            continue
        path = pathlib.PurePosixPath(name)
        if (path.name == '.env' or path.name.startswith('.env.') and path.name != '.env.example' or
            '.local.' in path.name or path.suffix in {'.pem', '.key', '.p12', '.pfx', '.dump', '.har', '.bundle', '.pdf', '.pyc'} or
            any(part in {'api-data', 'backups', 'logs', 'node_modules', 'target', '__pycache__'} for part in path.parts)):
            failures.append((name, 0, 'private-or-runtime-file'))
            continue
        data = git('show', ':' + name) if args.staged else pathlib.Path(name).read_bytes()
        try:
            text = data.decode()
        except UnicodeError:
            continue  # Binary assets require human review plus the secret scan.
        failures.extend((name, number, rule) for number, rule in findings(name, text))
    for name, number, rule in sorted(set(failures)):
        print(f'{name}:{number}: {rule}', file=sys.stderr)
    if failures:
        print('Use fictional/reserved examples or move private material outside Git; matched values are not printed.', file=sys.stderr)
        return 1
    print('Tracked content privacy checks passed.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
