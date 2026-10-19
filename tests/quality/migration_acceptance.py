#!/usr/bin/env python3
"""Execute cumulative migrations on one uniquely owned disposable MySQL 8/InnoDB.

No application, provider, payment transport, or shared database is used. Identity
negative contracts already covered by MemberIdentityMysqlAcceptanceTest are
referenced, not reimplemented; the aggregate gate must validate that independent
receipt. This runner adds the missing tenant/configuration migration boundaries.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import sys
import time
import uuid

from evidence import source_identity
from owned_resources import remove_owned

REPO = Path(__file__).resolve().parents[2]
MIGRATIONS = ('2026-10-08-coupon-marketing.sql', '2026-10-08-coupon-permissions.sql',
              '2026-10-11-member-identity.sql')
MIGRATION_CHECKS = (
    'mysql8-innodb-legacy-fixture', 'cumulative-first-apply',
    'complete-schema-and-permissions', 'historical-rights-uid-assets-orders-grants-preserved',
    'complete-rerun-idempotent', 'partial-template-rejected-without-repair',
    'partial-instance-rejected-before-template-ddl', 'partial-table-rejected-without-repair',
    'wrong-complete-column-shape-rejected', 'wrong-complete-index-shape-rejected',
    'missing-query-permission-rejected', 'missing-menu-ancestor-rejected',
    'ambiguous-query-permission-rejected', 'type2-query-permission-supported',
    'disabled-menu-ancestor-rejected', 'identity-cross-tenant-rejected-before-mutation',
    'identity-config-conflict-rejected-before-mutation')
IDENTITY_REUSE = ('migrationRejectsWhitespaceIdentityBeforeAnyMutation',
    'migrationRejectsCanonicalPhoneCollisionIncludingDeletedMember',
    'migrationRejectsDuplicateProviderBeforeChangingAnyMember',
    'migrationRejectsPartialValueIndexBeforeMutation',
    'migrationKeepsUidAssetsAndOrdersAndSnapshotRestoresOriginalIdentity')
NEW_COLUMNS = {
    'yshop_coupon': ['template_version','claim_start_time','claim_end_time','coupon_kind','claim_mode','redemption_code_hash'],
    'yshop_coupon_user': ['template_version','redeemed_order_id','redeemed_at','invalid_reason'],
    'yshop_coupon_claim': ['user_id','request_key','coupon_id','coupon_user_id','claim_mode','request_hash','create_time'],
    'yshop_coupon_newcomer': ['user_id','coupon_id','coupon_user_id','registered_at','create_time'],
    'yshop_coupon_operation': ['event_key','coupon_id','coupon_user_id','order_id','actor_id','actor_type','kind','reason','create_time']}
NEW_INDEXES = {
    ('yshop_coupon','uk_coupon_code_hash'): (0,['redemption_code_hash']),
    ('yshop_coupon_user','idx_coupon_claim_limit'): (1,['coupon_id','user_id']),
    ('yshop_coupon_user','idx_coupon_redemption'): (1,['redeemed_order_id']),
    ('yshop_coupon_claim','PRIMARY'): (0,['user_id','request_key']),
    ('yshop_coupon_claim','uk_claim_instance'): (0,['coupon_user_id']),
    ('yshop_coupon_newcomer','PRIMARY'): (0,['user_id']),
    ('yshop_coupon_operation','PRIMARY'): (0,['event_key']),
    ('yshop_coupon_operation','idx_coupon_operation'): (1,['coupon_id','create_time']),
    ('yshop_user','uk_member_mobile'): (0,['mobile']),
    ('yshop_user','uk_member_openid'): (0,['openid']),
    ('yshop_user','uk_member_routine_openid'): (0,['routine_openid'])}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--run-id', default=None)
    args = parser.parse_args()
    run_id = args.run_id or uuid.uuid4().hex
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,96}', run_id):
        parser.error('run-id must contain 1-96 ASCII letters, digits, underscores or hyphens')
    root = args.output.resolve()
    root.mkdir(parents=True, mode=0o700, exist_ok=False)
    os.chmod(root, 0o700)
    owner = uuid.uuid4().hex
    name = 'yshop-quality-migrations-' + owner
    schema = 'quality_migrations_' + owner[:16]
    identity = source_identity()
    report = {'schemaVersion':1, 'runId':run_id, 'owner':owner, **identity,
              'startedAt':time.time(), 'complete':False, 'result':'FAIL',
              'testedMigrationDigests':{m:hashlib.sha256((REPO/'yshop-drink-boot3/sql/migrations'/m).read_bytes()).hexdigest() for m in MIGRATIONS},
              'cleanup':'NOT_RUN', 'checks':[], 'migrations':list(MIGRATIONS),
              'identityContractReuse':{'status':'EXTERNAL_REQUIRED',
                  'runner':'tests/quality/member-score-mysql.py',
                  'suite':'MemberIdentityMysqlAcceptanceTest', 'requiredTests':list(IDENTITY_REUSE)},
              'boundaries':{'ownedDisposableServicesOnly':True, 'providerCalls':0, 'financialTransportCalls':0}}

    def private(filename, content):
        path = root / filename
        path.write_text(content)
        path.chmod(0o600)
        return path

    password = secrets.token_hex(24)
    secret = private('root.secret', password)
    client = private('client.cnf', '[client]\nuser=root\npassword='+password+'\ndefault-character-set=utf8mb4\n')
    sequence = 0
    def docker(*command, input=None, allow_failure=False):
        nonlocal sequence
        result = subprocess.run(['docker', *command], input=input, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
        if result.returncode and not allow_failure:
            sequence += 1
            private('private-error-'+str(sequence)+'.log', result.stderr)
            raise RuntimeError('DOCKER_OR_MYSQL_OPERATION_FAILED')
        return result if allow_failure else result.stdout.strip()

    def sql(query, db=True, allow_failure=False):
        command = ['exec','-i',name,'mysql','--defaults-extra-file=/run/client.cnf',
                   '--protocol=TCP','-h','127.0.0.1','--batch','--raw','--skip-column-names']
        if db: command.append(schema)
        return docker(*command, input=query, allow_failure=allow_failure)

    ddl = (REPO/'yshop-drink-boot3/sql/yixiang-drink-open.sql').read_text()
    tables = dict(re.findall(r'(CREATE TABLE `((?:yshop_|system_)[^`]+)` \(.*?\) ENGINE=.*?;)', ddl, re.S))
    # Only DDL is extracted. No canonical database records, contacts or credentials are loaded.
    canonical = {table:statement for statement,table in tables.items()}
    required = ['yshop_coupon','yshop_coupon_user','yshop_user','yshop_user_bill',
                'yshop_store_order','system_menu','system_role_menu','system_social_user_bind']
    if any(table not in canonical for table in required):
        raise RuntimeError('CANONICAL_FIXTURE_DDL_MISSING')
    migrations = [(REPO/'yshop-drink-boot3/sql/migrations'/m).read_text() for m in MIGRATIONS]

    def fixture():
        sql('DROP DATABASE IF EXISTS '+schema+'; CREATE DATABASE '+schema+';', db=False)
        sql('\n'.join(canonical[t] for t in required))
        sql("""
INSERT INTO yshop_user(id,username,mobile,openid,routine_openid,now_money,integral,brokerage_price,tenant_id,deleted)
VALUES (101,'synthetic-101','13800000001','SyntheticCase','SyntheticMini',123.45,67.89,10.25,0,0),
       (102,'synthetic-102','13800000002','syntheticcase','syntheticmini',9.50,11,2,0,1);
INSERT INTO yshop_coupon(id,title,least,value,start_time,end_time,create_time,update_time,instructions,exchange_code,receive,distribute)
VALUES(201,'Synthetic legacy rights',10,2,'2025-01-01','2025-02-01','2025-01-01','2025-01-02','Synthetic only','synthetic-legacy-code',2,10);
INSERT INTO yshop_coupon_user(id,title,least,value,start_time,end_time,user_id,coupon_id,status,deleted)
VALUES(301,'Synthetic unused',10,2,'2025-01-01','2025-02-01',101,201,0,0),
      (302,'Synthetic consumed',10,2,'2025-01-01','2025-02-01',102,201,1,1);
INSERT INTO yshop_store_order(id,order_id,uid,pay_type,mark,cost,create_time,paid,status,coupon_id,coupon_price,pay_price)
VALUES(401,'synthetic-paid-legacy',101,'synthetic','Synthetic only',8,'2025-01-02',1,2,302,2,8),
      (402,'synthetic-unpaid-legacy',102,'synthetic','Synthetic only',3,'2025-01-03',0,0,0,0,3);
INSERT INTO yshop_user_bill(id,uid,title,number,balance,create_time)
VALUES(501,101,'Synthetic historical asset evidence',12.50,123.45,'2025-01-02');
INSERT INTO system_menu(id,name,permission,type,parent_id) VALUES
(601,'Synthetic ancestor','',2,0),(602,'Synthetic query','coupon::query',3,601),
(603,'Synthetic unrelated','synthetic:query',3,601);
INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,deleted) VALUES
(701,801,602,0,0),(702,802,603,0,1);
""")

    def columns():
        return [json.loads(line) for line in sql("SELECT JSON_ARRAY(TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,COLLATION_NAME,EXTRA,ORDINAL_POSITION) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION").splitlines()]

    def indexes():
        return [json.loads(line) for line in sql("SELECT JSON_ARRAY(TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,SUB_PART,INDEX_TYPE) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX").splitlines()]

    def schema_snapshot():
        return {'columns':columns(),'indexes':indexes(), 'tables':sql("SELECT TABLE_NAME,ENGINE,TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME")}

    def rows_snapshot(selected=None, old_menu=False):
        all_columns = columns()
        selected = selected or {t:[c[1] for c in all_columns if c[0]==t] for t in sorted({c[0] for c in all_columns})}
        return {t:sorted(sql('SELECT JSON_ARRAY('+','.join('HEX(`'+c+'`)' for c in names)+') FROM `'+t+'`'+(' WHERE id IN (601,602,603)' if old_menu and t=='system_menu' else '')).splitlines()) for t,names in selected.items()}

    def check(check_name, action):
        entry = {'name':check_name,'result':'FAIL'}
        try:
            details = action()
            entry.update(result='PASS', details=details or {})
        except Exception as error:
            entry['reason'] = str(error) if re.fullmatch(r'[A-Z_]{3,100}', str(error)) else 'MIGRATION_ASSERTION_FAILED'
        report['checks'].append(entry)

    def require(condition, reason):
        if not condition: raise AssertionError(reason)

    def reject(change, migration, expected):
        fixture()
        if change: sql(change)
        before_schema, before_rows = schema_snapshot(), rows_snapshot()
        result = sql(migrations[migration], allow_failure=True)
        matched = result.returncode != 0 and expected in result.stderr
        unchanged = before_schema == schema_snapshot() and before_rows == rows_snapshot()
        require(matched, 'EXPECTED_EXPLICIT_REJECTION_MISSING')
        require(unchanged, 'REJECTION_MODIFIED_SCHEMA_OR_HISTORY')
        return {'mysqlExit':result.returncode,'errorCode':expected,'schemaUnchanged':True,
                'historyUnchanged':True,'beforeSchemaDigest':digest(before_schema),'beforeRowsDigest':digest(before_rows)}

    class ControlledInterrupted(BaseException): pass
    def interrupted(signum, frame): raise ControlledInterrupted()
    previous = {s:signal.signal(s,interrupted) for s in (signal.SIGTERM,signal.SIGINT)}
    try:
        docker('run','-d','--name',name,'--label','yshop.quality.owner='+owner,
            '--cap-drop=NET_RAW','--security-opt','no-new-privileges',
            '--mount','type=bind,source='+str(secret)+',target=/run/root.secret,readonly',
            '--mount','type=bind,source='+str(client)+',target=/run/client.cnf,readonly',
            '-e','MYSQL_ROOT_PASSWORD_FILE=/run/root.secret','mysql:8.0')
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            if sql('SELECT 1',db=False,allow_failure=True).returncode==0: break
            if not json.loads(docker('inspect',name))[0]['State']['Running']: raise RuntimeError('MYSQL_EXITED')
            time.sleep(1)
        else: raise RuntimeError('MYSQL_START_TIMEOUT')
        version=sql('SELECT VERSION()',db=False)
        require(version.startswith('8.'),'MYSQL_EIGHT_REQUIRED')
        report['mysqlVersion']=version;report['engine']='InnoDB'
        fixture()
        original_columns = {t:[c[1] for c in columns() if c[0]==t] for t in required}
        baseline = rows_snapshot(original_columns)
        baseline_schema = schema_snapshot()
        check('mysql8-innodb-legacy-fixture',lambda:require(set(line.split('\t')[1] for line in baseline_schema['tables'].splitlines())=={'InnoDB'},'INNODB_REQUIRED'))
        def apply():
            for migration in migrations: sql(migration)
            return {'applied':list(MIGRATIONS)}
        check('cumulative-first-apply',apply)
        def complete_schema():
            observed_columns=columns()
            types={'template_version':'bigint','claim_start_time':'datetime','claim_end_time':'datetime',
                'coupon_kind':'varchar(16)','claim_mode':'varchar(16)','redemption_code_hash':'char(64)',
                'redeemed_order_id':'varchar(32)','redeemed_at':'datetime','invalid_reason':'varchar(200)',
                'user_id':'bigint','request_key':'varchar(64)','coupon_id':'bigint','coupon_user_id':'bigint',
                'request_hash':'char(64)','create_time':'datetime','registered_at':'datetime',
                'event_key':'varchar(96)','order_id':'varchar(32)','actor_id':'bigint','actor_type':'varchar(16)',
                'kind':'varchar(24)','reason':'varchar(200)'}
            nullable={('yshop_coupon',n) for n in ['claim_start_time','claim_end_time','redemption_code_hash']}|{('yshop_coupon_user',n) for n in ['redeemed_order_id','redeemed_at','invalid_reason']}|{('yshop_coupon_operation',n) for n in ['coupon_user_id','order_id','actor_id']}
            defaults={('yshop_coupon','coupon_kind'):'REGULAR',('yshop_coupon','claim_mode'):'PUBLIC'}
            for t,names in NEW_COLUMNS.items():
                for n in names:
                    row=next(c for c in observed_columns if c[:2]==[t,n])
                    default='0' if n=='template_version' else ('CURRENT_TIMESTAMP' if n=='create_time' else defaults.get((t,n)))
                    actual_default=row[4].upper().replace('()','') if row[4] and n=='create_time' else row[4]
                    require(row[2:4]==[types[n],'YES' if (t,n) in nullable else 'NO'] and actual_default==default,'COUPON_COLUMN_SHAPE_MISMATCH')
                    if n in ['request_key','event_key','redemption_code_hash']:require(row[5]=='ascii_bin','COUPON_ASCII_COLLATION_REQUIRED')
            original_survivors=[c for c in observed_columns if c[0] in original_columns and c[1] in original_columns[c[0]] and not(c[0]=='yshop_user' and c[1] in ['mobile','openid','routine_openid'])]
            original_expected=[c for c in baseline_schema['columns'] if not(c[0]=='yshop_user' and c[1] in ['mobile','openid','routine_openid'])]
            require(original_survivors==original_expected,'LEGACY_COLUMNS_CHANGED')
            for t,names in NEW_COLUMNS.items():
                added=[c[1] for c in observed_columns if c[0]==t and (t not in original_columns or c[1] not in original_columns[t])]
                require(added==names,'COLUMN_INVENTORY_MISMATCH')
            observed_indexes=indexes()
            for (t,n),(unique,names) in NEW_INDEXES.items():
                rows=[r for r in observed_indexes if r[:2]==[t,n]]
                require([r[4] for r in rows]==names and all(r[2]==unique and r[5] is None and r[6]=='BTREE' for r in rows),'INDEX_SHAPE_MISMATCH')
            unchanged_indexes=[r for r in observed_indexes if (r[0],r[1]) not in NEW_INDEXES]
            require(unchanged_indexes==baseline_schema['indexes'],'LEGACY_OR_UNEXPECTED_INDEX_CHANGE')
            require(set(line.split('\t')[0] for line in schema_snapshot()['tables'].splitlines())==set(required)|set(NEW_COLUMNS)-{'yshop_coupon','yshop_coupon_user'},'TABLE_INVENTORY_MISMATCH')
            require(sql("SELECT GROUP_CONCAT(CONCAT(permission,':',parent_id) ORDER BY permission) FROM system_menu WHERE id NOT IN (601,602,603)")==','.join(p+':601' for p in ['coupon:user:delete','coupon:user:export','coupon:user:query']),'PERMISSION_INVENTORY_MISMATCH')
            require(sql("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND ENGINE<>'InnoDB'")=='0','INNODB_REQUIRED')
            for col,kind in [('mobile','char(11)'),('openid','varchar(100)'),('routine_openid','varchar(100)')]:
                require(any(r[0]=='yshop_user' and r[1]==col and r[2]==kind and r[3]=='YES' and r[5]=='ascii_bin' for r in observed_columns),'IDENTITY_COLUMN_SHAPE_MISMATCH')
            return {'schemaDigest':digest(schema_snapshot()),'tableCount':len(required)+3,'indexesChecked':len(NEW_INDEXES)}
        check('complete-schema-and-permissions',complete_schema)
        def history():
            after=rows_snapshot(original_columns,old_menu=True)
            require(after==baseline,'LEGACY_HISTORY_CHANGED')
            return {'beforeDigest':digest(baseline),'afterDigest':digest(after),'tables':required}
        check('historical-rights-uid-assets-orders-grants-preserved',history)
        def rerun():
            sql("INSERT INTO yshop_coupon_claim(user_id,request_key,coupon_id,coupon_user_id,claim_mode,request_hash) VALUES(101,'synthetic-request',201,301,'PUBLIC',REPEAT('a',64)); INSERT INTO yshop_coupon_newcomer(user_id,coupon_id,coupon_user_id,registered_at) VALUES(101,201,301,'2025-01-01'); INSERT INTO yshop_coupon_operation(event_key,coupon_id,coupon_user_id,actor_type,kind,reason) VALUES('synthetic-event',201,301,'MEMBER','CLAIM','synthetic only')")
            before=schema_snapshot(),rows_snapshot()
            apply()
            require(before==(schema_snapshot(),rows_snapshot()),'RERUN_CHANGED_SCHEMA_OR_ROWS')
            return {'schemaDigest':digest(before[0]),'rowsDigest':digest(before[1]),'applications':2}
        check('complete-rerun-idempotent',rerun)
        check('partial-template-rejected-without-repair',lambda:reject('ALTER TABLE yshop_coupon ADD template_version BIGINT NOT NULL DEFAULT 0',0,'COUPON_SCHEMA_PARTIAL_OR_MISMATCH'))
        check('partial-instance-rejected-before-template-ddl',lambda:reject('ALTER TABLE yshop_coupon_user ADD template_version BIGINT NOT NULL DEFAULT 0',0,'COUPON_SCHEMA_PARTIAL_OR_MISMATCH'))
        check('partial-table-rejected-without-repair',lambda:reject('CREATE TABLE yshop_coupon_claim(user_id BIGINT PRIMARY KEY) ENGINE=InnoDB',0,'COUPON_SCHEMA_PARTIAL_OR_MISMATCH'))
        def wrong_shape(change):
            fixture(); sql(migrations[0]);sql(change)
            before=schema_snapshot(),rows_snapshot()
            result=sql(migrations[0],allow_failure=True)
            require(result.returncode!=0 and 'COUPON_SCHEMA_PARTIAL_OR_MISMATCH' in result.stderr,'EXPECTED_EXPLICIT_REJECTION_MISSING')
            require(before==(schema_snapshot(),rows_snapshot()),'REJECTION_MODIFIED_SCHEMA_OR_HISTORY')
            return {'schemaUnchanged':True,'historyUnchanged':True,'errorCode':'COUPON_SCHEMA_PARTIAL_OR_MISMATCH'}
        check('wrong-complete-column-shape-rejected',lambda:wrong_shape('ALTER TABLE yshop_coupon MODIFY template_version INT NOT NULL DEFAULT 0'))
        check('wrong-complete-index-shape-rejected',lambda:wrong_shape('ALTER TABLE yshop_coupon DROP INDEX uk_coupon_code_hash, ADD KEY uk_coupon_code_hash(redemption_code_hash(8))'))
        check('missing-query-permission-rejected',lambda:reject('DELETE FROM system_menu WHERE id=602',1,'COUPON_PERMISSION_ANCESTOR_REQUIRED'))
        check('missing-menu-ancestor-rejected',lambda:reject('DELETE FROM system_menu WHERE id=601',1,'COUPON_PERMISSION_ANCESTOR_REQUIRED'))
        check('ambiguous-query-permission-rejected',lambda:reject("INSERT INTO system_menu(id,name,permission,type,parent_id) VALUES(604,'synthetic duplicate','coupon::query',3,601)",1,'COUPON_PERMISSION_ANCESTOR_REQUIRED'))
        def type2_permission():
            fixture();sql('UPDATE system_menu SET type=2 WHERE id=602')
            sql(migrations[1]);sql(migrations[1])
            require(sql("SELECT COUNT(*) FROM system_menu WHERE permission IN ('coupon:user:query','coupon:user:export','coupon:user:delete') AND parent_id=601")=='3','TYPE_TWO_PERMISSION_PARENT_MISMATCH')
            return {'queryType':2,'descriptors':3,'rerunIdempotent':True}
        check('type2-query-permission-supported',type2_permission)
        check('disabled-menu-ancestor-rejected',lambda:reject('UPDATE system_menu SET status=1 WHERE id=601',1,'COUPON_PERMISSION_ANCESTOR_REQUIRED'))
        check('identity-cross-tenant-rejected-before-mutation',lambda:reject('UPDATE yshop_user SET tenant_id=1 WHERE id=101',2,'MEMBER_IDENTITY_INVALID_OR_TENANT_CONFLICT'))
        check('identity-config-conflict-rejected-before-mutation',lambda:reject("CREATE TABLE system_social_client(id BIGINT PRIMARY KEY,user_type INT,status INT,deleted BIT,tenant_id BIGINT,social_type INT,client_id VARCHAR(100)) ENGINE=InnoDB; INSERT INTO system_social_client VALUES(1,1,0,0,0,34,'synthetic-unverified-appid')",2,'MEMBER_APPID_CONFIGURATION_REQUIRES_VERIFICATION'))
        report['complete']=[c['name'] for c in report['checks']]==list(MIGRATION_CHECKS)
        if report['complete'] and all(c['result']=='PASS' for c in report['checks']):report['result']='PASS'
    except (Exception,ControlledInterrupted) as error:
        report['reason']=str(error) if re.fullmatch(r'[A-Z_]{3,100}',str(error)) else 'MIGRATION_CONTROLLED_FAILURE'
    finally:
        for s in previous: signal.signal(s,signal.SIG_IGN)
        try:
            report['resources']=remove_owned(docker,[name],owner)
            report['cleanup']='PASS'
        except Exception:
            report['cleanup']='FAIL';report['result']='FAIL';report['cleanupReason']='OWNED_RESOURCE_CLEANUP_FAILED'
        for file in (secret,client):file.unlink(missing_ok=True)
        report['sourceUnchanged']=source_identity()==identity
        if not report['sourceUnchanged']:report['result']='FAIL'
        report['endedAt']=time.time()
        private('report.json',json.dumps(report,indent=2))
        for s,handler in previous.items():signal.signal(s,handler)
    print(json.dumps({k:report[k] for k in ['runId','result','cleanup','sourceUnchanged','complete']}))
    return 0 if report['result']=='PASS' and report['cleanup']=='PASS' else 1

if __name__=='__main__':sys.exit(main())
