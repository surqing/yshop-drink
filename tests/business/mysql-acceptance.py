#!/usr/bin/env python3
"""Synthetic multi-store acceptance; dedicated disposable MySQL 8/InnoDB schema only."""
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'tests/quality'))
from evidence import Evidence, execute, manifest, workspace
WORKSPACE = workspace(REPO)
PRIVATE = WORKSPACE / '.local-dev/acceptance'


def main():
    coupon = '--coupon' in sys.argv
    catalog = '--catalog' in sys.argv or coupon
    PRIVATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    PRIVATE.chmod(0o700)
    spec = importlib.util.spec_from_file_location('local_database', WORKSPACE / '.local-dev/database.py')
    db = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(db)
    version = db.mysql('SELECT VERSION();').strip()
    if not re.match(r'^8[.]0[.](?:[3-9][0-9]|29)(?:\D|$)', version):
        raise RuntimeError('SUPPORTED_MYSQL_8_REQUIRED')
    suffix = secrets.token_hex(4)
    schema, account = 'yshop_acceptance_phase6a_' + suffix, 'accept6a_' + suffix
    password = secrets.token_hex(24)
    jdbc = PRIVATE / ('ordering-jdbc-' + suffix + '.properties')
    log = PRIVATE / ('ordering-mysql-' + suffix + '.log')
    schema_created = account_created = False
    receipt = PRIVATE / ("resources-" + suffix + ".json")
    try:
        db.mysql(f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4;")
        schema_created = True
        receipt.write_text(json.dumps({'schema': schema, 'account': account, 'credentialsIncluded': False}))
        receipt.chmod(0o600)
        db.mysql(f"CREATE USER '{account}'@'%' IDENTIFIED BY '{password}';")
        account_created = True
        db.mysql(f"GRANT ALL PRIVILEGES ON `{schema}`.* TO '{account}'@'%';")
        jdbc.write_text(f'url=jdbc:mysql://127.0.0.1:3306/{schema}?useSSL=false&connectTimeout=5000&socketTimeout=30000&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&sessionVariables=innodb_lock_wait_timeout=2\nusername={account}\npassword={password}\n')
        jdbc.chmod(0o600)
        env = os.environ.copy()
        env['JAVA_HOME'] = str(WORKSPACE / '.dev-tools/java-home')
        env['PATH'] = env['JAVA_HOME'] + '/bin' + os.pathsep + env['PATH']
        env['YSHOP_ORDERING_ACCEPTANCE_CONFIG'] = str(jdbc)
        command = [str(WORKSPACE / '.dev-tools/maven/bin/mvn'),
                   '-Dmaven.repo.local=' + str(WORKSPACE / '.local-dev/cache/maven'),
                   '-pl', 'yshop-module-mall/yshop-module-order-biz', '-Pmysql-acceptance', 'test',
                   '-Dtest=' + ('OrderingDatabaseTest,CatalogDatabaseTest,CatalogEditingMysqlAcceptance,CouponDatabaseTest' if coupon else 'OrderingDatabaseTest,CatalogDatabaseTest,CatalogEditingMysqlAcceptance' if catalog else 'OrderingDatabaseTest'), '-Dsurefire.failIfNoSpecifiedTests=true']
        evidence = Evidence(PRIVATE / 'quality')
        selected = next(x.split('=', 1)[1].split(',') for x in command if x.startswith('-Dtest='))
        registered = manifest(REPO)
        expected = {k: v for k, v in registered.items() if k.rsplit('.', 1)[-1] in selected}
        if len(expected) != len(selected):
            raise RuntimeError('UNREGISTERED_TEST_SUITE')
        command += evidence.arguments(expected)
        returncode = execute(command, REPO / 'yshop-drink-boot3', env, log)
        if returncode:
            raise RuntimeError('ORDERING_ACCEPTANCE_FAILED_PRIVATE_LOG_SAVED')
        engines = db.mysql(f"SELECT COUNT(*),SUM(ENGINE='InnoDB') FROM information_schema.TABLES WHERE TABLE_SCHEMA='{schema}';").strip()
        table_count, innodb_count = map(int, engines.split('\t'))
        if table_count != innodb_count or table_count < (20 if catalog else 17):
            raise RuntimeError('INNODB_REQUIRED')
        if coupon:
            required = ['yshop_coupon', 'yshop_coupon_user', 'yshop_coupon_claim', 'yshop_coupon_newcomer', 'yshop_coupon_operation']
            present = db.mysql(f"SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='{schema}';").splitlines()
            if not set(required).issubset(present):
                raise RuntimeError('COUPON_SCHEMA_INCOMPLETE')
        verified = evidence.validate()
        test_count = tests = verified['tests']
        summary = dict(coupon=coupon, tables=table_count, innodbTables=innodb_count, result='PASS', mysql=version, engine='InnoDB', tests=test_count, catalog=catalog, failures=0, errors=0, developmentDatabaseUsed=False, paymentRequests={'value':0,'evidence':'DECLARED_NO_PROVIDER_PATH'}, runId=evidence.id, exactNamesChecked=True, log=str(log))
        report = log.with_suffix('.json')

    finally:
        try:
            if schema_created or account_created:
                assert re.fullmatch(r'yshop_acceptance_phase6a_[a-f0-9]{8}', schema)
                assert re.fullmatch(r'accept6a_[a-f0-9]{8}', account)
                try:
                    if schema_created: db.mysql(f"DROP DATABASE `{schema}`;")
                finally:
                    if account_created: db.mysql(f"DROP USER '{account}'@'%';")
            receipt.unlink(missing_ok=True)
        finally:
            jdbc.unlink(missing_ok=True)
    summary['cleanup'] = 'PASS'
    report.write_text(json.dumps(summary, indent=2) + '\n')
    report.chmod(0o600)
    print(json.dumps(summary))
    return 0


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        import traceback
        print(json.dumps({'result':'FAIL','errorType':type(failure).__name__,'sites':[(f.name,f.lineno) for f in traceback.extract_tb(failure.__traceback__)]}))
        print('Ordering MySQL acceptance failed; inspect private local acceptance logs.', file=sys.stderr)
        sys.exit(1)
