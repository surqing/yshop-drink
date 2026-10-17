#!/usr/bin/env python3
"""Legacy HTTP/GUI launcher using the shared run-owned environment lifecycle."""
import argparse
import json
import os
from pathlib import Path
import signal
import sys
import time
import traceback
from evidence import source_identity
from owned_resources import failed_report, gui_receipt
from e2e.environment import OwnedEnvironment

repo=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('--output',required=True)
parser.add_argument('--port',type=int,default=48883)
parser.add_argument('--hold-for-gui',type=int,default=0)
parser.add_argument('--hold-for-device',type=int,default=0)
parser.add_argument('--cleanup-probe',action='store_true')
parser.add_argument('--instances',type=int,choices=[1,2],default=1)
args=parser.parse_args()
if not 0<=args.hold_for_gui<=900 or not 0<=args.hold_for_device<=900:raise RuntimeError('GUI_HOLD_LIMIT_REQUIRED')
if not 1024<=args.port<=65535:raise RuntimeError('INVALID_LOOPBACK_PORT')
root=Path(args.output).resolve()
environment=OwnedEnvironment(repo,root,port=args.port)
report=environment.report
report.update(gui='NOT_EXECUTED',mini='NOT_EXECUTED',scope='owned disposable backend HTTP writes')
report['runId']=os.environ.get('YSHOP_QUALITY_RUN_ID',environment.owner)
cleanup_phase=False

class ControlledInterrupted(BaseException):pass

def interrupted(signum,frame):
    if cleanup_phase:
        report.update(result='FAIL',reasonType='ControlledInterrupted',interruptedDuringCleanup=True)
        return
    signal.signal(signal.SIGTERM,signal.SIG_IGN)
    signal.signal(signal.SIGINT,signal.SIG_IGN)
    raise ControlledInterrupted('CONTROLLED_RUN_INTERRUPTED')

signal.signal(signal.SIGTERM,interrupted);signal.signal(signal.SIGINT,interrupted)
try:
    environment.start(instances=args.instances,require_build=os.environ.get('YSHOP_CROSS_END')=='1')
    from business_http import smoke, multi_process_smoke
    smoke(environment.sql,root,report,args.port)
    if args.instances==2:
        multi_process_smoke(environment.sql,root,report,args.port,args.port+1)
    if os.environ.get('YSHOP_CROSS_END')=='1':
        report['stage']='CROSS_END'
        from cross_end import run_cross_end
        run_cross_end(environment.sql,root,report,args.port,args.port+1)
    if args.hold_for_gui or args.hold_for_device:
        environment.file('gui-ready.json',json.dumps({'owner':environment.owner,'backendPort':args.port,'result':'READY','syntheticAccount':True}))
        started=time.time();deadline=started+max(args.hold_for_gui,args.hold_for_device)
        while time.time()<deadline and not (root/'gui-finished').exists():time.sleep(1)
        if args.hold_for_gui:
            try:
                report['guiEvidence']=gui_receipt(root/'gui-report.json',started,environment.identity,environment.owner)
                report['gui']='PASS'
            except Exception as error:
                report.update(gui='BLOCKED',result='BLOCKED',guiReasonType=type(error).__name__)
    if source_identity()!=environment.identity:raise RuntimeError('SOURCE_CHANGED_DURING_TEST')
except (Exception,ControlledInterrupted) as error:
    root.mkdir(parents=True,mode=0o700,exist_ok=True)
    environment.file('failure-private.log',traceback.format_exc())
    failed_report(report,error,report.get('stage','PROVISION'))
finally:
    cleanup_phase=True
    if args.cleanup_probe and root.exists():
        environment.file('cleanup-started','READY');time.sleep(2)
    cleanup=environment.close()
    if cleanup['status']!='PASSED':report['result']='FAIL'
    report['sourceUnchanged']=source_identity()==environment.identity
    report['endedAt']=time.time()
    if not report['sourceUnchanged']:report['result']='FAIL'
    root.mkdir(parents=True,mode=0o700,exist_ok=True)
    environment.file('report.json',json.dumps(report,indent=2))
    print(json.dumps({'result':report['result'],'cleanup':report['cleanup'],'checks':len(report.get('checks',[]))}))
sys.exit(0 if report['result']=='PASS' and report['cleanup']=='PASS' else 1)
