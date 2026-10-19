#!/usr/bin/env python3
"""Real score redemption and receipt concurrency on owned disposable MySQL/InnoDB."""
import argparse, json, os, re, secrets, shutil, signal, subprocess, sys, time, uuid
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "quality"))
from evidence import Evidence, execute, manifest, source_identity
from acceptance_support import supported_mysql
from owned_resources import remove_owned

REPO=Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',required=True);a=p.parse_args()
    root=Path(a.output).resolve()
    if root.is_relative_to(REPO) and not root.is_relative_to(REPO / '.quality'):
        raise RuntimeError('OUTPUT_INSIDE_SOURCE')
    root.mkdir(parents=True,mode=0o700,exist_ok=False)
    owner=uuid.uuid4().hex;name='yshop-quality-score-'+owner
    schema='yshop_quality_score_'+owner[:16];user='qa_score_'+owner[:16]
    password=secrets.token_hex(24);root_password=secrets.token_hex(24)
    def private(name,contents):
        path=root/name;path.write_text(contents);path.chmod(0o600);return path
    root_secret=private('root.secret',root_password)
    client=private('client.cnf','[client]\nuser=root\npassword='+root_password+'\n')
    report={'owner':owner,'runId':owner,'startedAt':time.time(),'complete':False,'sourceUnchanged':False,'result':'FAIL','cleanup':'NOT_RUN',**source_identity()}
    def docker(*args,input=None):
        r=subprocess.run(['docker',*args],input=input,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180)
        if r.returncode:raise RuntimeError('DOCKER_OPERATION_FAILED')
        return r.stdout.strip()
    class ControlledInterrupted(BaseException):pass
    def interrupted(signum,frame):raise ControlledInterrupted("CONTROLLED_INTERRUPTED")
    previous={sig:signal.signal(sig,interrupted) for sig in (signal.SIGINT,signal.SIGTERM)}
    try:
        docker('run','--detach','--name',name,'--label','yshop.quality.owner='+owner,
               '--cap-drop=ALL','--cap-add=CHOWN','--cap-add=DAC_OVERRIDE','--cap-add=SETUID','--cap-add=SETGID','--cap-add=FOWNER','--security-opt','no-new-privileges','--publish','127.0.0.1::3306',
               '--mount','type=bind,source='+str(root_secret)+',target=/run/secrets/root,readonly',
               '--mount','type=bind,source='+str(client)+',target=/run/secrets/client.cnf,readonly',
               '--env','MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root','mysql:8.0')
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
        config=private('jdbc.properties','url=jdbc:mysql://127.0.0.1:'+str(port)+'/'+schema+'?useSSL=false&allowPublicKeyRetrieval=true\nusername='+user+'\npassword='+password+'\n')
        version=docker('exec',name,'mysql','--defaults-extra-file=/run/secrets/client.cnf','-N','-e','SELECT VERSION()')
        if not supported_mysql(version):raise RuntimeError('SUPPORTED_MYSQL_8_REQUIRED')
        env=os.environ.copy();env['YSHOP_SCORE_ACCEPTANCE_CONFIG']=str(config)
        expected={k:v for k,v in manifest(REPO).items() if k.rsplit('.',1)[-1] in {'ScoreOrderDatabaseTest','ScoreReceiptConcurrencyDatabaseTest'}}
        if len(expected)!=2:raise RuntimeError('SCORE_SUITE_MISSING')
        ev=Evidence(root/'evidence');maven=env.get('YSHOP_MAVEN',shutil.which('mvn') or 'mvn')
        command=[maven,'-pl','yshop-server','-am','-Pmysql-acceptance','test','-Dtest=ScoreOrderDatabaseTest,ScoreReceiptConcurrencyDatabaseTest','-Dsurefire.failIfNoSpecifiedTests=false']
        if env.get('YSHOP_MAVEN_REPOSITORY'):command+=['-Dmaven.repo.local='+env['YSHOP_MAVEN_REPOSITORY']]
        command+=ev.arguments(expected)
        mod=REPO/'yshop-drink-boot3'
        code=execute(command,mod,env,root/'maven.log',1800);report['diagnostics']=ev.diagnostics()
        if code:raise RuntimeError('SCORE_MYSQL_TEST_FAILED')
        report['evidence']=ev.validate()
        rows=docker('exec',name,'mysql','--defaults-extra-file=/run/secrets/client.cnf','-N','-e',
                    "SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA='"+schema+"'").splitlines()
        engines=dict(row.split('\t') for row in rows)
        expected_tables={'yshop_score_order','yshop_score_product','yshop_user','yshop_user_address','yshop_user_bill','yshop_store_order','yshop_store_order_status'}
        report['schemaInventory']={'expected':sorted(expected_tables),'observed':sorted(engines)}
        if set(engines)!=expected_tables or set(engines.values())!={'InnoDB'}:raise RuntimeError('INNODB_SCHEMA_REQUIRED')
        report['mysqlVersion']=docker('exec',name,'mysql','--defaults-extra-file=/run/secrets/client.cnf','-N','-e','SELECT VERSION()')
        report['innodbTables']=len(rows)
    except (Exception,ControlledInterrupted) as exc:
        report['reasonType']=type(exc).__name__;report['reason']=str(exc) if re.fullmatch(r'[A-Z_]{3,80}',str(exc)) else 'SCORE_CONTROLLED_STEP_FAILED'
    finally:
        for sig in previous:signal.signal(sig,signal.SIG_IGN)
        try:
            report['resources']=remove_owned(docker,[name],owner)
            report['cleanup']='PASS'
        except Exception:
            report['cleanup']='FAIL';report['result']='FAIL';report['cleanupReason']='OWNED_RESOURCE_CLEANUP_FAILED'
        for f in [root_secret,client,root/'jdbc.properties']:
            f.unlink(missing_ok=True)
        current=source_identity()
        report['sourceUnchanged']=all(report[key]==current[key] for key in ('sourceSha','sourceDigest'))
        report['endedAt']=time.time();report['complete']=True
        if report['cleanup']=='PASS' and report.get('evidence') and report['sourceUnchanged'] and 'reasonType' not in report:
            report['result']='PASS'
        (root/'report.json').write_text(json.dumps(report,indent=2))
        for sig,handler in previous.items():signal.signal(sig,handler)
    print(json.dumps({'result':report['result'],'cleanup':report['cleanup'],'tests':report.get('evidence',{}).get('tests',0)}))
    return 0 if report['result']=='PASS' and report['cleanup']=='PASS' else 1
if __name__=='__main__':sys.exit(main())
