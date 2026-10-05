#!/usr/bin/env python3
"""Build provenance is embedded only from an unchanged clean Git checkout."""
import json
import os
import subprocess
import sys
from private_support import PRIVATE, REPO, WORKSPACE, guarded_main, private_json


def git(*args):
    return subprocess.check_output(['git', *args], cwd=REPO, stderr=subprocess.DEVNULL).decode().strip()


def run():
    if git('status', '--porcelain'):
        raise ValueError()
    revision=git('rev-parse','HEAD')
    env=os.environ.copy();env['JAVA_HOME']=str(WORKSPACE/'.dev-tools/java-home')
    env['PATH']=str(WORKSPACE/'.dev-tools/java-home/bin')+os.pathsep+env['PATH']
    env['MAVEN_OPTS']='-Xmx2g'
    PRIVATE.mkdir(mode=0o700,exist_ok=True)
    log=PRIVATE/'verified-backend-build.log'
    fd=os.open(log,os.O_CREAT|os.O_TRUNC|os.O_WRONLY|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'w') as out:
        p=subprocess.run([str(WORKSPACE/'.dev-tools/maven/bin/mvn'), 'install','package',
             '-Dmaven.test.skip=true','-Dyshop.build.revision='+revision,
             '-Dmaven.repo.local='+str(WORKSPACE/'.local-dev/cache/maven')],
             cwd=REPO/'yshop-drink-boot3',env=env,stdout=out,stderr=subprocess.STDOUT)
    if p.returncode or git('status','--porcelain') or git('rev-parse','HEAD')!=revision:
        raise ValueError()
    import hashlib
    artifact=REPO/'yshop-drink-boot3/yshop-server/target/yshop-server.jar'
    report={'result':'PASS','sourceRevision':revision,'artifactSha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),
            'checkoutCleanBeforeAndAfter':True,'providerCalls':0}
    private_json(PRIVATE/'verified-build.json',report)
    print(json.dumps(report))
    return 0


if __name__=='__main__':
    sys.exit(guarded_main(run))
