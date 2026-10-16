"""Real HTTP/provider-boundary/payment-service integration probe on owned dependencies."""
import argparse
import json
import os
from pathlib import Path
import signal
import sys
import time
import traceback
import urllib.error
import urllib.request
import uuid
from evidence import source_identity
from .bridge import invoke
from .contract import write_reports
from .environment import OwnedEnvironment
from .support import TestSupport


def probe(repo,output):
    root=Path(output).resolve()/uuid.uuid4().hex;root.mkdir(parents=True,mode=0o700)
    os.umask(0o077);identity=source_identity(repo);owner=uuid.uuid4().hex
    environment=OwnedEnvironment(repo,root/'owned',identity=identity,owner=owner,run_id=root.name)
    support=TestSupport(repo,root/'support',identity,owner)
    report={**identity,'runId':root.name,'owner':owner,'profile':'support-probe','scope':'real HTTP, real SMS code lifecycle, real completion services; provider boundaries replaced',
            'status':'INCONCLUSIVE','complete':False,'cleanup':'NOT_RUN','miniStatus':'NOT_RUN','startedAt':time.time(),'scenarios':[]}
    checks=[]
    def check(name,value):
        checks.append({'id':name,'status':'PASSED' if value else 'FAILED','executed':True})
        if not value:raise AssertionError(name)
    previous={}
    def interrupted(signum,frame):raise KeyboardInterrupt('CONTROLLED_INTERRUPTION')
    for sig in [signal.SIGINT,signal.SIGTERM]:previous[sig]=signal.signal(sig,interrupted)
    try:
        report['testSupport']=support.prepare()
        environment.start(configure=support.configure,launcher=support.launch)
        deadline=time.monotonic()+15
        while not (support.root/'support-ready.json').is_file():
            if time.monotonic()>deadline:raise RuntimeError('SUPPORT_NOT_READY')
            time.sleep(.1)
        context={**environment.context(),**support.context()}
        check('environment does not execute hidden business smoke',environment.sql('SELECT COUNT(*) FROM yshop_store_product;').strip()=='0')
        base=context['backend']
        def api(method,path,body=None,token=None,success=True):
            if not path.startswith(('/admin-api/','/app-api/')) or any(value in path for value in ['/order/pay','/refund','/recharge','/notify','/prepay']):raise ValueError('UNSAFE_PROBE_PATH')
            request=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,
                    headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})},method=method)
            try:
                with urllib.request.urlopen(request,timeout=30) as response:value=json.load(response)
            except urllib.error.HTTPError as response:value=json.load(response)
            if success and value.get('code')!=0:raise AssertionError('PROBE_API_REJECTED:'+path)
            return value.get('data') if success else value
        admin=api('POST','/admin-api/system/auth/login',{'username':'qualityadmin','password':'syntheticQA12'})['accessToken']
        proof={'phoneCode':'e2e-phone-p1-'+uuid.uuid4().hex,'loginCode':'e2e-login-p1-'+uuid.uuid4().hex}
        member=api('POST','/app-api/member/auth/weixin-mini-app-login',proof)
        token=member['accessToken']
        check('real provider-backed authentication issues a member token',bool(admin and token) and member['userInfo']['id']==101)
        denied=api('POST','/app-api/member/auth/weixin-mini-app-login',proof,success=False)
        check('consumed external proof is rejected',denied.get('code')!=0)
        api('POST','/app-api/member/auth/send-sms-code',{'mobile':'13800000001','scene':1})
        outbox=support.root/'outbox/13800000001.json';deadline=time.monotonic()+30
        while not outbox.exists():
            if time.monotonic()>deadline:raise RuntimeError('SMS_OUTBOX_TIMEOUT')
            time.sleep(.1)
        delivery=json.loads(outbox.read_text());check('real SMS transport reaches owned outbox',delivery['owner']==owner and len(delivery['code'])==6)
        sms={'mobile':'13800000001','code':delivery['code']}
        denied=api('POST','/app-api/member/auth/sms-login',{**sms,'code':'000000'},success=False)
        check('incorrect SMS code is rejected',denied.get('code')!=0)
        sms_login=api('POST','/app-api/member/auth/sms-login',sms)
        check('real SMS code creates real session',bool(sms_login['accessToken']))
        denied=api('POST','/app-api/member/auth/sms-login',sms,success=False)
        check('SMS code is consumed exactly once',denied.get('code')!=0)
        product={'shopId':101,'image':'data:image/svg+xml;base64,PHN2Zy8+','slider_image':['data:image/svg+xml;base64,PHN2Zy8+'],
                 'store_name':'E2E '+owner,'store_info':'synthetic','keyword':'synthetic','cate_id':'101','description':'synthetic probe','unit_name':'杯',
                 'is_show':1,'is_integral':0,'spec_type':0,'items':[],
                 'attrs':[{'price':19,'stock':10,'pic':'data:image/svg+xml;base64,PHN2Zy8+','cost':0,'ot_price':19,'sku':'默认','detail':{}}]}
        api('POST','/admin-api/product/store-product/create',product,admin)
        pid,version=map(int,environment.sql('SELECT id,catalog_version FROM yshop_store_product ORDER BY id DESC LIMIT 1;').split('\t'))
        now=int(time.time()*1000)
        coupon=api('POST','/admin-api/coupon/create',{'shopId':'101','title':'E2E Coupon '+owner,'isSwitch':1,'least':0,'value':5,'startTime':now-86400000,'endTime':now+86400000,'claimStartTime':now-86400000,'claimEndTime':now+86400000,'type':0,'distribute':10,'limit':1,'claimMode':'PUBLIC','couponKind':'REGULAR','score':0,'receive':0},admin)
        api('POST','/app-api/coupon/receive',{'id':coupon,'requestKey':uuid.uuid4().hex},token)
        right=int(environment.sql('SELECT id FROM yshop_coupon_user WHERE user_id=101 AND coupon_id='+str(coupon)+';'))
        order=api('POST','/app-api/order/create',{'shopId':'101','mobile':'13800000001','couponId':str(right),'orderType':'takein','productId':[str(pid)],'spec':['默认'],'number':['1'],'payType':'weixin','idempotencyKey':uuid.uuid4().hex,'choices':[{'version':version,'selections':[]}]},token)['orderId']
        first=invoke(context,'complete',{'orderId':order})
        check('real completion service first success',first.get('result')=='FIRST_SUCCESS')
        second=invoke(context,'complete',{'orderId':order})
        check('real completion service duplicate is idempotent',second.get('result')=='IDEMPOTENT_DUPLICATE')
        check('real transaction persists payment and coupon once',environment.sql("SELECT paid,pay_price FROM yshop_store_order WHERE order_id='"+order+"';")=='1\t14.00' and environment.sql("SELECT COUNT(*) FROM yshop_coupon_operation WHERE coupon_id="+str(coupon)+" AND kind='REDEEM';")=='1')
        check('one real financial receipt and matching ledger',environment.sql("SELECT COUNT(*) FROM yshop_order_payment WHERE order_id='"+order+"' AND status='SUCCESS';")=='1' and environment.sql("SELECT COUNT(*) FROM yshop_user_bill WHERE extend_field='"+order+"' AND number=14.00;")=='1')
        report['status']='PASSED'
    except BaseException as error:
        report.update(status='FAILED' if isinstance(error,AssertionError) else 'INCONCLUSIVE',reasonType=type(error).__name__)
        (root/'failure-private.log').write_text(traceback.format_exc())
    finally:
        cleanup=environment.close();report['cleanup']=cleanup['status'];report['cleanupReceipt']=cleanup
        report.update(complete=True,endedAt=time.time(),sourceUnchanged=source_identity(repo)==identity,
                      scenarios=[{'id':'provider-and-completion','variant':'api-sql','status':report['status'],'checks':checks}])
        if cleanup['status']!='PASSED' or not report['sourceUnchanged']:report['status']='INCONCLUSIVE'
        write_reports(root,report)
        for sig,handler in previous.items():signal.signal(sig,handler)
    print(json.dumps({'status':report['status'],'checks':len(checks),'cleanup':report['cleanup'],'report':str(root/'report.json')}))
    return 0 if report['status']=='PASSED' else 1

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True);args=parser.parse_args()
    sys.exit(probe(Path(__file__).resolve().parents[3],args.output))
