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
                      'substitutes':['WeChat Mini provider','SMS provider transport'],
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

    def launch(self,config,port):
        return ['java','-De2e.owner='+self.owner,'-De2e.root='+str(self.root),'-cp',self.classpath,
                'e2esupport.OwnedE2eApplication','--spring.config.location=file:'+str(config),'--server.port='+str(port)]

    def context(self):
        return {'commandDir':str(self.mailbox),'smsOutbox':str(self.root/'outbox')}
