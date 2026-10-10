#!/usr/bin/env python3
"""Controlled dangerous mutations in disposable source copies, never the checkout.
A compile/environment failure is NOT a killed mutant. A passing original is required.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
from evidence import Evidence, execute, workspace, manifest, source_identity

REPO=Path(__file__).resolve().parents[2]
# Every operator is explicit and must match exactly once. Redundant defence removal can survive;
# this is reported rather than counted as a kill or hidden from the denominator.
OPERATORS=[
 ('auth-admin-reenable-revocation','AdminUserServiceImpl.java','oauth2Tokens.revokeUserTokens(id, co.yixiang.yshop.framework.common.enums.UserTypeEnum.ADMIN.getValue());','/* mutation: omitted admin family revocation */;','OAuth2LifecycleDatabaseTest'),
 ('inventory-repeat-release','OrderPlacementService.java','> 0) return;','> 0 && false) return;','OrderingDatabaseTest'),
 ('cross-store-price','OrderPlacementService.java','BigDecimal basePrice=money(sku.get("price"));','BigDecimal basePrice=money(jdbc.queryForObject("SELECT v.price FROM yshop_store_product_attr_value v JOIN yshop_store_product p ON p.id=v.product_id WHERE p.shop_id<>? AND p.deleted=0 ORDER BY v.id LIMIT 1", Object.class, shopId));','OrderingDatabaseTest'),
 ('coupon-double-reservation','CouponLifecycle.java','BigDecimal discount = discount(row, uid, shop, type, subtotal, now);','var eligibility=new HashMap<>(row); eligibility.put("status",0); eligibility.put("reserved_order_id",null); BigDecimal discount = discount(eligibility, uid, shop, type, subtotal, now);','CouponDatabaseTest'),
 ('duplicate-fulfillment-complete','PaymentProcessor.java','if (state == PaymentState.SUCCESS) return PaymentResult.IDEMPOTENT_DUPLICATE;','if (state == PaymentState.SUCCESS) { var replay=orders.lockPaymentOrder(event.getOrderId()); effects.apply(replay, "WECHAT".equals(event.getProvider()) ? "weixin" : "alipay"); return PaymentResult.IDEMPOTENT_DUPLICATE; }','PaymentDatabaseTest'),
 ('auth-refresh-disable-bypass','OAuth2TokenServiceImpl.java','if(!Objects.equals(lockPrincipal(id,type),CommonStatusEnum.ENABLE.getStatus()))','if(false)','OAuth2LifecycleDatabaseTest'),
 ('auth-cache-revocation-bypass','OAuth2TokenServiceImpl.java','OAuth2AccessTokenDO hint=oauth2AccessTokenMapper.selectByAccessToken(token);','OAuth2AccessTokenDO hint=getAccessToken(token);','OAuth2LifecycleDatabaseTest'),
 ('payment-freeze-bypass','EncryptedWechatV3ClientFactory.java','if (!enabled) throw new IllegalStateException("WECHAT_V3_DISABLED");','if (false) throw new IllegalStateException("WECHAT_V3_DISABLED");','PaymentCredentialDatabaseTest'),
 ('late-success-terminal','PaymentProcessor.java','if (!PaymentAttemptState.valueOf(attempt.getStatus()).active())','if (false)','PaymentAttemptDatabaseTest'),
 ('duplicate-event-result','PaymentProcessor.java','if (state == PaymentState.SUCCESS) return PaymentResult.IDEMPOTENT_DUPLICATE;','if (state == PaymentState.SUCCESS) return PaymentResult.FIRST_SUCCESS;','PaymentDatabaseTest'),
 ('inventory-wrong-debit','OrderPlacementService.java','stock=stock-?,sales=sales+?','stock=stock+?,sales=sales+?','OrderingDatabaseTest'),
 ('cross-store-all-defences','OrderPlacementService.java','number(product, "shop_id") != shopId','false','OrderingDatabaseTest'),
 ('coupon-total-all-defences','CouponMarketingService.java','if(actual>=number(c,"distribute"))','if(false)','CouponDatabaseTest'),
 ('coupon-reuse-state','CouponPolicy.java','!"AVAILABLE".equals(state(row, now, false, false))','false','CouponDatabaseTest'),
 ('coupon-use-expired','CouponPolicy.java','if (!end.isAfter(now)) return "EXPIRED";','if (false) return "EXPIRED";','CouponDatabaseTest'),

 ('inventory-sku-check','OrderPlacementService.java','number(sku, "stock") < line.quantity()','false','OrderingDatabaseTest'),
 ('inventory-conditional-update','OrderPlacementService.java','stock>=?','stock<=?','OrderingDatabaseTest'),
 ('cross-store-product','OrderPlacementService.java','number(product, "shop_id") != shopId','false','OrderingDatabaseTest'),
 ('employee-store-scope','StoreAccessService.java','allowed != null && !allowed.contains(shopId)','false','OrderingDatabaseTest'),
 ('cancel-payment-guard','OrderPlacementService.java','cancellationGuard.assertSafeAfterOrderLock(orderId);','/* mutation: omitted admission */','PaymentCancellationDatabaseTest'),
 ('cancel-uncertain-safe','PaymentCancellationGuard.java','Set.of("CANCELED", "EXPIRED", "FAILED").contains(state)','Set.of("CREATED", "PREPAY_CREATED", "CANCELED", "EXPIRED", "FAILED").contains(state)','PaymentCancellationDatabaseTest'),
 ('cancel-remote-proof','PaymentCancellationGuard.java','if (!neverRequested && !remoteSafe)','if (false)','PaymentCancellationDatabaseTest'),
 ('coupon-member-limit','CouponMarketingService.java','if(owned>=number(c,"limit"))','if(false)','CouponDatabaseTest'),
 ('coupon-total-limit','CouponMarketingService.java','if(actual>=number(c,"distribute"))','if(false)','CouponDatabaseTest'),
 ('coupon-expiration','CouponMarketingService.java','if(!end.isAfter(now()))','if(false)','CouponDatabaseTest'),
 ('coupon-claim-rate','CouponCodeGuard.java','if (!allowed) throw','if (false) throw','CouponCodeSecurityTest'),
 ('coupon-redis-fail-open','CouponCodeGuard.java','unavailable(); return;','return;','CouponCodeSecurityTest'),
 ('payment-amount','PaymentProcessor.java','if (payable != event.getAmountCents())','if (false)','PaymentDatabaseTest'),
]

# Narrow attribution is explicit, not an exclusion from the ordinary regression gate.
# Full-suite operators remain unchanged; these scopes isolate previously mixed runtime errors.
TARGET_METHODS={
 'auth-admin-reenable-revocation':'adminDisableReenableCannotResurrectOldCredentials',
 'inventory-conditional-update': 'availableStockConditionalDebitMustSucceed',
 'cancel-payment-guard': 'activeOrUncertainBlocksCustomerAndExpiry',
 'cancel-uncertain-safe': 'activeOrUncertainBlocksCustomerAndExpiry',
}

def scoped_cases(registry, selectors):
    selected={}
    for selector in selectors:
        suite,_,method=selector.partition('#')
        matches=[k for k in registry if k.rsplit('.',1)[-1]==suite]
        if len(matches)!=1:raise RuntimeError('MUTATION_SUITE_NOT_UNIQUE')
        name=matches[0]
        cases={k:v for k,v in registry[name].items() if not method or k.split('(',1)[0].split('[',1)[0]==method}
        if not cases:raise RuntimeError('MUTATION_METHOD_NOT_REGISTERED')
        selected[name]=cases
    return selected


def classify_mutation(failures, errors):
    # Runtime initialization/SQL/transport errors invalidate attribution even if other tests assert.
    return 'ASSERTION_FAILURE' if failures > 0 and errors == 0 else 'INCONCLUSIVE'

def run(output, only=None):
    output.mkdir(parents=True,exist_ok=False,mode=0o700)
    env=os.environ.copy()
    for key in ['YSHOP_ACCEPTANCE_CONFIG','YSHOP_ORDERING_ACCEPTANCE_CONFIG','YSHOP_INGRESS_BASE_URL','YSHOP_COUPON_REDIS_ACCEPTANCE']:
        env.pop(key,None)
    operators=[x for x in OPERATORS if only is None or x[0] in only]
    if not operators:raise RuntimeError('NO_MUTATIONS_SELECTED')
    registry=manifest(REPO)
    results=[];identity=source_identity();run_id=uuid.uuid4().hex;started=time.time()
    with tempfile.TemporaryDirectory(prefix='yshop-mutation-') as temp:
        copy=Path(temp)/'source'
        shutil.copytree(REPO,copy,ignore=shutil.ignore_patterns('.git','target','node_modules','unpackage','__pycache__','dist'))
        boot=copy/'yshop-drink-boot3'
        maven=env.get('YSHOP_MAVEN',shutil.which('mvn') or str(workspace(REPO)/'.dev-tools/maven/bin/mvn'))
        def test(suites,folder):
            evidence=Evidence(folder)
            selected=scoped_cases(registry,suites)
            owners={str(f.parents[len(Path('src/test/java/'+k.replace('.', '/')+'.java').parts)-1].relative_to(boot)) for k in selected for f in boot.glob('**/src/test/java/'+k.replace('.', '/')+'.java')}
            if not owners:raise RuntimeError('NO_TEST_OWNERS')
            command=[maven,'-pl',','.join(sorted(owners)),'-am','test','-Dtest='+','.join(suites),'-Dsurefire.failIfNoSpecifiedTests=false']
            if env.get('YSHOP_MAVEN_REPOSITORY'):command+=['-Dmaven.repo.local='+env['YSHOP_MAVEN_REPOSITORY']]
            # failIfNoSpecifiedTests=false applies ONLY to upstream reactor modules with no selected
            # tests. Complete exact suite/case validation below makes missing target tests fail.
            command+=evidence.arguments(selected)
            code=execute(command,boot,env,evidence.root/'maven.log',timeout=1800)
            if code==0:
                evidence.validate()
                return 'PASS',0
            failures=0;errors=0;observed=set();valid=True
            for file in evidence.directory.rglob('TEST-*.xml'):
                root=ET.parse(file).getroot()
                if root.get('name') not in selected:valid=False;continue
                observed.add(root.get('name'))
                props={p.get('name'):p.get('value') for p in root.findall('properties/property')}
                if props.get('quality.runId')!=evidence.id:valid=False;continue
                from collections import Counter
                if dict(Counter(c.get('name') for c in root.findall('testcase')))!=selected[root.get('name')] or int(root.get('skipped','-1'))!=0:valid=False
                # Assertion failures prove the test detected an incorrect outcome. Initialization,
                # compiler and transport errors are inconclusive even if Maven exits nonzero.
                failures+=len(root.findall('testcase/failure'));errors+=len(root.findall('testcase/error'))
            (evidence.root/'diagnostics.json').write_text(json.dumps(evidence.diagnostics(),indent=2))
            return classify_mutation(failures,errors) if valid and observed==set(selected) else 'INCONCLUSIVE',failures
        suites=sorted({x[4] for x in operators})
        baseline, failures=test(suites,output/'baseline')
        if baseline!='PASS':raise RuntimeError('ORIGINAL_TEST_BASELINE_FAILED')
        for name,filename,before,after,suite in operators:
            selector=suite+('#'+TARGET_METHODS[name] if name in TARGET_METHODS else '')
            files=list(boot.glob('**/src/main/java/**/'+filename))
            if len(files)!=1:
                results.append({'mutation':name,'result':'INAPPLICABLE','reason':'SOURCE_NOT_UNIQUE'});continue
            file=files[0];original=file.read_text();count=original.count(before)
            # The inventory SQL deliberately has two independent aggregate/SKU clauses: mutate
            # both, preserving placeholder count and compilability.
            if count!=1 and name not in {'inventory-conditional-update','inventory-wrong-debit','auth-admin-reenable-revocation'}:
                results.append({'mutation':name,'result':'INAPPLICABLE','reason':'EXACT_OPERATOR_NOT_FOUND','matches':count});continue
            try:
                original_result,_=test([selector],output/(name+'-original'))
                if original_result!='PASS':raise RuntimeError('ORIGINAL_TEST_BASELINE_FAILED')
                mutated=original.replace(before,after)
                if name=='cross-store-all-defences':
                    mutated=mutated.replace('number(category, "shop_id") != shopId','false').replace('WHERE id=? AND shop_id=? AND deleted=0 AND is_show=1','WHERE id=? AND ? IS NOT NULL AND deleted=0 AND is_show=1')
                if name=='coupon-total-all-defences':
                    mutated=mutated.replace('receive<distribute','1=1')
                if name=='inventory-repeat-release':
                    mutated=mutated.replace('if (line.get("released_at") != null)','if (false)')
                    mutated=mutated.replace('AND sales>=?', 'AND ? IS NOT NULL')
                    mutated=mutated.replace('refund_status=0 AND deleted=0', 'refund_status=0')
                if name=='coupon-double-reservation':
                    mutated=mutated.replace('AND status=0 AND reserved_order_id IS NULL AND invalid_reason', 'AND invalid_reason')
                if name=='auth-cache-revocation-bypass':
                    start=mutated.index('        if(family==null || DateUtils.isExpired(family.getExpiresTime())')
                    end=mutated.index('        OAuth2AccessTokenDO current=',start)
                    mutated=mutated[:start]+mutated[end:]
                    mutated=mutated.replace('OAuth2AccessTokenDO current=oauth2AccessTokenMapper.selectByAccessToken(token);','OAuth2AccessTokenDO current=getAccessToken(token);')

                file.write_text(mutated)
                outcome,assertions=test([selector],output/name)
                results.append({'mutation':name,'result':'KILLED' if outcome=='ASSERTION_FAILURE' else 'SURVIVED' if outcome=='PASS' else 'INCONCLUSIVE','assertionFailures':assertions,'suite':selector,'source':str(file.relative_to(copy)),'originalBaseline':'PASS','originalHash':hashlib.sha256(original.encode()).hexdigest(),'mutatedHash':hashlib.sha256(mutated.encode()).hexdigest()})
            finally:file.write_text(original)
            (output/'report.json').write_text(json.dumps({'baseline':baseline,'mutations':results},indent=2))
            print(name,results[-1]['result'],flush=True)
        # Source tree remains untouched; temporary mutant copy is removed by context manager.
    summary={**identity,'runId':run_id,'startedAt':started,'endedAt':time.time(),'sourceUnchanged':source_identity()==identity,'baseline':baseline,'mutations':results,'intentionalMutationResidue':False,'paymentRequests':{'value':0,'evidence':'DECLARED_H2_SYNTHETIC_ONLY'}}
    (output/'report.json').write_text(json.dumps(summary,indent=2))
    return 0 if summary['sourceUnchanged'] and results and all(x['result']=='KILLED' for x in results) else 1

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--only',help='Comma-separated operator names; unselected operators are not reported as tested');a=p.parse_args()
    try:sys.exit(run(Path(a.output),a.only.split(",") if a.only else None))
    except Exception as exc:
        print(json.dumps({'result':'FAIL','errorType':type(exc).__name__}))
        sys.exit(1)
