#!/usr/bin/env python3
"""Fail-closed test dispatcher. Reports are private, fresh and tied to source/run IDs."""
import argparse
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
from evidence import Evidence, execute, manifest, workspace

REPO = Path(__file__).resolve().parents[2]
BOOT = REPO / 'yshop-drink-boot3'
MODES = ['QUICK','BUSINESS','INTEGRATION','PAYMENT-SAFETY','MINIPROGRAM','FULL','MUTATION']
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
        entry={'name':name,'result':'FAIL','timeoutSeconds':timeout}
        start=time.time()
        try:
            code=execute(command,cwd,self.env,folder/'output.log',timeout)
            entry['exitCode']=code
            if code:raise RuntimeError('SUBPROCESS_FAILED')
            if validator:entry['evidence']=validator(folder/'output.log')
            entry['result']='PASS'
        except Exception as exc:
            entry['reason']=str(exc) if isinstance(exc,RuntimeError) else type(exc).__name__
        if diagnostic:
            try: entry['diagnostics']=diagnostic()
            except Exception: entry['diagnosticError']='DIAGNOSTICS_UNAVAILABLE';entry['result']='FAIL'
        entry['seconds']=round(time.time()-start,3)
        self.steps.append(entry);self.save()
        print(name+': '+entry['result'],flush=True)
        return entry['result']=='PASS'

    def blocked(self, name, reason):
        self.steps.append({'name':name,'result':'BLOCKED','reason':reason});self.save()
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
        files=sorted([*REPO.glob('tests/*.test.mjs'),*REPO.glob('tests/business/*-test.mjs')])
        for index,file in enumerate(files):
            def node_evidence(log):
                text=log.read_text();counts={k:int(v) for k,v in re.findall(r'^# (tests|pass|fail|skipped|cancelled|todo) (\d+)$',text,re.M)}
                if counts.get('tests',0)<=0 or counts.get('tests')!=counts.get('pass') or any(counts.get(k,0) for k in ['fail','skipped','cancelled','todo']):raise RuntimeError('NODE_REPORT_INCOMPLETE')
                return counts
            self.step('node-'+str(index),[shutil.which('node') or 'node','--test','--test-reporter=tap',str(file)],timeout=180,validator=node_evidence)
        for index,file in enumerate([REPO/'tests/quality/test_evidence.py',REPO/'tests/quality/test_secret_guard.py',REPO/'tests/smoke/test_secret_scan.py',REPO/'tests/payment/prepayment-tools-test.py']):
            def python_evidence(log):
                text=log.read_text();match=re.search(r'Ran (\d+) tests? in',text)
                if not match or int(match[1])==0 or not re.search(r'^OK$',text,re.M):raise RuntimeError('PYTHON_REPORT_INCOMPLETE_OR_SKIPPED')
                return {'tests':int(match[1])}
            self.step('python-'+str(index),[sys.executable,str(file)],timeout=180,validator=python_evidence)

    def integration(self):
        helper=workspace(REPO)/'.local-dev/database.py'
        if not helper.exists():self.blocked('mysql','LOCAL_ISOLATED_DATABASE_HELPER_REQUIRED');return
        self.step('mysql-business',[sys.executable,'tests/business/mysql-acceptance.py','--coupon'],timeout=2400)
        self.step('mysql-payment',[sys.executable,'tests/payment/mysql-acceptance.py','--coupon'],timeout=2400)
        if self.env.get('YSHOP_SYNTHETIC_TLS_DIR'):
            self.step('synthetic-https-ingress',[sys.executable,'tests/quality/ingress.py'],timeout=900)
        else:self.blocked('synthetic-https-ingress','EXPLICIT_SYNTHETIC_TLS_FIXTURE_REQUIRED')
        if not self.env.get('YSHOP_COUPON_REDIS_CONFIG'):self.blocked('redis','EXPLICIT_PRIVATE_LOOPBACK_REDIS_CONFIG_REQUIRED')
        else:
            self.env['YSHOP_COUPON_REDIS_ACCEPTANCE']='true'
            self.java([k for k in manifest(REPO) if k.endswith('.CouponCodeRedisAcceptanceTest')])

    def build(self):
        cmd=[self.maven,'install','package','-Dmaven.test.skip=true']
        if self.env.get('YSHOP_MAVEN_REPOSITORY'):cmd+=['-Dmaven.repo.local='+self.env['YSHOP_MAVEN_REPOSITORY']]
        self.step('backend-build',cmd,BOOT)
        self.step('vue-build',['pnpm','build:local'],REPO/'yshop-drink-vue3',timeout=600)
        self.step('vue-types',['pnpm','ts:check'],REPO/'yshop-drink-vue3',timeout=600)

    def configured(self,name,key):
        if not self.env.get(key):self.blocked(name,'EXPLICIT_CONTROLLED_COMMAND_REQUIRED');return
        argv=json.loads(self.env[key])
        if not isinstance(argv,list) or not argv or not all(isinstance(x,str) for x in argv):raise RuntimeError('INVALID_CONTROLLED_COMMAND')
        self.step(name,argv,timeout=600)

    def save(self):
        report={'runId':self.id,'sourceSha':self.source_sha,
                'sourceDigest':self.digest,'result':'PASS' if self.steps and all(s['result']=='PASS' for s in self.steps) else 'NOT_READY','steps':self.steps,
                'paymentRequests':{'value':None,'evidence':'NOT_MEASURED_BY_DISPATCHER'},'realFinancialOperations':{'value':0,'evidence':'DECLARED_SYNTHETIC_ONLY'}}
        (self.root/'report.json').write_text(json.dumps(report,indent=2))
        return report


def main():
    p=argparse.ArgumentParser();p.add_argument('mode',choices=MODES);p.add_argument('--output',default=os.environ.get('YSHOP_QUALITY_OUTPUT',str(Path(tempfile.gettempdir())/'yshop-quality')));a=p.parse_args()
    r=Runner(a.output)
    try:
        if a.mode in ['QUICK','FULL']:r.quick()
        if a.mode in ['BUSINESS','PAYMENT-SAFETY','FULL']:
            selected=None
            if a.mode=='BUSINESS':selected=[k for k in manifest(REPO) if k.endswith(('.OrderingDatabaseTest','.CatalogDatabaseTest','.CouponDatabaseTest','.CouponCodeSecurityTest'))]
            if a.mode=='PAYMENT-SAFETY':selected=[k for k in manifest(REPO) if '.payment.' in k or '.credential.' in k or '.preflight.' in k]
            if selected is not None:selected=[k for k in selected if k.rsplit('.',1)[-1] not in CONDITIONAL]
            r.java(selected)
        if a.mode in ['INTEGRATION','FULL']:r.integration()
        if a.mode=='FULL':
            r.build();r.configured('uniapp','YSHOP_UNIAPP_ARGV');r.configured('strict-secret-scan','YSHOP_SECRET_SCAN_ARGV')
        if a.mode in ['MINIPROGRAM','FULL']:r.configured('mini-program','YSHOP_MINIPROGRAM_ARGV')
        if a.mode=='MUTATION':r.step('mutation',[sys.executable,'tests/quality/mutate.py','--output',str(r.root/'mutations')],timeout=7200)
        if source_digest()!=r.digest:r.blocked('source-integrity','SOURCE_CHANGED_DURING_RUN')
    except Exception as exc:r.blocked('dispatcher',type(exc).__name__)
    report=r.save();print(json.dumps({'runId':r.id,'result':report['result'],'report':str(r.root/'report.json')}))
    return 0 if report['result']=='PASS' else 1

if __name__=='__main__':sys.exit(main())
