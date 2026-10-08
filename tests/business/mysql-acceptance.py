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
WORKSPACE = REPO.parent
PRIVATE = WORKSPACE / '.local-dev/acceptance'


def main():
    coupon = '--coupon' in sys.argv
    catalog = '--catalog' in sys.argv or coupon
    PRIVATE.mkdir(parents=True, exist_ok=True)
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
    created = False
    try:
        db.mysql(f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4; CREATE USER '{account}'@'%' IDENTIFIED BY '{password}'; GRANT ALL PRIVILEGES ON `{schema}`.* TO '{account}'@'%';")
        created = True
        jdbc.write_text(f'url=jdbc:mysql://127.0.0.1:3306/{schema}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&sessionVariables=innodb_lock_wait_timeout=2\nusername={account}\npassword={password}\n')
        jdbc.chmod(0o600)
        env = os.environ.copy()
        env['JAVA_HOME'] = str(WORKSPACE / '.dev-tools/java-home')
        env['PATH'] = env['JAVA_HOME'] + '/bin' + os.pathsep + env['PATH']
        env['YSHOP_ORDERING_ACCEPTANCE_CONFIG'] = str(jdbc)
        command = [str(WORKSPACE / '.dev-tools/maven/bin/mvn'),
                   '-Dmaven.repo.local=' + str(WORKSPACE / '.local-dev/cache/maven'),
                   '-pl', 'yshop-module-mall/yshop-module-order-biz', '-Pmysql-acceptance', 'test',
                   '-Dtest=' + ('OrderingDatabaseTest,CatalogDatabaseTest,CatalogEditingMysqlAcceptance,CouponDatabaseTest' if coupon else 'OrderingDatabaseTest,CatalogDatabaseTest,CatalogEditingMysqlAcceptance' if catalog else 'OrderingDatabaseTest'), '-Dsurefire.failIfNoSpecifiedTests=false']
        with log.open('w') as output:
            log.chmod(0o600)
            result = subprocess.run(command, cwd=REPO / 'yshop-drink-boot3', env=env, stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
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
        xml = REPO / 'yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz/target/surefire-reports/TEST-co.yixiang.yshop.module.order.ordering.OrderingDatabaseTest.xml'
        suite = ET.parse(xml).getroot()
        if any(suite.attrib.get(k, '0') != '0' for k in ('errors', 'failures', 'skipped')) or int(suite.attrib['tests']) < 80:
            raise RuntimeError('ACCEPTANCE_REPORT_INCOMPLETE')
        test_count = int(suite.attrib['tests'])
        if catalog:
            extra = ET.parse(xml.with_name('TEST-co.yixiang.yshop.module.order.ordering.CatalogDatabaseTest.xml')).getroot()
            if any(extra.attrib.get(k,'0') != '0' for k in ('errors','failures','skipped')) or int(extra.attrib['tests']) < 88:
                raise RuntimeError('CATALOG_ACCEPTANCE_REPORT_INCOMPLETE')
            test_count += int(extra.attrib['tests'])
            editing = ET.parse(xml.with_name('TEST-co.yixiang.yshop.module.order.ordering.CatalogEditingMysqlAcceptance.xml')).getroot()
            if any(editing.attrib.get(k,'0') != '0' for k in ('errors','failures','skipped')) or int(editing.attrib['tests']) < 24:
                raise RuntimeError('CATALOG_EDITING_ACCEPTANCE_INCOMPLETE')
            test_count += int(editing.attrib['tests'])
        if coupon:
            coupon_suite = ET.parse(xml.with_name('TEST-co.yixiang.yshop.module.order.ordering.CouponDatabaseTest.xml')).getroot()
            if any(coupon_suite.attrib.get(k,'0') != '0' for k in ('errors','failures','skipped')) or int(coupon_suite.attrib['tests']) < 281:
                raise RuntimeError('COUPON_ACCEPTANCE_INCOMPLETE')
            test_count += int(coupon_suite.attrib['tests'])
        summary = dict(coupon=coupon, tables=table_count, innodbTables=innodb_count, result='PASS', mysql=version, engine='InnoDB', tests=test_count, catalog=catalog, failures=0, errors=0, developmentDatabaseUsed=False, paymentRequests=0, log=str(log))
        report = log.with_suffix('.json')
        report.write_text(json.dumps(summary, indent=2) + '\n')
        report.chmod(0o600)
        print(json.dumps(summary))
    finally:
        if created:
            assert re.fullmatch(r'yshop_acceptance_phase6a_[a-f0-9]{8}', schema)
            assert re.fullmatch(r'accept6a_[a-f0-9]{8}', account)
            db.mysql(f"DROP DATABASE `{schema}`; DROP USER '{account}'@'%';")
        jdbc.unlink(missing_ok=True)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('Ordering MySQL acceptance failed; inspect private local acceptance logs.', file=sys.stderr)
        sys.exit(1)
