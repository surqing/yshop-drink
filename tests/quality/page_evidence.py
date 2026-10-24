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


def printer_browser_receipt(path, started, identity, run_id, expected, script_hash):
    """Accept the registered owned printer browser sequence only after resource cleanup."""
    import hashlib
    import re
    path=Path(path)
    if not path.is_file() or path.stat().st_mtime < started:
        raise RuntimeError('PRINTER_BROWSER_REPORT_MISSING_OR_STALE')
    receipt=json.loads(path.read_text())
    if receipt.get('runId')!=run_id or any(receipt.get(k)!=v for k,v in identity.items()):
        raise RuntimeError('PRINTER_BROWSER_SOURCE_MISMATCH')
    owner=receipt.get('owner','')
    if (not re.fullmatch('[a-f0-9]{32}',owner) or receipt.get('complete') is not True
        or receipt.get('result')!='PASS' or receipt.get('sourceUnchanged') is not True
        or receipt.get('startedAt',0)<started or receipt.get('endedAt',0)<receipt.get('startedAt',0)
        or receipt.get('scriptSha256')!=script_hash):
        raise RuntimeError('PRINTER_BROWSER_INCOMPLETE_OR_STALE')
    checks=receipt.get('checks',[])
    if (not expected or len(set(expected))!=len(expected) or not isinstance(checks,list)
        or len(checks)!=len(expected) or sorted(checks)!=sorted(expected)):
        raise RuntimeError('PRINTER_BROWSER_EXACT_CHECKS_REQUIRED')
    resources=receipt.get('resources',{})
    if (receipt.get('cleanup')!='PASS' or resources.get('owner')!=owner
        or any(resources.get(key) is not True for key in ('browserClosed','contextClosed','serverExited','portReleased','ownedLoopbackOnly','ownershipHandshakeChecked'))
        or type(resources.get('previewPid')) is not int or resources['previewPid']<=0
        or receipt.get('fixture',{}).get('owner')!=owner
        or type(receipt.get('loopbackPort')) is not int or not 1024<=receipt['loopbackPort']<=65535
        or receipt.get('realProviderRequests')!=0 or receipt.get('blockedExternalOrigins')!=[]):
        raise RuntimeError('PRINTER_BROWSER_OWNED_CLEANUP_REQUIRED')
    artifacts=receipt.get('artifacts',[])
    if not artifacts:raise RuntimeError('PRINTER_BROWSER_ARTIFACTS_REQUIRED')
    for item in artifacts:
        name=item.get('path','')
        if not name or Path(name).name!=name or not name.endswith('.png'):
            raise RuntimeError('PRINTER_BROWSER_ARTIFACT_INVALID')
        file=path.parent/name
        if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest()!=item.get('sha256'):
            raise RuntimeError('PRINTER_BROWSER_ARTIFACT_INVALID')
    return {'tests':len(checks),'exactNamesChecked':True,'cases':checks,'runId':run_id,'cleanup':'PASS','owner':owner}
