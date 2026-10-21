#!/usr/bin/env python3
"""Own a disposable Docker MySQL; verify auth lifecycle with no private service dependency."""
import argparse, hashlib, json, os, re, secrets, shutil, signal, subprocess, sys, time, uuid
from pathlib import Path
from evidence import Evidence, execute, manifest, source_identity
from owned_resources import remove_owned

REPO=Path(__file__).resolve().parents[2]

def checkout_digest(checkout):
    names=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=REPO).decode().split('\0')
    digest=hashlib.sha256()
    for name in sorted(filter(None,names)):
        file=checkout/name
        if not file.is_file():raise RuntimeError('CHECKOUT_SOURCE_MISSING')
        digest.update(name.encode()+b'\0'+file.read_bytes())
    return digest.hexdigest()

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--tests',default='MemberIdentityMysqlAcceptanceTest');p.add_argument('--checkout',type=Path,default=REPO);a=p.parse_args()
    checkout=a.checkout.resolve();tested_digest=checkout_digest(checkout)
    root=Path(a.output).resolve();root.mkdir(parents=True,mode=0o700,exist_ok=False)
    owner=uuid.uuid4().hex;name='yshop-quality-identity-'+owner
    schema='yshop_quality_identity_'+owner[:16];user='qa_identity_'+owner[:16]
    password=secrets.token_hex(24);root_password=secrets.token_hex(24)
    def private(name,contents):
        path=root/name;path.write_text(contents);path.chmod(0o600);return path
    root_secret=private('root.secret',root_password)
    client=private('client.cnf','[client]\nuser=root\npassword='+root_password+'\n')
    report={'owner':owner,'runId':owner,'result':'FAIL','cleanup':'NOT_RUN',**source_identity(),'testedSourceDigest':tested_digest}
    class ControlledInterrupted(BaseException):pass
    def interrupted(signum,frame):raise ControlledInterrupted('CONTROLLED_INTERRUPTED')
    previous={sig:signal.signal(sig,interrupted) for sig in (signal.SIGINT,signal.SIGTERM)}
    def docker(*args,input=None):
        r=subprocess.run(['docker',*args],input=input,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180)
        if r.returncode:
            private('docker-error.log',r.stderr)
            raise RuntimeError('DOCKER_OPERATION_FAILED')
        return r.stdout.strip()
    redis='yshop-quality-identity-redis-'+owner
    created=False
    try:
        docker('run','--detach','--name',name,'--label','yshop.quality.owner='+owner,
               '--cap-drop=ALL','--cap-add=CHOWN','--cap-add=DAC_OVERRIDE','--cap-add=SETUID','--cap-add=SETGID','--cap-add=FOWNER','--security-opt','no-new-privileges','--publish','127.0.0.1::3306',
               '--mount','type=bind,source='+str(root_secret)+',target=/run/secrets/root,readonly',
               '--mount','type=bind,source='+str(client)+',target=/run/secrets/client.cnf,readonly',
               '--env','MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root','mysql:8.0','--log-bin-trust-function-creators=1')
        created=True
        for _ in range(120):
            try:
                docker('exec',name,'mysql','--defaults-extra-file=/run/secrets/client.cnf','--protocol=TCP','-h','127.0.0.1','-N','-e','SELECT 1');break
            except RuntimeError:
                if json.loads(docker('inspect',name))[0]['State']['Status']=='exited':raise RuntimeError('MYSQL_CONTAINER_EXITED')
                time.sleep(1)
        else:raise RuntimeError('MYSQL_START_TIMEOUT')
        binding=json.loads(docker('inspect',name))[0]['NetworkSettings']['Ports']['3306/tcp'][0]
        if binding['HostIp']!='127.0.0.1':raise RuntimeError('NON_LOOPBACK_DATABASE')
        port=int(binding['HostPort'])
        docker('exec','-i',name,'mysql','--defaults-extra-file=/run/secrets/client.cnf',input=
               'CREATE DATABASE '+schema+'; CREATE USER '+user+"@'%' IDENTIFIED BY '"+password+"'; GRANT ALL ON "+schema+".* TO "+user+"@'%';")
        ddl=(checkout/'yshop-drink-boot3/sql/yixiang-drink-open.sql').read_text()
        tables=re.findall(r'CREATE TABLE `(?:yshop_|system_)[^`]+` \(.*?\) ENGINE=.*?;',ddl,re.S)
        if not tables:raise RuntimeError('CANONICAL_DDL_MISSING')
        docker('exec','-i',name,'mysql','--defaults-extra-file=/run/secrets/client.cnf',schema,input='\n'.join(tables))
        for migration in ['2026-10-03-wallet-ledger.sql','2026-10-11-member-identity.sql',
                          '2026-10-22-order-fulfillment.sql']:
            docker('exec','-i',name,'mysql','--defaults-extra-file=/run/secrets/client.cnf',schema,input=(checkout/'yshop-drink-boot3/sql/migrations'/migration).read_text())
        redis_password=secrets.token_hex(24)
        redis_conf=private('redis.conf','bind 0.0.0.0\nprotected-mode yes\nrequirepass '+redis_password+'\nsave ""\nappendonly no\n')
        docker('run','-d','--name',redis,'--label','yshop.quality.owner='+owner,'--user',str(os.getuid())+':'+str(os.getgid()),'--cap-drop=ALL','--security-opt','no-new-privileges','-p','127.0.0.1::6379','--mount','type=bind,source='+str(redis_conf)+',target=/run/redis.conf,readonly','redis:7.4','redis-server','/run/redis.conf')
        rbind=json.loads(docker('inspect',redis))[0]['NetworkSettings']['Ports']['6379/tcp'][0]
        if rbind['HostIp']!='127.0.0.1':raise RuntimeError('NON_LOOPBACK_REDIS')
        config=private('jdbc.properties' ,'url=jdbc:mysql://127.0.0.1:'+str(port)+'/'+schema+'?useSSL=false&allowPublicKeyRetrieval=true\nusername='+user+'\npassword='+password+'\nredisPort='+rbind['HostPort']+'\nredisPassword=' + redis_password + '\nrepo='+str(checkout)+'\n')
        env=os.environ.copy();env['YSHOP_IDENTITY_ACCEPTANCE_CONFIG']=str(config)
        expected={k:v for k,v in manifest(REPO).items() if k.endswith('.MemberIdentityMysqlAcceptanceTest')}
        if len(expected)!=1:raise RuntimeError('AUTH_SUITE_MISSING')
        suite,_,method=a.tests.partition('#')
        if suite!='MemberIdentityMysqlAcceptanceTest':raise RuntimeError('IDENTITY_SUITE_REQUIRED')
        if method:
            expected={k:{n:c for n,c in cases.items() if n.split('(',1)[0]==method} for k,cases in expected.items()}
            if any(not cases for cases in expected.values()):raise RuntimeError('UNREGISTERED_IDENTITY_TEST')
        ev=Evidence(root/'evidence');maven=env.get('YSHOP_MAVEN',shutil.which('mvn') or 'mvn')
        # Compile every dependency from this checkout; do not test stale installed artifacts.
        # Upstream modules have no matching server test. Exact server discovery is checked by Evidence.
        command=[maven,'-pl','yshop-server','-am','-Pmysql-acceptance','test','-Dtest='+a.tests,'-Dsurefire.failIfNoSpecifiedTests=false']
        if env.get('YSHOP_MAVEN_REPOSITORY'):command+=['-Dmaven.repo.local='+env['YSHOP_MAVEN_REPOSITORY']]
        command+=ev.arguments(expected)
        mod=checkout/'yshop-drink-boot3'
        code=execute(command,mod,env,root/'maven.log',1800);report['diagnostics']=ev.diagnostics()
        if checkout_digest(checkout)!=tested_digest:raise RuntimeError('CHECKOUT_CHANGED_DURING_TEST')
        if code:raise RuntimeError('AUTH_MYSQL_TEST_FAILED')
        report['evidence']=ev.validate()
        rows=docker('exec',name,'mysql','--defaults-extra-file=/run/secrets/client.cnf','-N','-e',
                    "SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA='"+schema+"'").splitlines()
        expected_tables={re.search(r'CREATE TABLE `([^`]+)`',table).group(1) for table in tables}
        expected_tables.update({'yshop_member_wallet_transaction','yshop_member_recharge_order',
                                'yshop_order_fulfillment_log'})
        engines=dict(row.split('\t') for row in rows)
        report['schemaInventory']={'expected':sorted(expected_tables),'observed':sorted(engines)}
        if set(engines)!=expected_tables or set(engines.values())!={'InnoDB'}:raise RuntimeError('INNODB_SCHEMA_REQUIRED')
        report['mysqlVersion']=docker('exec',name,'mysql','--defaults-extra-file=/run/secrets/client.cnf','-N','-e','SELECT VERSION()')
        report['innodbTables']=len(rows);report['result']='PASS'
    except (Exception,ControlledInterrupted) as exc:
        report['reasonType']=type(exc).__name__;report['reason']=str(exc) if re.fullmatch(r'[A-Z_]{3,80}',str(exc)) else 'AUTH_CONTROLLED_STEP_FAILED'
    finally:
        for sig in previous:signal.signal(sig,signal.SIG_IGN)
        try:
            report['resources']=remove_owned(docker,[name,redis],owner)
            report['cleanup']='PASS'
        except Exception:
            report['cleanup']='FAIL';report['result']='FAIL';report['cleanupReason']='OWNED_RESOURCE_CLEANUP_FAILED'
        for f in [root_secret,client,root/'jdbc.properties',root/'redis.conf']:
            f.unlink(missing_ok=True)
        (root/'report.json').write_text(json.dumps(report,indent=2))
        for sig,handler in previous.items():signal.signal(sig,handler)
    print(json.dumps({'result':report['result'],'cleanup':report['cleanup'],'tests':report.get('evidence',{}).get('tests',0)}))
    return 0 if report['result']=='PASS' and report['cleanup']=='PASS' else 1
if __name__=='__main__':sys.exit(main())
