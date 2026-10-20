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
        def sql(query):
            return docker('exec',name,'mysql','--defaults-extra-file=/run/secrets/client.cnf','-N','--database='+schema,'-e',query)
        migration=(REPO/'yshop-drink-boot3/sql/migrations/2026-10-19-score-submission.sql').read_text()
        def apply_migration(rejected=None):
            run=subprocess.run(['docker','exec','-i',name,'mysql','--defaults-extra-file=/run/secrets/client.cnf','--database='+schema],
                               input=migration,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60)
            if rejected:
                if run.returncode==0 or rejected not in run.stderr:raise RuntimeError('MIGRATION_EXPECTED_REJECTION_MISSING')
            elif run.returncode:raise RuntimeError('MIGRATION_EXECUTION_FAILED')
        legacy_tables=['yshop_score_order','yshop_score_product','yshop_user','yshop_user_address','yshop_user_bill']
        baseline=(REPO/'yshop-drink-boot3/sql/yixiang-drink-open.sql').read_text()
        for table in legacy_tables:
            ddl=re.search(r'CREATE TABLE `'+table+r'`.*?;',baseline,re.S)
            if not ddl:raise RuntimeError('MIGRATION_LEGACY_DDL_MISSING')
            sql(ddl.group())
        sql("INSERT INTO yshop_user(id,integral,deleted)VALUES(81,123.45,0)")
        sql("INSERT INTO yshop_score_order(id,uid,order_id,product_id,number,score,total_score)VALUES(81,81,'legacy-score-intact',99,2,25,50)")
        def history():
            return [sql('SELECT * FROM '+table+' ORDER BY id') for table in legacy_tables]
        before=history();migration_cases=[]
        # Missing prerequisites must reject before the new table is created.
        sql('RENAME TABLE yshop_score_product TO score_product_temporarily_missing')
        apply_migration('SCORE_SUBMISSION_LEGACY_SCHEMA_REQUIRED')
        if sql("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_score_submission'")!='0':raise RuntimeError('MIGRATION_MUTATED_BEFORE_PREFLIGHT')
        sql('RENAME TABLE score_product_temporarily_missing TO yshop_score_product');migration_cases.append('missing_legacy_rejected_before_create')
        apply_migration();migration_cases.append('first_apply')
        if sql('SELECT COUNT(*) FROM yshop_score_submission')!='0':raise RuntimeError('MIGRATION_BACKFILLED_HISTORY')
        sql("INSERT INTO yshop_score_submission(uid,idempotency_key,request_hash,order_id)VALUES(81,'migration_repeat_key_81',REPEAT('a',64),'legacy-score-intact')")
        repeated=sql('SELECT * FROM yshop_score_submission');apply_migration()
        if repeated!=sql('SELECT * FROM yshop_score_submission'):raise RuntimeError('MIGRATION_CHANGED_COMMITTED_KEYS')
        migration_cases.append('repeat_preserves_keys_and_history')
        mutations={
            'missing_unique':'ALTER TABLE yshop_score_submission DROP INDEX uk_score_submission_order',
            'wrong_key_length':'ALTER TABLE yshop_score_submission MODIFY idempotency_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL',
            'wrong_collation':'ALTER TABLE yshop_score_submission MODIFY idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL',
            'nullable_hash':'ALTER TABLE yshop_score_submission MODIFY request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL',
            'extra_column':'ALTER TABLE yshop_score_submission ADD incompatible INT',
            'wrong_engine':'ALTER TABLE yshop_score_submission ENGINE=MyISAM',
            'extra_prefix_unique':'ALTER TABLE yshop_score_submission ADD UNIQUE KEY incompatible(request_hash(8))',
        }
        for label,mutation in mutations.items():
            sql(mutation);shape=sql('SHOW CREATE TABLE yshop_score_submission');rows=sql('SELECT * FROM yshop_score_submission')
            apply_migration('SCORE_SUBMISSION_SCHEMA_MISMATCH')
            if shape!=sql('SHOW CREATE TABLE yshop_score_submission') or rows!=sql('SELECT * FROM yshop_score_submission') or before!=history():raise RuntimeError('MIGRATION_REJECTION_MUTATED_DATA')
            migration_cases.append(label+'_rejected_without_mutation')
            sql('DROP TABLE yshop_score_submission');apply_migration()
        if before!=history():raise RuntimeError('MIGRATION_CHANGED_HISTORY')
        report['migrationAcceptance']={'result':'PASS','cases':migration_cases,'legacyDataUnchanged':True,'noHistoricalBackfill':True}
        env=os.environ.copy();env['YSHOP_SCORE_ACCEPTANCE_CONFIG']=str(config)
        expected={k:v for k,v in manifest(REPO).items() if k.rsplit('.',1)[-1] in {'ScoreOrderDatabaseTest','ScoreSubmissionDatabaseTest','ScoreReceiptConcurrencyDatabaseTest'}}
        if len(expected)!=3:raise RuntimeError('SCORE_SUITE_MISSING')
        ev=Evidence(root/'evidence');maven=env.get('YSHOP_MAVEN',shutil.which('mvn') or 'mvn')
        command=[maven,'-pl','yshop-server','-am','-Pmysql-acceptance','test','-Dtest=ScoreOrderDatabaseTest,ScoreSubmissionDatabaseTest,ScoreReceiptConcurrencyDatabaseTest','-Dsurefire.failIfNoSpecifiedTests=false']
        if env.get('YSHOP_MAVEN_REPOSITORY'):command+=['-Dmaven.repo.local='+env['YSHOP_MAVEN_REPOSITORY']]
        command+=ev.arguments(expected)
        mod=REPO/'yshop-drink-boot3'
        code=execute(command,mod,env,root/'maven.log',1800);report['diagnostics']=ev.diagnostics()
        if code:raise RuntimeError('SCORE_MYSQL_TEST_FAILED')
        report['evidence']=ev.validate()
        rows=docker('exec',name,'mysql','--defaults-extra-file=/run/secrets/client.cnf','-N','-e',
                    "SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA='"+schema+"'").splitlines()
        engines=dict(row.split('\t') for row in rows)
        expected_tables={'yshop_score_submission','yshop_score_order','yshop_score_product','yshop_user','yshop_user_address','yshop_user_bill','yshop_store_order','yshop_store_order_status'}
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
