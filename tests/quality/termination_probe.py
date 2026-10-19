#!/usr/bin/env python3
"""Cancel the actual owned launcher during JVM readiness and prove cleanup, never shared data."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
from backend_artifact import verify_artifact
from evidence import source_identity
from loopback_ports import backend_pair

REPO=Path(__file__).resolve().parents[2]

def probe(output,phase):
    identity=source_identity()
    verify_artifact(REPO/'yshop-drink-boot3/yshop-server/target/yshop-server.jar',REPO/'.quality/backend-build.json',identity)
    root=Path(output).resolve();root.mkdir(mode=0o700,parents=True,exist_ok=False)
    owned=root/'owned';env=os.environ.copy();env['YSHOP_CROSS_END']='0'
    report={**identity,'result':'FAIL','scope':'actual launcher cancellation during '+phase,'checks':[]}
    with (root/'launcher-private.log').open('w') as log:
        os.chmod(log.name,0o600)
        argv=[sys.executable,'tests/quality/business-backend.py','--output',str(owned),'--port',str(backend_pair())]
        if phase=='cleanup':argv.append('--cleanup-probe')
        child=subprocess.Popen(argv,cwd=REPO,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        try:
            deadline=time.monotonic()+180
            marker=owned/('process.json' if phase=='readiness' else 'cleanup-started')
            while not marker.exists():
                if child.poll() is not None or time.monotonic()>deadline:raise RuntimeError('OWNED_LAUNCH_NOT_READY')
                time.sleep(.05)
            # process.json is written immediately after Popen, while health readiness retries run.
            os.killpg(child.pid,signal.SIGTERM);child.wait(timeout=120)
            r=json.loads((owned/'report.json').read_text());pid=json.loads((owned/'process.json').read_text())['pid']
            try:os.kill(pid,0);gone=False
            except ProcessLookupError:gone=True
            checks={'interruption fails run':r.get('result')=='FAIL' and r.get('reasonType')=='ControlledInterrupted',
                'owned containers and volumes removed':r.get('cleanup')=='PASS' and r.get('resources',{}).get('containersRemoved')==2 and r.get('resources',{}).get('anonymousVolumesRemoved')==2,
                'owned JVM terminated':gone,
                'source unchanged':source_identity()==identity and r.get('sourceUnchanged') is True}
            report['checks']=[{'name':n,'result':'PASS' if ok else 'FAIL','executed':True} for n,ok in checks.items()]
            report['owner']=r.get('owner');report['runId']=r.get('runId');report['cleanup']=r.get('cleanup')
            report['result']='PASS' if all(checks.values()) else 'FAIL'
        finally:
            if child.poll() is None:
                os.killpg(child.pid,signal.SIGTERM)
                try:child.wait(timeout=120)
                except subprocess.TimeoutExpired:os.killpg(child.pid,signal.SIGKILL);child.wait()
            (root/'report.json').write_text(json.dumps(report,indent=2))
    print(json.dumps({'result':report['result'],'checks':len(report['checks']),'cleanup':report.get('cleanup')}))
    return 0 if report['result']=='PASS' else 1

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True);parser.add_argument('--phase',choices=['readiness','cleanup'],default='readiness')
    args=parser.parse_args();sys.exit(probe(args.output,args.phase))
