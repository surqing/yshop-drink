"""Synthetic identities and actual API assertions for owned disposable backend only."""
import datetime, json, subprocess, urllib.request, urllib.error, uuid
from pathlib import Path

def seed(sql,root,repo):
    import zipfile
    jar=repo/'yshop-drink-boot3/yshop-server/target/yshop-server.jar'
    with zipfile.ZipFile(jar) as bundle:
        for prefix in ['spring-security-crypto-','spring-jcl-']:
            names=[n for n in bundle.namelist() if n.startswith('BOOT-INF/lib/'+prefix) and n.endswith('.jar')]
            assert len(names)==1
            (root/Path(names[0]).name).write_bytes(bundle.read(names[0]))
    crypto=next(root.glob('spring-security-crypto-*.jar'))
    src=root/'Hash.java';src.write_text('import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder; public class Hash {public static void main(String[] args) {System.out.print(new BCryptPasswordEncoder().encode(new java.util.Scanner(System.in).nextLine()));}}')
    subprocess.run(['javac','-cp',str(crypto),str(src)],check=True,capture_output=True)
    cp=str(root)+':'+str(crypto)+':'+str(next(root.glob('spring-jcl-*.jar')))
    hashed=subprocess.run(['java','-cp',cp,'Hash'],input='syntheticQA12\n',text=True,capture_output=True,check=True).stdout
    assert hashed.startswith('$2')
    sql("INSERT INTO system_oauth2_client(client_id,secret,name,logo,status,access_token_validity_seconds,refresh_token_validity_seconds,redirect_uris,authorized_grant_types,scopes,auto_approve_scopes) VALUES('default','synthetic-test-only','quality','',0,3600,7200,'[]','[\"password\",\"refresh_token\"]','[]','[]');")
    sql("INSERT INTO system_role(id,name,code,sort,status,type) VALUES(101,'QA HQ','super_admin',0,0,1),(102,'QA staff','quality_staff',1,0,2); INSERT INTO system_users(id,username,password,nickname,status,tenant_id) VALUES(101,'qualityadmin','"+hashed+"','synthetic-hq',0,0),(102,'qualitystaff','"+hashed+"','synthetic-staff',0,0); INSERT INTO system_user_role(user_id,role_id) VALUES(101,101),(102,102);")
    sql("INSERT INTO system_menu(id,name,permission,type,parent_id,path,component,component_name,status) VALUES(3110,'Quality Mall','',1,0,'/mall',NULL,NULL,0),(3111,'商品经营','shop:store-product:query',2,3110,'products','mall/product/storeProduct/index','QualityProducts',0),(3112,'优惠券','coupon::query',2,3110,'coupons','mall/coupon/index','QualityCoupons',0);")
    for i,permission in enumerate(['shop:store-product:create','shop:store-product:update','shop:store-product:query','order:store-order:query','order:store-order:update']):
        sql("INSERT INTO system_menu(id,name,permission,type,status) VALUES("+str(3100+i)+",'QA permission','"+permission+"',3,0); INSERT INTO system_role_menu(role_id,menu_id) VALUES(102,"+str(3100+i)+");")
    sql("INSERT INTO yshop_user(id,username,password,nickname,mobile,status,create_time,tenant_id) VALUES(101,'qualitymember','"+hashed+"','synthetic-member','13800000001',0,NOW(),0),(102,'qualityother','"+hashed+"','synthetic-other','13800000002',0,NOW(),0);")
    sql("INSERT INTO yshop_store_shop(id,name,mobile,image,images,address,address_map,start_time,end_time,lng,lat,status,min_price,delivery_price,admin_id,uniprint_id) VALUES(101,'QA Shop A','13800000000','','','synthetic','synthetic','2026-01-01 00:00:00','2026-12-31 23:59:59','114','33',1,0,0,'102',''),(102,'QA Shop B','13800000000','','','synthetic','synthetic','2026-01-01 00:00:00','2026-12-31 23:59:59','114','33',1,0,0,'',''); INSERT INTO yshop_store_product_category(id,shop_id,shop_name,parent_id,name,pic_url,sort,status) VALUES(101,101,'QA Shop A',0,'QA Drinks','',0,0),(102,102,'QA Shop B',0,'QA Drinks','',0,0);")

def smoke(sql,root,report,port):
    checks=[];sent=[];issued=[]
    def check(name,predicate):
        checks.append({'name':name,'result':'PASS' if predicate else 'FAIL','executed':True});report['checks']=checks
        if not predicate:raise RuntimeError('HTTP_ASSERTION_FAILED')
    def api(method,path,data=None,token=None,success=True,drop_response=False):
        if any(p in path for p in ['/order/pay','/notify','/prepay','/refund','/recharge']):raise RuntimeError('FINANCIAL_PATH_FORBIDDEN')
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        request=urllib.request.Request('http://127.0.0.1:'+str(port)+path,data=json.dumps(data).encode() if data is not None else None,headers=headers,method=method)
        sent.append({'method':method,'path':path.split('?')[0]})
        try:
            with urllib.request.urlopen(request,timeout=15) as response:
                if drop_response:raise ConnectionResetError('SYNTHETIC_LOST_RESPONSE')
                result=json.load(response)
        except urllib.error.HTTPError as response:
            result=json.load(response)
        if success and result.get('code')!=0:
            (root/'response-diagnostic.json').write_text(json.dumps({'path':path.split('?')[0],'code':result.get('code')}));(root/'response-diagnostic.json').chmod(0o600)
            raise RuntimeError('HTTP_API_REJECTED:'+path.split('?')[0])
        value=result.get('data')
        if isinstance(value,dict):issued.extend(value[k] for k in ['accessToken','refreshToken'] if isinstance(value.get(k),str))
        return value if success else result
    admin=api('POST','/admin-api/system/auth/login',{'username':'qualityadmin','password':'syntheticQA12'})['accessToken']
    staff=api('POST','/admin-api/system/auth/login',{'username':'qualitystaff','password':'syntheticQA12'})['accessToken']
    member=api('POST','/app-api/member/auth/login',{'mobile':'13800000001','password':'syntheticQA12'})
    token=member['accessToken'];other=api('POST','/app-api/member/auth/login',{'mobile':'13800000002','password':'syntheticQA12'})['accessToken']
    check('actual admin/member password login',bool(admin and staff and token and other))
    image='data:image/svg+xml;base64,PHN2Zy8+'
    products=[]
    for shop,price in [(101,15),(102,20)]:
        product={'shopId':shop,'image':image,'slider_image':[image],'store_name':'QA Drink '+str(shop),'store_info':'synthetic','keyword':'synthetic','cate_id':str(shop),'description':'synthetic HTTP acceptance','unit_name':'杯','is_show':1,'is_integral':0,'spec_type':0,'items':[],'attrs':[{'price':price,'stock':10,'pic':image,'cost':0,'ot_price':price,'sku':'默认','detail':{}}]}
        api('POST','/admin-api/product/store-product/create',product,admin)
        row=sql('SELECT id FROM yshop_store_product WHERE shop_id='+str(shop)+' AND deleted=0 ORDER BY id DESC LIMIT 1;');pid=int(row.strip())
        sku=int(sql('SELECT id FROM yshop_store_product_attr_value WHERE product_id='+str(pid)+' ORDER BY id LIMIT 1;').strip())
        products.append((pid,sku,shop,price))
    check('admin creates two store products with distinct prices',len(products)==2)
    pid,sku,shop,price=products[0]
    version=int(sql('SELECT catalog_version FROM yshop_store_product WHERE id='+str(pid)+';').strip())
    api('PUT','/admin-api/product/catalog/price',{'productId':pid,'skuId':sku,'price':16,'version':version,'reason':'synthetic QA edit','key':uuid.uuid4().hex},admin)
    check('price edit leaves other store price unchanged',sql('SELECT price FROM yshop_store_product_attr_value WHERE id='+str(products[1][1])+';').strip()=='20.00')
    version=int(sql('SELECT catalog_version FROM yshop_store_product WHERE id='+str(pid)+';').strip())
    groups=[{'id':'toppings','name':'加料','kind':'TOPPING','multiple':True,'min':0,'max':2,'maxPerOption':2,'enabled':True,'when':None,'options':[{'id':'pearl','name':'珍珠','surcharge':2,'enabled':True,'defaultQuantity':0}]}]
    api('PUT','/admin-api/product/catalog/configuration',{'productId':pid,'version':version,'configuration':{'groups':groups}},admin)
    denied=api('GET','/admin-api/product/store-product/get?id='+str(products[1][0]),token=staff,success=False)
    check('staff cannot read store B product',denied.get('code')!=0)
    now=datetime.datetime.now();start=int((now-datetime.timedelta(days=1)).timestamp()*1000);end=int((now+datetime.timedelta(days=2)).timestamp()*1000)
    coupon={'shopId':'101','title':'QA Coupon','isSwitch':1,'least':20,'value':5,'startTime':start,'endTime':end,'claimStartTime':start,'claimEndTime':end,'type':0,'distribute':10,'limit':1,'claimMode':'PUBLIC','couponKind':'REGULAR','score':0,'receive':0}
    cid=api('POST','/admin-api/coupon/create',coupon,admin)
    claim={'id':cid,'requestKey':uuid.uuid4().hex};api('POST','/app-api/coupon/receive',claim,token);api('POST','/app-api/coupon/receive',claim,token)
    rights=int(sql('SELECT id FROM yshop_coupon_user WHERE user_id=101 AND coupon_id='+str(cid)+';').strip());check('coupon retry grants one right',int(sql('SELECT COUNT(*) FROM yshop_coupon_user WHERE user_id=101;').strip())==1)
    version=int(sql('SELECT catalog_version FROM yshop_store_product WHERE id='+str(pid)+';').strip())
    spec=sql('SELECT sku FROM yshop_store_product_attr_value WHERE id='+str(sku)+';').strip()
    check('SKU label roundtrips UTF8',spec=='默认')
    order={'shopId':'101','mobile':'13800000001','couponId':str(rights),'orderType':'takein','productId':[str(pid)],'spec':[spec],'number':['2'],'payType':'weixin','idempotencyKey':uuid.uuid4().hex,'choices':[{'version':version,'selections':[{'groupId':'toppings','optionId':'pearl','quantity':1}]}]}
    try:
        api('POST','/app-api/order/create',order,token,drop_response=True)
        raise RuntimeError('FAULT_NOT_INJECTED')
    except ConnectionResetError:pass
    again=api('POST','/app-api/order/create',order,token);oid=again['orderId'];check('lost response same-key retry keeps one unpaid order',int(sql('SELECT COUNT(*) FROM yshop_store_order;').strip())==1)
    amounts=sql("SELECT pay_price,paid FROM yshop_store_order WHERE order_id='"+oid+"';").strip().split('\t');check('server price and topping minus coupon unpaid',amounts==['31.00','0'])
    detail=api('GET','/app-api/order/detail/'+oid,token=token);check('member sees pending order',detail.get('paid')==0)
    current=int(sql('SELECT catalog_version FROM yshop_store_product WHERE id='+str(pid)+';').strip())
    api('PUT','/admin-api/product/catalog/price',{'productId':pid,'skuId':sku,'price':19,'version':current,'reason':'synthetic history test','key':uuid.uuid4().hex},admin)
    check('catalog edit leaves existing order price snapshot unchanged',sql("SELECT pay_price FROM yshop_store_order WHERE order_id='"+oid+"';").strip()=='31.00')
    deny=api('GET','/app-api/order/detail/'+oid,token=other,success=False);check('other member cannot read order',deny.get('code')!=0)
    order_id=int(sql("SELECT id FROM yshop_store_order WHERE order_id='"+oid+"';").strip())
    check('authorized staff reads store A order',bool(api('GET','/admin-api/order/store-order/get?id='+str(order_id),token=staff)))
    bpid,bsku,bshop,bprice=products[1];bspec=sql('SELECT sku FROM yshop_store_product_attr_value WHERE id='+str(bsku)+';').strip()
    border={'shopId':'102','mobile':'13800000002','couponId':'0','orderType':'takein','productId':[str(bpid)],'spec':[bspec],'number':['1'],'payType':'weixin','idempotencyKey':uuid.uuid4().hex,'choices':[{'version':1,'selections':[]}]}
    boid=api('POST','/app-api/order/create',border,other)['orderId'];bid=int(sql("SELECT id FROM yshop_store_order WHERE order_id='"+boid+"';").strip())
    denied=api('GET','/admin-api/order/store-order/get?id='+str(bid),token=staff,success=False);check('staff cannot read store B order',denied.get('code')!=0)
    api('POST','/app-api/order/cancel',{'id':boid},other)
    api('POST','/app-api/order/cancel',{'id':oid},token);api('POST','/app-api/order/cancel',{'id':oid},token)
    check('cancel retry restores inventory once',sql('SELECT stock FROM yshop_store_product_attr_value WHERE id='+str(sku)+';').strip()=='10')
    check('cancel restores coupon once',sql('SELECT status FROM yshop_coupon_user WHERE id='+str(rights)+';').strip()=='0' and sql("SELECT COUNT(*) FROM yshop_coupon_operation WHERE kind='RELEASE';").strip()=='1')
    before_success=sql('SELECT COUNT(*) FROM system_login_log WHERE user_id=101 AND user_type=1 AND result=0;').strip()
    api('PUT','/admin-api/member/user/update',{'id':101,'status':1},admin)
    rejected=api('POST','/app-api/member/auth/refresh-token?refreshToken='+member['refreshToken'],success=False);check('disabled member refresh denied',rejected.get('code')!=0)
    rejected=api('POST','/app-api/member/auth/login',{'mobile':'13800000001','password':'syntheticQA12'},success=False);check('disabled member login denied',rejected.get('code')!=0)
    check('disable/refresh never add successful login logs',sql('SELECT COUNT(*) FROM system_login_log WHERE user_id=101 AND user_type=1 AND result=0;').strip()==before_success)
    api('PUT','/admin-api/member/user/update',{'id':101,'status':0},admin)
    rejected=api('POST','/app-api/member/auth/refresh-token?refreshToken='+member['refreshToken'],success=False);check('re-enable cannot resurrect revoked refresh',rejected.get('code')!=0)
    fresh=api('POST','/app-api/member/auth/login',{'mobile':'13800000001','password':'syntheticQA12'})
    api('POST','/app-api/member/auth/logout',token=fresh['accessToken'])
    rejected=api('POST','/app-api/member/auth/refresh-token?refreshToken='+fresh['refreshToken'],success=False);check('HTTP logout revokes refresh',rejected.get('code')!=0)
    check('all orders unpaid',sql('SELECT COUNT(*) FROM yshop_store_order WHERE paid<>0;').strip()=='0')
    check('financial tables untouched',all(sql('SELECT COUNT(*) FROM '+t+';').strip()=='0' for t in ['yshop_member_wallet_transaction','yshop_member_recharge_order','yshop_order_payment','yshop_order_payment_attempt']))
    log=(root/'backend.log').read_text(errors='replace')
    check('issued tokens absent from backend log',all(value not in log for value in issued))
    report['httpRequests']=len(sent);report['paymentRequests']={'value':0,'evidence':'MEASURED_THIS_HTTP_HARNESS_ONLY','globalNetworkMeasured':False};report['result']='PASS'

def multi_process_smoke(sql,root,report,first,second):
    """Actual two JVMs share only MySQL/Redis, including lifecycle races, not mocked services."""
    from concurrent.futures import ThreadPoolExecutor
    import threading
    issued=[];requests=0;counter_lock=threading.Lock()
    def api(port,path,data=None,token=None):
        nonlocal requests
        if not path.startswith(('/app-api/member/auth/','/admin-api/system/auth/','/admin-api/member/user/update')):
            raise RuntimeError('AUTH_ONLY_MULTIPROCESS_PATH_REQUIRED')
        request=urllib.request.Request('http://127.0.0.1:'+str(port)+path,
            data=json.dumps(data).encode() if data is not None else b'',
            headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})},
            method='PUT' if path.startswith('/admin-api/member/user/update') else 'POST')
        try:
            with urllib.request.urlopen(request,timeout=30) as response:r=json.load(response)
        except urllib.error.HTTPError as response:r=json.load(response)
        with counter_lock:
            requests+=1
            if isinstance(r.get('data'),dict):issued.extend(r['data'][k] for k in ['accessToken','refreshToken'] if isinstance(r['data'].get(k),str))
        return r
    def checked(name,value):
        if not value:raise RuntimeError('MULTIPROCESS_ASSERTION_FAILED')
        report['checks'].append({'name':name,'result':'PASS','executed':True})
    def login():
        r=api(first,'/app-api/member/auth/login',{'mobile':'13800000001','password':'syntheticQA12'})
        if r.get('code')!=0:raise RuntimeError('SYNTHETIC_LOGIN_FAILED')
        return r['data']
    admin=api(first,'/admin-api/system/auth/login',{'username':'qualityadmin','password':'syntheticQA12'})['data']['accessToken']
    def race(fn):
        gate=threading.Barrier(20)
        with ThreadPoolExecutor(max_workers=20) as workers:
            def task(n):gate.wait(timeout=30);return fn(n,first if n%2==0 else second)
            return list(workers.map(task,range(20)))
    for round in range(5):
        token=login();refresh=token['refreshToken']
        rows=race(lambda n,port:api(port,'/app-api/member/auth/refresh-token?refreshToken='+refresh))
        checked('two-process refresh only one current access round '+str(round),all(r.get('code')==0 for r in rows) and sql("SELECT COUNT(*) FROM system_oauth2_access_token WHERE user_id=101 AND user_type=1 AND deleted=0;").strip()=='1')
        rows=race(lambda n,port:api(port,'/admin-api/member/user/update',{'id':101,'status':1},admin) if n==0 else api(port,'/app-api/member/auth/refresh-token?refreshToken='+refresh))
        checked('two-process disable revokes final family round '+str(round),rows[0].get('code')==0 and sql("SELECT COUNT(*) FROM system_oauth2_refresh_token WHERE user_id=101 AND user_type=1 AND deleted=0;").strip()=='0' and api(second,'/app-api/member/auth/refresh-token?refreshToken='+refresh).get('code')!=0)
        checked('two-process reenable keeps old refresh revoked round '+str(round),api(second,'/admin-api/member/user/update',{'id':101,'status':0},admin).get('code')==0 and api(first,'/app-api/member/auth/refresh-token?refreshToken='+refresh).get('code')!=0)
        token=login();refresh=token['refreshToken']
        rows=race(lambda n,port:api(port,'/app-api/member/auth/logout',token=token['accessToken']) if n==0 else api(port,'/app-api/member/auth/refresh-token?refreshToken='+refresh))
        checked('two-process logout revokes final family round '+str(round),rows[0].get('code')==0 and sql("SELECT COUNT(*) FROM system_oauth2_refresh_token WHERE user_id=101 AND user_type=1 AND deleted=0;").strip()=='0' and api(second,'/app-api/member/auth/refresh-token?refreshToken='+refresh).get('code')!=0)
    checked('two-process all orders remain unpaid',sql('SELECT COUNT(*) FROM yshop_store_order WHERE paid<>0;').strip()=='0')
    logs=(root/'backend.log').read_text(errors='replace')+(root/'backend-second.log').read_text(errors='replace')
    checked('two-process issued credentials absent from logs',all(t not in logs for t in issued))
    report['multiProcess']={'instances':2,'rounds':5,'workers':20,'httpRequests':requests,'sameJvm':False,'sharedState':'owned MySQL and Redis','result':'PASS'}
