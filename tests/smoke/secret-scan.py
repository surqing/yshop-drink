#!/usr/bin/env python3
"""Scan added source and selected runtime files without printing private values."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess
import sys

repo = Path(__file__).resolve().parents[2]
workspace = repo.parent
parser = argparse.ArgumentParser()
parser.add_argument('--runtime', action='append', default=[])
parser.add_argument('--stdin-identities', action='store_true')
parser.add_argument('--stdin-runtime', action='store_true', help='Read identities and runtime text only through stdin')
args = parser.parse_args()
private_values = []
for file in [workspace / '.local-dev/.env', workspace / '.uniapp-dev/server/application-wechat.properties']:
    for line in file.read_text().splitlines():
        if '=' not in line or line.lstrip().startswith('#'):
            continue
        key, value = line.split('=', 1)
        if any(part in key.lower() for part in ['password', 'secret']):
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
changed = subprocess.check_output(['git', 'diff', '--name-only', 'HEAD'], cwd=repo).decode().splitlines()
untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=repo).decode().splitlines()
failed = []
if any(value in runtime_text for value in private_values):
    failed.append('Runtime console (memory only)')
# Scan whole new/changed files, which also detects accidentally copied baseline keys.
for rel in sorted(set(changed + untracked)):
    file = repo / rel
    if file.is_file() and any(value in file.read_bytes() for value in private_values):
        failed.append(str(file))
for given in args.runtime:
    target = Path(given)
    files = target.rglob('*') if target.is_dir() else [target]
    for file in files:
        if file.is_file() and any(value in file.read_bytes() for value in private_values):
            failed.append(str(file))
# Also scan the diff representation: credentials can accidentally be introduced
# on changed lines or copied into comments/documentation.
diff = subprocess.check_output(['git', 'diff', 'HEAD'], cwd=repo)
if any(value in diff for value in private_values):
    failed.append('Git diff')
print(json.dumps({'result': 'FAIL' if failed else 'PASS', 'files': sorted(set(failed))}))
sys.exit(1 if failed else 0)
