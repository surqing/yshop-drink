#!/usr/bin/env python3
"""Owned loopback TLS proxy for the isolated callback test; leaves existing ingress untouched."""
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import uuid
from evidence import workspace
REPO=Path(__file__).resolve().parents[2]
IMAGE='nginx@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94'

def main():
    folder=Path(os.environ['YSHOP_SYNTHETIC_TLS_DIR']).resolve()
    # Existing authorized local synthetic CA, never system or merchant trust material.
    subject=subprocess.check_output(['openssl','x509','-in',str(folder/'ca.pem'),'-noout','-subject']).decode()
    if 'yshop synthetic local CA' not in subject:raise RuntimeError('SYNTHETIC_CA_REQUIRED')
    subprocess.run(['openssl','verify','-CAfile',str(folder/'ca.pem'),str(folder/'server.pem')],capture_output=True,check=True)
    name='yshop-quality-ingress-'+uuid.uuid4().hex[:12];volume=name+'-tls'
    def docker(*argv,**kwargs):return subprocess.run(['docker',*argv],capture_output=True,check=True,timeout=60,**kwargs).stdout
    volume_created=container_created=False
    try:
        docker('volume','create','--label','quality.owner='+name,volume);volume_created=True
        archive=io.BytesIO()
        linux=sys.platform.startswith('linux')
        config=(REPO/'scripts/payment/callback-nginx.conf').read_text().replace('host.docker.internal:48081','127.0.0.1:48881' if linux else 'host.docker.internal:48881')
        # Linux host networking preserves the test backend's loopback-only binding.
        # Docker Desktop has its own host DNS/proxy; overriding it causes 502.
        if linux:config=config.replace('listen 8443 ssl;', 'listen 127.0.0.1:48444 ssl;')
        with tarfile.open(fileobj=archive,mode='w') as out:
            for file in ['server.pem','server-key.pem','nginx.conf']:
                data=config.encode() if file=='nginx.conf' else (folder/file).read_bytes()
                info=tarfile.TarInfo(file);info.size=len(data);info.uid=101;info.gid=101;info.mode=0o600
                out.addfile(info,io.BytesIO(data))
        docker('run','--rm','-i','--network','none','--entrypoint','sh','-v',volume+':/tls',IMAGE,'-c','tar xf - -C /tls',input=archive.getvalue())
        docker('create','--name',name,'--label','quality.owner='+name,*(['--network','host'] if linux else []),'--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--user','101:101','--tmpfs','/tmp:rw,nosuid,nodev,noexec,mode=1777',*([] if linux else ['-p','127.0.0.1:48444:8443']),'-v',volume+':/tls:ro','--entrypoint','nginx',IMAGE,'-c','/tls/nginx.conf','-g','daemon off;');container_created=True
        docker('start',name)
        env=os.environ.copy();env['YSHOP_INGRESS_BASE_URL']='https://localhost:48444';env['YSHOP_INGRESS_CA']=str(folder/'ca.pem')
        result=subprocess.run([sys.executable,str(REPO/'tests/payment/mysql-acceptance.py'),'--prepayment','--ingress-only'],env=env,timeout=780)
        if result.returncode:raise RuntimeError('SYNTHETIC_INGRESS_TEST_FAILED')
    finally:
        try:
            if container_created:
                state=json.loads(docker('inspect',name))[0]
                if state['Config']['Labels'].get('quality.owner')!=name:raise RuntimeError('OWNERSHIP_MISMATCH')
                docker('rm','-f',name)
                if docker('ps','-a','--filter','name=^/'+name+'$','--format','{{.Names}}').strip():raise RuntimeError('INGRESS_CONTAINER_REMAINS')
        finally:
            if volume_created:
                state=json.loads(docker('volume','inspect',volume))[0]
                if state['Labels'].get('quality.owner')!=name:raise RuntimeError('OWNERSHIP_MISMATCH')
                docker('volume','rm',volume)
                if docker('volume','ls','--filter','name=^'+volume+'$','--format','{{.Name}}').strip():raise RuntimeError('INGRESS_VOLUME_REMAINS')
    print(json.dumps({'result':'PASS','scope':'synthetic localhost TLS only','cleanup':'PASS','existingIngressChanged':False}))
if __name__=='__main__':
    try:main()
    except Exception as exc:
        print(json.dumps({'result':'FAIL','errorType':type(exc).__name__}));sys.exit(1)
