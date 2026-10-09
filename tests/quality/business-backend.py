#!/usr/bin/env python3
"""Actual isolated HTTP writes; no seed INSERTs, no shared data, no provider routes."""
import argparse,json,os,secrets,subprocess,sys,time,uuid,re,signal
from evidence import source_identity
from owned_resources import remove_owned,assert_backend_owner
from pathlib import Path
import yaml
repo=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser();parser.add_argument('--output',required=True);parser.add_argument('--port',type=int,default=48883);parser.add_argument('--hold-for-gui',type=int,default=0);args=parser.parse_args()
if not 0<=args.hold_for_gui<=900:raise RuntimeError('GUI_HOLD_LIMIT_REQUIRED')
if not 1024<=args.port<=65535:raise RuntimeError('INVALID_LOOPBACK_PORT')
owner=uuid.uuid4().hex;root=Path(args.output).resolve();root.mkdir(parents=True,mode=0o700,exist_ok=False)
mysql='yshop-quality-business-mysql-'+owner;redis='yshop-quality-business-redis-'+owner;names=[mysql,redis];process=None;volumes=[]
schema='yshop_quality_business_'+owner[:16];pw=secrets.token_hex(24);rpw=secrets.token_hex(24)
report={'owner':owner,'result':'FAIL','cleanup':'NOT_RUN',**source_identity(),'scope':'owned disposable MySQL/Redis/backend HTTP writes','gui':'NOT_EXECUTED','mini':'NOT_EXECUTED'}
def file(n,s):
 p=root/n;p.write_text(s);p.chmod(0o600);return p
def docker(*args,input=None):
 p=subprocess.run(['docker',*args],input=input,text=True,capture_output=True,timeout=180)
 if p.returncode:
  file('operation-failure.log',p.stderr);raise RuntimeError('DOCKER_OPERATION_FAILED')
 return p.stdout.strip()
def sql(s):return docker('exec','-i',mysql,'mysql','--defaults-extra-file=/run/client.cnf',schema,'-N',input=s)
def merge(a,b):
 for k,v in b.items():
  if isinstance(v,dict) and isinstance(a.get(k),dict):merge(a[k],v)
  else:a[k]=v
try:
 secret=file('root.secret',pw);client=file('client.cnf','[client]\ndefault-character-set=utf8mb4\nuser=root\npassword='+pw+'\n')
 docker('run','-d','--name',mysql,'--label','yshop.quality.owner='+owner,'--cap-drop=NET_RAW','--security-opt','no-new-privileges','-p','127.0.0.1::3306','--mount','type=bind,source='+str(secret)+',target=/run/root.secret,readonly','--mount','type=bind,source='+str(client)+',target=/run/client.cnf,readonly','-e','MYSQL_ROOT_PASSWORD_FILE=/run/root.secret','mysql:8.0')
 for _ in range(100):
  try:docker('exec',mysql,'mysql','--defaults-extra-file=/run/client.cnf','-N','-e','SELECT 1');break
  except RuntimeError:time.sleep(1)
 else:raise RuntimeError('MYSQL_NOT_READY')
 docker('exec',mysql,'mysql','--defaults-extra-file=/run/client.cnf','-e','CREATE DATABASE '+schema+' CHARACTER SET utf8mb4')
 port=int(json.loads(docker('inspect',mysql))[0]['NetworkSettings']['Ports']['3306/tcp'][0]['HostPort'])
 config=file('redis.conf','bind 0.0.0.0\nprotected-mode yes\nrequirepass '+rpw+'\nsave ""\nappendonly no\n')
 docker('run','-d','--name',redis,'--label','yshop.quality.owner='+owner,'--cap-drop=NET_RAW','-p','127.0.0.1::6379','--mount','type=bind,source='+str(config)+',target=/run/redis.conf,readonly','redis:7.4','redis-server','/run/redis.conf')
 redisport=int(json.loads(docker('inspect',redis))[0]['NetworkSettings']['Ports']['6379/tcp'][0]['HostPort'])
 seed=(repo/'yshop-drink-boot3/sql/yixiang-drink-open.sql').read_text();ddl=re.findall(r'CREATE TABLE `[^`]+` \(.*?\) ENGINE=.*?;',seed,re.S)
 if not ddl or any('INSERT INTO' in x for x in ddl):raise RuntimeError('SCHEMA_ONLY_REQUIRED')
 sql('SET FOREIGN_KEY_CHECKS=0;\n'+'\n'.join(ddl)+'\nSET FOREIGN_KEY_CHECKS=1;');report['seedInsertRowsCopied']=0
 migrations=repo/'yshop-drink-boot3/sql/migrations'
 files=[migrations/n for n in ['payment-credential-columns.sql','2026-10-03-payment-finalization.sql','2026-10-03-wallet-ledger.sql','2026-10-04-payment-attempt.sql','2026-10-04-wechat-v3.sql','2026-10-04-payment-live-readiness.sql','2026-10-08-multistore-ordering.sql','2026-10-08-product-catalog.sql','2026-10-08-coupon-marketing.sql','2026-10-08-coupon-permissions.sql']]
 for p in files:
  report['stage']='MIGRATION:'+p.name;sql(p.read_text())
 # A dedicated DB account, never reuse developer or merchant credentials.
 dbuser='qa_business_'+owner[:16];dbpw=secrets.token_hex(24)
 docker('exec','-i',mysql,'mysql','--defaults-extra-file=/run/client.cnf',input="CREATE USER '"+dbuser+"'@'%' IDENTIFIED BY '"+dbpw+"'; GRANT ALL ON "+schema+".* TO '"+dbuser+"'@'%';")
 base={}
 allowed={'spring','mybatis-plus','mybatis-plus-join','yshop','aj','easy-trans'}
 for doc in yaml.safe_load_all((repo/'yshop-drink-boot3/yshop-server/src/main/resources/application.yaml').read_text()):
  if doc:merge(base,{k:v for k,v in doc.items() if k in allowed})
 merge(base,{'server':{'address':'127.0.0.1','port':args.port},'spring':{'profiles':{'active':'quality-isolated'},'datasource':{'dynamic':{'primary':'master','datasource':{'master':{'url':'jdbc:mysql://127.0.0.1:'+str(port)+'/'+schema+'?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC','username':dbuser,'password':dbpw,'driver-class-name':'com.mysql.cj.jdbc.Driver'}}}},'data':{'redis':{'host':'127.0.0.1','port':redisport,'password':rpw,'database':0}},'autoconfigure':{'exclude':['org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration']}},'yshop':{'demo':False,'tenant':{'enable':False},'captcha':{'enable':False},'pay':{'wechat-v3':{'enabled':False,'reconciliation-enabled':False}}},'wx':{'mp':{'app-id':'synthetic-quality-local','secret':'synthetic-test-only-no-provider'},'miniapp':{'appid':'synthetic-quality-local','secret':'synthetic-test-only-no-provider'}},'logging':{'level':{'root':'WARN','co.yixiang':'WARN'}}})
 merge(base,{'management':{'endpoints':{'web':{'exposure':{'include':['health','info']}}},'info':{'env':{'enabled':True}}},'info':{'qualityOwner':owner}})
 logging=file('logback.xml','<configuration><appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="CONSOLE"/></root></configuration>')
 base['logging']['config']='file:'+str(logging)
 from business_http import seed,smoke
 seed(sql,root,repo)
 config=file('application.yaml',yaml.safe_dump(base,allow_unicode=True));log=root/'backend.log'
 with log.open('w') as out:
  log.chmod(0o600);process=subprocess.Popen(['java','-jar',str(repo/'yshop-drink-boot3/yshop-server/target/yshop-server.jar'),'--spring.config.location=file:'+str(config)],stdout=out,stderr=subprocess.STDOUT,start_new_session=True)
 file('process.json',json.dumps({'pid':process.pid,'port':args.port}));report['port']=args.port
 import urllib.request
 for _ in range(120):
  if process.poll() is not None:raise RuntimeError('ISOLATED_BACKEND_EXITED')
  try:
   with urllib.request.urlopen('http://127.0.0.1:'+str(args.port)+'/actuator/health',timeout=2) as r:
    if json.load(r).get('status')=='UP':report['stage']='ISOLATED_BACKEND_HEALTH';break
  except Exception:time.sleep(1)
 else:raise RuntimeError('ISOLATED_BACKEND_TIMEOUT')
 with urllib.request.urlopen('http://127.0.0.1:'+str(args.port)+'/actuator/info',timeout=2) as response:
  assert_backend_owner(json.load(response),owner)
 report['backendOwnershipVerified']=True
 smoke(sql,root,report,args.port)
 if args.hold_for_gui:
  file('gui-ready.json',json.dumps({'owner':owner,'backendPort':args.port,'result':'READY','syntheticAccount':True}))
  deadline=time.time()+args.hold_for_gui
  while time.time()<deadline and not (root/'gui-finished').exists():time.sleep(1)
 if source_identity()!={k:report[k] for k in ['sourceSha','sourceDigest']}:raise RuntimeError('SOURCE_CHANGED_DURING_TEST')
except Exception as e:report['reasonType']=type(e).__name__;report['failedStage']=report.get('stage','PROVISION')
finally:
 try:
  if process and process.poll() is None:
   os.killpg(process.pid,signal.SIGTERM)
   try:process.wait(timeout=20)
   except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait(timeout=5)
  report['resources']=remove_owned(docker,names,owner)
  report['cleanup']='PASS'
 except Exception:report['cleanup']='FAIL';report['result']='FAIL'
 for n in ['root.secret','client.cnf','redis.conf','application.yaml','logback.xml']:(root/n).unlink(missing_ok=True)
 (root/'report.json').write_text(json.dumps(report,indent=2));print(json.dumps({'result':report['result'],'cleanup':report['cleanup'],'checks':len(report.get('checks',[]))}))
sys.exit(0 if report['result']=='PASS' and report['cleanup']=='PASS' else 1)
