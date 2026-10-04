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
    if '--install-recovery-migration' in sys.argv[1:] or '--install-triggers' in sys.argv[1:] or '--install-attempt-migration' in sys.argv[1:] or '--install-v3-migration' in sys.argv[1:]:
        # Privileged DDL only, scoped to the schema in this run's private JDBC configuration.
        settings = dict(line.split('=',1) for line in Path(os.environ['YSHOP_ACCEPTANCE_CONFIG']).read_text().splitlines() if '=' in line)
        match = re.fullmatch(r'jdbc:mysql://127[.]0[.]0[.]1:3306/(yshop_acceptance_phase5[bcdefg]_[a-f0-9]{8})[?].*',settings.get('url',''))
        if not match or not re.fullmatch(r'accept5[bcdefg]_[a-f0-9]{8}',settings.get('username','')):
            raise RuntimeError('ISOLATED_DATABASE_REQUIRED')
        if '--install-recovery-migration' in sys.argv[1:]:
            database.mysql('USE `' + match.group(1) + '`; ' + (REPO / 'yshop-drink-boot3/sql/migrations/2026-10-04-payment-live-readiness.sql').read_text())
            return 0
        if '--install-v3-migration' in sys.argv[1:]:
            database.mysql('USE `' + match.group(1) + '`; ' + (REPO / 'yshop-drink-boot3/sql/migrations/2026-10-04-wechat-v3.sql').read_text())
            return 0
        if '--install-attempt-migration' in sys.argv[1:]:
            database.mysql('USE `' + match.group(1) + '`; ' + (REPO / 'yshop-drink-boot3/sql/migrations/2026-10-04-payment-attempt.sql').read_text())
            return 0
        statements = (REPO / 'yshop-drink-boot3/sql/migrations/2026-10-03-wallet-ledger.sql').read_text()
        statements = re.sub(r'(?m)^--.*$', '', statements)
        triggers = [statement.strip() for statement in statements.split(';') if statement.strip().startswith('CREATE TRIGGER')]
        if len(triggers) != 2:
            raise RuntimeError('IMMUTABILITY_MIGRATION_INCOMPLETE')
        database.mysql('USE `' + match.group(1) + '`; ' + ';'.join(triggers) + ';')
        return 0
    preflight_mode = '--preflight' in sys.argv[1:]
    readiness_mode = '--readiness' in sys.argv[1:] or preflight_mode
    v3_mode = '--v3' in sys.argv[1:] or readiness_mode
    attempt_mode = '--attempt' in sys.argv[1:] or v3_mode
    wallet_mode = '--wallet' in sys.argv[1:] or attempt_mode
    phase = '5g' if preflight_mode else '5f' if readiness_mode else '5e' if v3_mode else '5d' if attempt_mode else '5c' if wallet_mode else '5b'
    suffix = secrets.token_hex(4)
    schema = 'yshop_acceptance_phase' + phase + '_' + suffix
    account = 'accept' + phase + '_' + secrets.token_hex(4)
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
                   'test', '-Dtest=' + ('PaymentDatabaseTest,WalletDatabaseTest,PaymentAttemptDatabaseTest,WechatV3DatabaseTest,PaymentLiveReadinessDatabaseTest,LiveMerchantPreflightDatabaseTest' if preflight_mode else 'PaymentDatabaseTest,WalletDatabaseTest,PaymentAttemptDatabaseTest,WechatV3DatabaseTest,PaymentLiveReadinessDatabaseTest' if readiness_mode else 'PaymentDatabaseTest,WalletDatabaseTest,PaymentAttemptDatabaseTest,WechatV3DatabaseTest' if v3_mode else 'PaymentDatabaseTest,WalletDatabaseTest,PaymentAttemptDatabaseTest' if attempt_mode else 'PaymentDatabaseTest,WalletDatabaseTest' if wallet_mode else 'PaymentDatabaseTest'), '-Dsurefire.failIfNoSpecifiedTests=false']
        with log.open('w') as output:
            log.chmod(0o600)
            result = subprocess.run(command, cwd=REPO / 'yshop-drink-boot3', env=env,
                                    stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError('MYSQL_ACCEPTANCE_FAILED_PRIVATE_LOG_SAVED')
        table_engines = database.mysql(f"SELECT COUNT(*),COALESCE(SUM(ENGINE='InnoDB'),0) FROM information_schema.TABLES "
                                       f"WHERE TABLE_SCHEMA='{schema}';").strip()
        if table_engines != '12\t12':
            raise RuntimeError('INNODB_REQUIRED')
        xml = REPO / 'yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz/target/surefire-reports/TEST-co.yixiang.yshop.module.order.payment.PaymentDatabaseTest.xml'
        suites = [ET.parse(xml).getroot()]
        if wallet_mode:
            suites.append(ET.parse(xml.with_name('TEST-co.yixiang.yshop.module.order.payment.WalletDatabaseTest.xml')).getroot())
        if attempt_mode:
            suites.append(ET.parse(xml.with_name('TEST-co.yixiang.yshop.module.order.payment.PaymentAttemptDatabaseTest.xml')).getroot())
        if v3_mode:
            suites.append(ET.parse(xml.with_name('TEST-co.yixiang.yshop.module.order.payment.WechatV3DatabaseTest.xml')).getroot())
        if readiness_mode:
            suites.append(ET.parse(xml.with_name('TEST-co.yixiang.yshop.module.order.payment.PaymentLiveReadinessDatabaseTest.xml')).getroot())
        if preflight_mode:
            suites.append(ET.parse(xml.with_name('TEST-co.yixiang.yshop.module.order.payment.LiveMerchantPreflightDatabaseTest.xml')).getroot())
        if any(suite.attrib['failures'] != '0' or suite.attrib['errors'] != '0' for suite in suites) or int(suites[0].attrib['tests']) < 55:
            raise RuntimeError('ACCEPTANCE_REPORT_INCOMPLETE')
        tests = sum(int(suite.attrib['tests']) for suite in suites)
        summary = {'result': 'PASS', 'mysql': version, 'engine': 'InnoDB', 'tests': tests, 'walletMode': wallet_mode, 'attemptMode': attempt_mode, 'v3Mode': v3_mode, 'readinessMode': readiness_mode, 'preflightMode': preflight_mode,
                   'failures': 0, 'errors': 0, 'developmentDatabaseUsed': False,
                   'realPaymentRequests': 0, 'log': str(log)}
        report.write_text(json.dumps(summary, indent=2) + '\n')
        report.chmod(0o600)
        print(json.dumps(summary))
        return 0
    finally:
        if created:
            # Names originate only from random fixed-length hexadecimal identifiers in this run.
            assert re.fullmatch(r'yshop_acceptance_phase5[bcdefg]_[a-f0-9]{8}', schema)
            assert re.fullmatch(r'accept5[bcdefg]_[a-f0-9]{8}', account)
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
