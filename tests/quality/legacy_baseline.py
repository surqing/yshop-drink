#!/usr/bin/env python3
"""Run frozen develop, unchanged, with an external owned HTTP/SQL harness.

Only launcher configuration, baseline-present SQL migrations and synthetic data
are adapted. No production source or compiled classes are patched.
"""
import argparse
import ast
import hashlib
import io
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tarfile
import time
import traceback
import urllib.error
import urllib.request
import uuid

from defect_contract import (assertion_fingerprints, load_contract, migrations_for_source)


def assertion_equivalence(reference, launcher):
    original = assertion_fingerprints(reference)
    if assertion_fingerprints(launcher) != original:
        raise RuntimeError('BASELINE_ASSERTION_DRIFT')
    return original


class ControlledInterrupted(BaseException):
    pass


def archive_identity(repo, source, manifest, sha):
    """Use source_identity's sorted names+NUL+bytes digest on archive files only."""
    digest = hashlib.sha256()
    for name, expected in sorted(manifest.items()):
        content = (source / name).read_bytes()
        if hashlib.sha256(content).hexdigest() != expected:
            raise RuntimeError('BASELINE_PRODUCTION_SOURCE_CHANGED:' + name)
        digest.update(name.encode() + b'\0' + content)
    return {'sourceSha': sha, 'sourceDigest': digest.hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument('--output', required=True)
    parser.add_argument('--base', required=True, help='Exact baseline revision; no implicit historic baseline')
    parser.add_argument('--offline', action='store_true', help='Use an already populated Maven cache')
    args = parser.parse_args()
    os.umask(0o077)
    repo = Path(args.repo).resolve()
    root = Path(args.output).resolve()
    if root.is_relative_to(repo):
        raise RuntimeError('PRIVATE_OUTPUT_OUTSIDE_SOURCE_REQUIRED')
    root.mkdir(parents=True, mode=0o700, exist_ok=False)
    source = root / 'source'
    source.mkdir(mode=0o700)
    sha = subprocess.check_output(['git', 'rev-parse', '--verify', args.base+'^{commit}'], cwd=repo, text=True).strip()
    archive = subprocess.check_output(['git', 'archive', sha], cwd=repo)
    (root / 'source.tar').write_bytes(archive)
    manifest = {}
    with tarfile.open(fileobj=io.BytesIO(archive)) as bundle:
        for member in bundle.getmembers():
            if member.name.startswith('/') or '..' in Path(member.name).parts or member.issym() or member.islnk():
                raise RuntimeError('PLAIN_ARCHIVE_REQUIRED')
            if member.isfile():
                manifest[member.name] = hashlib.sha256(bundle.extractfile(member).read()).hexdigest()
        bundle.extractall(source)
    identity = archive_identity(repo, source, manifest, sha)
    (root / 'source-manifest.json').write_text(json.dumps(manifest, sort_keys=True, indent=2))
    from evidence import source_identity, execute
    harness_identity = source_identity(repo)
    contract = load_contract(repo, identity, sha)
    migrations = migrations_for_source(source)
    runid = uuid.uuid4().hex
    report = {**identity, 'baselineSource': identity, 'harnessSource': harness_identity,
              'runId': runid, 'owner': runid, 'startedAt': time.time(),
              'scope': 'frozen develop; six unchanged real HTTP/SQL assertions; synthetic points and unpaid pickup only',
              'cases': [], 'status': 'INCONCLUSIVE', 'cleanup': 'NOT_RUN', 'complete': False,
              'testScriptSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'referenceTestScriptSha256': hashlib.sha256((repo/'tests/defects/http_red.py').read_bytes()).hexdigest(),
              'migrations': migrations, 'defectContract': contract, 'seedInsertRowsCopied': 0,
              'financialProviderRequests': {'value': 0, 'scope': 'this loopback HTTP harness path guard only', 'globalNetworkMeasured': False}}
    env = None
    cleanup_phase = False
    def save():
        (root/'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False))
    def interrupted(signum, frame):
        if cleanup_phase:
            return
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        raise ControlledInterrupted('CONTROLLED_BASELINE_INTERRUPTED')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        report['assertionFingerprints'] = assertion_equivalence(repo/'tests/defects/http_red.py', Path(__file__))
        command = ['mvn', '-B', *(['-o'] if args.offline else []), '-DskipTests', 'package']
        if os.environ.get('YSHOP_MAVEN_REPOSITORY'):
            command += ['-Dmaven.repo.local=' + os.environ['YSHOP_MAVEN_REPOSITORY']]
        build = {'runId': uuid.uuid4().hex, 'startedAt': time.time(), 'command': command, **identity}
        report['build'] = build
        result = execute(command, source/'yshop-drink-boot3', os.environ.copy(), root/'build-private.log', timeout=1800)
        build.update(exitCode=result, endedAt=time.time())
        text = (root/'build-private.log').read_text()
        build['modules'] = len(re.findall(r'^\[INFO\] \S.*? \.{2,} SUCCESS \[', text, re.M))
        build['result'] = 'PASS' if result == 0 and build['modules'] == 55 and 'BUILD SUCCESS' in text else 'FAIL'
        if build['result'] != 'PASS':
            raise RuntimeError('BASELINE_FULL_REACTOR_BUILD_FAILED')
        archive_identity(repo, source, manifest, sha)
        jar = source/'yshop-drink-boot3/yshop-server/target/yshop-server.jar'
        report['artifactHash'] = report['backendArtifactHash'] = hashlib.sha256(jar.read_bytes()).hexdigest()
        import e2e.environment as lifecycle
        # Baseline's own migration set; never import a candidate-only migration.
        lifecycle.MIGRATIONS = migrations
        env = lifecycle.OwnedEnvironment(source, root/'owned', identity=identity, owner=runid, run_id=runid)
        def fixtures(owned, config):
            owned.sql("INSERT INTO yshop_store_product(id,shop_id,image,slider_image,store_name,store_info,keyword,cate_id,stock,sales,is_show,is_integral) VALUES(9101,101,'','','Synthetic item','','','101',10,0,1,0); INSERT INTO yshop_store_product_attr_value(id,product_id,sku,stock,sales,price,cost) VALUES(9101,9101,'默认',10,0,16,0);")
        env.start(require_build=False, configure=fixtures)
        report['backendOwnershipVerified'] = env.report['backendOwnershipVerified']
        report['ownedContext'] = {'owner': env.owner, 'mysql': env.mysql, 'redis': env.redis, 'schema': env.schema, 'backendPort': env.port}
        base = 'http://127.0.0.1:' + str(env.port)
        sql = env.sql
        def scalar(q): return sql(q).strip()
        def n(q): return int(scalar(q))
        def api(method, path, body=None, token=None):
            if not path.startswith(('/app-api/', '/admin-api/')) or re.search(r'/pay(?:/|$)|/prepay|/refund|/recharge|/notify|/weixin|/wechat', path):
                raise RuntimeError('PROVIDER_PATH_FORBIDDEN')
            request = urllib.request.Request(base+path, data=json.dumps(body).encode() if body is not None else None,
                headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer '+token} if token else {})}, method=method)
            try:
                with urllib.request.urlopen(request, timeout=30) as response: return json.load(response)
            except urllib.error.HTTPError as response: return json.load(response)
        response = api('POST', '/app-api/member/auth/login', {'mobile': '13800000001', 'password': 'syntheticQA12'})
        if response['code'] != 0: raise RuntimeError('FIXTURE_LOGIN_FAILED')
        token = response['data']['accessToken']
        sql("INSERT INTO yshop_user_address(id,uid,real_name,address,phone,detail,create_time,tenant_id) VALUES(9101,101,'Synthetic A','Synthetic street A','13800000101','Unit A',NOW(),0),(9102,102,'Synthetic B','Synthetic street B','13800000102','Unit B',NOW(),0);")
        sql("INSERT INTO yshop_score_product(id,title,image,images,`desc`,score,stock,sales,is_switch) VALUES(9101,'Synthetic reward','','','Synthetic only',100,10,0,1);")
        def reset(stock=10, listed=1, points=1000):
            if contract['scoreIdempotencyRequired']: sql('DELETE FROM yshop_score_submission WHERE uid=101')
            sql("DELETE FROM yshop_score_order WHERE product_id=9101; DELETE FROM yshop_user_bill WHERE uid=101 AND category='integral'; UPDATE yshop_score_product SET stock="+str(stock)+",sales=0,is_switch="+str(listed)+" WHERE id=9101; UPDATE yshop_user SET integral="+str(points)+" WHERE id=101;")
        def reward(num='1', address='9101'):
            payload={'productId':'9101','addressId':address,'num':num}
            if contract['scoreIdempotencyRequired']: payload['idempotencyKey']=uuid.uuid4().hex
            return api('POST','/app-api/score-order/submit',payload,token)
        def state(o,r):
            o.update(code=r['code'],orders=n('SELECT COUNT(*) FROM yshop_score_order WHERE product_id=9101'),stock=n('SELECT stock FROM yshop_score_product WHERE id=9101'),points=scalar('SELECT integral FROM yshop_user WHERE id=101'))
        def case(id,title,fn):
            for repetition in (1,2):
                row={'id':id,'name':title,'repetition':repetition,'status':'INCONCLUSIVE','actual':{}}
                report['cases'].append(row)
                try: fn(row['actual']); row['status']='PASSED'
                except AssertionError as error: row.update(status='FAILED',assertion=str(error))
                except Exception as error:
                    row.update(status='INCONCLUSIVE',errorType=type(error).__name__)
                    (root/(id+'-'+str(repetition)+'-private.log')).write_text(traceback.format_exc())
                save()
                line=id+' '+str(repetition)+' '+row['status']+' '+json.dumps(row['actual'])
                with (root/'raw-results.log').open('a') as log: log.write(line+'\n')
                print(line,flush=True)
        # These six business bodies and assertions are identical to http_red.py.
        def quantity(o):
            reset();r=reward('2');state(o,r);o['totalScore']=n('SELECT total_score FROM yshop_score_order WHERE product_id=9101 ORDER BY id DESC LIMIT 1')
            if r['code']!=0:raise RuntimeError('VALID_QUANTITY_FIXTURE_FAILED')
            assert o['points']=='800.00' and o['totalScore']==200,'Two rewards at 100 points each must debit and record 200 points'
        case('D002','reward quantity must multiply points cost',quantity)
        def stock(o):
            reset(stock=1);r=reward('2');state(o,r)
            assert r['code']!=0 and o['orders']==0 and o['stock']==1 and o['points']=='1000.00','Insufficient reward stock must reject and roll back order and points'
        case('D003','insufficient reward stock must roll back',stock)
        def unlisted(o):
            reset(listed=0);r=reward();state(o,r)
            assert r['code']!=0 and o['orders']==0 and o['points']=='1000.00','Off-shelf reward must not be ordered or debit points'
        case('D005','off-shelf reward must reject order',unlisted)
        def zero(o):
            reset();r=reward('0');state(o,r)
            assert r['code']!=0 and o['orders']==0 and o['points']=='1000.00','Zero quantity must reject without creating paid reward order or point debit'
        case('D006','zero reward quantity must reject',zero)
        def ledger(o):
            reset();r=reward();state(o,r)
            o['ledgerBalance']=scalar("SELECT balance FROM yshop_user_bill WHERE uid=101 AND category='integral' ORDER BY id DESC LIMIT 1")
            o['ledgerDebit']=scalar("SELECT number FROM yshop_user_bill WHERE uid=101 AND category='integral' ORDER BY id DESC LIMIT 1")
            assert r['code']==0 and o['points']=='900.00' and o['ledgerDebit']=='100.00','single reward fixture must debit points'
            assert o['ledgerBalance']=='900.00','Point ledger remaining balance must equal post-debit member balance'
        case('D007','reward ledger must record remaining balance',ledger)
        def phone(o):
            pid,version=map(int,sql('SELECT id,catalog_version FROM yshop_store_product WHERE shop_id=101 ORDER BY id LIMIT 1').strip().split('\t'))
            r=api('POST','/app-api/order/create',{'shopId':'101','mobile':'13800000001','couponId':'0','orderType':'takein','productId':[str(pid)],'spec':['默认'],'number':['1'],'payType':'weixin','idempotencyKey':uuid.uuid4().hex,'choices':[{'version':version,'selections':[]}]},token)
            if r['code']!=0:raise RuntimeError('PICKUP_FIXTURE_FAILED')
            oid=r['data']['orderId'];assert re.fullmatch('[a-zA-Z0-9_-]+',oid)
            try:
                o['submittedPhonePersisted']=scalar("SELECT user_phone FROM yshop_store_order WHERE order_id='"+oid+"'")=='13800000001'
                assert o['submittedPhonePersisted'],'Pickup order must retain submitted contact phone for merchant fulfillment'
            finally:api('POST','/app-api/order/cancel',{'id':oid},token)
        case('D008','pickup order must retain contact phone',phone)
        reset()
        report['financialState'] = {table:n('SELECT COUNT(*) FROM '+table) for table in ['yshop_member_wallet_transaction','yshop_member_recharge_order','yshop_order_payment','yshop_order_payment_attempt']}
        report['unpaidPickupOrdersOnly'] = n('SELECT COUNT(*) FROM yshop_store_order WHERE paid<>0') == 0
        if any(report['financialState'].values()) or not report['unpaidPickupOrdersOnly']:
            raise RuntimeError('FINANCIAL_STATE_CHANGED')
        report['status']='FAILED' if any(c['status']=='FAILED' for c in report['cases']) else 'PASSED'
        if any(c['status']=='INCONCLUSIVE' for c in report['cases']):report['status']='INCONCLUSIVE'
    except (Exception, ControlledInterrupted) as error:
        report.update(status='INCONCLUSIVE',errorType=type(error).__name__)
        (root/'failure-private.log').write_text(traceback.format_exc())
    finally:
        cleanup_phase = True
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        if env is not None:
            receipt=env.close();report['cleanupReceipt']={**receipt,'owner':runid,'runId':runid};report['cleanup']='PASSED' if receipt['status']=='PASSED' else 'FAILED'
        else: report['cleanup']='PASSED';report['cleanupReceipt']={'status':'PASSED','containersRemoved':0,'anonymousVolumesRemoved':0,'ownershipChecked':True}
        try:report['sourceUnchanged']=archive_identity(repo,source,manifest,sha)==identity
        except Exception:report['sourceUnchanged']=False
        report['harnessUnchanged']=source_identity(repo)==harness_identity
        report.update(endedAt=time.time(),complete=True)
        if report['cleanup']!='PASSED' or not report['sourceUnchanged']:report['status']='INCONCLUSIVE'
        report['exitCode']=0 if report['status']=='PASSED' else 1
        (root/'exit-code.txt').write_text(str(report['exitCode'])+'\n')
        save()
    print(json.dumps({'status':report['status'],'cases':len(report['cases']),'cleanup':report['cleanup'],'report':str(root/'report.json')}))
    return report['exitCode']


if __name__=='__main__':
    sys.exit(main())
