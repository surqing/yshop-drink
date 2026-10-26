"""Compile only test-support sources against this run's verified production artifact."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile
from backend_artifact import verify_artifact
from evidence import source_identity, execute


def extract_application(jar, destination):
    destination=Path(destination)
    with zipfile.ZipFile(jar) as bundle:
        entries=[]
        for entry in bundle.infolist():
            if entry.is_dir():continue
            if entry.filename.startswith('BOOT-INF/classes/'):
                relative=Path('classes')/entry.filename[len('BOOT-INF/classes/'):]
            elif entry.filename.startswith('BOOT-INF/lib/'):
                relative=Path('lib')/entry.filename[len('BOOT-INF/lib/'):]
            elif entry.filename.startswith('META-INF/spring/') or entry.filename=='META-INF/spring.factories':
                relative=Path('classes')/entry.filename
            else:continue
            if '..' in relative.parts or relative.is_absolute():raise ValueError('ARTIFACT_PATH_INVALID')
            if entry.filename.startswith('BOOT-INF/classes/e2esupport/'):
                raise ValueError('TEST_SUPPORT_IN_PRODUCTION_ARTIFACT')
            entries.append((entry,relative))
        for entry,relative in entries:
            target=destination/relative;target.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
            target.write_bytes(bundle.read(entry));target.chmod(0o600)
    return [str(destination/'classes'),str(destination/'lib/*')]


class TestSupport:
    def __init__(self,repo,root,identity,owner):
        self.repo=Path(repo);self.root=Path(root);self.identity=identity;self.owner=owner
        self.mailbox=self.root/('commands-'+owner)
        self.tenant_enabled_port=None

    def prepare(self):
        jar=self.repo/'yshop-drink-boot3/yshop-server/target/yshop-server.jar'
        certificate=verify_artifact(jar,self.repo/'.quality/backend-build.json',self.identity)
        self.root.mkdir(parents=True,mode=0o700,exist_ok=False)
        for folder in [self.mailbox,self.mailbox/'requests',self.mailbox/'responses',self.root/'outbox']:
            folder.mkdir(mode=0o700)
        classpath=extract_application(jar,self.root/'application')
        classes=self.root/'classes';classes.mkdir(mode=0o700)
        source=self.repo/'yshop-drink-boot3/yshop-server/src/test/java/e2esupport/OwnedE2eApplication.java'
        command=['javac','-cp',os.pathsep.join(classpath),'-d',str(classes),str(source)]
        if execute(command,self.repo,os.environ.copy(),self.root/'compile-private.log',120):
            raise RuntimeError('TEST_SUPPORT_COMPILE_FAILED')
        if source_identity(self.repo)!=self.identity:raise RuntimeError('SOURCE_CHANGED_DURING_TEST')
        self.classpath=os.pathsep.join([str(classes),*classpath])
        self.receipt={**self.identity,'productionArtifactHash':certificate['artifactHash'],
                      'sourceHash':hashlib.sha256(source.read_bytes()).hexdigest(),
                      'classes':{str(p.relative_to(classes)):hashlib.sha256(p.read_bytes()).hexdigest() for p in classes.rglob('*.class')},
                      'testSupportExcludedFromProductionJar':True,
                      'substitutes':['WeChat Mini provider','SMS provider transport','WeChat MP provider deny','payment SDK provider deny'],
                      'businessServices':'real application services, mappers and transactions'}
        return self.receipt

    def configure(self,environment,config):
        if environment.owner!=self.owner:raise ValueError('TEST_SUPPORT_OWNER_MISMATCH')
        environment.sql("INSERT INTO system_sms_channel(id,signature,code,status,api_key,api_secret) VALUES(901,'E2E','E2E',0,'synthetic','synthetic');"+
                        "INSERT INTO system_sms_template(id,type,status,code,name,content,params,api_template_id,channel_id,channel_code) VALUES(901,1,0,'user-sms-login','E2E login','code {code}','[\"code\"]','e2e-login',901,'E2E');")
        config['yshop']['sms-code']={'expire-times':'10m','send-frequency':'1s','send-maximum-quantity-per-day':100,
                                    'begin-code':100000,'end-code':999999}
        config['info']['e2eTestSupport']='provider-boundaries-only'
        config['yshop']['payment']={'recovery-delay-ms':3600000}

    def configure_printer_recovery(self,environment):
        # Synthetic least-privilege operator identities for the R2 browser/API
        # scenario. The business action itself still runs through the real HTTP
        # controller, authorization interceptor, service and transaction.
        environment.sql(
            "INSERT INTO system_tenant(id,name,contact_name,status,domain,package_id,expire_time,account_count,creator,updater) "
            "VALUES(901,'QA printer recovery tenant','Synthetic QA',0,'',0,'2099-12-31 23:59:59',20,'quality','quality'),"
            "(902,'QA printer recovery tenant B','Synthetic QA',0,'',0,'2099-12-31 23:59:59',20,'quality','quality');"
            "INSERT INTO system_role(id,name,code,sort,status,type,tenant_id) VALUES"
            "(910110,'QA printer operators','business_headquarters',1,0,2,0),"
            "(910111,'QA printer query','business_headquarters',2,0,2,0),"
            "(910112,'QA printer no query','business_headquarters',3,0,2,0),"
            "(910113,'QA printer tenant','business_headquarters',4,0,2,901),"
            "(910117,'QA printer tenant B','business_headquarters',5,0,2,902);"
            "INSERT INTO system_users(id,username,password,nickname,status,tenant_id) "
            "SELECT 910110,'qualityhq',password,'synthetic-printer-hq',0,0 FROM system_users WHERE id=101;"
            "INSERT INTO system_users(id,username,password,nickname,status,tenant_id) "
            "SELECT 910114,'qualityhq2',password,'synthetic-printer-hq-2',0,0 FROM system_users WHERE id=101;"
            "INSERT INTO system_users(id,username,password,nickname,status,tenant_id) "
            "SELECT 910115,'qualityhqrevoked',password,'synthetic-printer-hq-revoked',0,0 FROM system_users WHERE id=101;"
            "INSERT INTO system_users(id,username,password,nickname,status,tenant_id) "
            "SELECT 910111,'qualityhqquery',password,'synthetic-printer-query',0,0 FROM system_users WHERE id=101;"
            "INSERT INTO system_users(id,username,password,nickname,status,tenant_id) "
            "SELECT 910112,'qualityhqnoquery',password,'synthetic-printer-no-query',0,0 FROM system_users WHERE id=101;"
            "INSERT INTO system_users(id,username,password,nickname,status,tenant_id) "
            "SELECT 910113,'qualityhqcross',password,'synthetic-printer-cross-tenant',0,901 FROM system_users WHERE id=101;"
            "INSERT INTO system_users(id,username,password,nickname,status,tenant_id) "
            "SELECT 910117,'qualityhqother',password,'synthetic-printer-other-tenant',0,902 FROM system_users WHERE id=101;"
            "INSERT INTO system_user_role(user_id,role_id,tenant_id) VALUES"
            "(910110,910110,0),(910114,910110,0),(910115,910110,0),"
            "(910111,910111,0),(910112,910112,0),(910113,910113,901),(910117,910117,902);"
            "INSERT INTO system_role_menu(role_id,menu_id,tenant_id) "
            "SELECT 910110,id,0 FROM system_menu WHERE id IN (2000,2308) OR permission IN ('store:web-print:query','store:web-print:authorize');"
            "INSERT INTO system_role_menu(role_id,menu_id,tenant_id) "
            "SELECT 910111,id,0 FROM system_menu WHERE id IN (2000,2308) OR permission='store:web-print:query';"
            "INSERT INTO system_role_menu(role_id,menu_id,tenant_id) "
            "SELECT 910113,id,901 FROM system_menu WHERE id IN (2000,2308) OR permission IN "
            "('store:web-print:query','store:web-print:authorize','store:web-print:create','store:web-print:test');"
            "INSERT INTO system_role_menu(role_id,menu_id,tenant_id) "
            "SELECT 910117,id,902 FROM system_menu WHERE id IN (2000,2308) OR permission IN "
            "('store:web-print:query','store:web-print:authorize','store:web-print:create','store:web-print:test');"
        )

    def configure_batch_stock_access(self,environment):
        # Ordinary synthetic users exercise the real admin login, method-security
        # checks, role-menu cache eviction, and fresh store assignment lookup.
        environment.sql(
            "INSERT INTO system_role(id,name,code,sort,data_scope,data_scope_dept_ids,status,type,tenant_id) VALUES"
            "(910120,'R4 stock writer','r4_stock_writer',1,1,'',0,2,0),"
            "(910121,'R4 stock query','r4_stock_query',2,1,'',0,2,0),"
            "(910122,'R4 stock peer','r4_stock_peer',3,1,'',0,2,0),"
            "(910123,'R4 stock assignment revocation','r4_stock_assignment',4,1,'',0,2,0);"
            "INSERT INTO system_users(id,username,password,nickname,status,tenant_id) "
            "SELECT 910120,'r4stockwriter',password,'synthetic-r4-writer',0,0 FROM system_users WHERE id=101;"
            "INSERT INTO system_users(id,username,password,nickname,status,tenant_id) "
            "SELECT 910121,'r4stockquery',password,'synthetic-r4-query',0,0 FROM system_users WHERE id=101;"
            "INSERT INTO system_users(id,username,password,nickname,status,tenant_id) "
            "SELECT 910122,'r4stockpeer',password,'synthetic-r4-peer',0,0 FROM system_users WHERE id=101;"
            "INSERT INTO system_users(id,username,password,nickname,status,tenant_id) "
            "SELECT 910123,'r4stockassign',password,'synthetic-r4-assignment',0,0 FROM system_users WHERE id=101;"
            "INSERT INTO system_user_role(user_id,role_id,tenant_id) VALUES"
            "(910120,910120,0),(910121,910121,0),(910122,910122,0),(910123,910123,0);"
            "INSERT INTO system_role_menu(role_id,menu_id,tenant_id) SELECT 910120,id,0 FROM system_menu WHERE id IN (2169,2171);"
            "INSERT INTO system_role_menu(role_id,menu_id,tenant_id) SELECT 910121,id,0 FROM system_menu WHERE id=2169;"
            "INSERT INTO system_role_menu(role_id,menu_id,tenant_id) SELECT 910122,id,0 FROM system_menu WHERE id IN (2169,2171);"
            "INSERT INTO system_role_menu(role_id,menu_id,tenant_id) SELECT 910123,id,0 FROM system_menu WHERE id IN (2169,2171);"
            "UPDATE yshop_store_shop SET admin_id=CONCAT(admin_id,',910120,910121,910122,910123') WHERE id=101;"
        )

    def launch(self,config,port):
        command=['java','-De2e.owner='+self.owner,'-De2e.root='+str(self.root),'-cp',self.classpath,
                 'e2esupport.OwnedE2eApplication','--spring.config.location=file:'+str(config),'--server.port='+str(port)]
        if port==self.tenant_enabled_port:
            # This second app instance exists only to exercise the tenant-enabled
            # printer HTTP boundary against the shared owned schema. Its scheduled
            # worker must not race the primary instance for test jobs or split the
            # synthetic provider-call counters across JVMs; E2 invokes the real
            # worker explicitly through the primary owned mailbox.
            command.extend(['--yshop.tenant.enable=true','--e2e.printer-tenant-probe=true',
                            '--yshop.printer.order-print-poll-ms=2147483647'])
        return command

    def context(self):
        return {'commandDir':str(self.mailbox),'smsOutbox':str(self.root/'outbox')}
