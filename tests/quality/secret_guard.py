#!/usr/bin/env python3
"""Offline PR diff guard. Exact local secrets are scanned separately, never supplied to CI."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

PRIVATE_KEY=re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----')
CREDENTIAL=re.compile(r'''(?ix)(?:password|app[_-]?secret|api[_-]?v3[_-]?key|master[_-]?key)\s*(?:=|:)\s*["']([A-Za-z0-9+/=_-]{16,})["']''')

def unsafe(line):
    # Recognizable examples/placeholders are allowed, random-looking literals are not.
    if PRIVATE_KEY.search(line):return True
    for value in CREDENTIAL.findall(line):
        if not value.lower().startswith(('synthetic','test-only','example','placeholder','changeme')):
            return True
    return False

def scan(repo,base):
    ref=subprocess.check_output(['git','rev-parse','--verify',base+'^{commit}'],cwd=repo).decode().strip()
    diff=subprocess.check_output(['git','diff','--no-ext-diff','--unified=0',ref],cwd=repo).decode(errors='replace')
    found=[];file='diff'
    for line in diff.splitlines():
        if line.startswith('+++ b/'):file=line[6:]
        elif line.startswith('+') and not line.startswith('+++') and unsafe(line[1:]):found.append(file)
    for name in subprocess.check_output(['git','ls-files','--others','--exclude-standard','-z'],cwd=repo).decode().split('\0'):
        p=repo/name
        if name and p.is_file() and any(unsafe(x) for x in p.read_text(errors='replace').splitlines()):found.append(name)
    return sorted(set(found))

def main():
    p=argparse.ArgumentParser();p.add_argument('--base',required=True);a=p.parse_args()
    try:
        found=scan(Path(__file__).resolve().parents[2],a.base)
        print(json.dumps({'result':'FAIL' if found else 'PASS','files':found,'scope':'new diff + untracked; PEM and credential literals; complementary to exact private-value scan'}))
        return bool(found)
    except Exception:
        print(json.dumps({'result':'FAIL','reason':'DIFF_UNAVAILABLE'}));return 1
if __name__=='__main__':sys.exit(main())
