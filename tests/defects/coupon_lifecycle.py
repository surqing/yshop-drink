#!/usr/bin/env python3
import argparse,hashlib,json,os,re,signal,subprocess,sys,threading,time,traceback,urllib.request,uuid,xml.etree.ElementTree as ET
from pathlib import Path
import yaml
p=argparse.ArgumentParser();p.add_argument('--repo',default=str(Path(__file__).resolve().parents[2]));p.add_argument('--output',required=True);p.add_argument('--java-report',required=True);a=p.parse_args()
repo=Path(a.repo).resolve();assets=Path(__file__).resolve().parent;sys.path.insert(0,str(repo/'tests/quality'))
from evidence import source_identity,execute
from backend_artifact import verify_artifact
from loopback_ports import backend_pair
identity=source_identity(repo);verify_artifact(repo/'yshop-drink-boot3/yshop-server/target/yshop-server.jar',repo/'.quality/backend-build.json',identity)
root=Path(a.output).resolve()
if root.is_relative_to(repo):raise RuntimeError('PRIVATE_OUTPUT_OUTSIDE_SOURCE_REQUIRED')
root.mkdir(parents=True,mode=0o700,exist_ok=False);os.umask(0o077);owned=root/'owned';port=backend_pair();base='http://127.0.0.1:'+str(port)
report={**identity,'runId':uuid.uuid4().hex,'startedAt':time.time(),'status':'INCONCLUSIVE','checks':[],'testAssets':{n:hashlib.sha256((assets/n).read_bytes()).hexdigest() for n in ['coupon_lifecycle.py','coupon-pages.cjs','SyntheticCouponCompletion.java']},'cleanup':'NOT_RUN','scope':'official simulator pages with real HTTP/SQL; trusted synthetic PaymentFinalizationService using existing test configuration ; existing fixture stubs notification/statistics/cart adapters and delegates balance updates to the real mapper'}
def save(): (root/'report.json').write_text(json.dumps(report,indent=2))
log=(root/'launcher-private.log').open('w');child=subprocess.Popen([sys.executable,'tests/quality/business-backend.py','--output',str(owned),'--port',str(port),'--hold-for-device','900'],cwd=repo,env=os.environ.copy(),stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
def check(name,ok):
 report['checks'].append({'name':name,'status':'PASSED' if ok else 'FAILED','executed':True});save()
 if not ok:raise AssertionError(name)
def api(method,path,data=None,token=None):
 if not path.startswith(('/app-api/','/admin-api/')) or re.search(r'/pay(?:/|$)|/prepay|/refund|/recharge|/notify|/weixin|/wechat',path):raise RuntimeError('PROVIDER_PATH_FORBIDDEN')
 req=urllib.request.Request(base+path,data=json.dumps(data).encode() if data is not None else None,headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})},method=method)
 with urllib.request.urlopen(req,timeout=30) as r:v=json.load(r)
 if v['code']!=0:raise RuntimeError('FIXTURE_API_REJECTED')
 return v['data']
try:
 deadline=time.monotonic()+400
 while not (owned/'gui-ready.json').exists():
  if child.poll() is not None:raise RuntimeError('OWNED_LAUNCHER_EXITED')
  if time.monotonic()>deadline:raise RuntimeError('OWNED_LAUNCHER_TIMEOUT')
  time.sleep(.3)
 ready=json.loads((owned/'gui-ready.json').read_text());owner=ready['owner'];mysql='yshop-quality-business-mysql-'+owner;schema='yshop_quality_business_'+owner[:16]
 with urllib.request.urlopen(base+'/actuator/info',timeout=10) as r:assert json.load(r)['qualityOwner']==owner
 metadata=json.loads(subprocess.check_output(['docker','inspect',mysql],text=True))[0];assert metadata['Config']['Labels']['yshop.quality.owner']==owner
 def sql(q):
  r=subprocess.run(['docker','exec','-i',mysql,'mysql','--defaults-extra-file=/run/client.cnf',schema,'-N'],input=q,text=True,capture_output=True,timeout=30)
  if r.returncode:raise RuntimeError('OWNED_SQL_FAILED')
  return r.stdout.strip()
 admin=api('POST','/admin-api/system/auth/login',{'username':'qualityadmin','password':'syntheticQA12'})['accessToken'];member=api('POST','/app-api/member/auth/login',{'mobile':'13800000001','password':'syntheticQA12'})['accessToken']
 now=int(time.time()*1000);coupon=api('POST','/admin-api/coupon/create',{'shopId':'101','title':'QA Page Lifecycle','isSwitch':1,'least':0,'value':5,'startTime':now-86400000,'endTime':now+86400000,'claimStartTime':now-86400000,'claimEndTime':now+86400000,'type':0,'distribute':10,'limit':1,'claimMode':'PUBLIC','couponKind':'REGULAR','score':0,'receive':0},admin)
 # The exact fresh ordinary test classpath provides the existing financial fixture, JUnit and fake transport dependencies.
 jr=json.loads((Path(a.java_report)/'report.json').read_text());assert all(jr[k]==v for k,v in identity.items()) and jr.get('complete') and jr.get('result')=='PASS' and jr.get('sourceUnchanged')
 xmls=list(Path(a.java_report).glob('java-evidence/*/surefire/TEST-co.yixiang.yshop.module.order.payment.PaymentDatabaseTest.xml'));assert len(xmls)==1
 tree=ET.parse(xmls[0]);cp=next(v.get('value') for v in tree.findall('properties/property') if v.get('name')=='java.class.path')
 classes=root/'helper-classes';classes.mkdir()
 if execute(['javac','-cp',cp,'-d',str(classes),str(assets/'SyntheticCouponCompletion.java')],repo,os.environ.copy(),root/'helper-compile-private.log',90):raise RuntimeError('SYNTHETIC_HELPER_COMPILE_FAILED')
 ds=yaml.safe_load((owned/'application.yaml').read_text())['spring']['datasource']['dynamic']['datasource']['master'];settings=root/'synthetic-db.properties';settings.write_text('\n'.join(k+'='+ds[k] for k in ['url','username','password']));settings.chmod(0o600)
 checks=re.findall(r"await check\('([^']+)'",(assets/'coupon-pages.cjs').read_text());assert len(checks)==12
 import mini_pages as mini
 os.environ['YSHOP_AUTOMATOR_PATH']=str(repo/'tests/mini/node_modules/miniprogram-automator')
 bridge_errors=[]
 def bridge(stop):
  try:
   request=root/'mini/payment-needed.json'
   while not request.exists():
    if stop.wait(.1):return
   order=json.loads(request.read_text())['orderId'];assert re.fullmatch('[a-zA-Z0-9_-]{1,64}',order)
   check('page claim created exactly one right',sql('SELECT COUNT(*) FROM yshop_coupon_user WHERE coupon_id='+str(coupon)+' AND user_id=101')=='1')
   check('page order reserved coupon and nineteen minus five price',sql("SELECT o.pay_price,o.paid,c.status,c.reserved_order_id=o.order_id FROM yshop_store_order o JOIN yshop_coupon_user c ON c.id=o.coupon_id WHERE o.order_id='"+order+"'")=='14.00\t0\t1\t1')
   result=root/'synthetic-result.json'
   if execute(['java','-cp',str(classes)+os.pathsep+cp,'co.yixiang.yshop.module.order.payment.SyntheticCouponCompletion',str(settings),order,str(result)],repo,os.environ.copy(),root/'helper-private.log',120):raise RuntimeError('SYNTHETIC_HELPER_FAILED')
   report['syntheticCompletion']=json.loads(result.read_text())
   check('synthetic success and duplicate redeem exactly once',sql("SELECT COUNT(*) FROM yshop_coupon_operation WHERE coupon_id="+str(coupon)+" AND kind='REDEEM'")=='1' and sql("SELECT paid FROM yshop_store_order WHERE order_id='"+order+"'")=='1')
   check('one financial receipt and one matching business ledger',sql("SELECT COUNT(*) FROM yshop_order_payment WHERE order_id='"+order+"' AND status='SUCCESS'")=='1' and sql("SELECT COUNT(*) FROM yshop_user_bill WHERE extend_field='"+order+"' AND number=14.00")=='1')
   check('paid coupon stays reserved and inventory is not released',sql("SELECT COUNT(*) FROM yshop_coupon_operation WHERE coupon_id="+str(coupon)+" AND kind='RELEASE'")=='0' and sql('SELECT stock FROM yshop_store_product WHERE shop_id=101 ORDER BY id LIMIT 1')=='9')
   (root/'mini/payment-done.json').write_text('done')
  except BaseException as e:bridge_errors.append(type(e).__name__);(root/'bridge-failure-private.log').write_text(traceback.format_exc())
 def execute_with_bridge(command,cwd,env,log,timeout=1800,**kwargs):
  if command[0]=='node' and str(command[1]).endswith('coupon-pages.cjs'):
   stop=threading.Event();thread=threading.Thread(target=bridge,args=(stop,),daemon=True);thread.start()
   try:return execute(command,cwd,env,log,timeout,**kwargs)
   finally:stop.set();thread.join(timeout=130)
  return execute(command,cwd,env,log,timeout,**kwargs)
 mini.execute=execute_with_bridge
 report['mini']=mini.run_mini(root,report,{'backend':base,'member':member},script=str(assets/'coupon-pages.cjs'),checks=checks)
 if bridge_errors:raise RuntimeError('SYNTHETIC_BRIDGE_FAILED')
 if report['mini'].get('result')!='PASSED':raise RuntimeError('MINI_NOT_PASSED')
 report['status']='PASSED'
except Exception as e:
 report.update(status='FAILED' if isinstance(e,AssertionError) else 'INCONCLUSIVE',errorType=type(e).__name__);(root/'failure-private.log').write_text(traceback.format_exc())
finally:
 if (owned/'gui-ready.json').exists():(owned/'gui-finished').write_text('done')
 elif child.poll() is None:os.killpg(child.pid,signal.SIGTERM)
 try:child.wait(timeout=120)
 except subprocess.TimeoutExpired:
  os.killpg(child.pid,signal.SIGTERM);child.wait(timeout=120)
 log.close()
 if (owned/'report.json').exists():
  launch=json.loads((owned/'report.json').read_text());report['cleanup']='PASSED' if launch.get('cleanup')=='PASS' else 'FAILED'
 (root/'synthetic-db.properties').unlink(missing_ok=True)
 report['sourceUnchanged']=source_identity(repo)==identity;report['endedAt']=time.time();report['complete']=True
 if report['cleanup']!='PASSED' or not report['sourceUnchanged']:report['status']='INCONCLUSIVE'
 save()
print(json.dumps({'status':report['status'],'cleanup':report['cleanup'],'report':str(root/'report.json')}));sys.exit(0 if report['status']=='PASSED' else 1)
