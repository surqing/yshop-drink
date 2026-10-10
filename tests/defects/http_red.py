#!/usr/bin/env python3
"""Isolated HTTP/SQL defect regressions. Failed business assertions remain FAILED."""
import argparse, hashlib, json, os, re, signal, subprocess, sys, time, traceback, urllib.request, urllib.error, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--repo',default=str(Path(__file__).resolve().parents[2]));p.add_argument('--output',required=True);p.add_argument('--repeat',type=int,choices=range(1,11),default=2);a=p.parse_args()
repo=Path(a.repo).resolve();sys.path.insert(0,str(repo/'tests/quality'))
from evidence import source_identity
from backend_artifact import verify_artifact
from loopback_ports import backend_pair
identity=source_identity(repo);verify_artifact(repo/'yshop-drink-boot3/yshop-server/target/yshop-server.jar',repo/'.quality/backend-build.json',identity)
root=Path(a.output).resolve()
if root.is_relative_to(repo):raise RuntimeError('PRIVATE_OUTPUT_OUTSIDE_SOURCE_REQUIRED')
root.mkdir(parents=True,mode=0o700,exist_ok=False);os.umask(0o077)
runid=uuid.uuid4().hex;port=backend_pair();owned=root/'owned';base='http://127.0.0.1:'+str(port)
report={**identity,'runId':runid,'startedAt':time.time(),'scope':'real HTTP and owned MySQL; synthetic members/points only','cases':[],'cleanup':'NOT_RUN','status':'INCONCLUSIVE','financialProviderRequests':{'value':0,'scope':'this loopback HTTP harness path guard only','globalNetworkMeasured':False},'testScriptSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
log=(root/'launcher-private.log').open('w')
child=subprocess.Popen([sys.executable,'tests/quality/business-backend.py','--output',str(owned),'--port',str(port),'--hold-for-device','900'],cwd=repo,env=os.environ.copy(),stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
def save(): (root/'report.json').write_text(json.dumps(report,indent=2,ensure_ascii=False))
def api(method,path,body=None,token=None):
 if not path.startswith(('/app-api/','/admin-api/')) or re.search(r'/pay(?:/|$)|/prepay|/refund|/recharge|/notify|/weixin|/wechat',path):raise RuntimeError('PROVIDER_PATH_FORBIDDEN')
 req=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})},method=method)
 try:
  with urllib.request.urlopen(req,timeout=30) as r:return json.load(r)
 except urllib.error.HTTPError as e:return json.load(e)
def login(mobile):
 r=api('POST','/app-api/member/auth/login',{'mobile':mobile,'password':'syntheticQA12'});
 if r['code']!=0:raise RuntimeError('FIXTURE_LOGIN_FAILED')
 return r['data']['accessToken']
def scalar(q):return sql(q).strip()
def n(q):return int(scalar(q))
def case(id,title,fn):
 for repetition in range(1,a.repeat+1):
  row={'id':id,'name':title,'repetition':repetition,'status':'INCONCLUSIVE','actual':{}};report['cases'].append(row)
  try:fn(row['actual']);row['status']='PASSED'
  except AssertionError as e:row.update(status='FAILED',assertion=str(e))
  except Exception as e:row.update(status='INCONCLUSIVE',errorType=type(e).__name__);(root/(id+'-'+str(repetition)+'-private.log')).write_text(traceback.format_exc())
  save();print(id,repetition,row['status'],json.dumps(row['actual']),flush=True)
try:
 deadline=time.monotonic()+400
 while not (owned/'gui-ready.json').exists():
  if child.poll() is not None:raise RuntimeError('OWNED_LAUNCHER_EXITED')
  if time.monotonic()>deadline:raise RuntimeError('OWNED_LAUNCHER_TIMEOUT')
  time.sleep(.3)
 ready=json.loads((owned/'gui-ready.json').read_text());owner=ready['owner']
 with urllib.request.urlopen(base+'/actuator/info',timeout=10) as r:assert json.load(r)['qualityOwner']==owner
 mysql='yshop-quality-business-mysql-'+owner;schema='yshop_quality_business_'+owner[:16]
 metadata=json.loads(subprocess.check_output(['docker','inspect',mysql],text=True))[0];assert metadata['Config']['Labels']['yshop.quality.owner']==owner
 def sql(q):
  r=subprocess.run(['docker','exec','-i',mysql,'mysql','--defaults-extra-file=/run/client.cnf',schema,'-N'],input=q,text=True,capture_output=True,timeout=30)
  if r.returncode:raise RuntimeError('OWNED_SQL_FAILED')
  return r.stdout
 token=login('13800000001')
 # Dedicated fixtures, no imported member/merchant data.
 sql("INSERT INTO yshop_user_address(id,uid,real_name,address,phone,detail,create_time,tenant_id) VALUES(9101,101,'Synthetic A','Synthetic street A','13800000101','Unit A',NOW(),0),(9102,102,'Synthetic B','Synthetic street B','13800000102','Unit B',NOW(),0);")
 sql("INSERT INTO yshop_score_product(id,title,image,images,`desc`,score,stock,sales,is_switch) VALUES(9101,'Synthetic reward','','','Synthetic only',100,10,0,1);")
 def reset(stock=10,listed=1,points=1000):
  sql("DELETE FROM yshop_score_order WHERE product_id=9101; DELETE FROM yshop_user_bill WHERE uid=101 AND category='integral'; UPDATE yshop_score_product SET stock="+str(stock)+",sales=0,is_switch="+str(listed)+" WHERE id=9101; UPDATE yshop_user SET integral="+str(points)+" WHERE id=101;")
 def reward(num='1',address='9101'):return api('POST','/app-api/score-order/submit',{'productId':'9101','addressId':address,'num':num},token)
 def state(o,r):
  o.update(code=r['code'],orders=n('SELECT COUNT(*) FROM yshop_score_order WHERE product_id=9101'),stock=n('SELECT stock FROM yshop_score_product WHERE id=9101'),points=scalar('SELECT integral FROM yshop_user WHERE id=101'))
 def profile(o):
  before=scalar('SELECT mobile FROM yshop_user WHERE id=101')
  try:
   r=api('POST','/app-api/member/user/update-nickname',{'nickname':'Synthetic member','birthday':'2000-01-01','gender':0,'avatar':'synthetic-avatar','mobile':'13800000002'},token)
   o.update(code=r['code'],mobileChanged=scalar('SELECT mobile FROM yshop_user WHERE id=101')!=before,duplicateMobileOwners=n("SELECT COUNT(*) FROM yshop_user WHERE mobile='13800000002'"),victimPasswordLoginCode=api('POST','/app-api/member/auth/login',{'mobile':'13800000002','password':'syntheticQA12'})['code'])
   assert not o['mobileChanged'] and o['duplicateMobileOwners']==1,'Profile update must not change verified mobile or corrupt another member login identity'
  finally:sql("UPDATE yshop_user SET mobile='13800000001' WHERE id=101")
 case('D001','profile must preserve verified unique phone identity',profile)
 def quantity(o):
  reset();r=reward('2');state(o,r);o['totalScore']=n('SELECT total_score FROM yshop_score_order WHERE product_id=9101 ORDER BY id DESC LIMIT 1')
  if r['code']!=0:raise RuntimeError('VALID_QUANTITY_FIXTURE_FAILED')
  assert o['points']=='800.00' and o['totalScore']==200,'Two rewards at 100 points each must debit and record 200 points'
 case('D002','reward quantity must multiply points cost',quantity)
 def stock(o):
  reset(stock=1);r=reward('2');state(o,r)
  assert r['code']!=0 and o['orders']==0 and o['stock']==1 and o['points']=='1000.00','Insufficient reward stock must reject and roll back order and points'
 case('D003','insufficient reward stock must roll back',stock)
 def address(o):
  reset();r=reward(address='9102');state(o,r)
  o['foreignAddressCopied']=n("SELECT COUNT(*) FROM yshop_score_order WHERE uid=101 AND customer_phone='13800000102'")
  if o['orders']:
   oid=n('SELECT id FROM yshop_score_order WHERE product_id=9101 ORDER BY id DESC LIMIT 1');d=api('GET','/app-api/score-order/detail?id='+str(oid),token=token)
   o['foreignAddressReadable']=d.get('data',{}).get('customerPhone')=='13800000102'
  assert r['code']!=0 and o['foreignAddressCopied']==0,'A member must not use or read another member shipping address through reward checkout'
 case('D004','reward checkout must enforce address ownership',address)
 def unlisted(o):
  reset(listed=0);r=reward();state(o,r)
  assert r['code']!=0 and o['orders']==0 and o['points']=='1000.00','Off-shelf reward must not be ordered or debit points'
 case('D005','off-shelf reward must reject order',unlisted)
 def zero(o):
  reset();r=reward('0');state(o,r)
  assert r['code']!=0 and o['orders']==0 and o['points']=='1000.00','Zero quantity must reject without creating paid reward order or point debit'
 case('D006','zero reward quantity must reject',zero)
 def ledger(o):
  reset();r=reward();state(o,r)
  o['ledgerBalance']=scalar("SELECT balance FROM yshop_user_bill WHERE uid=101 AND category='integral' ORDER BY id DESC LIMIT 1")
  o['ledgerDebit']=scalar("SELECT number FROM yshop_user_bill WHERE uid=101 AND category='integral' ORDER BY id DESC LIMIT 1")
  assert r['code']==0 and o['points']=='900.00' and o['ledgerDebit']=='100.00','single reward fixture must debit points'
  assert o['ledgerBalance']=='900.00','Point ledger remaining balance must equal post-debit member balance'
 case('D007','reward ledger must record remaining balance',ledger)
 def phone(o):
  pid,version=map(int,sql('SELECT id,catalog_version FROM yshop_store_product WHERE shop_id=101 ORDER BY id LIMIT 1').strip().split('\t'))
  r=api('POST','/app-api/order/create',{'shopId':'101','mobile':'13800000001','couponId':'0','orderType':'takein','productId':[str(pid)],'spec':['默认'],'number':['1'],'payType':'weixin','idempotencyKey':uuid.uuid4().hex,'choices':[{'version':version,'selections':[]}]},token)
  if r['code']!=0:raise RuntimeError('PICKUP_FIXTURE_FAILED')
  oid=r['data']['orderId'];assert re.fullmatch('[a-zA-Z0-9_-]+',oid)
  try:
   o['submittedPhonePersisted']=scalar("SELECT user_phone FROM yshop_store_order WHERE order_id='"+oid+"'")=='13800000001'
   assert o['submittedPhonePersisted'],'Pickup order must retain submitted contact phone for merchant fulfillment'
  finally:api('POST','/app-api/order/cancel',{'id':oid},token)
 case('D008','pickup order must retain contact phone',phone)
 reset()
 report['status']='FAILED' if any(c['status']=='FAILED' for c in report['cases']) else 'PASSED'
 if any(c['status']=='INCONCLUSIVE' for c in report['cases']):report['status']='INCONCLUSIVE'
except Exception as e:
 report.update(status='INCONCLUSIVE',errorType=type(e).__name__);(root/'failure-private.log').write_text(traceback.format_exc())
finally:
 if (owned/'gui-ready.json').exists():(owned/'gui-finished').write_text('finished')
 else:
  if child.poll() is None:os.killpg(child.pid,signal.SIGTERM)
 try:child.wait(timeout=120)
 except subprocess.TimeoutExpired:
  os.killpg(child.pid,signal.SIGTERM)
  try:child.wait(timeout=120)
  except subprocess.TimeoutExpired:report['cleanup']='FAILED'
 log.close()
 if (owned/'report.json').exists():
  launch=json.loads((owned/'report.json').read_text());report['cleanup']='PASSED' if launch.get('cleanup')=='PASS' else 'FAILED';report['ownedBaseline']=launch.get('result')
 report['sourceUnchanged']=source_identity(repo)==identity;report['endedAt']=time.time();report['complete']=True
 if report['cleanup']!='PASSED' or not report['sourceUnchanged']:report['status']='INCONCLUSIVE'
 save()
print(json.dumps({'status':report['status'],'cases':len(report['cases']),'cleanup':report['cleanup'],'report':str(root/'report.json')}))
sys.exit(0 if report['status']=='PASSED' else 1)
