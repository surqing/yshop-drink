#!/usr/bin/env python3
"""Exact-value regression scan of committed PR changes, working changes and runtime."""
import argparse
import json
import io
import zipfile
from pathlib import Path
import shlex
import subprocess
import sys

DEFAULT_BASE = 'baseline-runtime-validated-2026-10-03'


def scan_repository(repo, private_values, base_ref=DEFAULT_BASE):
    def git(*args):
        return subprocess.check_output(['git', *args], cwd=repo, stderr=subprocess.DEVNULL)
    # Resolve refs before use; invalid bases fail, never silently scan an empty diff.
    reference = git('rev-parse', '--verify', base_ref + '^{commit}').decode().strip()
    base = git('merge-base', 'HEAD', reference).decode().strip()
    committed = git('diff', '--name-only', '-z', base, 'HEAD').decode().split('\0')
    working = git('diff', '--name-only', '-z', 'HEAD').decode().split('\0')
    untracked = git('ls-files', '--others', '--exclude-standard', '-z').decode().split('\0')
    names = sorted(set(filter(None, committed + working + untracked)))
    failed = []
    for name in names:
        file = repo / name
        if file.is_file() and any(value in file.read_bytes() for value in private_values):
            failed.append(str(file))
    # Inspect committed content even if a later local edit removes a leaked value.
    for label, diff in [('Committed Git diff', git('diff', base, 'HEAD')),
                        ('Working Git diff', git('diff', 'HEAD'))]:
        if any(value in diff for value in private_values):
            failed.append(label)
    return failed, {'base': base, 'committedFiles': len(set(filter(None, committed))),
                    'workingFiles': len(set(filter(None, working))),
                    'untrackedFiles': len(set(filter(None, untracked)))}


def artifact_contains_private(data, values, depth=0):
    """Inspect compressed JVM artifacts in memory; never extract or echo entries."""
    if any(value in data for value in values):
        return True
    if not data.startswith(b'PK\x03\x04'):
        return False
    if depth > 3:
        raise ValueError('Archive nesting unavailable')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if sum(x.file_size for x in archive.infolist()) > 1024*1024*1024:
            raise ValueError('Archive scan bound exceeded')
        for entry in archive.infolist():
            if entry.is_dir():continue
            if artifact_contains_private(archive.read(entry), values, depth+1):return True
    return False


def main():
    repo = Path(__file__).resolve().parents[2]
    workspace = repo.parent
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default=DEFAULT_BASE, help='PR base ref; scan from its merge-base with HEAD')
    parser.add_argument('--runtime', action='append', default=[])
    parser.add_argument('--stdin-identities', action='store_true')
    parser.add_argument('--stdin-runtime', action='store_true', help='Read identities and runtime text only through stdin')
    args = parser.parse_args()
    private_values = []
    try:
        for file in [workspace / '.local-dev/.env', workspace / '.uniapp-dev/server/application-wechat.properties',
                     workspace / '.uniapp-dev/server/application-payment.properties']:
            if not file.exists():
                continue
            for line in file.read_text().splitlines():
                if '=' not in line or line.lstrip().startswith('#'):
                    continue
                key, value = line.split('=', 1)
                if any(part in key.lower() for part in ['password', 'secret', 'master-key', 'master_key']):
                    value = value.strip()
                    if file.suffix != '.properties':
                        value = ''.join(shlex.split(value))
                    if len(value) >= 8 and '${' not in value:
                        private_values.append(value.encode())
        if args.stdin_identities:
            private_values.extend(str(value).encode() for value in json.load(sys.stdin).values() if value)
        runtime_text = b''
        if args.stdin_runtime:
            incoming = json.load(sys.stdin)
            private_values.extend(str(value).encode() for value in incoming.get('identities', {}).values() if value)
            runtime_text = incoming.get('runtime', '').encode()
        if not private_values:
            raise ValueError('No protected values supplied')
        failed, coverage = scan_repository(repo, private_values, args.base)
        if any(value in runtime_text for value in private_values):
            failed.append('Runtime console (memory only)')
        for given in args.runtime:
            target = Path(given)
            if not target.exists():
                failed.append(str(target))
                continue
            files = target.rglob('*') if target.is_dir() else [target]
            for file in files:
                if file.is_file() and artifact_contains_private(file.read_bytes(), private_values):
                    failed.append(str(file))
        print(json.dumps({'result': 'FAIL' if failed else 'PASS', 'files': sorted(set(failed)), **coverage}))
        return 1 if failed else 0
    except Exception:
        # Never echo malformed private configuration, stdin or arbitrary git ref text.
        print(json.dumps({'result': 'FAIL', 'files': ['Scan input/base unavailable']}))
        return 1


if __name__ == '__main__':
    sys.exit(main())
