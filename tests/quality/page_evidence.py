"""Exact, fresh page receipts. No exit-code-only PASS, skipped/expected failures or retries."""
import json
from pathlib import Path

STATUSES = {'PASSED', 'FAILED', 'SKIPPED', 'BLOCKED', 'NOT_RUN', 'INCONCLUSIVE'}

def status_of(result):
    return {'PASS': 'PASSED', 'FAIL': 'FAILED', 'NOT_READY': 'INCONCLUSIVE'}.get(result, result)

def overall_status(steps, unchanged=True):
    if not unchanged: return 'INCONCLUSIVE'
    values = [status_of(s.get('result', 'NOT_RUN')) for s in steps]
    if not values: return 'NOT_RUN'
    if any(v not in STATUSES for v in values): return 'INCONCLUSIVE'
    for status in ['FAILED', 'INCONCLUSIVE', 'BLOCKED', 'NOT_RUN', 'SKIPPED']:
        if status in values: return status
    return 'PASSED'

def browser_receipt(path, started, identity, run_id, expected):
    path=Path(path)
    if not path.is_file() or path.stat().st_mtime < started:
        raise RuntimeError('PAGE_REPORT_MISSING_OR_STALE')
    receipt=json.loads(path.read_text())
    if receipt.get('runId') != run_id or any(receipt.get(k)!=v for k,v in identity.items()):
        raise RuntimeError('PAGE_REPORT_IDENTITY_MISMATCH')
    if receipt.get('result')!='passed' or receipt.get('startedAt',0)<started or receipt.get('endedAt',0)<receipt.get('startedAt',0):
        raise RuntimeError('PAGE_RUN_FAILED_OR_STALE')
    cases=receipt.get('cases',[])
    names=[c.get('name') for c in cases]
    if not expected or len(set(expected))!=len(expected) or sorted(receipt.get('planned',[]))!=sorted(expected) or sorted(names)!=sorted(expected):
        raise RuntimeError('PAGE_TEST_INVENTORY_CHANGED')
    if any(c.get('status')!='passed' or c.get('expectedStatus')!='passed' or c.get('retry')!=0 for c in cases):
        raise RuntimeError('PAGE_TESTS_FAILED_SKIPPED_OR_RETRIED')
    return {'tests':len(cases), 'exactNamesChecked':True, 'cases':names, 'runId':run_id,
            'scope':receipt.get('scope'), 'status':'PASSED'}

def mini_receipt(path, started, identity, run_id, expected):
    # The common controlled receipt verifies checks, identity, execution and cleanup.
    from run import controlled_receipt
    result=controlled_receipt(path,started,identity,run_id,expected)
    r=json.loads(Path(path).read_text())
    if r.get('compiledSource')!=identity or not r.get('compiledHashesVerified') or r.get('transport')!='owned-loopback':
        raise RuntimeError('MINI_ARTIFACT_NOT_ATTRIBUTED')
    if r.get('paymentRequests')!=0 or r.get('blockedFinancialRequests')!=0:
        raise RuntimeError('MINI_FINANCIAL_ADMISSION')
    return result
