#!/usr/bin/env python3
"""Run synthetic finalization acceptance in an isolated MySQL 8/InnoDB schema.

Uses the existing Git-ignored local-dev Docker environment, never the development database.
Private JDBC configuration and logs stay outside the repository. No provider network calls.
"""
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
    PRIVATE.mkdir(parents=True, exist_ok=True)
    helper = WORKSPACE / '.local-dev/database.py'
    spec = importlib.util.spec_from_file_location('local_database', helper)
    database = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(database)
    version = database.mysql('SELECT VERSION();').strip()
    if not version.startswith('8.0.'):
        raise RuntimeError('MYSQL_8_0_REQUIRED')
    suffix = secrets.token_hex(4)
    schema = 'yshop_acceptance_phase5b_' + suffix
    account = 'accept5b_' + secrets.token_hex(4)
    password = secrets.token_hex(24)
    jdbc = PRIVATE / ('jdbc-' + suffix + '.properties')
    log = PRIVATE / ('mysql-' + suffix + '.log')
    report = PRIVATE / ('mysql-' + suffix + '.json')
    created = False
    try:
        database.mysql(f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4; "
                       f"CREATE USER '{account}'@'%' IDENTIFIED BY '{password}'; "
                       f"GRANT ALL PRIVILEGES ON `{schema}`.* TO '{account}'@'%';")
        created = True
        jdbc.write_text(f'url=jdbc:mysql://127.0.0.1:3306/{schema}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai\n'
                        f'username={account}\npassword={password}\n')
        jdbc.chmod(0o600)
        env = os.environ.copy()
        env['JAVA_HOME'] = str(WORKSPACE / '.dev-tools/java-home')
        env['PATH'] = str(WORKSPACE / '.dev-tools/java-home/bin') + os.pathsep + env['PATH']
        env['YSHOP_ACCEPTANCE_CONFIG'] = str(jdbc)
        env['MAVEN_OPTS'] = '-Xmx2g'
        command = [str(WORKSPACE / '.dev-tools/maven/bin/mvn'),
                   '-Dmaven.repo.local=' + str(WORKSPACE / '.local-dev/cache/maven'),
                   '-pl', 'yshop-module-mall/yshop-module-order-biz', '-Pmysql-acceptance',
                   'test', '-Dtest=PaymentDatabaseTest', '-Dsurefire.failIfNoSpecifiedTests=false']
        with log.open('w') as output:
            log.chmod(0o600)
            result = subprocess.run(command, cwd=REPO / 'yshop-drink-boot3', env=env,
                                    stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError('MYSQL_ACCEPTANCE_FAILED_PRIVATE_LOG_SAVED')
        table_engines = database.mysql(f"SELECT COUNT(*),COALESCE(SUM(ENGINE='InnoDB'),0) FROM information_schema.TABLES "
                                       f"WHERE TABLE_SCHEMA='{schema}';").strip()
        if table_engines != '8\t8':
            raise RuntimeError('INNODB_REQUIRED')
        xml = REPO / 'yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz/target/surefire-reports/TEST-co.yixiang.yshop.module.order.payment.PaymentDatabaseTest.xml'
        suite = ET.parse(xml).getroot()
        if suite.attrib['failures'] != '0' or suite.attrib['errors'] != '0' or int(suite.attrib['tests']) < 55:
            raise RuntimeError('ACCEPTANCE_REPORT_INCOMPLETE')
        summary = {'result': 'PASS', 'mysql': version, 'engine': 'InnoDB', 'tests': int(suite.attrib['tests']),
                   'failures': 0, 'errors': 0, 'developmentDatabaseUsed': False,
                   'realPaymentRequests': 0, 'log': str(log)}
        report.write_text(json.dumps(summary, indent=2) + '\n')
        report.chmod(0o600)
        print(json.dumps(summary))
        return 0
    finally:
        if created:
            # Names originate only from random fixed-length hexadecimal identifiers in this run.
            assert re.fullmatch(r'yshop_acceptance_phase5b_[a-f0-9]{8}', schema)
            assert re.fullmatch(r'accept5b_[a-f0-9]{8}', account)
            database.mysql(f"DROP DATABASE `{schema}`; DROP USER '{account}'@'%';")
        if jdbc.exists():
            jdbc.unlink()


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception:
        # JDBC errors/configuration must not expose private credentials to terminal/chat.
        print('MySQL acceptance failed; inspect the private local acceptance log.', file=sys.stderr)
        sys.exit(1)
