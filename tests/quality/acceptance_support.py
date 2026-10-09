"""Portable test tool/config selection, with legacy local adapters kept compatible."""
import os,re,shutil
from pathlib import Path

def mysql_port():
    value=os.environ.get('YSHOP_MYSQL_PORT','3306')
    if not value.isdecimal() or not 1024<=int(value)<=65535:raise RuntimeError('INVALID_ISOLATED_MYSQL_PORT')
    return value

def maven_command(workspace):
    value=os.environ.get('YSHOP_MAVEN') or shutil.which('mvn') or str(workspace/'.dev-tools/maven/bin/mvn')
    cmd=[value]
    cache=os.environ.get('YSHOP_MAVEN_REPOSITORY')
    if cache:cmd+=['-Dmaven.repo.local='+cache]
    return cmd

def database_helper(workspace):
    return Path(os.environ.get('YSHOP_DATABASE_HELPER',str(workspace/'.local-dev/database.py')))

def supported_mysql(version):
    if not isinstance(version,str) or 'mariadb' in version.lower():return False
    m=re.match(r'^(\d+)\.(\d+)\.(\d+)(?:\D|$)',version)
    return bool(m and int(m[1])==8 and (int(m[2])>0 or int(m[3])>=29))

def assert_removed(database,schema,account):
    if database.mysql("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+schema+"';").strip()!='0':
        raise RuntimeError('ISOLATED_SCHEMA_NOT_DESTROYED')
    if database.mysql("SELECT COUNT(*) FROM mysql.user WHERE User='"+account+"';").strip()!='0':
        raise RuntimeError('ISOLATED_ACCOUNT_NOT_DESTROYED')
