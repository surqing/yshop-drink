"""One owner for disposable dependencies and application processes; no business tests."""
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import time
import urllib.request
import uuid
from backend_artifact import verify_artifact
from evidence import source_identity
from loopback_ports import backend_pair
from owned_resources import assert_backend_owner, remove_owned

MIGRATIONS = ['payment-credential-columns.sql','2026-10-03-payment-finalization.sql',
              '2026-10-03-wallet-ledger.sql','2026-10-04-payment-attempt.sql',
              '2026-10-04-wechat-v3.sql','2026-10-04-payment-live-readiness.sql',
              '2026-10-08-multistore-ordering.sql','2026-10-08-product-catalog.sql',
              '2026-10-08-coupon-marketing.sql','2026-10-08-coupon-permissions.sql',
              '2026-10-11-member-identity.sql','2026-10-19-score-submission.sql','2026-10-05-printer-management.sql']


def merge(target, values):
    for key,value in values.items():
        if isinstance(value,dict) and isinstance(target.get(key),dict):
            merge(target[key],value)
        else:
            target[key]=value


class OwnedEnvironment:
    def __init__(self, repo, root, *, identity=None, owner=None, run_id=None, port=None):
        self.repo=Path(repo).resolve();self.root=Path(root).resolve()
        if self.root.is_relative_to(self.repo) and not self.root.is_relative_to(self.repo/'.quality'):
            raise ValueError('PRIVATE_OUTPUT_REQUIRED')
        self.owner=owner or uuid.uuid4().hex
        if not re.fullmatch(r'[a-f0-9]{32}',self.owner):raise ValueError('OWNER_INVALID')
        self.identity=identity if identity is not None else source_identity(self.repo)
        self.run_id=run_id or self.owner;self.port=port
        self.mysql='yshop-quality-business-mysql-'+self.owner
        self.redis='yshop-quality-business-redis-'+self.owner
        self.names=[self.mysql,self.redis];self.schema='yshop_quality_business_'+self.owner[:16]
        self.processes=[];self.ready=False;self.closed=False
        self.report={**self.identity,'owner':self.owner,'runId':self.run_id,'startedAt':time.time(),
                     'result':'FAIL','cleanup':'NOT_RUN','scope':'owned disposable dependencies; no business assertions'}

    def file(self,name,text):
        path=self.root/name
        with path.open('w') as output:
            os.chmod(path,0o600);output.write(text)
        return path

    def docker(self,*args,input=None):
        result=subprocess.run(['docker',*args],input=input,text=True,capture_output=True,timeout=180,start_new_session=True)
        if result.returncode:
            if self.root.exists():self.file('operation-failure.log',result.stderr)
            raise RuntimeError('DOCKER_OPERATION_FAILED')
        return result.stdout.strip()

    def assert_mysql_owner(self):
        info=json.loads(self.docker('inspect',self.mysql))[0]
        if info['Config'].get('Labels',{}).get('yshop.quality.owner')!=self.owner:
            raise RuntimeError('DATABASE_OWNERSHIP_MISMATCH')

    def sql(self,query):
        self.assert_mysql_owner()
        return self.docker('exec','-i',self.mysql,'mysql','--defaults-extra-file=/run/client.cnf',self.schema,'-N',input=query)

    def start(self, *, instances=1, require_build=True, launcher=None, configure=None):
        if self.ready or self.closed:raise RuntimeError('ENVIRONMENT_ALREADY_USED')
        if instances not in (1,2):raise ValueError('INVALID_BACKEND_INSTANCE_COUNT')
        if os.getuid()==0:raise RuntimeError('NONROOT_TEST_LAUNCHER_REQUIRED')
        self.root.mkdir(parents=True,mode=0o700,exist_ok=False);os.chmod(self.root,0o700)
        jar=self.repo/'yshop-drink-boot3/yshop-server/target/yshop-server.jar'
        if require_build:
            self.report['backendBuild']=verify_artifact(jar,self.repo/'.quality/backend-build.json',self.identity)
        self.report['stage']='DEPENDENCIES'
        root_password=secrets.token_hex(24);db_password=secrets.token_hex(24);redis_password=secrets.token_hex(24)
        self.db_user='qa_business_'+self.owner[:16]
        oracle_user='qa_oracle_'+self.owner[:16];oracle_password=secrets.token_hex(24)
        secret=self.file('root.secret',root_password)
        client=self.file('client.cnf',f'[client]\ndefault-character-set=utf8mb4\nuser=root\npassword={root_password}\n')
        oracle=self.file('oracle.cnf',f'[client]\ndefault-character-set=utf8mb4\nuser={oracle_user}\npassword={oracle_password}\n')
        self.docker('run','-d','--name',self.mysql,'--label','yshop.quality.owner='+self.owner,
                    '--cap-drop=NET_RAW','--security-opt','no-new-privileges','-p','127.0.0.1::3306',
                    '--mount','type=bind,source='+str(secret)+',target=/run/root.secret,readonly',
                    '--mount','type=bind,source='+str(client)+',target=/run/client.cnf,readonly',
                    '--mount','type=bind,source='+str(oracle)+',target=/run/oracle.cnf,readonly',
                    '-e','MYSQL_ROOT_PASSWORD_FILE=/run/root.secret','mysql:8.0')
        for _ in range(100):
            try:
                self.docker('exec',self.mysql,'mysql','--defaults-extra-file=/run/client.cnf','--protocol=TCP','-h','127.0.0.1','-N','-e','SELECT 1');break
            except RuntimeError:time.sleep(1)
        else:raise RuntimeError('MYSQL_NOT_READY')
        self.docker('exec',self.mysql,'mysql','--defaults-extra-file=/run/client.cnf','-e','CREATE DATABASE '+self.schema+' CHARACTER SET utf8mb4')
        info=json.loads(self.docker('inspect',self.mysql))[0]
        mysql_port=int(info['NetworkSettings']['Ports']['3306/tcp'][0]['HostPort'])
        config=self.file('redis.conf','bind 0.0.0.0\nprotected-mode yes\nrequirepass '+redis_password+'\nsave ""\nappendonly no\n')
        self.docker('run','-d','--name',self.redis,'--label','yshop.quality.owner='+self.owner,
                    '--user',str(os.getuid())+':'+str(os.getgid()),'--cap-drop=ALL','--security-opt','no-new-privileges',
                    '-p','127.0.0.1::6379','--mount','type=bind,source='+str(config)+',target=/run/redis.conf,readonly',
                    'redis:7.4','redis-server','/run/redis.conf')
        redis_port=int(json.loads(self.docker('inspect',self.redis))[0]['NetworkSettings']['Ports']['6379/tcp'][0]['HostPort'])
        self.report['stage']='SCHEMA'
        seed=(self.repo/'yshop-drink-boot3/sql/yixiang-drink-open.sql').read_text()
        ddl=re.findall(r'CREATE TABLE `[^`]+` \(.*?\) ENGINE=.*?;',seed,re.S)
        if not ddl or any('INSERT INTO' in row for row in ddl):raise RuntimeError('SCHEMA_ONLY_REQUIRED')
        self.sql('SET FOREIGN_KEY_CHECKS=0;\n'+'\n'.join(ddl)+'\nSET FOREIGN_KEY_CHECKS=1;')
        self.report['seedInsertRowsCopied']=0
        for name in MIGRATIONS:
            # Permission descriptors require an actual menu ancestor. Our schema-only
            # fixture creates that menu below; production migrations reject its absence.
            if name in ('2026-10-08-coupon-permissions.sql','2026-10-05-printer-management.sql'):continue
            self.report['stage']='MIGRATION:'+name
            self.sql((self.repo/'yshop-drink-boot3/sql/migrations'/name).read_text())
        self.docker('exec','-i',self.mysql,'mysql','--defaults-extra-file=/run/client.cnf',input=
                    "CREATE USER '"+self.db_user+"'@'%' IDENTIFIED BY '"+db_password+"'; GRANT ALL ON "+self.schema+".* TO '"+self.db_user+"'@'%';"+
                    "CREATE USER '"+oracle_user+"'@'%' IDENTIFIED BY '"+oracle_password+"'; GRANT SELECT ON "+self.schema+".* TO '"+oracle_user+"'@'%';")
        self.settings={'url':'jdbc:mysql://127.0.0.1:'+str(mysql_port)+'/'+self.schema+'?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
                       'username':self.db_user,'password':db_password,'driver-class-name':'com.mysql.cj.jdbc.Driver'}
        base={};allowed={'spring','mybatis-plus','mybatis-plus-join','yshop','aj','easy-trans'}
        import yaml  # Optional owned-runtime dependency; QUICK imports remain standard-library only.
        for doc in yaml.safe_load_all((self.repo/'yshop-drink-boot3/yshop-server/src/main/resources/application.yaml').read_text()):
            if doc:merge(base,{key:value for key,value in doc.items() if key in allowed})
        self.port=self.port or backend_pair()
        merge(base,{'server':{'address':'127.0.0.1','port':self.port},'spring':{'profiles':{'active':'quality-isolated'},
                    'datasource':{'dynamic':{'primary':'master','datasource':{'master':self.settings}}},
                    'data':{'redis':{'host':'127.0.0.1','port':redis_port,'password':redis_password,'database':0}},
                    'autoconfigure':{'exclude':['org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration']}},
                    'yshop':{'demo':False,'tenant':{'enable':False},'captcha':{'enable':False},
                             'pay':{'wechat-v3':{'enabled':False,'reconciliation-enabled':False}}},
                    'wx':{'mp':{'app-id':'synthetic-quality-local','secret':'synthetic-test-only-no-provider'},
                          'miniapp':{'appid':'synthetic-quality-local','secret':'synthetic-test-only-no-provider'}},
                    'logging':{'level':{'root':'WARN','co.yixiang':'WARN'}},
                    'management':{'endpoints':{'web':{'exposure':{'include':['health','info']}}},'info':{'env':{'enabled':True}}},
                    'info':{'qualityOwner':self.owner}})
        logging=self.file('logback.xml','<configuration><appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="CONSOLE"/></root></configuration>')
        base['logging']['config']='file:'+str(logging)
        from business_http import seed as seed_identities
        seed_identities(self.sql,self.root,self.repo)
        if '2026-10-05-printer-management.sql' in MIGRATIONS:
            self.report['stage']='MIGRATION:2026-10-05-printer-management.sql'
            self.sql((self.repo/'yshop-drink-boot3/sql/migrations/2026-10-05-printer-management.sql').read_text())
        if '2026-10-08-coupon-permissions.sql' in MIGRATIONS:
            self.report['stage']='MIGRATION:2026-10-08-coupon-permissions.sql'
            self.sql((self.repo/'yshop-drink-boot3/sql/migrations/2026-10-08-coupon-permissions.sql').read_text())
        if configure:configure(self,base)
        config=self.file('application.yaml',yaml.safe_dump(base,allow_unicode=True))
        self.report['backendArtifactHash']=hashlib.sha256(jar.read_bytes()).hexdigest()
        for index in range(instances):
            self.report['stage']='BACKEND_READINESS'
            suffix='-second' if index else ''
            java=['java'];agent=os.environ.get('YSHOP_QUALITY_COVERAGE_AGENT')
            if agent and index==0:
                if not Path(agent).is_file():raise RuntimeError('EXPLICIT_COVERAGE_AGENT_REQUIRED')
                java+=['-javaagent:'+agent+'=destfile='+str(self.root/'backend-coverage.exec')+',append=false']
            command=(launcher(config,self.port+index) if launcher else java+['-jar',str(jar),'--spring.config.location=file:'+str(config),'--server.port='+str(self.port+index)])
            log=self.root/('backend'+suffix+'.log')
            with log.open('w') as output:
                os.chmod(log,0o600)
                child=subprocess.Popen(command,cwd=self.repo,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
            self.processes.append(child)
            self.file('process'+suffix+'.json.tmp',json.dumps({'pid':child.pid,'port':self.port+index})).replace(self.root/('process'+suffix+'.json'))
            for _ in range(120):
                if child.poll() is not None:raise RuntimeError('ISOLATED_BACKEND_EXITED')
                try:
                    with urllib.request.urlopen('http://127.0.0.1:'+str(self.port+index)+'/actuator/info',timeout=2) as response:
                        assert_backend_owner(json.load(response),self.owner)
                    break
                except Exception:time.sleep(1)
            else:raise RuntimeError('ISOLATED_BACKEND_TIMEOUT')
        self.ready=True;self.report.update(backendOwnershipVerified=True,port=self.port,stage='READY')
        return self

    def context(self):
        if not self.ready or self.closed:raise RuntimeError('ENVIRONMENT_NOT_READY')
        return {**self.identity,'owner':self.owner,'runId':self.run_id,'backend':'http://127.0.0.1:'+str(self.port),
                'mysql':self.mysql,'schema':self.schema,'oracleConfig':'/run/oracle.cnf'}

    def close(self):
        if self.closed:return self.cleanup_receipt
        errors=[]
        for child in reversed(self.processes):
            if child.poll() is None:
                try:
                    os.killpg(child.pid,signal.SIGTERM)
                    try:child.wait(timeout=20)
                    except subprocess.TimeoutExpired:os.killpg(child.pid,signal.SIGKILL);child.wait(timeout=5)
                except Exception:errors.append('BACKEND_CLEANUP_FAILED')
        resources={}
        try:resources=remove_owned(self.docker,self.names,self.owner)
        except Exception:errors.append('RESOURCE_CLEANUP_FAILED')
        for name in ['root.secret','client.cnf','oracle.cnf','redis.conf','application.yaml','logback.xml']:
            (self.root/name).unlink(missing_ok=True)
        self.cleanup_receipt={'status':'FAILED' if errors else 'PASSED',**resources,'errors':errors}
        self.report.update(cleanup='FAIL' if errors else 'PASS',resources=resources)
        self.closed=True;self.ready=False
        return self.cleanup_receipt
