#!/usr/bin/env python3
"""Loopback-only real Nginx/TLS ingress. Synthetic CA is never added to system trust."""
import argparse
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import ssl
import time
import urllib.request
import urllib.error
from private_support import PRIVATE, REPO, guarded_main, private_json

DOCKER = '/Applications/Docker.app/Contents/Resources/bin/docker'
IMAGE = 'nginx@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94'
NAME = 'yshop-drink-local-ingress-streamed'
VOLUME = 'yshop-drink-phase5h-tls'


def command(args):
    return subprocess.run(args, check=True, capture_output=True, timeout=60).stdout


def certificates(folder):
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(folder, 0o700)
    if (folder / 'server.pem').exists():
        command(['openssl', 'x509', '-in', str(folder / 'server.pem'), '-checkend', '86400', '-noout'])
        return
    config = folder / 'tls.cnf'
    config.write_text('[req]\ndistinguished_name=dn\nprompt=no\n[dn]\nCN=localhost\n[server]\nsubjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n')
    command(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '30',
             '-subj', '/CN=yshop synthetic local CA', '-keyout', str(folder / 'ca-key.pem'),
             '-out', str(folder / 'ca.pem')])
    command(['openssl', 'req', '-newkey', 'rsa:2048', '-nodes', '-config', str(config),
             '-keyout', str(folder / 'server-key.pem'), '-out', str(folder / 'server.csr')])
    command(['openssl', 'x509', '-req', '-in', str(folder / 'server.csr'), '-CA', str(folder / 'ca.pem'),
             '-CAkey', str(folder / 'ca-key.pem'), '-CAcreateserial', '-days', '7',
             '-extfile', str(config), '-extensions', 'server', '-out', str(folder / 'server.pem')])
    for p in folder.iterdir():
        p.chmod(0o600)


def run():
    args = argparse.ArgumentParser()
    args.add_argument('action', choices=['start', 'stop'])
    args.add_argument('--upstream-port', type=int, default=48081)
    a = args.parse_args()
    if not 1024 <= a.upstream_port <= 65535:
        raise ValueError()
    info = subprocess.run([DOCKER, 'inspect', NAME], capture_output=True)
    if info.returncode == 0:
        state = json.loads(info.stdout)[0]
        if state['Config'].get('Labels', {}).get('project') != 'yshop-drink-phase5h':
            raise ValueError()
        command([DOCKER, 'rm', '-f', NAME])
    if a.action == 'stop':
        print(json.dumps({'result': 'STOPPED'}))
        return 0
    folder = PRIVATE / 'ingress'
    certificates(folder)
    config = (REPO / 'scripts/payment/callback-nginx.conf').read_text()
    config = config.replace('host.docker.internal:48081', 'host.docker.internal:' + str(a.upstream_port))
    target = folder / 'nginx.conf'
    target.write_text(config)
    target.chmod(0o600)
    # Stream only these synthetic files to an owned local volume. Docker Desktop need not
    # read the user's Documents folder; no system file-sharing/Keychain trust changes.
    command([DOCKER, 'volume', 'create', '--label', 'project=yshop-drink-phase5h', VOLUME])
    v = json.loads(command([DOCKER, 'volume', 'inspect', VOLUME]))[0]
    if v.get('Labels', {}).get('project') != 'yshop-drink-phase5h':
        raise ValueError()
    archive = io.BytesIO()
    with tarfile.open(fileobj=archive, mode='w') as tar:
        for name in ['server.pem', 'server-key.pem', 'nginx.conf']:
            content = (folder / name).read_bytes()
            info = tarfile.TarInfo(name)
            info.size = len(content)
            info.mode = 0o600
            info.uid = 101
            info.gid = 101
            tar.addfile(info, io.BytesIO(content))
    subprocess.run([DOCKER, 'run', '--rm', '-i', '--network', 'none',
                    '--label', 'project=yshop-drink-phase5h', '--entrypoint', 'sh',
                    '-v', VOLUME + ':/tls', IMAGE, '-c', 'tar xf - -C /tls'],
                   input=archive.getvalue(), capture_output=True, check=True, timeout=60)
    command([DOCKER, 'run', '-d', '--name', NAME, '--label', 'project=yshop-drink-phase5h',
             '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--user', '101:101',
             '--tmpfs', '/tmp:rw,nosuid,nodev,noexec,mode=1777',
             '-p', '127.0.0.1:48443:8443', '-v', VOLUME + ':/tls:ro', '--entrypoint', 'nginx',
             IMAGE, '-c', '/tls/nginx.conf', '-g', 'daemon off;'])
    # Docker's detached command success does not prove Nginx started or TLS works.
    verified = False
    for _ in range(20):
        try:
            urllib.request.urlopen('https://localhost:48443/',
                                   context=ssl.create_default_context(cafile=str(folder / 'ca.pem')), timeout=2)
        except urllib.error.HTTPError as error:
            verified = error.code == 404
        except urllib.error.URLError:
            pass
        if verified:
            break
        time.sleep(0.2)
    if not verified:
        raise RuntimeError()
    private_json(folder / 'state.json', {'image': IMAGE, 'upstreamPort': a.upstream_port,
                 'https': 'https://localhost:48443', 'systemTrustChanged': False,
                 'accessLogging': False, 'bodyOrSignatureLogging': False, 'publicIngress': False})
    print(json.dumps({'result': 'STARTED', 'https': 'https://localhost:48443', 'publicIngress': False}))
    return 0


if __name__ == '__main__':
    sys.exit(guarded_main(run))
