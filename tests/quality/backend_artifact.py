"""Require a fresh complete-reactor build certificate before live cross-end execution."""
import hashlib
import json
from pathlib import Path

def verify_artifact(jar, certificate, identity):
    jar=Path(jar);certificate=Path(certificate)
    if not jar.is_file() or not certificate.is_file():
        raise RuntimeError('CURRENT_BACKEND_BUILD_REQUIRED')
    record=json.loads(certificate.read_text())
    if (record.get('result')!='PASS' or not record.get('runId') or record.get('modules')!=55
        or any(record.get(k)!=v for k,v in identity.items())
        or record.get('artifactHash')!=hashlib.sha256(jar.read_bytes()).hexdigest()):
        raise RuntimeError('CURRENT_BACKEND_BUILD_REQUIRED')
    return record
