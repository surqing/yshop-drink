#!/usr/bin/env python3
"""Inventory tracked test assets and evidence without assuming that presence means execution."""
import argparse
import fnmatch
import hashlib
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
                # Validate the complete original certificate, then accept only unchanged suite plans.
                # Adding one case must not hide executed, still-compatible sibling suites.
                result.update({k:v for k,v in found.items() if registry.get(k)==expected[k]})
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
                executed[str(files[int(parts[1])].relative_to(REPO))]={**step,'executionIdentity':{k:r[k] for k in ['runId','sourceSha','sourceDigest']}}
    if mini:
        r=json.loads(Path(mini).read_text())
        target=REPO/'tests/quality/mini-readonly.cjs'
        # A private device harness or historical screenshot is not this asset's execution.
        if (r.get('script')=='tests/quality/mini-readonly.cjs'
            and r.get('assetHash')==hashlib.sha256(target.read_bytes()).hexdigest()
            and r.get('result')=='PASS' and r.get('cleanup')=='PASS'
            and r.get('checks') and all(c.get('ok') is True for c in r['checks'])):
            executed['tests/quality/mini-readonly.cjs']={'result':'PASS','evidence':{'checks':len(r['checks']),'scope':r['scope']}}

    covered_classes=set()
    if coverage:
        for cls in ET.parse(coverage).getroot().findall('package/class'):
            counter=cls.find("counter[@type='METHOD']")
            if counter is not None and int(counter.get('covered',0))>0:covered_classes.add(cls.get('name'))
    registry=json.loads((REPO/'tests/quality/java-manifest.json').read_text())
    assets=[]
    policy=json.loads((REPO/'tests/quality/inventory-policy.json').read_text())
    references={}
    for candidate in sorted(names):
        if candidate.startswith('docs/quality/') or candidate.endswith(('.json','.svg','.png','.jpg')):continue
        f=REPO/candidate
        if f.is_file() and f.stat().st_size<500000:references[candidate]=f.read_text(errors='replace')
    for name in sorted(names):
        p=REPO/name
        if not p.is_file():continue
        infrastructure='yshop-spring-boot-starter-test/src/main/' in name
        is_test='/src/test/' in name or name.startswith(('tests/','yshop-drink-vue3/tests/')) or infrastructure
        config=p.name in ['pom.xml','package.json','vitest.config.ts'] or name.startswith('.github/workflows/')
        script=name.startswith('scripts/') and p.suffix in ['.py','.sh','.cjs','.mjs']
        shell=p.suffix=='.sh'
        if not (is_test or config or script or shell):continue
        text=p.read_text(errors='replace');function=p.stem;category='build configuration' if config else 'operational helper' if script or shell else 'test resource'
        command='consumed by owning tests';state='UNVERIFIED';result='NOT_EXECUTED';environment='none';assertions='not applicable';skip='none';realdb=False;wechat=False;synthetic=False;execution_identity=None
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
                execution_identity={k:r[k] for k in ['runId','sourceSha','sourceDigest']}
                result=f"tests={r['tests']}; failures={r['failures']}; errors={r['errors']}; skips={r['skipped']}"
                state='BROKEN' if r['failures']+r['errors'] else 'SKIPPED' if r['skipped'] else 'ACTIVE'
            else:state='SKIPPED' if skip!='none' else 'UNVERIFIED'
            if suite not in registry:state='BROKEN';result='NOT_REGISTERED'
        elif p.suffix=='.mjs' and is_test:
            category='Node assertion suite';command=f'node --experimental-default-type=module --test {name}';assertions='yes' if 'assert.' in text else 'REVIEW';environment='Node20';state='UNVERIFIED'
        elif p.suffix=='.py' and is_test:
            category='Python suite' if any(isinstance(n,ast.ClassDef) and any((isinstance(b,ast.Attribute) and b.attr=='TestCase') or (isinstance(b,ast.Name) and b.id=='TestCase') for b in n.bases) for n in ast.walk(ast.parse(text))) else 'Python runner/helper'
            command=f'python3 {name} (see documented flags)';assertions='yes' if 'assert' in text else 'see called suite';environment='Python3; runner-specific tools'
        elif p.suffix=='.cjs' and is_test:
            if name=='tests/quality/frontend-coverage.cjs':
                category='coverage helper';environment='Node20; fixed Vue test dependencies';command='invoked by tests/quality/run.py frontend';assertions='conversion validates core source execution'
            else:
                category='WeChat UI automation';wechat=True;environment='macOS; official CLI + automator; explicit local API';command=f'node {name}';assertions='yes' if 'check(' in text else 'REVIEW'
        elif name.startswith('yshop-drink-vue3/tests/') and p.name.endswith('.test.ts'):
            category='Vue component suite';environment='Node20; Vitest; jsdom; Vue compiler; mocked HTTP only';command='python3 tests/quality/run.py frontend';assertions='explicit expected values, rendered component and API/emit assertions'
            for run in runs:
                r=json.loads(Path(run).read_text())
                for step in r['steps']:
                    if step['name']=='vue-components' and step['result']=='PASS' and name.split('yshop-drink-vue3/',1)[1] in step.get('evidence',{}).get('cases',{}):
                        state='ACTIVE';result='PASS '+str(len(step['evidence']['cases'][name.split('yshop-drink-vue3/',1)[1]]))+' cases'
                        execution_identity={k:r[k] for k in ['runId','sourceSha','sourceDigest']}
        elif config:command='quality dispatcher / documented build';result='configuration, not test';state='ACTIVE'
        elif p.suffix in ['.sql','.yaml','.xml']:result='fixture; consumption depends on owning suite';state='UNVERIFIED'
        if infrastructure:
            category='shared test infrastructure';environment='JDK17; H2/owned loopback mock Redis';command='consumed by owning JUnit tests';assertions='fixture/support code, not standalone tests'
            cls=name.split('/src/main/java/')[1][:-5] if '/src/main/java/' in name and name.endswith('.java') else None
            if cls in covered_classes:state='ACTIVE';result='executed methods measured by current attributed JaCoCo run; not standalone PASS'
        if p.name=='ProjectReactor.java':state='OBSOLETE';result='source rewriting utility, never execute as a test';command='DO NOT RUN'
        if script:command='not automatically executed; may require live authorization';result='operational tool, tested by prepayment-tools-test.py where covered'
        if tools and name in tools:
            tool=tools[name]
            attributed=tool.get('executionIdentity')
            certified=tool.get('executed') is True and isinstance(attributed,dict) and all(attributed.get(k) for k in ['runId','sourceSha','sourceDigest'])
            state='ACTIVE' if certified else 'DECLARED_NOT_CERTIFIED'
            result=tool['result'] if certified else 'Declared metadata only; not an execution certificate: '+tool['result']
            execution_identity=attributed if certified else None
            environment=tool.get('environment',environment);realdb=tool.get('realDatabase',realdb);synthetic=tool.get('syntheticPayment',synthetic)
        if name in executed:
            step=executed[name];state='ACTIVE' if step['result']=='PASS' else 'BROKEN';result=step['result']+' '+json.dumps(step.get('evidence',{}))
            execution_identity=step.get('executionIdentity')
        if p.name=='MailSendServiceImplTest.java':function+='; live SMTP demo retired, 9 mocked cases retained'
        called='quality dispatcher'  if category in ['JUnit','Node assertion suite'] else 'see runner audit'
        execution_status=state
        is_suite=category in ['JUnit','Node assertion suite','Python suite','WeChat UI automation','Vue component suite']
        state='ACTIVE_TEST' if is_suite else 'ACTIVE_HELPER'
        reason='Registered executable test; execution is reported separately.' if is_suite else 'Build/fixture/helper, not an independent test PASS.'
        if name in policy:state=policy[name]['status'];reason=policy[name]['reason']
        if execution_status=='BROKEN':state='BROKEN'
        callers=[n for n,t in references.items() if n!=name and (name in t or (p.name in t and len(p.name)>8))]
        if category=='JUnit':callers=['tests/quality/run.py: manifest dispatcher',*callers]
        if category=='Node assertion suite':callers=['tests/quality/run.py: quick discovery',*callers]
        if '/src/test/resources/' in name:
            callers+=['owning module test classpath; BaseDbUnitTest/ActiveProfiles consumption where declared']
        if not callers:callers=['no static reference found; preserved extension/manual entry; no deletion inference']
        assets.append(dict(path=name,assetHash=hashlib.sha256(p.read_bytes()).hexdigest(),category=category,function=function,command=command,caller=called,callerEvidence=callers,classificationReason=reason,environment=environment,realDatabase=realdb,wechat=wechat,syntheticPayment=synthetic,recentExecution=result,executionStatus=execution_status,executionIdentity=execution_identity,skip=skip,assertions=assertions,duplicateCoverage='shared fixtures/repeated cases are not unique scenarios',status=state))
        asset=assets[-1]
        default=category in ['Node assertion suite','Python suite','Vue component suite']
        if category=='JUnit':
            pom=ET.parse(REPO/'yshop-drink-boot3/pom.xml').getroot()
            ns={'m':'http://maven.apache.org/POM/4.0.0'}
            patterns=[n.text for plugin in pom.findall('.//m:plugin',ns) if plugin.findtext('m:artifactId',namespaces=ns)=='maven-surefire-plugin' for n in plugin.findall('m:configuration/m:includes/m:include',ns)]
            patterns=patterns or ['**/Test*.java','**/*Test.java','**/*Tests.java','**/*TestCase.java']
            default=any(fnmatch.fnmatch(name,pattern) for pattern in patterns)
        actual='PASS' if execution_identity and (result.startswith('PASS') or result.startswith('tests=')) else 'FAIL' if execution_status=='BROKEN' else 'ENV_BLOCKED' if state in ['CONDITIONAL','BLOCKED_EXTERNAL'] else 'NOT_RUN'
        audit='BROKEN' if actual=='FAIL' else 'OBSOLETE' if state=='OBSOLETE' else 'ENV_BLOCKED' if actual=='ENV_BLOCKED' else 'DISCOVERABLE_BUT_NOT_RUN' if is_suite and actual=='NOT_RUN' else 'ACTIVE'
        asset.update(defaultDiscovered=default,defaultExecutionEligible=default and skip=='none',lastActualStatus=actual,auditClassification=audit,
                     productionTargets=sorted(set(re.findall(r'import (?:static )?(co\.yixiang\.[\w.]+)',text))) if category=='JUnit' else function,
                     requiresExternalResources=environment!='none' and (wechat or realdb or state in ['CONDITIONAL','BLOCKED_EXTERNAL']),
                     localPrivateReferences=bool(re.search(r'\.local-dev|\.uniapp-dev|/Applications/',text)),
                     requiresPrivateFiles=wechat or state in ['CONDITIONAL','BLOCKED_EXTERNAL'],
                     possibleSharedDataWrites=bool(re.search(r'INSERT |UPDATE |DELETE |TRUNCATE |jdbc\.update|request\.(?:post|put|delete)',text)),
                     paymentSafety='owned synthetic database/fake transport required' if synthetic else 'private device guard required' if wechat else 'no payment provider called by ordinary suite; external tools remain conditional',
                     faultInjection=bool(re.search(r'fault|rollback|failClosed|Failure|mutation|throws|assertThrows',text)),
                     replacement='none established; preserve asset',findings=['NOT_DISCOVERED_BY_DEFAULT_MAVEN: explicitly dispatched in owned MySQL gate'] if category=='JUnit' and not default and 'MysqlAcceptance' in p.stem else [],recommendation=reason)
    return assets


def main():
    p=argparse.ArgumentParser();p.add_argument('--evidence',action='append',default=[]);p.add_argument('--run',action='append',default=[]);p.add_argument('--mini');p.add_argument('--tool-evidence');p.add_argument('--coverage');p.add_argument('--output',default='docs/quality/test-inventory.md');a=p.parse_args()
    assets=collect(a.evidence,a.run,a.mini,json.loads(Path(a.tool_evidence).read_text()) if a.tool_evidence else None,a.coverage);dest=REPO/a.output;dest.parent.mkdir(parents=True,exist_ok=True)
    header='''# Test asset inventory — Phase 6Q

Generated from all tracked/new test assets and build/operational entry points. File presence is not proof of execution. Maintenance categories ACTIVE_TEST/ACTIVE_HELPER/CONDITIONAL/BLOCKED_EXTERNAL/REDUNDANT/OBSOLETE/BROKEN/UNKNOWN are independent of execution status. No helper is counted as a standalone passing test. Unexecuted conditional paths are deliberate; no inference of dead code from absence of a caller. Full per-file fields are in [test-inventory.json](test-inventory.json).

Commands below omit private environment flags; use `tests/quality/run.py` for attributed execution. MySQL column means the suite has an actual isolated-MySQL path, not that every invocation uses MySQL. Repeat annotations contribute invocations, not different scenarios. Historical evidence is not substituted for this audit's results. Local path references are recorded separately from required private environment dependencies; static caller/write/fault flags are review hints, not proof of runtime behavior.

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
