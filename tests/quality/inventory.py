#!/usr/bin/env python3
"""Inventory tracked test assets and evidence without assuming that presence means execution."""
import argparse
import ast
from collections import Counter
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
REPO=Path(__file__).resolve().parents[2]

def certified_suites(folder, registry):
    """Inventory consumes certificates, never adopts arbitrary/old XML as a fresh PASS."""
    result={}
    for certificate in Path(folder).rglob('evidence.json'):
        try:
            summary=json.loads(certificate.read_text());run=json.loads((certificate.parent/'run.json').read_text())
            if summary.get('result')!='PASS' or not summary.get('exactNamesChecked'):continue
            if any(summary.get(k)!=run.get(k) for k in ['runId','sourceSha','sourceDigest']):continue
            expected=run['expected']
            if any(registry.get(k)!=v for k,v in expected.items()):continue
            found={};valid=True
            for file in (certificate.parent/'surefire').rglob('TEST-*.xml'):
                root=ET.parse(file).getroot();name=root.get('name')
                props={v.get('name'):v.get('value') for v in root.findall('properties/property')}
                cases=root.findall('testcase');counts=dict(Counter(c.get('name') for c in cases))
                if (name in found or file.stat().st_mtime < (certificate.parent/'run.json').stat().st_mtime or props.get('quality.runId')!=run['runId'] or not cases
                    or counts!=expected.get(name) or len(cases)!=int(root.get('tests',-1))
                    or any(int(root.get(k,-1))!=0 for k in ['failures','errors','skipped'])
                    or any(c.find(k) is not None for c in cases for k in ['failure','error','skipped'])):
                    valid=False;break
                found[name]={'tests':len(cases),'failures':0,'errors':0,'skipped':0,
                             'sourceSha':summary['sourceSha'],'sourceDigest':summary['sourceDigest'],'runId':run['runId']}
            if valid and set(found)==set(expected) and sum(x['tests'] for x in found.values())==summary['tests']:
                result.update(found)
        except (OSError,ValueError,KeyError,ET.ParseError):continue
    return result


def collect(evidence=None, runs=(), mini=None, tools=None, coverage=None):
    names=set(filter(None,subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=REPO).decode().split('\0')))
    reports={}
    registry=json.loads((REPO/'tests/quality/java-manifest.json').read_text())
    if evidence:
        for folder in evidence:reports.update(certified_suites(folder,registry))
    executed={}
    node_files=sorted([*REPO.glob('tests/*.test.mjs'),*REPO.glob('tests/business/*-test.mjs')])
    python_files=[REPO/'tests/quality/test_evidence.py',REPO/'tests/quality/test_secret_guard.py',REPO/'tests/quality/test_owned_resources.py',REPO/'tests/smoke/test_secret_scan.py',REPO/'tests/payment/prepayment-tools-test.py']
    for run in runs:
        r=json.loads(Path(run).read_text())
        for step in r['steps']:
            parts=step['name'].split('-')
            if len(parts)==2 and parts[0] in {'node','python'} and parts[1].isdigit():
                files=node_files if parts[0]=='node' else python_files
                executed[str(files[int(parts[1])].relative_to(REPO))]=step
    if mini:
        r=json.loads(Path(mini).read_text());executed['tests/quality/mini-readonly.cjs']={'result':r['result'],'evidence':{'checks':len(r['checks']),'scope':r['scope']}}
    covered_classes=set()
    if coverage:
        for cls in ET.parse(coverage).getroot().findall('package/class'):
            counter=cls.find("counter[@type='METHOD']")
            if counter is not None and int(counter.get('covered',0))>0:covered_classes.add(cls.get('name'))
    registry=json.loads((REPO/'tests/quality/java-manifest.json').read_text())
    assets=[]
    for name in sorted(names):
        p=REPO/name
        if not p.is_file():continue
        infrastructure='yshop-spring-boot-starter-test/src/main/' in name
        is_test='/src/test/' in name or name.startswith('tests/') or infrastructure
        config=p.name in ['pom.xml','package.json'] or name.startswith('.github/workflows/')
        script=name.startswith('scripts/') and p.suffix in ['.py','.sh','.cjs','.mjs']
        shell=p.suffix=='.sh'
        if not (is_test or config or script or shell):continue
        text=p.read_text(errors='replace');function=p.stem;category='build configuration' if config else 'operational helper' if script or shell else 'test resource'
        command='consumed by owning tests';state='UNVERIFIED';result='NOT_EXECUTED';environment='none';assertions='not applicable';skip='none';realdb=False;wechat=False;synthetic=False
        if '/src/test/java/' in name and re.search(r'@(Test\b|RepeatedTest\b|ParameterizedTest\b)',text):
            category='JUnit';suite=name.split('/src/test/java/')[1][:-5].replace('/','.')
            module=name.split('/src/test/')[0]
            command=f'mvn -f {module}/pom.xml test -Dtest={p.stem} (quality dispatcher adds fresh reports)'
            environment='JDK17; H2/mock/embedded Redis'
            if p.stem.endswith('DatabaseTest') or 'MysqlAcceptance' in p.stem:environment+='; MySQL mode requires random isolated schema';realdb=True;synthetic='payment/' in name
            if 'CouponCodeRedisAcceptance' in name:environment='explicit loopback Redis, independent clients';realdb=True
            if 'CallbackIngress' in name:environment='isolated MySQL + synthetic localhost HTTPS ingress';synthetic=True
            skip='conditional/disabled annotation present' if re.search(r'@Disabled|@EnabledIf',text) else 'none'
            assertions='yes' if re.search(r'\b(assert\w+|verify|fail)\s*\(',text) else 'REVIEW: no direct assertion'
            function='; '.join(registry.get(suite,{}))
            r=reports.get(suite)
            if r:
                result=f"tests={r['tests']}; failures={r['failures']}; errors={r['errors']}; skips={r['skipped']}"
                state='BROKEN' if r['failures']+r['errors'] else 'SKIPPED' if r['skipped'] else 'ACTIVE'
            else:state='SKIPPED' if skip!='none' else 'UNVERIFIED'
            if suite not in registry:state='BROKEN';result='NOT_REGISTERED'
        elif p.suffix=='.mjs' and is_test:
            category='Node assertion suite';command=f'node --test {name}';assertions='yes' if 'assert.' in text else 'REVIEW';environment='Node20';state='UNVERIFIED'
        elif p.suffix=='.py' and is_test:
            category='Python suite' if any(isinstance(n,ast.ClassDef) and any((isinstance(b,ast.Attribute) and b.attr=='TestCase') or (isinstance(b,ast.Name) and b.id=='TestCase') for b in n.bases) for n in ast.walk(ast.parse(text))) else 'Python runner/helper'
            command=f'python3 {name} (see documented flags)';assertions='yes' if 'assert' in text else 'see called suite';environment='Python3; runner-specific tools'
        elif p.suffix=='.cjs' and is_test:
            category='WeChat UI automation';wechat=True;environment='macOS; official CLI + automator; explicit local API';command=f'node {name}';assertions='yes' if 'check(' in text else 'REVIEW'
        elif config:command='quality dispatcher / documented build';result='configuration, not test';state='ACTIVE'
        elif p.suffix in ['.sql','.yaml','.xml']:result='fixture; consumption depends on owning suite';state='UNVERIFIED'
        if infrastructure:
            category='shared test infrastructure';environment='JDK17; H2/owned loopback mock Redis';command='consumed by owning JUnit tests';assertions='fixture/support code, not standalone tests'
            cls=name.split('/src/main/java/')[1][:-5] if '/src/main/java/' in name and name.endswith('.java') else None
            if cls in covered_classes:state='ACTIVE';result='executed methods measured by current attributed JaCoCo run; not standalone PASS'
        if p.name=='ProjectReactor.java':state='OBSOLETE';result='source rewriting utility, never execute as a test';command='DO NOT RUN'
        if script:command='not automatically executed; may require live authorization';result='operational tool, tested by prepayment-tools-test.py where covered'
        if tools and name in tools:
            state='ACTIVE';result=tools[name]['result'];environment=tools[name].get('environment',environment);realdb=tools[name].get('realDatabase',realdb);synthetic=tools[name].get('syntheticPayment',synthetic)
        if name in executed:
            step=executed[name];state='ACTIVE' if step['result']=='PASS' else 'BROKEN';result=step['result']+' '+json.dumps(step.get('evidence',{}))
        if p.name=='MailSendServiceImplTest.java':function+='; live SMTP demo retired, 9 mocked cases retained'
        called='quality dispatcher'  if category in ['JUnit','Node assertion suite'] else 'see runner audit'
        assets.append(dict(path=name,category=category,function=function,command=command,caller=called,environment=environment,realDatabase=realdb,wechat=wechat,syntheticPayment=synthetic,recentExecution=result,skip=skip,assertions=assertions,duplicateCoverage='shared fixtures/repeated cases are not unique scenarios',status=state))
    return assets


def main():
    p=argparse.ArgumentParser();p.add_argument('--evidence',action='append',default=[]);p.add_argument('--run',action='append',default=[]);p.add_argument('--mini');p.add_argument('--tool-evidence');p.add_argument('--coverage');p.add_argument('--output',default='docs/quality/test-inventory.md');a=p.parse_args()
    assets=collect(a.evidence,a.run,a.mini,json.loads(Path(a.tool_evidence).read_text()) if a.tool_evidence else None,a.coverage);dest=REPO/a.output;dest.parent.mkdir(parents=True,exist_ok=True)
    header='''# Test asset inventory — Phase 6Q

Generated from all tracked/new test assets and build/operational entry points. File presence is not proof of execution. `ACTIVE` for a resource/config means used by an entry point, not an independently passing test. `UNVERIFIED` is deliberate; no inference of dead code from absence of a caller. Full per-file fields are in [test-inventory.json](test-inventory.json).

Commands below omit private environment flags; use `tests/quality/run.py` for attributed execution. MySQL column means the suite has an actual isolated-MySQL path, not that every invocation uses MySQL. Repeat annotations contribute invocations, not different scenarios. Historical evidence is not substituted for this audit's results.

| Asset | Category/function | Entry/caller | Dependencies | Database / WeChat / synthetic payment | Executed result | Skip / assertions | Maintenance |
|---|---|---|---|---|---|---|---|
'''
    rows=[]
    for x in assets:
        vals=[x['path'],x['category']+' / '+x['function'],x['command']+' / '+x['caller'],x['environment'],f"{x['realDatabase']} / {x['wechat']} / {x['syntheticPayment']}",x['recentExecution'],x['skip']+' / '+x['assertions'],x['status']]
        rows.append('| '+' | '.join(str(v).replace('|','/').replace('\n',' ') for v in vals)+' |')
    dest.write_text(header+'\n'.join(rows)+'\n')
    dest.with_suffix('.json').write_text(json.dumps(assets,indent=2)+'\n')
    print(json.dumps({'assets':len(assets),'states':dict(Counter(x['status'] for x in assets))}))
if __name__=='__main__':main()
