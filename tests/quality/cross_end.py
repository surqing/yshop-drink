"""Actual Vue -> HTTP -> Spring -> owned MySQL oracles; optional current-compiled Mini and load."""
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import time
import urllib.request
import uuid
from evidence import execute, source_identity
from page_evidence import browser_receipt

REPO=Path(__file__).resolve().parents[2]
SQL_CHECKS=['coupon edit and disable persisted','issued rights unchanged by disabling template',
            'unpaid A amount details stock and B isolation','no financial records', 'cancel restores stock exactly once']

def cross_receipt(report,identity,performance=False,mini=False):
    cross=report.get('crossEnd',{})
    checks=cross.get('sqlChecks',[])
    expected=json.loads((REPO/'tests/quality/cross-end-manifest.json').read_text())
    browser=cross.get('browser',{})
    if browser.get('tests')!=len(expected) or browser.get('runId')!=report.get('runId') or browser.get('status')!='PASSED':
        raise RuntimeError('CROSS_END_BROWSER_NOT_CERTIFIED')
    if cross.get('result')!='PASSED' or cross.get('browser',{}).get('cases')!=expected or [c.get('name') for c in checks]!=SQL_CHECKS or any(c.get('result')!='PASS' or c.get('executed') is not True for c in checks):
        raise RuntimeError('CROSS_END_ORACLES_INCOMPLETE')
    if mini and cross.get('mini',{}).get('result')!='PASSED':raise RuntimeError('MINI_PAGES_NOT_PASSED')
    if performance and cross.get('performance',{}).get('result')!='PASSED':raise RuntimeError('PERFORMANCE_NOT_PASSED')
    if any(report.get(k)!=v for k,v in identity.items()):raise RuntimeError('CROSS_END_SOURCE_MISMATCH')
    return {'tests':cross['browser']['tests']+len(checks), 'exactNamesChecked':True,
            'scope':cross['scope'],'cleanup':report.get('cleanup'),'runId':report['runId']}

def run_cross_end(sql,root,report,first,second):
    base='http://127.0.0.1:'+str(first)
    def api(method,path,body=None,token=None):
        if not path.startswith(('/admin-api/','/app-api/')) or re.search(r'/order/pay|/pay/order|/refund|/recharge|/wechat-v3/',path):raise RuntimeError('UNSAFE_CROSS_END_PATH')
        request=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,
            headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})},method=method)
        with urllib.request.urlopen(request,timeout=30) as response:r=json.load(response)
        if r.get('code')!=0:raise RuntimeError('CROSS_END_API_REJECTED')
        return r.get('data')
    admin=api('POST','/admin-api/system/auth/login',{'username':'qualityadmin','password':'syntheticQA12'})['accessToken']
    staff=api('POST','/admin-api/system/auth/login',{'username':'qualitystaff','password':'syntheticQA12'})['accessToken']
    member=api('POST','/app-api/member/auth/login',{'mobile':'13800000001','password':'syntheticQA12'})['accessToken']
    other=api('POST','/app-api/member/auth/login',{'mobile':'13800000002','password':'syntheticQA12'})['accessToken']
    pid,sku,version=map(int,sql('SELECT p.id,v.id,p.catalog_version FROM yshop_store_product p JOIN yshop_store_product_attr_value v ON v.product_id=p.id WHERE p.shop_id=101 ORDER BY p.id LIMIT 1;').strip().split('\t'))
    bpid,bsku,bversion=map(int,sql('SELECT p.id,v.id,p.catalog_version FROM yshop_store_product p JOIN yshop_store_product_attr_value v ON v.product_id=p.id WHERE p.shop_id=102 ORDER BY p.id LIMIT 1;').strip().split('\t'))
    def order(shop,product,version):
        return {'shopId':str(shop),'mobile':'13800000001','couponId':'0','orderType':'takein','productId':[str(product)],'spec':['默认'],'number':['1'],'payType':'weixin','idempotencyKey':uuid.uuid4().hex,'choices':[{'version':version,'selections':[]}]}
    before_rights=sql('SELECT id,status,reserved_order_id FROM yshop_coupon_user ORDER BY id;')
    order_b=api('POST','/app-api/order/create',order(102,bpid,bversion),other)['orderId']
    fixture={'owner':report['owner'],**{k:report[k] for k in ['sourceSha','sourceDigest']},'backend':base,
        'admin':admin,'staff':staff,'member':member,'couponTitle':'QA UI '+report['owner'][:12],
        'orderB':order_b,'productA':pid,'skuA':sku,'shopA':101,'shopB':102}
    cross={'result':'NOT_RUN','mini':{'result':'NOT_RUN'},'scope':'actual Vue, HTTP, Spring and owned MySQL; prepared synthetic API identity; GUI CAPTCHA/login not certified','sqlChecks':[]}
    report['crossEnd']=cross
    private=root/'cross-fixture.json'
    def save_fixture():private.write_text(json.dumps(fixture));private.chmod(0o600)
    save_fixture()
    try:
        if os.environ.get('YSHOP_MINI_PAGES')=='1':
            from mini_pages import run_mini
            cross['mini']=run_mini(root,report,fixture)
            if cross['mini']['result']!='PASSED':raise RuntimeError('MINI_PAGES_BLOCKED_OR_FAILED')
            fixture['orderA']=cross['mini']['orderId']
            cross['scope']='actual compiled WeChat pages -> HTTP -> Spring -> owned MySQL -> actual Vue merchant order page'
        else:
            fixture['orderA']=api('POST','/app-api/order/create',order(101,pid,version),member)['orderId']
        save_fixture()
        with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
        output=root/'browser';expected=json.loads((REPO/'tests/quality/cross-end-manifest.json').read_text())
        env=os.environ.copy();env.update(YSHOP_BROWSER_PORT=str(port),YSHOP_BROWSER_OUTPUT=str(output),
            YSHOP_BROWSER_SCOPE=cross['scope'],YSHOP_CROSS_FIXTURE=str(private),YSHOP_CROSS_OWNER=report['owner'],
            YSHOP_QUALITY_RUN_ID=report['runId'],YSHOP_QUALITY_SOURCE_SHA=report['sourceSha'],YSHOP_QUALITY_SOURCE_DIGEST=report['sourceDigest'])
        started=time.time()
        code=execute(['pnpm','exec','playwright','test','--config','playwright.config.ts','cross-end.spec.ts'],REPO/'yshop-drink-vue3',env,root/'browser-private.log',600)
        if code:raise RuntimeError('CROSS_END_BROWSER_FAILED')
        identity={k:report[k] for k in ['sourceSha','sourceDigest']}
        cross['browser']=browser_receipt(output/'receipt.json',started,identity,report['runId'],expected)
        def check(name,ok):
            cross['sqlChecks'].append({'name':name,'result':'PASS' if ok else 'FAIL','executed':True})
            if not ok:raise RuntimeError('CROSS_END_SQL_ASSERTION_FAILED')
        title,enabled=sql('SELECT title,is_switch FROM yshop_coupon ORDER BY id LIMIT 1;').strip().split('\t')
        check(SQL_CHECKS[0],title==fixture['couponTitle'] and enabled=='0')
        check(SQL_CHECKS[1],sql('SELECT id,status,reserved_order_id FROM yshop_coupon_user ORDER BY id;')==before_rights)
        oid=fixture['orderA']
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',oid):raise RuntimeError('INVALID_SYNTHETIC_ORDER_ID')
        state=sql("SELECT pay_price,paid,total_num,shop_id FROM yshop_store_order WHERE order_id='"+oid+"';").strip().split('\t')
        lines=sql("SELECT COUNT(*) FROM yshop_store_order_cart_info i JOIN yshop_store_order o ON o.id=i.oid WHERE o.order_id='"+oid+"';").strip()
        check(SQL_CHECKS[2],state==['19.00','0','1','101'] and lines=='1' and sql('SELECT stock FROM yshop_store_product_attr_value WHERE id='+str(sku)+';').strip()=='9' and sql('SELECT stock FROM yshop_store_product_attr_value WHERE id='+str(bsku)+';').strip()=='9')
        check(SQL_CHECKS[3],all(sql('SELECT COUNT(*) FROM '+table+';').strip()=='0' for table in ['yshop_order_payment','yshop_order_payment_attempt','yshop_member_wallet_transaction','yshop_member_recharge_order']))
        if os.environ.get('YSHOP_PERFORMANCE')=='1':
            from performance import measure
            cross['performance']=measure(sql,root,fixture,second)
        api('POST','/app-api/order/cancel',{'id':oid},member);api('POST','/app-api/order/cancel',{'id':oid},member)
        api('POST','/app-api/order/cancel',{'id':order_b},other)
        check(SQL_CHECKS[4],sql('SELECT stock FROM yshop_store_product_attr_value WHERE id='+str(sku)+';').strip()=='10' and sql('SELECT stock FROM yshop_store_product_attr_value WHERE id='+str(bsku)+';').strip()=='10')
        cross['result']='PASSED'
    finally:
        private.unlink(missing_ok=True)
