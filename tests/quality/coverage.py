#!/usr/bin/env python3
"""Cross-module JaCoCo CLI report from explicitly supplied, run-owned execution data."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
from evidence import execute
REPO=Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser();p.add_argument('--run',required=True,type=Path);p.add_argument('--cli',required=True,type=Path);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
    data=list(a.run.glob('java-evidence/*/coverage.exec'))
    if not data or any(not f.stat().st_size for f in data):raise RuntimeError('FRESH_EXECUTION_DATA_REQUIRED')
    if not (a.run/'report.json').exists():raise RuntimeError('ATTRIBUTED_RUN_REQUIRED')
    a.output.mkdir(parents=True,exist_ok=False,mode=0o700)
    cmd=['java','-jar',str(a.cli),'report',*[str(x) for x in data],'--xml',str(a.output/'jacoco.xml'),'--html',str(a.output/'html')]
    for classes in (REPO/'yshop-drink-boot3').glob('**/target/classes'):
        cmd+=['--classfiles',str(classes)]
    for source in (REPO/'yshop-drink-boot3').glob('**/src/main/java'):
        cmd+=['--sourcefiles',str(source)]
    if execute(cmd,REPO,os.environ.copy(),a.output/'jacoco.log',180):raise RuntimeError('COVERAGE_REPORT_FAILED')
    xml=ET.parse(a.output/'jacoco.xml').getroot()
    def counters(root):
        result={}
        for c in root.findall('counter'):
            missed,covered=int(c.get('missed')),int(c.get('covered'))
            result[c.get('type')]={'missed':missed,'covered':covered,'percent':round(100*covered/(missed+covered),2) if missed+covered else None}
        return result
    names={'OrderPlacementService','PaymentCancellationGuard','PaymentAttemptService','PaymentProcessor','PaymentEffects','CouponMarketingService','CouponLifecycle','CatalogOptions','StoreAccessService','MemberAuthServiceImpl','PermissionServiceImpl'}
    classes={}
    for cls in xml.findall('package/class'):
        if cls.get('name').rsplit('/',1)[-1] in names:
            classes[cls.get('name')]={'counters':counters(cls),'unexecutedMethods':[m.get('name') for m in cls.findall('method') if any(c.get('type')=='METHOD' and int(c.get('missed'))>0 for c in m.findall('counter'))]}
    summary={'result':'MEASURED','runId':json.loads((a.run/'report.json').read_text())['runId'],'runResult':json.loads((a.run/'report.json').read_text())['result'],'scope':'all compiled src/main classes including test infrastructure; high-risk business classes separately, no DTO threshold','counters':counters(xml),'highRisk':classes,'rawReport':'jacoco.xml'}
    (a.output/'coverage.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary))
if __name__=='__main__':
    try:main()
    except Exception as e:print(json.dumps({'result':'FAIL','errorType':type(e).__name__}));sys.exit(1)
