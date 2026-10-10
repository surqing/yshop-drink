#!/usr/bin/env python3
"""Portable controlled gate: own MySQL/Redis/TLS, no pre-existing private services."""
import argparse,json,os,secrets,subprocess,sys,time,uuid
from pathlib import Path
from evidence import Evidence,execute,manifest,source_identity
from owned_resources import remove_owned,failed_report

REPO=Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',required=True);a=p.parse_args()
    root=Path(a.output).resolve();root.mkdir(parents=True,exist_ok=False,mode=0o700)
    owner=uuid.uuid4().hex;mysql='yshop-quality-heavy-'+owner;redis=mysql+'-redis'
    identity=source_identity();env=os.environ.copy();steps=[]
    report={**identity,'runId':owner,'result':'FAIL','cleanup':'NOT_RUN','steps':steps,
            'scope':'owned Docker MySQL/Redis/auth/synthetic HTTPS; GUI/Mini excluded',
            'paymentRequests':{'value':0,'evidence':'DECLARED_SYNTHETIC_TRANSPORT_ONLY','globalNetworkMeasured':False}}
    def private(name,text):
        f=root/name;f.parent.mkdir(parents=True,exist_ok=True,mode=0o700);f.write_text(text);f.chmod(0o600);return f
    def docker(*args,input=None):
        r=subprocess.run(['docker',*args],input=input,text=True,capture_output=True,timeout=180)
        if r.returncode:raise RuntimeError('OWNED_DOCKER_OPERATION_FAILED')
        return r.stdout.strip()
    def command(name,argv,validator=None,timeout=2400):
        item={'name':name,'result':'FAIL'};steps.append(item)
        try:
            code=execute(argv,REPO,env,root/(name+'.log'),timeout);item['exitCode']=code
            if code:raise RuntimeError('CONTROLLED_SUBPROCESS_FAILED')
            if validator:item['evidence']=validator()
            item['result']='PASS'
        except Exception as e:
            failed_report(item,e,'CONTROLLED_STEP')
            diagnostics=[]
            for file in root.glob('**/diagnostics.json'):
                try:
                    data=json.loads(file.read_text())
                    if all(data.get(k)==v for k,v in identity.items()):
                        diagnostics.append({k:data[k] for k in ['runId','reports','failures','missingSuites'] if k in data})
                except (OSError,ValueError):pass
            item['diagnostics']=diagnostics
            database=root/'database-diagnostics.json'
            if database.exists():item['databaseDiagnostics']=json.loads(database.read_text())
        print(name+': '+item['result'],flush=True)
    def acceptance_log(name,suffixes):
        rows=[]
        for line in (root/(name+'.log')).read_text().splitlines():
            try:r=json.loads(line)
            except ValueError:continue
            if isinstance(r,dict) and r.get('exactNamesChecked'):rows.append(r)
        expected=sum(sum(v.values()) for k,v in manifest(REPO).items() if k.rsplit('.',1)[-1] in suffixes)
        if len(rows)!=1 or expected<=0:raise RuntimeError('ACCEPTANCE_REPORT_MISSING')
        r=rows[0]
        if r.get('result')!='PASS' or r.get('cleanup')!='PASS' or r.get('tests')!=expected or any(r.get(k)!=v for k,v in identity.items()):
            raise RuntimeError('ACCEPTANCE_REPORT_INVALID')
        return {k:r[k] for k in ['runId','tests','sourceSha','sourceDigest','cleanup']}
    def auth_report():
        r=json.loads((root/'auth/report.json').read_text())
        if r.get('result')!='PASS' or r.get('cleanup')!='PASS' or any(r.get(k)!=v for k,v in identity.items()):raise RuntimeError('AUTH_REPORT_INVALID')
        e=r.get('evidence',{})
        expected=sum(sum(v.values()) for k,v in manifest(REPO).items() if k.endswith('.OAuth2LifecycleDatabaseTest'))
        if e.get('tests')!=expected or not e.get('exactNamesChecked'):raise RuntimeError('AUTH_TESTS_MISSING')
        return {'tests':expected,'cleanup':'PASS'}
    try:
        if os.getuid()==0:raise RuntimeError('NONROOT_TEST_LAUNCHER_REQUIRED')
        pw=secrets.token_hex(24);rpw=secrets.token_hex(24)
        secret=private('mysql.secret',pw);client=private('mysql.cnf','[client]\ndefault-character-set=utf8mb4\nuser=root\npassword='+pw+'\n')
        docker('run','-d','--name',mysql,'--label','yshop.quality.owner='+owner,'--cap-drop=NET_RAW','--security-opt','no-new-privileges',
               '-p','127.0.0.1::3306','--mount','type=bind,source='+str(secret)+',target=/run/root.secret,readonly',
               '--mount','type=bind,source='+str(client)+',target=/run/client.cnf,readonly','-e','MYSQL_ROOT_PASSWORD_FILE=/run/root.secret','mysql:8.0')
        for _ in range(120):
            try:docker('exec',mysql,'mysql','--defaults-extra-file=/run/client.cnf','--protocol=TCP','-h','127.0.0.1','-N','-e','SELECT 1');break
            except RuntimeError:time.sleep(1)
        else:raise RuntimeError('OWNED_MYSQL_START_TIMEOUT')
        binding=json.loads(docker('inspect',mysql))[0]['NetworkSettings']['Ports']['3306/tcp'][0]
        if binding['HostIp']!='127.0.0.1':raise RuntimeError('LOOPBACK_REQUIRED')
        cfg=private('database.json',json.dumps({'container':mysql,'owner':owner}))
        env.update(YSHOP_OWNED_DATABASE_CONFIG=str(cfg),YSHOP_DATABASE_HELPER=str(REPO/'tests/quality/database_adapter.py'),YSHOP_MYSQL_PORT=binding['HostPort'],
                   YSHOP_ACCEPTANCE_OUTPUT=str(root/'acceptance'),YSHOP_TEST_WORKSPACE=str(root/'workspace'),
                   YSHOP_PAY_WECHAT_V3_ENABLED='false',YSHOP_PAY_WECHAT_V3_RECONCILIATION_ENABLED='false')
        (root/'workspace').mkdir(mode=0o700)
        rc=private('redis.conf','bind 0.0.0.0\nprotected-mode yes\nrequirepass '+rpw+'\nsave ""\nappendonly no\n')
        docker('run','-d','--name',redis,'--label','yshop.quality.owner='+owner,'--user',str(os.getuid())+':'+str(os.getgid()),'--cap-drop=ALL','--security-opt','no-new-privileges',
               '-p','127.0.0.1::6379','--mount','type=bind,source='+str(rc)+',target=/run/redis.conf,readonly','redis:7.4','redis-server','/run/redis.conf')
        rbind=json.loads(docker('inspect',redis))[0]['NetworkSettings']['Ports']['6379/tcp'][0]
        redis_config=private('redis.properties','url=redis://127.0.0.1:'+rbind['HostPort']+'\npassword='+rpw+'\n')
        env.update(YSHOP_COUPON_REDIS_ACCEPTANCE='true',YSHOP_COUPON_REDIS_CONFIG=str(redis_config))
        command('mysql-business',[sys.executable,'tests/business/mysql-acceptance.py','--coupon'],lambda:acceptance_log('mysql-business',{'OrderingDatabaseTest','CatalogDatabaseTest','CatalogEditingMysqlAcceptance','CouponDatabaseTest'}))
        command('mysql-financial',[sys.executable,'tests/payment/mysql-acceptance.py','--coupon'],lambda:acceptance_log('mysql-financial',{'PaymentDatabaseTest','WalletDatabaseTest','PaymentAttemptDatabaseTest','WechatV3DatabaseTest','PaymentLiveReadinessDatabaseTest','LiveMerchantPreflightDatabaseTest','PaymentCancellationDatabaseTest','CouponPaymentDatabaseTest'}))
        command('mysql-auth',[sys.executable,'tests/quality/auth-mysql.py','--output',str(root/'auth')],auth_report)
        ev=Evidence(root/'redis-evidence');selected={k:v for k,v in manifest(REPO).items() if k.endswith('.CouponCodeRedisAcceptanceTest')}
        from acceptance_support import maven_command
        cmd=maven_command(REPO.parent)+['-f','yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz/pom.xml','-Pmysql-acceptance','test','-Dtest=CouponCodeRedisAcceptanceTest','-Dsurefire.failIfNoSpecifiedTests=true']+ev.arguments(selected)
        command('redis',cmd,ev.validate)
        tls=root/'tls';tls.mkdir(mode=0o700)
        def openssl(*args):
            if execute(['openssl',*args],tls,env,root/'openssl.log',60):raise RuntimeError('SYNTHETIC_TLS_GENERATION_FAILED')
        openssl('req','-x509','-newkey','rsa:2048','-nodes','-keyout','ca-key.pem','-out','ca.pem','-days','1','-subj','/CN=yshop synthetic local CA')
        openssl('req','-newkey','rsa:2048','-nodes','-keyout','server-key.pem','-out','server.csr','-subj','/CN=localhost')
        (tls/'extensions.conf').write_text('subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n')
        openssl('x509','-req','-in','server.csr','-CA','ca.pem','-CAkey','ca-key.pem','-CAcreateserial','-out','server.pem','-days','1','-extfile','extensions.conf')
        for f in tls.iterdir():f.chmod(0o600)
        env['YSHOP_SYNTHETIC_TLS_DIR']=str(tls)
        command('synthetic-tls',[sys.executable,'tests/quality/ingress.py'],lambda:acceptance_log('synthetic-tls',{'CallbackIngressEndToEndTest'}),timeout=1200)
        if source_identity()!=identity:raise RuntimeError('SOURCE_CHANGED_DURING_TEST')
        if not steps or any(s['result']!='PASS' for s in steps):raise RuntimeError('CONTROLLED_GATE_FAILED')
        report['result']='PASS'
    except Exception as e:failed_report(report,e,'CONTROLLED_GATE')
    finally:
        try:report['resources']=remove_owned(docker,[mysql,redis],owner);report['cleanup']='PASS'
        except Exception as e:failed_report(report,e,'CLEANUP');report['cleanup']='FAIL'
        for f in root.glob('**/*'):
            if f.is_file() and (f.name in ['mysql.secret','mysql.cnf','database.json','redis.conf','redis.properties'] or f.suffix in ['.key','.pem','.csr','.srl']):f.unlink()
        report['sourceUnchanged']=source_identity()==identity
        if not report['sourceUnchanged']:report['result']='FAIL'
        (root/'report.json').write_text(json.dumps(report,indent=2))
    print(json.dumps({'result':report['result'],'cleanup':report['cleanup']}))
    return 0 if report['result']=='PASS' and report['cleanup']=='PASS' else 1
if __name__=='__main__':sys.exit(main())
