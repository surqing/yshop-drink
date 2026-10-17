"""Exact E2E evidence validation and private, human-readable result artifacts."""
from collections import Counter
import html
import json
import os
from pathlib import Path
import time
import xml.etree.ElementTree as ET


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def validate_receipt(receipt, planned, identity, run_id, owner, started):
    require(isinstance(receipt,dict),'E2E_RECEIPT_INVALID')
    require(receipt.get('runId')==run_id and receipt.get('owner')==owner,'E2E_RECEIPT_FOREIGN')
    require(all(receipt.get(k)==v for k,v in identity.items()),'E2E_RECEIPT_SOURCE_MISMATCH')
    require(receipt.get('complete') is True and receipt.get('status')=='PASSED','E2E_RECEIPT_INCOMPLETE')
    require(receipt.get('driversCleanup')=='PASSED','E2E_DRIVER_CLEANUP_REQUIRED')
    beginning,end=receipt.get('startedAt'),receipt.get('endedAt')
    require(type(beginning) in (int,float) and type(end) in (int,float)
            and started<=beginning<=end<=time.time()+5,'E2E_RECEIPT_TIME_INVALID')
    require(bool(planned),'E2E_PLAN_EMPTY')
    expected={s['id']:s for s in planned}
    require(len(expected)==len(planned),'E2E_PLAN_DUPLICATE')
    discovery=[{'id':s['id'],'variant':s['variant']} for s in planned]
    require(receipt.get('planned')==discovery,'E2E_DISCOVERY_MISMATCH')
    cases=receipt.get('scenarios')
    require(isinstance(cases,list) and all(isinstance(c,dict) for c in cases),'E2E_SCENARIOS_INVALID')
    require(Counter(c.get('id') for c in cases)==Counter(expected.keys()),'E2E_SCENARIOS_MISMATCH')
    count=0
    for case in cases:
        plan=expected[case['id']]
        require(case.get('variant')==plan['variant'],'E2E_VARIANT_MISMATCH')
        require(case.get('status')=='PASSED' and case.get('retry')==0,'E2E_CASE_NOT_PASSED')
        checks=case.get('checks')
        require(isinstance(checks,list) and bool(checks) and all(isinstance(c,dict) for c in checks),'E2E_CHECKS_EMPTY')
        names=[c.get('id') for c in checks]
        require(len(set(names))==len(names) and Counter(names)==Counter(plan['expectedCheckIds']),
                'E2E_CHECKS_MISMATCH')
        require(all(c.get('executed') is True and c.get('status')=='PASSED' for c in checks),'E2E_CHECK_NOT_PASSED')
        if plan['variant']=='mini-admin':
            mini=case.get('miniEvidence')
            require(isinstance(mini,dict),'E2E_MINI_EVIDENCE_REQUIRED')
            require(mini.get('officialConnected') is True and mini.get('compiledHashesVerified') is True,
                    'E2E_MINI_RUNTIME_NOT_VERIFIED')
            require(mini.get('compiledSource')==identity and type(mini.get('compiledFileCount')) is int
                    and mini['compiledFileCount']>0,'E2E_MINI_COMPILATION_MISMATCH')
            require(all(type(mini.get(key)) is int and mini[key]==0 for key in ['paymentRequests','blockedExternalRequests']),
                    'E2E_MINI_EXTERNAL_REQUEST_RECORDED')
        count+=len(checks)
    return {'scenarios':len(cases),'checks':count,'exactNamesChecked':True}


def final_status(report, current_identity):
    if (report.get('complete') is not True or report.get('sourceUnchanged') is not True
        or any(report.get(k)!=v for k,v in current_identity.items())
        or report.get('cleanup')!='PASSED'):
        return 'INCONCLUSIVE'
    scenarios=report.get('scenarios',[])
    if any(s.get('status')=='FAILED' for s in scenarios):
        return 'FAILED'
    if report.get('status') in ('FAILED','BLOCKED','INCONCLUSIVE'):
        return report['status']
    if not scenarios:
        return 'NOT_RUN'
    if any(s.get('status')=='BLOCKED' for s in scenarios):
        return 'BLOCKED'
    if not report.get('receiptValidated') or any(s.get('status')!='PASSED' for s in scenarios):
        return 'INCONCLUSIVE'
    return 'PASSED'


def atomic_text(path, text):
    path=Path(path)
    temporary=path.with_name(path.name+'.tmp')
    with temporary.open('w') as output:
        os.chmod(temporary,0o600)
        output.write(text)
    temporary.replace(path)


def write_reports(root, report):
    root=Path(root);root.mkdir(parents=True,exist_ok=True,mode=0o700)
    atomic_text(root/'report.json',json.dumps(report,ensure_ascii=False,indent=2))
    cases=report.get('scenarios',[])
    failures=sum(s.get('status')=='FAILED' for s in cases)
    skipped=sum(s.get('status') not in ('PASSED','FAILED') for s in cases)
    # A process/environment failure must remain red in JUnit, even with no failed business case.
    infrastructure=report.get('status')!='PASSED' and not failures
    suite=ET.Element('testsuite',name='three-end-e2e',tests=str(len(cases)+int(infrastructure)),
                     failures=str(failures+int(infrastructure)),skipped=str(skipped))
    for case in cases:
        node=ET.SubElement(suite,'testcase',name=case['id'],classname=case.get('variant','unknown'))
        if case.get('status')=='FAILED':
            ET.SubElement(node,'failure',message=case.get('reasonCode','E2E_CHECK_FAILED'))
        elif case.get('status')!='PASSED':
            ET.SubElement(node,'skipped',message=case.get('status','NOT_RUN'))
    if infrastructure:
        node=ET.SubElement(suite,'testcase',name='execution-and-cleanup',classname='infrastructure')
        ET.SubElement(node,'failure',message=report.get('reasonCode',report.get('status','INCONCLUSIVE')))
    atomic_text(root/'junit.xml',ET.tostring(suite,encoding='unicode'))
    title='E2E '+report.get('status','INCONCLUSIVE')
    summary=[f'# {title}', '',f"Run: {report['runId']}",f"Profile: {report['profile']}",
             f"Platform: {report.get('hostPlatform','unknown')}",f"Source SHA: {report.get('sourceSha','unknown')}",
             f"Source digest: {report.get('sourceDigest','unknown')}",
             f"Scope: {report.get('scope','unknown')}",f"Mini: {report.get('miniStatus','NOT_RUN')}",
             f"Cleanup: {report.get('cleanup','NOT_RUN')}", '', '| Scenario | Variant | Status |', '|---|---|---|']
    rows=[]
    for case in cases:
        summary.append(f"| {case['id']} | {case.get('variant','unknown')} | {case.get('status','NOT_RUN')} |")
        checks=''.join('<li>'+html.escape(c.get('id',''))+': '+html.escape(c.get('status','NOT_RUN'))+
                       (' — '+html.escape(c['reasonCode']) if c.get('reasonCode') else '')+'</li>' for c in case.get('checks',[]))
        rows.append('<section><h2>'+html.escape(case['id'])+' — '+html.escape(case.get('status','NOT_RUN'))+
                    '</h2><p>'+html.escape(case.get('variant','unknown'))+'</p><ul>'+checks+'</ul></section>')
    atomic_text(root/'summary.md','\n'.join(summary)+'\n')
    document='<!doctype html><html lang="zh"><meta charset="utf-8"><title>'+html.escape(title)+\
             '</title><style>body{font:16px system-ui;max-width:1000px;margin:40px auto;padding:0 20px}section{border-top:1px solid #ddd}li{margin:8px 0}</style><h1>'+\
             html.escape(title)+'</h1><p>'+html.escape(report['runId'])+' · '+html.escape(report['profile'])+\
             ' · '+html.escape(report.get('hostPlatform','unknown'))+' · mini '+html.escape(report.get('miniStatus','NOT_RUN'))+\
             '</p><p>Source: '+html.escape(report.get('sourceSha','unknown'))+'<br>Digest: '+\
             html.escape(report.get('sourceDigest','unknown'))+'</p>'+''.join(rows)+'</html>'
    atomic_text(root/'report.html',document)
