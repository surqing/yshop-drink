"""E2E lifecycle entry; environment and drivers are supplied by the owned engine."""
import os
from pathlib import Path
import time
import uuid
from evidence import source_identity
from .contract import final_status, write_reports
from .registry import listing


def run(repo, output, profile, scenario=None):
    repo=Path(repo);root=Path(output).resolve()/uuid.uuid4().hex
    root.mkdir(parents=True,mode=0o700,exist_ok=False)
    os.chmod(root,0o700)
    identity=source_identity(repo);plan=listing(profile,scenario)
    report={**identity,**plan,'runId':root.name,'owner':uuid.uuid4().hex,'complete':False,
            'startedAt':time.time(),'status':'INCONCLUSIVE','cleanup':'NOT_RUN',
            'planned':plan['scenarios'],
            'scenarios':[{'id':s['id'],'variant':s['variant'],'status':'NOT_RUN','checks':[]} for s in plan['scenarios']],
            'receiptValidated':False,'sourceUnchanged':True}
    write_reports(root,report)
    # The independently testable CLI/report contract lands before the owned execution engine.
    report.update(status='BLOCKED',reasonCode='E2E_ENGINE_REQUIRED',cleanup='PASSED',complete=True,endedAt=time.time())
    current=source_identity(repo);report['sourceUnchanged']=identity==current
    report['status']=final_status(report,current)
    report['result']='PASS' if report['status']=='PASSED' else 'NOT_READY'
    write_reports(root,report)
    print(__import__('json').dumps({'runId':report['runId'],'status':report['status'],'report':str(root/'report.json')}))
    return 0 if report['status']=='PASSED' else 1
