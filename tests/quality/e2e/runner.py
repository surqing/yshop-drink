"""Single owner of preflight, current builds, environment, execution and final evidence."""
import contextlib
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import traceback
import uuid
from backend_artifact import verify_artifact
from evidence import execute, source_identity
from .contract import atomic_text, final_status, validate_receipt, write_reports
from .registry import listing


class Blocked(RuntimeError):pass


def source_workspace(repo):
    explicit=os.environ.get('YSHOP_TEST_WORKSPACE')
    if explicit:return Path(explicit).resolve()
    common=Path(subprocess.check_output(['git','rev-parse','--git-common-dir'],cwd=repo,text=True).strip())
    common=(repo/common).resolve() if not common.is_absolute() else common
    candidate=common.parent.parent
    return candidate if (candidate/'.local-dev/tools.sh').is_file() else repo.parent


def execution_environment(workspace):
    env=os.environ.copy()
    bins=[workspace/'.dev-tools'/name/'bin' for name in ['java-home','maven','node','pnpm']]
    bins.append(Path('/Applications/Docker.app/Contents/Resources/bin'))
    env['PATH']=os.pathsep.join([str(p) for p in bins if p.is_dir()]+[env.get('PATH','')])
    if (workspace/'.dev-tools/java-home').is_dir():env['JAVA_HOME']=str(workspace/'.dev-tools/java-home')
    cache=workspace/'.dev-tools/cache/playwright'
    if 'PLAYWRIGHT_BROWSERS_PATH' not in env and cache.is_dir():env['PLAYWRIGHT_BROWSERS_PATH']=str(cache)
    env.update(YSHOP_TEST_WORKSPACE=str(workspace),YSHOP_PAY_WECHAT_V3_ENABLED='false',
               YSHOP_PAY_WECHAT_V3_RECONCILIATION_ENABLED='false',PYTHONPATH=str(Path(__file__).resolve().parents[1]))
    return env


def preflight(repo,profile,env,root):
    if profile=='local':
        from mini_pages import mini_prerequisite
        reason=mini_prerequisite()
        if reason:raise Blocked(reason)
        if sys.platform!='darwin':raise Blocked('MACOS_MINI_EXECUTION_REQUIRED')
    if importlib.util.find_spec('yaml') is None:raise Blocked('LOCKED_PYTHON_DEPENDENCIES_REQUIRED')
    for tool in ['git','java','javac','mvn','node','pnpm','docker']:
        if not shutil.which(tool,path=env['PATH']):raise Blocked('TOOL_REQUIRED_'+tool.upper())
    if not (repo/'yshop-drink-vue3/node_modules/@playwright/test').is_dir():
        raise Blocked('LOCKED_BROWSER_DEPENDENCIES_REQUIRED')
    if execute(['docker','info','--format','{{.ServerVersion}}'],repo,env,root/'docker-preflight-private.log',20):
        raise Blocked('DOCKER_UNAVAILABLE')
    result=subprocess.run(['node','-e',"process.stdout.write(require('@playwright/test').chromium.executablePath())"],
                          cwd=repo/'yshop-drink-vue3',env=env,text=True,capture_output=True,timeout=15)
    if result.returncode or not Path(result.stdout).is_file():raise Blocked('PINNED_CHROMIUM_REQUIRED')


@contextlib.contextmanager
def process_environment(env):
    previous=os.environ.copy();os.environ.clear();os.environ.update(env)
    try:yield
    finally:os.environ.clear();os.environ.update(previous)


def run(repo, output, profile, scenario=None):
    repo=Path(repo).resolve();parent=Path(output).resolve()
    if parent.is_relative_to(repo) and not parent.is_relative_to(repo/'.quality'):
        raise ValueError('PRIVATE_OUTPUT_REQUIRED')
    root=parent/uuid.uuid4().hex;root.mkdir(parents=True,mode=0o700,exist_ok=False);os.chmod(root,0o700)
    identity=source_identity(repo);plan=listing(profile,scenario);workspace=source_workspace(repo)
    env=execution_environment(workspace)
    report={**identity,**plan,'runId':root.name,'owner':uuid.uuid4().hex,'complete':False,
            'startedAt':time.time(),'status':'INCONCLUSIVE','cleanup':'NOT_RUN','stage':'PREFLIGHT',
            'planned':plan['scenarios'],
            'scenarios':[{'id':s['id'],'variant':s['variant'],'status':'NOT_RUN','checks':[]} for s in plan['scenarios']],
            'receiptValidated':False,'sourceUnchanged':True,'workspace':str(workspace)}
    write_reports(root,report)
    environment=None;mini_context=None;mini_lock=None;context_path=root/'context-private.json'
    previous_signals={};interrupted=False
    def interrupt(signum,frame):
        nonlocal interrupted
        interrupted=True
        signal.signal(signal.SIGTERM,signal.SIG_IGN);signal.signal(signal.SIGINT,signal.SIG_IGN)
        raise KeyboardInterrupt('CONTROLLED_INTERRUPTION')
    for sig in [signal.SIGINT,signal.SIGTERM]:previous_signals[sig]=signal.signal(sig,interrupt)
    old_umask=os.umask(0o077)
    try:
        with process_environment(env):
            preflight(repo,profile,env,root)
            if profile=='local':
                lockdir=workspace/'.local-dev/quality';lockdir.mkdir(parents=True,mode=0o700,exist_ok=True)
                mini_lock=(lockdir/'three-end-mini.lock').open('a');os.chmod(mini_lock.name,0o600)
                try:fcntl.flock(mini_lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
                except BlockingIOError:raise Blocked('MINI_EXECUTION_SLOT_BUSY')
            report['stage']='BUILD';write_reports(root,report)
            jar=repo/'yshop-drink-boot3/yshop-server/target/yshop-server.jar'
            try:certificate=verify_artifact(jar,repo/'.quality/backend-build.json',identity)
            except RuntimeError:
                from run import Runner
                with (root/'build-dispatcher-private.log').open('w') as log,contextlib.redirect_stdout(log):
                    build=Runner(root/'build');build.backend();result=build.save()
                if result['result']!='PASS':raise RuntimeError('CURRENT_BACKEND_BUILD_FAILED')
                certificate=verify_artifact(jar,repo/'.quality/backend-build.json',identity)
            report['backendArtifact']=certificate
            from .environment import OwnedEnvironment
            from .support import TestSupport
            support=TestSupport(repo,root/'support',identity,report['owner'])
            report['testSupport']=support.prepare()
            environment=OwnedEnvironment(repo,root/'owned',identity=identity,owner=report['owner'],run_id=report['runId'])
            report['stage']='ENVIRONMENT';write_reports(root,report)
            environment.start(configure=support.configure,launcher=support.launch)
            deadline=time.monotonic()+15
            while not (support.root/'support-ready.json').exists():
                if time.monotonic()>deadline:raise RuntimeError('TEST_SUPPORT_READINESS_FAILED')
                time.sleep(.1)
            ready=json.loads((support.root/'support-ready.json').read_text())
            if ready.get('owner')!=report['owner'] or ready.get('status')!='PASSED':raise RuntimeError('TEST_SUPPORT_OWNER_MISMATCH')
            context={**environment.context(),**support.context(),'repo':str(repo),'python':sys.executable,
                     'profile':profile,'planned':plan['scenarios'],'output':str(root),
                     'accounts':{'admin':{'username':'qualityadmin','password':'syntheticQA12'},
                                 'staff':{'username':'qualitystaff','password':'syntheticQA12'}}}
            if profile=='local':
                report['stage']='MINI_BUILD';write_reports(root,report)
                from mini_pages import prepare_mini
                mini_context=prepare_mini(root,report,{'backend':context['backend']},
                                          guard_source=(repo/'tests/mini/e2e-guard.js').read_text())
                context['mini']=mini_context
            atomic_text(context_path,json.dumps(context))
            report['stage']='SCENARIOS';write_reports(root,report)
            env.update(YSHOP_E2E_CONTEXT=str(context_path),YSHOP_E2E_OUTPUT=str(root),
                       YSHOP_BROWSER_PORT=str(environment.port+2))
            started=time.time()
            try:
                code=execute(['pnpm','exec','playwright','test','--config','playwright.e2e.config.ts'],
                             repo/'yshop-drink-vue3',env,root/'playwright-private.log',1800,termination_grace=120)
            finally:
                receipt_path=root/'receipt.json'
                if receipt_path.is_file():
                    receipt=json.loads(receipt_path.read_text())
                    # Node reporter publishes only registered names and structural status fields.
                    if receipt.get('runId')==report['runId'] and receipt.get('owner')==report['owner']:
                        report['scenarios']=receipt.get('scenarios',report['scenarios'])
            if code:raise RuntimeError('E2E_SCENARIOS_FAILED')
            report['evidence']=validate_receipt(receipt,plan['scenarios'],identity,report['runId'],report['owner'],started)
            report.update(receiptValidated=True,status='PASSED')
    except Blocked as error:
        report.update(status='BLOCKED',reasonCode=str(error),failedStage=report['stage'])
    except BaseException as error:
        known={'CURRENT_BACKEND_BUILD_FAILED','TEST_SUPPORT_COMPILE_FAILED','TEST_SUPPORT_READINESS_FAILED',
               'TEST_SUPPORT_OWNER_MISMATCH','E2E_SCENARIOS_FAILED','SOURCE_CHANGED_DURING_TEST','TEST_PROCESS_TIMEOUT'}
        reason=str(error) if isinstance(error,RuntimeError) and str(error) in known else 'E2E_EXECUTION_INTERRUPTED' if interrupted else 'E2E_EXECUTION_FAILED'
        report.update(status='FAILED' if reason=='E2E_SCENARIOS_FAILED' else 'INCONCLUSIVE',reasonCode=reason,
                      reasonType=type(error).__name__,failedStage=report['stage'])
        atomic_text(root/'failure-private.log',traceback.format_exc())
    finally:
        report['stage']='CLEANUP'
        cleanup_errors=[]
        if mini_context:
            try:
                code=execute([mini_context['cli'],'close','--project',mini_context['project']],repo,env,root/'mini-close-private.log',60)
                if code:cleanup_errors.append('MINI_PROJECT_CLOSE_FAILED')
            except Exception:cleanup_errors.append('MINI_PROJECT_CLOSE_FAILED')
        if environment:
            with process_environment(env):cleanup=environment.close()
            report['cleanupReceipt']=cleanup
            if cleanup['status']!='PASSED':cleanup_errors.append('OWNED_ENVIRONMENT_CLEANUP_FAILED')
        context_path.unlink(missing_ok=True)
        if mini_lock:mini_lock.close()
        report.update(cleanup='FAILED' if cleanup_errors else 'PASSED',cleanupErrors=cleanup_errors,
                      complete=True,endedAt=time.time())
        current=source_identity(repo);report['sourceUnchanged']=identity==current
        report['status']=final_status(report,current)
        if profile=='local':report['miniStatus']=report['status'] if report.get('receiptValidated') else 'NOT_RUN' if all(s.get('status')=='NOT_RUN' for s in report['scenarios']) else report['status']
        report['result']='PASS' if report['status']=='PASSED' else 'NOT_READY'
        report['threeEndCorePassed']=bool(profile=='local' and not scenario and report['status']=='PASSED')
        report['stage']='FINISHED';write_reports(root,report)
        for sig,handler in previous_signals.items():signal.signal(sig,handler)
        os.umask(old_umask)
    print(json.dumps({'runId':report['runId'],'status':report['status'],'report':str(root/'report.json')}))
    return 0 if report['status']=='PASSED' else 1
