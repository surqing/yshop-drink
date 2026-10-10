#!/usr/bin/env python3
"""Fail-closed test dispatcher. Reports are private, fresh and tied to source/run IDs."""
import argparse
import ast
from collections import defaultdict
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
from evidence import Evidence, execute, manifest, workspace, source_identity
from page_evidence import overall_status, status_of, browser_receipt

REPO = Path(__file__).resolve().parents[2]
BOOT = REPO / 'yshop-drink-boot3'
MODES = ['QUICK','BUSINESS','INTEGRATION','PAYMENT-SAFETY','MINIPROGRAM','FULL','MUTATION','JAVA','FRONTEND','SECURITY','REPORT','BROWSER','CROSS-END','MINI-PAGES','PERFORMANCE','BUILD']

def aggregate_reports(paths, identity):
    if not paths: raise RuntimeError('CURRENT_REPORTS_REQUIRED')
    reports = [json.loads(Path(p).read_text()) for p in paths]
    ids = [r.get('runId') for r in reports]
    if len(set(ids)) != len(ids) or any(not i for i in ids): raise RuntimeError('DUPLICATE_OR_MISSING_RUN_ID')
    for path,r in zip(paths,reports):
        if any(r.get(k) != v for k,v in identity.items()): raise RuntimeError('STALE_REPORT_SOURCE')
        if r.get('complete') is not True or not r.get('steps') or r.get('result') != 'PASS' or r.get('sourceUnchanged') is not True or any(s.get('result') != 'PASS' for s in r['steps']):
            raise RuntimeError('INCOMPLETE_OR_FAILED_RUN')
        executed=sum(s.get('evidence',{}).get('tests',0) for s in r['steps'])
        if not executed and any(s.get('name')=='owned-controlled-dependencies' for s in r['steps']):
            controlled=json.loads((Path(path).parent/'controlled/report.json').read_text())
            if controlled.get('result')!='PASS' or controlled.get('cleanup')!='PASS' or any(controlled.get(k)!=v for k,v in identity.items()):raise RuntimeError('INCOMPLETE_OR_FAILED_RUN')
            executed=sum(s.get('evidence',{}).get('tests',0) for s in controlled.get('steps',[]))
        if executed<=0:raise RuntimeError('ZERO_EXECUTED_TESTS')
    return {'result':'PASS', **identity, 'runIds': ids, 'reports': [str(Path(p).resolve()) for p in paths],
            'scope':'explicit current-source reports only; repeated scopes are not summed as unique test cases'}

def vue_evidence(path, expected):
    report=json.loads(Path(path).read_text()); actual={}
    for suite in report.get('testResults',[]):
        relative=str(Path(suite['name']).relative_to(REPO/'yshop-drink-vue3'))
        cases=suite.get('assertionResults',[])
        if relative in actual or suite.get('status')!='passed' or any(c.get('status')!='passed' for c in cases):raise RuntimeError('VUE_TESTS_INCOMPLETE')
        actual[relative]=[c['title'] for c in cases]
    count=sum(map(len,actual.values()))
    if not count or actual!=expected or report.get('numTotalTests')!=count or report.get('numPassedTests')!=count or not report.get('success') or report.get('numPendingTests') or report.get('numTodoTests'):
        raise RuntimeError('VUE_TESTS_INCOMPLETE')
    return {'tests':count,'exactNamesChecked':True,'cases':actual}

def controlled_receipt(path, started, identity, run_id, expected):
    """An exit code is not evidence that controlled GUI/CLI assertions ran."""
    path=Path(path)
    if not path.is_file() or path.stat().st_mtime < started:
        raise RuntimeError('CONTROLLED_REPORT_MISSING_OR_STALE')
    receipt=json.loads(path.read_text())
    if receipt.get('runId')!=run_id or any(receipt.get(k)!=v for k,v in identity.items()):
        raise RuntimeError('CONTROLLED_REPORT_IDENTITY_MISMATCH')
    if receipt.get('result')!='PASS' or receipt.get('cleanup')!='PASS' or receipt.get('sourceUnchanged') is not True:
        raise RuntimeError('CONTROLLED_REPORT_FAILED_OR_INCOMPLETE')
    checks=receipt.get('checks')
    if not isinstance(checks,list) or not expected or len(checks)!=len(expected):
        raise RuntimeError('CONTROLLED_ASSERTIONS_MISSING')
    if len({c.get('name') for c in checks})!=len(checks) or {c.get('name') for c in checks}!=set(expected):
        raise RuntimeError('CONTROLLED_ASSERTIONS_CHANGED')
    if any(c.get('result')!='PASS' or c.get('executed') is not True for c in checks):
        raise RuntimeError('CONTROLLED_ASSERTIONS_FAILED_OR_NOT_EXECUTED')
    return {'tests':len(checks),'runId':run_id,'cleanup':'PASS','exactNamesChecked':True}
def python_diagnostics(text, file):
    tree=ast.parse(Path(file).read_text())
    methods={n.name for n in ast.walk(tree) if isinstance(n,ast.FunctionDef) and n.name.startswith('test_')}
    classes={n.name for n in ast.walk(tree) if isinstance(n,ast.ClassDef)}
    failures=[]
    for kind,method,cls in re.findall(r'^(FAIL|ERROR): (test_\w+) \(__main__\.(\w+)\)',text,re.M):
        failures.append({'kind':kind,'method':method if method in methods else 'UNKNOWN_CASE','class':cls if cls in classes else 'UNKNOWN_CLASS'})
    return {'asset':str(file),'failures':failures,'exceptionTypes':sorted(set(re.findall(r'^(AssertionError|AttributeError|ValueError|RuntimeError|TypeError|ImportError|ModuleNotFoundError|TimeoutError):',text,re.M)))}

def require_count(actual, expected):
    if actual != expected: raise RuntimeError('TEST_INVOCATIONS_CHANGED')


def require_suites(planned, registered, discovered):
    if set(planned)!=set(registered) or set(planned)!=set(discovered):raise RuntimeError('QUICK_SUITE_INVENTORY_CHANGED')

def backend_modules():
    ns={'m':'http://maven.apache.org/POM/4.0.0'}
    names=[]
    def visit(folder):
        pom=ET.parse(folder/'pom.xml').getroot();names.append(pom.findtext('m:artifactId',namespaces=ns))
        for node in pom.findall('m:modules/m:module',ns):visit(folder/node.text)
    visit(BOOT)
    if len(names)!=55 or len(set(names))!=55:raise RuntimeError('BACKEND_REACTOR_INVENTORY_CHANGED')
    return names

def backend_build_evidence(text,expected):
    text=re.sub(r'\x1b\[[0-9;]*m','',text)
    rows=re.findall(r'^\[INFO\] ([\w-]+) [.]+ (SUCCESS|FAILURE|SKIPPED)(?: |$)',text,re.M)
    if not rows or len(rows)!=len(expected) or {n for n,s in rows}!=set(expected) or any(s!='SUCCESS' for n,s in rows) or '[INFO] BUILD SUCCESS' not in text:
        raise RuntimeError('BACKEND_REACTOR_INCOMPLETE')
    return {'modules':len(rows),'allSucceeded':True,'moduleNames':[n for n,s in rows]}

def discover_quick_suites():
    paths=[]
    for file in (REPO/'tests').rglob('*.py'):
        tree=ast.parse(file.read_text())
        if any(isinstance(n,ast.ClassDef) and any((isinstance(b,ast.Attribute) and b.attr=='TestCase') or (isinstance(b,ast.Name) and b.id=='TestCase') for b in n.bases) for n in ast.walk(tree)):
            paths.append(str(file.relative_to(REPO)))
    paths += [str(f.relative_to(REPO)) for f in (REPO/'tests').rglob('*.mjs') if f.name.endswith(('.test.mjs','-test.mjs'))]
    return paths


CONDITIONAL = {'CouponCodeRedisAcceptanceTest','CallbackIngressEndToEndTest','CatalogEditingMysqlAcceptance'}


def source_digest():
    files=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=REPO).decode().split('\0')
    digest=hashlib.sha256()
    for name in sorted(filter(None,files)):
        p=REPO/name
        if p.is_file():digest.update(name.encode()+b'\0'+p.read_bytes())
    return digest.hexdigest()


def module_for(suite):
    relative = 'src/test/java/' + suite.replace('.', '/') + '.java'
    matches=list(BOOT.glob('**/'+relative))
    if len(matches)!=1:raise RuntimeError('TEST_SOURCE_MISSING_OR_AMBIGUOUS')
    return matches[0].parents[len(Path(relative).parts)-1]


class Runner:
    def __init__(self, parent):
        self.id=uuid.uuid4().hex
        self.root=Path(parent).resolve()/self.id
        self.root.mkdir(parents=True,mode=0o700,exist_ok=False)
        self.steps=[];self.digest=source_digest();self.started=time.time()
        self.source_sha=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO).decode().strip()
        self.env=os.environ.copy()
        self.env['YSHOP_TEST_WORKSPACE']=str(workspace(REPO))
        # Real payment flags are never enabled by this dispatcher.
        self.env['YSHOP_PAY_WECHAT_V3_ENABLED']='false'
        self.env['YSHOP_PAY_WECHAT_V3_RECONCILIATION_ENABLED']='false'
        self.maven=os.environ.get('YSHOP_MAVEN',shutil.which('mvn') or str(workspace(REPO)/'.dev-tools/maven/bin/mvn'))

    def step(self, name, command, cwd=REPO, timeout=1800, validator=None, diagnostic=None):
        folder=self.root/name;folder.mkdir(mode=0o700)
        entry={'name':name,'result':'FAIL','timeoutSeconds':timeout,'startedAt':time.time(),
               'commandSha256':hashlib.sha256(json.dumps(command).encode()).hexdigest(),'executable':Path(command[0]).name}
        (folder/'command.json').write_text(json.dumps({'argv':command,'cwd':str(cwd)}));(folder/'command.json').chmod(0o600)
        start=time.time()
        try:
            code=execute(command,cwd,self.env,folder/'output.log',timeout,termination_grace=120 if 'tests/quality/business-backend.py' in command else 5)
            entry['exitCode']=code
            if code:raise RuntimeError('SUBPROCESS_FAILED')
            if validator:entry['evidence']=validator(folder/'output.log')
            entry['result']='PASS'
        except Exception as exc:
            # Exceptions can carry tokens, request bodies or connection strings. Publish only
            # a closed structural code vocabulary; private logs retain debugging details.
            code=str(exc)
            public_codes={'SUBPROCESS_FAILED','TEST_PROCESS_TIMEOUT','BUILD_LOCK_TIMEOUT',
                          'TEST_INVOCATIONS_CHANGED','NODE_REPORT_INCOMPLETE',
                          'PYTHON_REPORT_INCOMPLETE_OR_SKIPPED','SOURCE_CHANGED_DURING_TEST',
                          'CONTROLLED_REPORT_MISSING_OR_STALE','CONTROLLED_REPORT_IDENTITY_MISMATCH',
                          'CONTROLLED_REPORT_FAILED_OR_INCOMPLETE','CONTROLLED_ASSERTIONS_MISSING',
                          'CONTROLLED_ASSERTIONS_CHANGED','CONTROLLED_ASSERTIONS_FAILED_OR_NOT_EXECUTED',
                          'FAILED_OR_SKIPPED_TESTS','MISSING_OR_UNEXPECTED_TEST_SUITE',
                          'TEST_NAMES_OR_INVOCATIONS_CHANGED','BACKEND_REACTOR_INCOMPLETE'}
            entry['reason']=code if isinstance(exc,RuntimeError) and code in public_codes else 'STEP_FAILED'
            entry['reasonType']=type(exc).__name__
        if diagnostic:
            try: entry['diagnostics']=diagnostic()
            except Exception: entry['diagnosticError']='DIAGNOSTICS_UNAVAILABLE';entry['result']='FAIL'
        entry['seconds']=round(time.time()-start,3)
        entry['status']=status_of(entry['result'])
        entry['endedAt']=time.time()
        self.steps.append(entry);self.save(final=False)
        print(name+': '+entry['result'],flush=True)
        return entry['result']=='PASS'

    def blocked(self, name, reason):
        self.steps.append({'name':name,'result':'BLOCKED','status':'BLOCKED','reason':reason});self.save(final=False)
        print(name+': BLOCKED',flush=True)

    def java(self, selected=None):
        registered=manifest(REPO)
        discovered=set()
        for file in BOOT.glob('**/src/test/java/**/*.java'):
            if re.search(r'@(Test\b|ParameterizedTest\b|RepeatedTest\b)',file.read_text()):
                discovered.add(str(file).split('/src/test/java/')[1][:-5].replace('/','.'))
        if discovered != set(registered):raise RuntimeError('JAVA_MANIFEST_SOURCE_MISMATCH')
        suites=selected if selected is not None else [k for k in registered if k.rsplit('.',1)[-1] not in CONDITIONAL]
        if not suites:raise RuntimeError('NO_SELECTED_TESTS')
        grouped=defaultdict(dict)
        for suite in suites:grouped[module_for(suite)][suite]=registered[suite]
        for mod,expected in sorted(grouped.items()):
            ev=Evidence(self.root/'java-evidence')
            cmd=[self.maven,'-Pquality-coverage','test','-Dtest='+','.join(k.rsplit('.',1)[-1] for k in expected),'-Dsurefire.failIfNoSpecifiedTests=true']
            if self.env.get('YSHOP_MAVEN_REPOSITORY'):cmd+=['-Dmaven.repo.local='+self.env['YSHOP_MAVEN_REPOSITORY']]
            cmd+=ev.arguments(expected)+['-Djacoco.destFile='+str(ev.root/'coverage.exec')]
            self.step('java-'+mod.name,cmd,mod,validator=lambda log,ev=ev:ev.validate(),diagnostic=ev.diagnostics)

    def quick(self):
        self.env['NODE_V8_COVERAGE']=str(self.root/'node-v8')
        expected=json.loads((REPO/'tests/quality/quick-manifest.json').read_text())
        files=sorted([*REPO.glob('tests/*.test.mjs'),*REPO.glob('tests/business/*-test.mjs')])
        planned=[str(f.relative_to(REPO)) for f in sorted([*REPO.glob('tests/*.test.mjs'),*REPO.glob('tests/business/*-test.mjs')])]
        planned+=['tests/quality/test_evidence.py','tests/quality/test_secret_guard.py','tests/quality/test_owned_resources.py','tests/quality/test_page_evidence.py','tests/smoke/test_secret_scan.py','tests/payment/prepayment-tools-test.py']
        require_suites(planned,expected,discover_quick_suites())
        for index,file in enumerate(files):
            def node_evidence(log):
                text=log.read_text();counts={k:int(v) for k,v in re.findall(r'^# (tests|pass|fail|skipped|cancelled|todo) (\d+)$',text,re.M)}
                if counts.get('tests',0)<=0 or counts.get('tests')!=counts.get('pass') or any(counts.get(k,0) for k in ['fail','skipped','cancelled','todo']):raise RuntimeError('NODE_REPORT_INCOMPLETE')
                require_count(counts['tests'],expected[str(file.relative_to(REPO))])
                return counts
            self.step('node-'+str(index),[shutil.which('node') or 'node','--experimental-default-type=module','--test','--test-reporter=tap',str(file)],timeout=180,validator=node_evidence)
        for index,file in enumerate([REPO/'tests/quality/test_evidence.py',REPO/'tests/quality/test_secret_guard.py',REPO/'tests/quality/test_owned_resources.py',REPO/'tests/quality/test_page_evidence.py',REPO/'tests/smoke/test_secret_scan.py',REPO/'tests/payment/prepayment-tools-test.py']):
            def python_evidence(log):
                text=log.read_text();match=re.search(r'Ran (\d+) tests? in',text)
                if not match or int(match[1])==0 or not re.search(r'^OK$',text,re.M):raise RuntimeError('PYTHON_REPORT_INCOMPLETE_OR_SKIPPED')
                require_count(int(match[1]),expected[str(file.relative_to(REPO))])
                return {'tests':int(match[1]),'asset':str(file.relative_to(REPO))}
            self.step('python-'+str(index),[sys.executable,str(file)],timeout=180,validator=python_evidence,diagnostic=lambda file=file,index=index:python_diagnostics((self.root/('python-'+str(index))/'output.log').read_text(),file))

    def frontend(self):
        expected=json.loads((REPO/'tests/quality/vue-manifest.json').read_text())
        discovered={str(f.relative_to(REPO/'yshop-drink-vue3')) for f in (REPO/'yshop-drink-vue3/tests').rglob('*.test.ts')}
        if discovered!=set(expected):raise RuntimeError('VUE_TEST_INVENTORY_CHANGED')
        output=self.root/'vue-result.json'
        self.env['YSHOP_VUE_COVERAGE']=str(self.root/'vue-coverage')
        self.step('vue-components',['pnpm','exec','vitest','run','--config','vitest.config.ts','--coverage','--reporter=json','--outputFile='+str(output)],REPO/'yshop-drink-vue3',timeout=600,validator=lambda log:vue_evidence(output,expected))
        self.step('uniapp-v8-coverage',[shutil.which('node') or 'node','tests/quality/frontend-coverage.cjs',str(self.root/'node-v8'),str(self.root/'node-coverage')],timeout=180,
                  validator=lambda log:json.loads((self.root/'node-coverage/coverage.json').read_text()))

    def browser(self):
        import socket
        expected=json.loads((REPO/'tests/quality/browser-manifest.json').read_text())
        discovered={f.name:re.findall(r"test\('([^']+)'",f.read_text()) for f in (REPO/'yshop-drink-vue3/e2e').glob('*.spec.ts') if f.name!='cross-end.spec.ts'}
        if discovered!=expected:raise RuntimeError('BROWSER_MANIFEST_SOURCE_MISMATCH')
        if not (REPO/'yshop-drink-vue3/node_modules/@playwright/test').exists():
            self.blocked('browser','LOCKED_PLAYWRIGHT_INSTALL_REQUIRED');return
        with socket.socket() as probe:
            probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
        output=self.root/'browser'
        self.env.update(YSHOP_BROWSER_PORT=str(port),YSHOP_BROWSER_OUTPUT=str(output),
            YSHOP_QUALITY_RUN_ID=self.id,YSHOP_QUALITY_SOURCE_SHA=self.source_sha,YSHOP_QUALITY_SOURCE_DIGEST=self.digest)
        started=time.time()
        self.step('browser-pages',['pnpm','exec','playwright','test','--config','playwright.config.ts',*expected],REPO/'yshop-drink-vue3',timeout=600,
            validator=lambda log:browser_receipt(output/'receipt.json',started,{'sourceSha':self.source_sha,'sourceDigest':self.digest},self.id,[n for names in expected.values() for n in names]))

    def cross_end(self, performance=False, mini=False):
        from backend_artifact import verify_artifact
        try:verify_artifact(BOOT/'yshop-server/target/yshop-server.jar',REPO/'.quality/backend-build.json',
                            {'sourceSha':self.source_sha,'sourceDigest':self.digest})
        except (RuntimeError,OSError,ValueError):
            self.blocked('cross-end','CURRENT_BACKEND_BUILD_REQUIRED');return
        if mini:
            from mini_pages import mini_prerequisite
            reason=mini_prerequisite()
            if reason:self.blocked('mini-pages',reason);return
        from loopback_ports import backend_pair
        port=backend_pair()
        output=self.root/'cross-end'
        def verified(log):
            r=json.loads((output/'report.json').read_text())
            identity={'sourceSha':self.source_sha,'sourceDigest':self.digest}
            if r.get('runId')!=self.id or r.get('result')!='PASS' or r.get('cleanup')!='PASS' or r.get('sourceUnchanged') is not True or any(r.get(k)!=v for k,v in identity.items()):
                raise RuntimeError('CROSS_END_FAILED_OR_INCOMPLETE')
            from cross_end import cross_receipt
            return cross_receipt(r,identity,performance,mini)
        self.env['YSHOP_CROSS_END']='1';self.env['YSHOP_QUALITY_RUN_ID']=self.id
        if performance:self.env['YSHOP_PERFORMANCE']='1'
        if mini:self.env['YSHOP_MINI_PAGES']='1'
        self.step('cross-end-owned',[sys.executable,'tests/quality/business-backend.py','--output',str(output),'--port',str(port),'--instances','2'],timeout=2400,validator=verified)

    def integration(self):
        output=self.root/'controlled'
        identity={'sourceSha':self.source_sha,'sourceDigest':self.digest}
        def verified(log):
            report=json.loads((output/'report.json').read_text())
            expected=['mysql-business','mysql-financial','mysql-auth','redis','synthetic-tls']
            if (report.get('result')!='PASS' or report.get('cleanup')!='PASS' or not report.get('sourceUnchanged')
                or any(report.get(k)!=v for k,v in identity.items())
                or [s.get('name') for s in report.get('steps',[])]!=expected
                or any(s.get('result')!='PASS' for s in report['steps'])):
                raise RuntimeError('CONTROLLED_REPORT_FAILED_OR_INCOMPLETE')
            return {'steps':expected,'cleanup':'PASS','runId':report['runId']}
        self.step('owned-controlled-dependencies',[sys.executable,'tests/quality/heavy.py','--output',str(output)],timeout=7200,validator=verified)

    def backend(self):
        cmd=[self.maven,'install','package','-Dmaven.test.skip=true']
        if self.env.get('YSHOP_MAVEN_REPOSITORY'):cmd+=['-Dmaven.repo.local='+self.env['YSHOP_MAVEN_REPOSITORY']]
        expected=backend_modules()
        if self.step('backend-build',cmd,BOOT,validator=lambda log:backend_build_evidence(log.read_text(),expected)):
            jar=BOOT/'yshop-server/target/yshop-server.jar'
            identity={'sourceSha':self.source_sha,'sourceDigest':self.digest}
            if source_identity()!=identity:raise RuntimeError('SOURCE_CHANGED_DURING_TEST')
            folder=REPO/'.quality';folder.mkdir(mode=0o700,exist_ok=True)
            (folder/'backend-build.json').write_text(json.dumps({**identity,'result':'PASS','runId':self.id,
                'modules':len(expected),'artifactHash':hashlib.sha256(jar.read_bytes()).hexdigest()}))

    def build(self):
        self.backend()
        self.step('vue-build',['pnpm','build:local'],REPO/'yshop-drink-vue3',timeout=600)
        self.step('vue-types',['pnpm','ts:check'],REPO/'yshop-drink-vue3',timeout=600)

    def configured(self,name,key):
        if not self.env.get(key):self.blocked(name,'EXPLICIT_CONTROLLED_COMMAND_REQUIRED');return
        argv=json.loads(self.env[key])
        if not isinstance(argv,list) or not argv or not all(isinstance(x,str) for x in argv):raise RuntimeError('INVALID_CONTROLLED_COMMAND')
        report=self.env.get(key+'_REPORT')
        expected=json.loads(self.env.get(key+'_CHECKS','[]'))
        if not report or not isinstance(expected,list) or not expected or not all(isinstance(x,str) for x in expected) or len(set(expected))!=len(expected):
            self.blocked(name,'CONTROLLED_REPORT_CONTRACT_REQUIRED');return
        self.env['YSHOP_QUALITY_RUN_ID']=self.id
        self.env['YSHOP_QUALITY_SOURCE_SHA']=self.source_sha
        self.env['YSHOP_QUALITY_SOURCE_DIGEST']=self.digest
        started=time.time()
        self.step(name,argv,timeout=600,validator=lambda log:controlled_receipt(report,started,
                  {'sourceSha':self.source_sha,'sourceDigest':self.digest},self.id,expected))

    def save(self, final=True):
        unchanged=source_identity()=={'sourceSha':self.source_sha,'sourceDigest':self.digest}
        status=overall_status(self.steps,unchanged)
        if not final and status=='PASSED':status='INCONCLUSIVE'
        report={'runId':self.id,'complete':final,'sourceUnchanged':unchanged,'sourceSha':self.source_sha,
                'status':status,
                'sourceDigest':self.digest,'result':'PASS' if final and unchanged and self.steps and all(s['result']=='PASS' for s in self.steps) else 'NOT_READY','steps':self.steps,
                'startedAt':self.started,'endedAt':time.time(),
                'paymentRequests':{'value':None,'evidence':'NOT_MEASURED_BY_DISPATCHER'},'realFinancialOperations':{'value':0,'evidence':'DECLARED_SYNTHETIC_ONLY'}}
        temporary=self.root/'report.json.tmp'
        temporary.write_text(json.dumps(report,indent=2))
        temporary.replace(self.root/'report.json')
        return report


def main():
    aliases={'FINANCE':'PAYMENT-SAFETY','MYSQL':'INTEGRATION'}
    p=argparse.ArgumentParser();p.add_argument('mode',type=lambda s:aliases.get(s.upper(),s.upper()),choices=MODES);p.add_argument('--run-report',action='append',default=[]);p.add_argument('--output',default=os.environ.get('YSHOP_QUALITY_OUTPUT',str(Path(tempfile.gettempdir())/'yshop-quality')));a=p.parse_args()
    if a.mode=='REPORT':
        try:print(json.dumps(aggregate_reports(a.run_report,source_identity())));return 0
        except Exception as e:print(json.dumps({'result':'FAIL','reason':str(e) if isinstance(e,RuntimeError) else type(e).__name__}));return 1
    r=Runner(a.output)
    try:
        if a.mode=='BUILD':r.build()
        if a.mode in ['QUICK','FULL','FRONTEND','SECURITY']:r.quick()
        if a.mode in ['BUSINESS','PAYMENT-SAFETY','FULL','JAVA']:
            selected=None
            if a.mode=='BUSINESS':selected=[k for k in manifest(REPO) if k.endswith(('.OrderingDatabaseTest','.CatalogDatabaseTest','.CouponDatabaseTest','.CouponCodeSecurityTest'))]
            if a.mode=='PAYMENT-SAFETY':selected=[k for k in manifest(REPO) if '.payment.' in k or '.credential.' in k or '.preflight.' in k]
            if selected is not None:selected=[k for k in selected if k.rsplit('.',1)[-1] not in CONDITIONAL]
            r.java(selected)
        if a.mode in ['INTEGRATION','FULL']:r.integration()
        if a.mode in ['FRONTEND','FULL']:r.frontend()
        if a.mode in ['BROWSER','FULL']:r.browser()
        if a.mode=='CROSS-END':r.cross_end()
        if a.mode=='MINI-PAGES':r.cross_end(mini=True)
        if a.mode=='PERFORMANCE':r.cross_end(performance=True)
        if a.mode=='SECURITY':
            r.java([k for k in manifest(REPO) if k.endswith(('.DesensitizeTest','.CouponCodeSecurityTest','.PermissionServiceImplTest','.OAuth2LifecycleDatabaseTest'))])
            r.step('offline-credential-guard',[sys.executable,'tests/quality/secret_guard.py','--base','f96c70a10978939f66392788fa3f0fb863d8aca2'])
        if a.mode=='FULL':
            r.build();r.configured('uniapp','YSHOP_UNIAPP_ARGV');r.configured('strict-secret-scan','YSHOP_SECRET_SCAN_ARGV')
        if a.mode in ['MINIPROGRAM','FULL']:r.configured('mini-program','YSHOP_MINIPROGRAM_ARGV')
        if a.mode=='MUTATION':
            r.step('mutation',[sys.executable,'tests/quality/mutate.py','--output',str(r.root/'mutations')],timeout=7200)
            r.step('frontend-mutation',[sys.executable,'tests/quality/frontend-mutate.py','--output',str(r.root/'frontend-mutations')],timeout=600)
        if source_digest()!=r.digest:r.blocked('source-integrity','SOURCE_CHANGED_DURING_RUN')
    except Exception as exc:r.blocked('dispatcher',type(exc).__name__)
    report=r.save();print(json.dumps({'runId':r.id,'result':report['result'],'report':str(r.root/'report.json')}))
    return 0 if report['result']=='PASS' else 1

if __name__=='__main__':sys.exit(main())
