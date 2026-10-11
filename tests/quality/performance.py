"""Bounded comparative baseline on two owned JVMs and real MySQL/Redis, never production."""
from concurrent.futures import ThreadPoolExecutor
import json
import math
import threading
import time
import urllib.request
import urllib.parse
import urllib.error

def percentile(values,p):
    if not values:raise RuntimeError('ZERO_PERFORMANCE_SAMPLES')
    return round(sorted(values)[math.ceil(p*len(values))-1],3)

def measure(sql,root,fixture,second):
    primary=fixture['backend'];secondary='http://127.0.0.1:'+str(second)
    outcomes=[]
    locks_before=sql("SELECT VARIABLE_NAME,VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME IN ('Innodb_row_lock_waits','Innodb_row_lock_time');")
    assets_before=sql("SELECT id,mobile,integral,now_money,openid,routine_openid FROM yshop_user ORDER BY id;")
    for scenario,path,authenticated in [('catalog','/app-api/product/products?shopId=101',False),('same-member-auth','/app-api/member/user/get',True)]:
        gate=threading.Barrier(12);started=time.perf_counter();samples=[];errors=[]
        def worker(index):
            gate.wait(timeout=15);rows=[]
            for _ in range(10):
                before=time.perf_counter()
                req=urllib.request.Request((primary if index%2==0 else secondary)+path,
                    headers={'Authorization':'Bearer '+fixture['member']} if authenticated else {})
                ok=False
                try:
                    with urllib.request.urlopen(req,timeout=15) as response:r=json.load(response)
                    ok=r.get('code')==0
                except Exception:pass
                rows.append((1000*(time.perf_counter()-before),ok))
            return rows
        with ThreadPoolExecutor(max_workers=12) as pool:
            for rows in pool.map(worker,range(12)):
                for latency,ok in rows:samples.append(latency);errors.append(not ok)
        seconds=time.perf_counter()-started
        p95=percentile(samples,.95);p99=percentile(samples,.99)
        # Generous smoke budget catches hang/outage. It is not an agreed production SLO.
        passed=len(samples)==120 and not any(errors) and p99<15000
        outcomes.append({'scenario':scenario,'requests':len(samples),'workers':12,'instances':2,
            'seconds':round(seconds,3),'throughputPerSecond':round(len(samples)/seconds,3),
            'p95Ms':p95,'p99Ms':p99,'errorRate':sum(errors)/len(samples),'result':'PASSED' if passed else 'FAILED'})
    def invoke(base,method,path,body=None,token=None):
        data=json.dumps(body).encode() if body is not None else b'' if method=='POST' else None
        request=urllib.request.Request(base+path,data=data,method=method,headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})})
        try:
            with urllib.request.urlopen(request,timeout=15) as response:return json.load(response)
        except urllib.error.HTTPError as response:return json.load(response)
    gate=threading.Barrier(20);started=time.perf_counter()
    def lifecycle(index):
        base=primary if index%2==0 else secondary;gate.wait(timeout=15);rows=[]
        for _ in range(20):
            begin=time.perf_counter();ok=False;phases={}
            try:
                phase=time.perf_counter();login=invoke(base,'POST','/app-api/member/auth/login',{'mobile':'13800000001','password':'syntheticQA12'})
                phases['login']=1000*(time.perf_counter()-phase);assert login['code']==0;token=login['data']
                phase=time.perf_counter();validated=invoke(base,'GET','/app-api/member/user/get',token=token['accessToken']);assert validated['code']==0 and validated['data']['id']==101
                phases['validate']=1000*(time.perf_counter()-phase)
                phase=time.perf_counter();refreshed=invoke(base,'POST','/app-api/member/auth/refresh-token?'+urllib.parse.urlencode({'refreshToken':token['refreshToken']}));assert refreshed['code']==0
                phases['refresh']=1000*(time.perf_counter()-phase);fresh=refreshed['data']['accessToken']
                phase=time.perf_counter();assert invoke(base,'POST','/app-api/member/auth/logout',token=fresh)['code']==0
                assert invoke(base,'GET','/app-api/member/user/get',token=fresh)['code']!=0
                phases['revoke-and-check']=1000*(time.perf_counter()-phase);ok=True
            except Exception:pass
            rows.append((1000*(time.perf_counter()-begin),ok,phases))
        return rows
    with ThreadPoolExecutor(max_workers=20) as pool:rows=[row for group in pool.map(lifecycle,range(20)) for row in group]
    seconds=time.perf_counter()-started;samples=[r[0] for r in rows];errors=sum(not r[1] for r in rows)
    unchanged=assets_before==sql("SELECT id,mobile,integral,now_money,openid,routine_openid FROM yshop_user ORDER BY id;")
    phases={name:{'samples':len(values),'p95Ms':percentile(values,.95),'p99Ms':percentile(values,.99)} for name in ['login','validate','refresh','revoke-and-check'] if (values:=[r[2][name] for r in rows if name in r[2]])}
    outcomes.append({'scenario':'member-token-lifecycle','workflows':len(rows),'requestsExpectedOnSuccess':2000,'workers':20,'rounds':20,'instances':2,
                     'seconds':round(seconds,3),'throughputPerSecond':round(len(rows)/seconds,3),'p95Ms':percentile(samples,.95),'p99Ms':percentile(samples,.99),
                     'phaseLatencies':phases,'errorRate':errors/len(rows),'assetsUnchanged':unchanged,
                     'result':'PASSED' if len(rows)==400 and errors==0 and unchanged and percentile(samples,.99)<15000 else 'FAILED'})
    locks_after=sql("SELECT VARIABLE_NAME,VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME IN ('Innodb_row_lock_waits','Innodb_row_lock_time');")
    def parse(text):return {k:int(v) for k,v in (line.split('\t') for line in text.splitlines())}
    before=parse(locks_before);after=parse(locks_after)
    report={'result':'PASSED' if all(r['result']=='PASSED' for r in outcomes) else 'FAILED','scenarios':outcomes,
            'mysqlLockDelta':{k:after[k]-before.get(k,0) for k in after},
            'scope':'240 catalog/authorization requests plus 400 token workflows across two JVMs; synthetic members, no production SLO or causal lock diagnosis',
            'releaseLoad':'NOT_RUN','crashRecovery':'NOT_RUN'}
    (root/'performance.json').write_text(json.dumps(report,indent=2))
    if report['result']!='PASSED':raise RuntimeError('PERFORMANCE_BASELINE_FAILED')
    return report
