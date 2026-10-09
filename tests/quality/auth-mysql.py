#!/usr/bin/env python3
"""Own a disposable Docker MySQL; verify auth lifecycle with no private service dependency."""
import argparse, json, os, secrets, shutil, subprocess, sys, time, uuid
from pathlib import Path
from evidence import Evidence, execute, manifest, source_identity
from owned_resources import remove_owned

REPO=Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',required=True);a=p.parse_args()
    root=Path(a.output).resolve();root.mkdir(parents=True,mode=0o700,exist_ok=False)
    owner=uuid.uuid4().hex;name='yshop-quality-auth-'+owner
    schema='yshop_quality_auth_'+owner[:16];user='qa_auth_'+owner[:16]
    password=secrets.token_hex(24);root_password=secrets.token_hex(24)
    def private(name,contents):
        path=root/name;path.write_text(contents);path.chmod(0o600);return path
    root_secret=private('root.secret',root_password)
    client=private('client.cnf','[client]\nuser=root\npassword='+root_password+'\n')
    report={'owner':owner,'result':'FAIL','cleanup':'NOT_RUN',**source_identity()}
    def docker(*args,input=None):
        r=subprocess.run(['docker',*args],input=input,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180)
        if r.returncode:raise RuntimeError('DOCKER_OPERATION_FAILED')
        return r.stdout.strip()
    created=False
    try:
        docker('run','--detach','--name',name,'--label','yshop.quality.owner='+owner,
               '--cap-drop=ALL','--cap-add=CHOWN','--cap-add=DAC_OVERRIDE','--cap-add=SETUID','--cap-add=SETGID','--cap-add=FOWNER','--security-opt','no-new-privileges','--publish','127.0.0.1::3306',
               '--mount','type=bind,source='+str(root_secret)+',target=/run/secrets/root,readonly',
               '--mount','type=bind,source='+str(client)+',target=/run/secrets/client.cnf,readonly',
               '--env','MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root','mysql:8.0')
        created=True
        for _ in range(120):
            try:
                docker('exec',name,'mysql','--defaults-extra-file=/run/secrets/client.cnf','-N','-e','SELECT 1');break
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
        env=os.environ.copy();env['YSHOP_AUTH_ACCEPTANCE_CONFIG']=str(config)
        expected={k:v for k,v in manifest(REPO).items() if k.endswith('.OAuth2LifecycleDatabaseTest')}
        if len(expected)!=1:raise RuntimeError('AUTH_SUITE_MISSING')
        ev=Evidence(root/'evidence');maven=env.get('YSHOP_MAVEN',shutil.which('mvn') or 'mvn')
        command=[maven,'-Pmysql-acceptance','test','-Dtest=OAuth2LifecycleDatabaseTest','-Dsurefire.failIfNoSpecifiedTests=true']
        if env.get('YSHOP_MAVEN_REPOSITORY'):command+=['-Dmaven.repo.local='+env['YSHOP_MAVEN_REPOSITORY']]
        command+=ev.arguments(expected)
        mod=REPO/'yshop-drink-boot3/yshop-module-system/yshop-module-system-biz'
        code=execute(command,mod,env,root/'maven.log',1800);report['diagnostics']=ev.diagnostics()
        if code:raise RuntimeError('AUTH_MYSQL_TEST_FAILED')
        report['evidence']=ev.validate()
        rows=docker('exec',name,'mysql','--defaults-extra-file=/run/secrets/client.cnf','-N','-e',
                    "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA='"+schema+"'").splitlines()
        if len(rows)!=4 or set(rows)!={'InnoDB'}:raise RuntimeError('INNODB_SCHEMA_REQUIRED')
        report['mysqlVersion']=docker('exec',name,'mysql','--defaults-extra-file=/run/secrets/client.cnf','-N','-e','SELECT VERSION()')
        report['innodbTables']=len(rows);report['result']='PASS'
    except Exception as exc:
        report['reason']=str(exc) if isinstance(exc,RuntimeError) else type(exc).__name__
    finally:
        try:
            report['resources']=remove_owned(docker,[name],owner)
            report['cleanup']='PASS'
        except Exception:
            report['cleanup']='FAIL';report['result']='FAIL';report['cleanupReason']='OWNED_RESOURCE_CLEANUP_FAILED'
        for f in [root_secret,client,root/'jdbc.properties']:
            f.unlink(missing_ok=True)
        (root/'report.json').write_text(json.dumps(report,indent=2))
    print(json.dumps({'result':report['result'],'cleanup':report['cleanup'],'tests':report.get('evidence',{}).get('tests',0)}))
    return 0 if report['result']=='PASS' and report['cleanup']=='PASS' else 1
if __name__=='__main__':sys.exit(main())
