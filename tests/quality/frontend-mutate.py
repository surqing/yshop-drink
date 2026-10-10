#!/usr/bin/env python3
"""Reverse the stale-response repair only in a disposable frontend source tree."""
import argparse, hashlib, json, os, re, shutil, sys, tempfile, uuid
from pathlib import Path
from evidence import execute, source_identity
REPO=Path(__file__).resolve().parents[2]
def main():
    p=argparse.ArgumentParser();p.add_argument('--output',required=True,type=Path);a=p.parse_args()
    a.output.mkdir(mode=0o700,parents=True,exist_ok=False);identity=source_identity();run_id=uuid.uuid4().hex
    expected=json.loads((REPO/'tests/quality/vue-manifest.json').read_text())
    result={'runId':run_id,**identity,'mutation':'coupon-form-stale-response','result':'INCONCLUSIVE','scope':'temporary source copy; API mocks; no browser/provider/database'}
    with tempfile.TemporaryDirectory(prefix='yshop-frontend-mutation-') as temp:
        copy=Path(temp).resolve()/'vue';shutil.copytree(REPO/'yshop-drink-vue3',copy,ignore=shutil.ignore_patterns('node_modules','dist','.quality','.git'))
        (copy/'node_modules').symlink_to(REPO/'yshop-drink-vue3/node_modules',target_is_directory=True)
        file=copy/'src/views/mall/coupon/Form.vue';original=file.read_text()
        def test(name):
            report=a.output/(name+'.json')
            env=os.environ.copy();env.pop('NODE_V8_COVERAGE',None)
            exit_code=execute(['pnpm','exec','vitest','run','--config','vitest.config.ts','--reporter=json','--outputFile='+str(report)],copy,env,a.output/(name+'.log'),180)
            data=json.loads(report.read_text());actual={};assertions=0
            for suite in data.get('testResults',[]):
                name=str(Path(suite['name']).relative_to(copy));cases=suite.get('assertionResults',[])
                actual[name]=[c['title'] for c in cases]
                for case in cases:
                    if case['status']=='failed':
                        if not case.get('failureMessages') or not all(m.startswith('AssertionError:') for m in case['failureMessages']):raise RuntimeError('NOT_ASSERTION_FAILURE')
                        assertions+=1
                    elif case['status']!='passed':raise RuntimeError('SKIPPED_OR_NOT_RUN')
            if actual!=expected or data.get('numTotalTests')!=sum(map(len,expected.values())) or data.get('numPendingTests') or data.get('numTodoTests'):
                raise RuntimeError('INCOMPLETE_TEST_REPORT')
            return exit_code, assertions
        code, failures=test('original')
        if code or failures:raise RuntimeError('BASELINE_FAILED')
        result['originalBaseline']='PASS';result['originalTests']=sum(map(len,expected.values()))
        needle='if (generation !== openGeneration) return'
        if original.count(needle)!=2:raise RuntimeError('OPERATOR_NOT_FOUND')
        mutated=original.replace(needle,'/* mutation: accept stale response */').replace('if (generation === openGeneration) loading.value=false','loading.value=false')
        file.write_text(mutated);code, failures=test('mutant')
        result.update(result='KILLED' if code and failures else 'SURVIVED' if not code else 'INCONCLUSIVE',assertionFailures=failures,
                      originalHash=hashlib.sha256(original.encode()).hexdigest(),mutatedHash=hashlib.sha256(mutated.encode()).hexdigest())
    vue_result={k:result[k] for k in ['mutation','result','originalBaseline','originalTests','assertionFailures','originalHash','mutatedHash']}
    with tempfile.TemporaryDirectory(prefix='yshop-cart-mutation-') as temp:
        copy=Path(temp).resolve();(copy/'tests/business').mkdir(parents=True)
        utils=copy/'yshop-drink-uniapp-vue3/utils';utils.mkdir(parents=True)
        shutil.copy(REPO/'tests/business/cart-context-test.mjs',copy/'tests/business/cart-context-test.mjs')
        for name in ['ordering-context.js','catalog-options.js']:shutil.copy(REPO/'yshop-drink-uniapp-vue3/utils'/name,utils/name)
        count=json.loads((REPO/'tests/quality/quick-manifest.json').read_text())['tests/business/cart-context-test.mjs']
        def cart_test(name):
            log=a.output/(name+'.log');env=os.environ.copy();env.pop('NODE_V8_COVERAGE',None)
            code=execute(['node','--experimental-default-type=module','--test','tests/business/cart-context-test.mjs'],copy,env,log,180)
            text=log.read_text();counts={k:int(v) for k,v in re.findall(r'^# (tests|pass|fail|skipped|cancelled|todo) (\d+)$',text,re.M)}
            if counts.get('tests')!=count or any(counts.get(k,0) for k in ['skipped','cancelled','todo']):raise RuntimeError('INVALID_CART_REPORT')
            return code,counts,text
        code,counts,text=cart_test('cart-original')
        if code or counts.get('pass')!=count:raise RuntimeError('CART_BASELINE_FAILED')
        file=utils/'ordering-context.js';original=file.read_text()
        before="if (!next?.id || String(state.store?.id || '') !== String(next.id))"
        if original.count(before)!=1:raise RuntimeError('CART_OPERATOR_NOT_FOUND')
        mutated=original.replace(before,"if (String(state.store?.id || '') !== String(next?.id || ''))");file.write_text(mutated)
        code,counts,text=cart_test('cart-mutant')
        cart_result={'mutation':'cart-null-store-retains-context','originalBaseline':'PASS','originalTests':count,
                     'result':'KILLED' if code and counts.get('fail')==1 and "name: 'AssertionError'" in text else 'SURVIVED' if not code else 'INCONCLUSIVE',
                     'assertionFailures':counts.get('fail',0),'originalHash':hashlib.sha256(original.encode()).hexdigest(),'mutatedHash':hashlib.sha256(mutated.encode()).hexdigest()}
    result['mutations']=[vue_result,cart_result]
    result['result']='KILLED' if all(m['result']=='KILLED' for m in result['mutations']) else 'NOT_READY'
    result['sourceUnchanged']=source_identity()==identity;result['intentionalMutationResidue']=False
    if not result['sourceUnchanged']:result['result']='INCONCLUSIVE'
    (a.output/'report.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
    return 0 if result['result']=='KILLED' else 1
if __name__=='__main__':
    try:sys.exit(main())
    except Exception as e:print(json.dumps({'result':'INCONCLUSIVE','errorType':type(e).__name__}));sys.exit(1)
