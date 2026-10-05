#!/usr/bin/env python3
"""Run the printer migration and concurrent service tests on disposable MySQL 8.

Pass the workspace holding the existing ignored .local-dev/database.py helper.
Only upstream table definitions and synthetic fixtures are imported, never private data.
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


def main():
    workspace = Path(sys.argv[1]).resolve() if len(sys.argv) == 2 else REPO.parent
    private = workspace / '.local-dev/acceptance'
    private.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('local_database', workspace / '.local-dev/database.py')
    database = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(database)
    version = database.mysql('SELECT VERSION();').strip()
    if not version.startswith('8.0.'):
        raise RuntimeError('MYSQL_8_REQUIRED')
    suffix = secrets.token_hex(4)
    schema, account = 'yshop_acceptance_printer_' + suffix, 'printertest_' + suffix
    password = secrets.token_hex(24)
    jdbc = private / ('printer-' + suffix + '.properties')
    log = private / ('printer-' + suffix + '.log')
    created = False
    try:
        database.mysql(f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4; "
                       f"CREATE USER '{account}'@'%' IDENTIFIED BY '{password}'; "
                       f"GRANT ALL PRIVILEGES ON `{schema}`.* TO '{account}'@'%';")
        created = True
        upstream = (REPO / 'yshop-drink-boot3/sql/yixiang-drink-open.sql').read_text()
        ddl = []
        for table in ('system_menu', 'yshop_store_shop'):
            ddl.append(re.search(r'^CREATE TABLE `' + table + r'` \(.*?;', upstream, re.M | re.S).group())
        menus = re.findall(r"^INSERT INTO `system_menu` VALUES \((?:2000|230[8-9]|231[0-3]),.*?;$", upstream, re.M)
        shops = """INSERT INTO yshop_store_shop
            (id,name,mobile,images,address,address_map,start_time,end_time,lng,lat)
            VALUES (1,'测试门店一','','','','',NOW(),NOW(),'0','0'),
                   (2,'测试门店二','','','','',NOW(),NOW(),'0','0');"""
        database.mysql('USE `' + schema + '`;\n' + '\n'.join(ddl + menus + [shops]))
        jdbc.write_text(f'url=jdbc:mysql://127.0.0.1:3306/{schema}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai\n'
                        f'username={account}\npassword={password}\n')
        jdbc.chmod(0o600)
        env = os.environ.copy()
        env['JAVA_HOME'] = str(workspace / '.dev-tools/java-home')
        env['PATH'] = str(workspace / '.dev-tools/java-home/bin') + os.pathsep + env['PATH']
        env['PRINTER_ACCEPTANCE_CONFIG'] = str(jdbc)
        command = [str(workspace / '.dev-tools/maven/bin/mvn'), '-o',
                   '-Dmaven.repo.local=' + str(workspace / '.local-dev/cache/maven'),
                   '-pl', 'yshop-module-mall/yshop-module-store-biz', '-am',
                   '-Pprinter-mysql-acceptance', 'test', '-Dtest=PrinterDatabaseTest',
                   '-Dsurefire.failIfNoSpecifiedTests=false']
        with log.open('w') as output:
            log.chmod(0o600)
            result = subprocess.run(command, cwd=REPO / 'yshop-drink-boot3', env=env,
                                    stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError('ACCEPTANCE_FAILED')
        engines = database.mysql(f"SELECT COUNT(*),SUM(ENGINE='InnoDB') FROM information_schema.TABLES WHERE TABLE_SCHEMA='{schema}';").strip()
        if engines != '6\t6':
            raise RuntimeError('INNODB_REQUIRED')
        report = REPO / 'yshop-drink-boot3/yshop-module-mall/yshop-module-store-biz/target/surefire-reports/TEST-co.yixiang.yshop.module.store.printer.PrinterDatabaseTest.xml'
        suite = ET.parse(report).getroot()
        if int(suite.attrib['tests']) < 15 or any(suite.attrib.get(key, '0') != '0' for key in ('failures', 'errors', 'skipped')):
            raise RuntimeError('REPORT_INCOMPLETE')
        summary = {'result': 'PASS', 'mysql': version, 'engine': 'InnoDB', 'tests': int(suite.attrib['tests']),
                   'migrationRepeated': True, 'upstreamTableDefinitions': True,
                   'developmentDatabaseUsed': False, 'realProviderRequests': 0, 'log': str(log)}
        (private / ('printer-' + suffix + '.json')).write_text(json.dumps(summary, indent=2) + '\n')
        print(json.dumps(summary))
    finally:
        if created:
            assert re.fullmatch(r'yshop_acceptance_printer_[a-f0-9]{8}', schema)
            assert re.fullmatch(r'printertest_[a-f0-9]{8}', account)
            database.mysql(f"DROP DATABASE `{schema}`; DROP USER '{account}'@'%';")
        jdbc.unlink(missing_ok=True)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('Printer MySQL acceptance failed; inspect the private local acceptance log.', file=sys.stderr)
        sys.exit(1)
