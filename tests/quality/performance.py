"""Bounded comparative baseline on two owned JVMs and real MySQL/Redis, never production."""
from concurrent.futures import ThreadPoolExecutor
import json
import math
import threading
import time
import urllib.request

def percentile(values,p):
    if not values:raise RuntimeError('ZERO_PERFORMANCE_SAMPLES')
    return round(sorted(values)[math.ceil(p*len(values))-1],3)

def measure(sql,root,fixture,second):
    primary=fixture['backend'];secondary='http://127.0.0.1:'+str(second)
    outcomes=[]
    locks_before=sql("SELECT VARIABLE_NAME,VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME IN ('Innodb_row_lock_waits','Innodb_row_lock_time');")
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
    locks_after=sql("SELECT VARIABLE_NAME,VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME IN ('Innodb_row_lock_waits','Innodb_row_lock_time');")
    def parse(text):return {k:int(v) for k,v in (line.split('\t') for line in text.splitlines())}
    before=parse(locks_before);after=parse(locks_after)
    report={'result':'PASSED' if all(r['result']=='PASSED' for r in outcomes) else 'FAILED','scenarios':outcomes,
            'mysqlLockDelta':{k:after[k]-before.get(k,0) for k in after},
            'scope':'bounded 240 requests; same identity authorization and catalog reads across two processes; no production SLO or causal lock diagnosis',
            'releaseLoad':'NOT_RUN','crashRecovery':'NOT_RUN'}
    (root/'performance.json').write_text(json.dumps(report,indent=2))
    if report['result']!='PASSED':raise RuntimeError('PERFORMANCE_BASELINE_FAILED')
    return report
