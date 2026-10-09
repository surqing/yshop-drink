#!/usr/bin/env python3
"""Cross-module JaCoCo CLI report from explicitly supplied, run-owned execution data."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
from evidence import execute,source_identity
REPO=Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser();p.add_argument('--run',required=True,type=Path);p.add_argument('--cli',required=True,type=Path);p.add_argument('--output',required=True,type=Path);p.add_argument('--backend-run',type=Path);a=p.parse_args()
    data=list(a.run.glob('java-evidence/*/coverage.exec'))
    if not data or any(not f.stat().st_size for f in data):raise RuntimeError('FRESH_EXECUTION_DATA_REQUIRED')
    if not (a.run/'report.json').exists():raise RuntimeError('ATTRIBUTED_RUN_REQUIRED')
    run=json.loads((a.run/'report.json').read_text());identity=source_identity()
    if run.get('result')!='PASS' or not run.get('sourceUnchanged') or any(run.get(k)!=v for k,v in identity.items()):raise RuntimeError('COVERAGE_SOURCE_OR_GATE_MISMATCH')
    for f in data:
        certificate=json.loads((f.parent/'evidence.json').read_text());receipt=json.loads((f.parent/'run.json').read_text())
        if certificate.get('result')!='PASS' or any(certificate.get(k)!=v for k,v in identity.items()) or f.stat().st_mtime < (f.parent/'run.json').stat().st_mtime or certificate.get('runId')!=receipt.get('runId'):
            raise RuntimeError('STALE_OR_FOREIGN_COVERAGE_DATA')
    if a.backend_run:
        backend=json.loads((a.backend_run/'report.json').read_text());extra=a.backend_run/'backend-coverage.exec'
        if backend.get('result')!='PASS' or backend.get('cleanup')!='PASS' or not backend.get('sourceUnchanged') or any(backend.get(k)!=v for k,v in identity.items()) or not extra.is_file() or extra.stat().st_size==0:raise RuntimeError('BACKEND_COVERAGE_NOT_CERTIFIED')
        import hashlib
        if backend.get('backendArtifactHash')!=hashlib.sha256((REPO/'yshop-drink-boot3/yshop-server/target/yshop-server.jar').read_bytes()).hexdigest():raise RuntimeError('BACKEND_COVERAGE_ARTIFACT_MISMATCH')
        data.append(extra)
    a.output.mkdir(parents=True,exist_ok=False,mode=0o700)
    cmd=['java','-jar',str(a.cli),'report',*[str(x) for x in data],'--xml',str(a.output/'jacoco.xml'),'--html',str(a.output/'html')]
    for classes in (REPO/'yshop-drink-boot3').glob('**/target/classes'):
        cmd+=['--classfiles',str(classes)]
    for source in (REPO/'yshop-drink-boot3').glob('**/src/main/java'):
        cmd+=['--sourcefiles',str(source)]
    if execute(cmd,REPO,os.environ.copy(),a.output/'jacoco.log',180):raise RuntimeError('COVERAGE_REPORT_FAILED')
    if 'does not match' in (a.output/'jacoco.log').read_text():raise RuntimeError('EXECUTION_CLASS_MISMATCH')
    xml=ET.parse(a.output/'jacoco.xml').getroot()
    def counters(root):
        result={}
        for c in root.findall('counter'):
            missed,covered=int(c.get('missed')),int(c.get('covered'))
            result[c.get('type')]={'missed':missed,'covered':covered,'percent':round(100*covered/(missed+covered),2) if missed+covered else None}
        return result
    names={'OrderPlacementService','PaymentCancellationGuard','PaymentAttemptService','PaymentProcessor','PaymentEffects','CouponMarketingService','CouponLifecycle','CatalogOptions','StoreAccessService','MemberAuthServiceImpl','PermissionServiceImpl','OAuth2TokenServiceImpl','OAuth2TokenApiImpl','UserServiceImpl','AdminAuthServiceImpl'}
    classes={}
    for cls in xml.findall('package/class'):
        if cls.get('name').rsplit('/',1)[-1] in names:
            classes[cls.get('name')]={'counters':counters(cls),'unexecutedMethods':[m.get('name') for m in cls.findall('method') if any(c.get('type')=='METHOD' and int(c.get('missed'))>0 for c in m.findall('counter'))]}
    summary={**identity,'result':'MEASURED','runId':run['runId'],'runResult':run['result'],'backendHttpIncluded':bool(a.backend_run),'scope':'all compiled src/main classes including test infrastructure; high-risk business classes separately, no DTO threshold','counters':counters(xml),'highRisk':classes,'rawReport':'jacoco.xml'}
    (a.output/'coverage.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary))
if __name__=='__main__':
    try:main()
    except Exception as e:print(json.dumps({'result':'FAIL','errorType':type(e).__name__}));sys.exit(1)
