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
sys.path.insert(0, str(REPO / 'tests/quality'))
from evidence import Evidence, execute, manifest, workspace
from acceptance_support import mysql_port, maven_command, database_helper, supported_mysql, assert_removed
WORKSPACE = workspace(REPO)
PRIVATE = Path(os.environ.get('YSHOP_ACCEPTANCE_OUTPUT',str(WORKSPACE / '.local-dev/acceptance')))


def main():
    PRIVATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    PRIVATE.chmod(0o700)
    helper = database_helper(WORKSPACE)
    spec = importlib.util.spec_from_file_location('local_database', helper)
    database = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(database)
    version = database.mysql('SELECT VERSION();').strip()
    if not supported_mysql(version):
        raise RuntimeError('MYSQL_8_0_REQUIRED')
    if '--install-recovery-migration' in sys.argv[1:] or '--install-triggers' in sys.argv[1:] or '--install-attempt-migration' in sys.argv[1:] or '--install-v3-migration' in sys.argv[1:]:
        # Privileged DDL only, scoped to the schema in this run's private JDBC configuration.
        settings = dict(line.split('=',1) for line in Path(os.environ['YSHOP_ACCEPTANCE_CONFIG']).read_text().splitlines() if '=' in line)
        match = re.fullmatch(r'jdbc:mysql://127[.]0[.]0[.]1:[0-9]{2,5}/(yshop_acceptance_phase5[bcdefgh]_[a-f0-9]{8})[?].*',settings.get('url',''))
        if not match or not re.fullmatch(r'accept5[bcdefgh]_[a-f0-9]{8}',settings.get('username','')):
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
    coupon_mode = '--coupon' in sys.argv[1:]
    cancellation_mode = '--cancellation' in sys.argv[1:] or coupon_mode
    prepayment_mode = '--prepayment' in sys.argv[1:]
    ingress_only = '--ingress-only' in sys.argv[1:]
    if ingress_only and not prepayment_mode:
        raise RuntimeError('PREPAYMENT_ISOLATION_REQUIRED')
    preflight_mode = '--preflight' in sys.argv[1:] or prepayment_mode or cancellation_mode
    readiness_mode = '--readiness' in sys.argv[1:] or preflight_mode
    v3_mode = '--v3' in sys.argv[1:] or readiness_mode
    attempt_mode = '--attempt' in sys.argv[1:] or v3_mode
    wallet_mode = '--wallet' in sys.argv[1:] or attempt_mode
    phase = '5h' if prepayment_mode else '5g' if preflight_mode else '5f' if readiness_mode else '5e' if v3_mode else '5d' if attempt_mode else '5c' if wallet_mode else '5b'
    suffix = secrets.token_hex(4)
    schema = 'yshop_acceptance_phase' + phase + '_' + suffix
    account = 'accept' + phase + '_' + secrets.token_hex(4)
    password = secrets.token_hex(24)
    jdbc = PRIVATE / ('jdbc-' + suffix + '.properties')
    log = PRIVATE / ('mysql-' + suffix + '.log')
    report = PRIVATE / ('mysql-' + suffix + '.json')
    schema_created = account_created = False
    evidence = None
    receipt = PRIVATE / ("resources-" + suffix + ".json")
    try:
        database.mysql(f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4;")
        schema_created = True
        receipt.write_text(json.dumps({'schema': schema, 'account': account, 'credentialsIncluded': False}))
        receipt.chmod(0o600)
        database.mysql(f"CREATE USER '{account}'@'%' IDENTIFIED BY '{password}';")
        account_created = True
        database.mysql(f"GRANT ALL PRIVILEGES ON `{schema}`.* TO '{account}'@'%';")
        jdbc.write_text(f'url=jdbc:mysql://127.0.0.1:{mysql_port()}/{schema}?useSSL=false&connectTimeout=5000&socketTimeout=30000&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai\n'
                        f'username={account}\npassword={password}\n')
        jdbc.chmod(0o600)
        env = os.environ.copy()
        env['YSHOP_ACCEPTANCE_CONFIG'] = str(jdbc)
        env['MAVEN_OPTS'] = '-Xmx2g'
        command = maven_command(WORKSPACE) + [
                   '-pl', 'yshop-module-mall/yshop-module-order-biz', '-Pmysql-acceptance',
                   'test', '-Dtest=' + ('PaymentDatabaseTest,WalletDatabaseTest,PaymentAttemptDatabaseTest,WechatV3DatabaseTest,PaymentLiveReadinessDatabaseTest,LiveMerchantPreflightDatabaseTest' if preflight_mode else 'PaymentDatabaseTest,WalletDatabaseTest,PaymentAttemptDatabaseTest,WechatV3DatabaseTest,PaymentLiveReadinessDatabaseTest' if readiness_mode else 'PaymentDatabaseTest,WalletDatabaseTest,PaymentAttemptDatabaseTest,WechatV3DatabaseTest' if v3_mode else 'PaymentDatabaseTest,WalletDatabaseTest,PaymentAttemptDatabaseTest' if attempt_mode else 'PaymentDatabaseTest,WalletDatabaseTest' if wallet_mode else 'PaymentDatabaseTest'), '-Dsurefire.failIfNoSpecifiedTests=true']
        if cancellation_mode:
            command[-2] += ',PaymentCancellationDatabaseTest'
        if coupon_mode:
            command[-2] += ',CouponPaymentDatabaseTest'
        if prepayment_mode:
            if env.get('YSHOP_INGRESS_BASE_URL') not in {'https://localhost:48443','https://localhost:48444'} or not env.get('YSHOP_INGRESS_CA'):
                raise RuntimeError('SYNTHETIC_LOOPBACK_INGRESS_REQUIRED')
            command[-2] += ',CallbackIngressEndToEndTest'
            if ingress_only:
                command[-2] = '-Dtest=CallbackIngressEndToEndTest'
        evidence = Evidence(PRIVATE / 'quality')
        selected = next(x.split('=', 1)[1].split(',') for x in command if x.startswith('-Dtest='))
        registered = manifest(REPO)
        expected = {k: v for k, v in registered.items() if k.rsplit('.', 1)[-1] in selected}
        if len(expected) != len(selected):
            raise RuntimeError('UNREGISTERED_TEST_SUITE')
        command += evidence.arguments(expected)
        returncode = execute(command, REPO / 'yshop-drink-boot3', env, log)
        if returncode:
            raise RuntimeError('MYSQL_ACCEPTANCE_FAILED_PRIVATE_LOG_SAVED')
        table_engines = database.mysql(f"SELECT COUNT(*),COALESCE(SUM(ENGINE='InnoDB'),0) FROM information_schema.TABLES "
                                       f"WHERE TABLE_SCHEMA='{schema}';").strip()
        if table_engines != '12\t12':
            raise RuntimeError('INNODB_REQUIRED')
        verified = evidence.validate()
        test_count = tests = verified['tests']
        summary = {'result': 'PASS', 'mysql': version, 'engine': 'InnoDB', 'tests': tests, 'walletMode': wallet_mode, 'attemptMode': attempt_mode, 'v3Mode': v3_mode, 'readinessMode': readiness_mode, 'preflightMode': preflight_mode, 'cancellationMode': cancellation_mode, 'couponMode': coupon_mode,
                   'failures': 0, 'errors': 0, 'developmentDatabaseUsed': False, 'ingressOnly': ingress_only,
                   'realPaymentRequests': {'value':0,'evidence':'DECLARED_NO_PROVIDER_PATH'}, 'runId':evidence.id, 'exactNamesChecked':True, 'log': str(log)}

    finally:
        if evidence is not None: evidence.diagnostics()
        try:
            if schema_created or account_created:
                # Names originate only from random fixed-length hexadecimal identifiers in this run.
                assert re.fullmatch(r'yshop_acceptance_phase5[bcdefgh]_[a-f0-9]{8}', schema)
                assert re.fullmatch(r'accept5[bcdefgh]_[a-f0-9]{8}', account)
                try:
                    if schema_created: database.mysql(f"DROP DATABASE `{schema}`;")
                finally:
                    if account_created: database.mysql(f"DROP USER '{account}'@'%';")
            if schema_created or account_created: assert_removed(database,schema,account)
            receipt.unlink(missing_ok=True)
        finally:
            jdbc.unlink(missing_ok=True)
    summary.update({k:verified[k] for k in ['sourceSha','sourceDigest']})
    summary['cleanup'] = 'PASS'
    report.write_text(json.dumps(summary, indent=2) + '\n')
    report.chmod(0o600)
    print(json.dumps(summary))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as failure:
        # JDBC errors/configuration must not expose private credentials to terminal/chat.
        import traceback
        print(json.dumps({'result':'FAIL','errorType':type(failure).__name__,'sites':[(f.name,f.lineno) for f in traceback.extract_tb(failure.__traceback__)]}))
        print('MySQL acceptance failed; inspect the private local acceptance log.', file=sys.stderr)
        sys.exit(1)
