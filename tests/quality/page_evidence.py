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
    if receipt.get('result')=='FAIL':
        raise RuntimeError('PRINTER_BROWSER_EXECUTION_FAILED')
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
    startup=receipt.get('startupOwnerCheck',{})
    final_fixture=receipt.get('finalFixtureOwnerCheck',{})
    if (startup.get('status')!='PASS' or startup.get('ownerMatched') is not True
        or final_fixture.get('status')!='PASS' or final_fixture.get('ownerMatched') is not True):
        raise RuntimeError('PRINTER_BROWSER_OWNER_CHECK_REQUIRED')
    if (receipt.get('cleanup')!='PASS' or resources.get('owner')!=owner
        or any(resources.get(key) is not True for key in ('browserClosed','contextClosed','serverExited','portReleased','ownedLoopbackOnly','ownershipHandshakeChecked'))
        or type(resources.get('previewPid')) is not int or resources['previewPid']<=0
        or receipt.get('fixture',{}).get('owner')!=owner
        or type(receipt.get('loopbackPort')) is not int or not 1024<=receipt['loopbackPort']<=65535
        or receipt.get('realProviderRequests')!=0 or receipt.get('blockedExternalRequestCount')!=0):
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


def printer_browser_diagnostics(path, started, identity, run_id, expected, script_hash):
    """Return a structural, allowlisted summary without copying private browser errors."""
    import re
    path=Path(path)
    if not path.is_file() or path.stat().st_mtime < started:
        raise RuntimeError('PRINTER_BROWSER_FAILURE_REPORT_MISSING_OR_STALE')
    receipt=json.loads(path.read_text())
    if receipt.get('runId')!=run_id or any(receipt.get(k)!=v for k,v in identity.items()):
        raise RuntimeError('PRINTER_BROWSER_FAILURE_SOURCE_MISMATCH')
    if receipt.get('result')=='PASS':
        checks=receipt.get('checks',[])
        if (checks!=expected or receipt.get('cleanup')!='PASS' or receipt.get('sourceUnchanged') is not True
            or receipt.get('scriptSha256')!=script_hash):
            raise RuntimeError('PRINTER_BROWSER_SUCCESS_DIAGNOSTIC_INVALID')
        return {'status':'PASSED','runId':run_id,**identity,'executedChecks':len(checks),
                'expectedChecks':len(expected),'cleanup':'PASS'}
    owner=receipt.get('owner','')
    if (not re.fullmatch('[a-f0-9]{32}',owner) or receipt.get('complete') is not True
        or receipt.get('result')!='FAIL' or receipt.get('sourceUnchanged') is not True
        or receipt.get('startedAt',0)<started or receipt.get('endedAt',0)<receipt.get('startedAt',0)
        or receipt.get('scriptSha256')!=script_hash):
        raise RuntimeError('PRINTER_BROWSER_FAILURE_REPORT_INCOMPLETE')
    checks=receipt.get('checks')
    if not expected or len(set(expected))!=len(expected) or not isinstance(checks,list) or checks!=expected[:len(checks)]:
        raise RuntimeError('PRINTER_BROWSER_FAILURE_CHECK_PREFIX_INVALID')
    stage=receipt.get('failedStage')
    if not isinstance(stage,str) or len(stage)>96 or not re.fullmatch(r'[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*',stage):
        raise RuntimeError('PRINTER_BROWSER_FAILURE_STAGE_INVALID')
    error_type=receipt.get('reasonType')
    if error_type not in ('Error','AssertionError','TimeoutError'):
        raise RuntimeError('PRINTER_BROWSER_FAILURE_TYPE_INVALID')
    duration=receipt.get('stageDurationMs')
    if type(duration) is not int or duration<0 or duration>600000:
        raise RuntimeError('PRINTER_BROWSER_FAILURE_DURATION_INVALID')
    if receipt.get('reasonCode')!='PRINTER_BROWSER_CHECK_FAILED':
        raise RuntimeError('PRINTER_BROWSER_FAILURE_REASON_INVALID')
    if receipt.get('lastPassedCheck')!=(checks[-1] if checks else None):
        raise RuntimeError('PRINTER_BROWSER_FAILURE_LAST_CHECK_INVALID')
    resources=receipt.get('resources',{})
    if (receipt.get('cleanup')!='PASS' or resources.get('owner')!=owner
        or any(resources.get(k) is not True for k in ('contextClosed','browserClosed','serverExited','portReleased','ownedLoopbackOnly'))):
        raise RuntimeError('PRINTER_BROWSER_FAILURE_CLEANUP_INVALID')

    def owner_status(field):
        value=receipt.get(field)
        if not isinstance(value,dict) or value.get('status') not in ('PASS','FAIL','NOT_RUN'):
            raise RuntimeError('PRINTER_BROWSER_FAILURE_OWNER_CHECK_INVALID')
        status=value['status']
        matched=value.get('ownerMatched')
        if (status=='PASS' and matched is not True) or (status=='FAIL' and matched is not False) or (status=='NOT_RUN' and matched not in (None,False)):
            raise RuntimeError('PRINTER_BROWSER_FAILURE_OWNER_CHECK_INVALID')
        return status

    startup_status=owner_status('startupOwnerCheck')
    final_status=owner_status('finalFixtureOwnerCheck')
    ui=receipt.get('failureUiState')
    ui_keys=('visibleDialogs','visibleSelectDropdowns','visibleLoadingMasks','disabledButtons','ariaBusyCount','bindingSelectCount','bindingOptionCount','bindingButtonCount')
    if (not isinstance(ui,dict) or any(type(ui.get(k)) is not int or ui[k]<0 for k in ui_keys)
        or (ui.get('bindingButtonDisabled') is not None and type(ui.get('bindingButtonDisabled')) is not bool)
        or (ui['bindingButtonCount']>0 and type(ui.get('bindingButtonDisabled')) is not bool)):
        raise RuntimeError('PRINTER_BROWSER_FAILURE_UI_STATE_INVALID')
    api_events=receipt.get('apiEvents')
    api_path=re.compile(r'^/admin-api/store/printer/(?:providers|shops|page|get|create|update|binding|authorize|reconnect|deauthorize|status|status/refresh|test-print|test-print/get|test-print/page|delete)$')
    if not isinstance(api_events,list) or len(api_events)>256:
        raise RuntimeError('PRINTER_BROWSER_FAILURE_API_EVENTS_INVALID')
    safe_events=[]
    for event in api_events:
        if (not isinstance(event,dict) or event.get('method') not in ('GET','POST','PUT','PATCH','DELETE')
            or not isinstance(event.get('path'),str) or not api_path.fullmatch(event['path'])):
            raise RuntimeError('PRINTER_BROWSER_FAILURE_API_EVENTS_INVALID')
        status=event.get('status')
        failed=event.get('failed',False)
        if (status is not None and (type(status) is not int or not 100<=status<=599)) or type(failed) is not bool:
            raise RuntimeError('PRINTER_BROWSER_FAILURE_API_EVENTS_INVALID')
        safe_events.append({'method':event['method'],'path':event['path'],'status':status,'failed':failed})

    counts={}
    for key in ('pageErrorCount','consoleErrorCount','blockedExternalRequestCount','realProviderRequests'):
        value=receipt.get(key)
        if type(value) is not int or value<0:
            raise RuntimeError('PRINTER_BROWSER_FAILURE_COUNTER_INVALID')
        counts[key]=value
    return {'status':'FAILED','runId':run_id,**identity,'executedChecks':len(checks),
            'expectedChecks':len(expected),'failedStage':stage,'errorType':error_type,
            'reasonCode':'PRINTER_BROWSER_CHECK_FAILED','stageDurationMs':duration,
            'lastPassedCheck':receipt.get('lastPassedCheck'),'cleanup':'PASS',
            'startupOwnerCheck':startup_status,'finalFixtureOwnerCheck':final_status,
            'failureUiState':{k:ui[k] for k in (*ui_keys,'bindingButtonDisabled')},
            'apiEvents':safe_events,**counts}
