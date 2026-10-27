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


_PRINTER_CONFIRM_OPERATIONS = (
    'printer.reconnect.confirm',
    'printer.test-print.confirm',
    'printer.test-print.lookup-confirm',
    'printer.binding.unbind-confirm',
    'printer.deauthorize.confirm',
    'printer.delete.confirm',
)
_PRINTER_CONFIRM_STAGES = tuple(
    f'{operation}.{action}'
    for operation in _PRINTER_CONFIRM_OPERATIONS
    for action in ('wait-visible', 'click-confirm', 'wait-hidden')
)
_PRINTER_DIAGNOSTIC_CODES = {
    'UNEXPECTED_DOCUMENT_NAVIGATION_DURING_CONFIRM',
    'UNEXPECTED_DOCUMENT_NAVIGATION',
    'CONFIRMATION_OBSERVATION_UNAVAILABLE',
}


def _printer_observation(value, prefix):
    import re
    fields=('visibleCount','confirmButtonCount','confirmButtonDisabled','documentLang')
    if not isinstance(value,dict) or value.get('status') not in ('OBSERVED','UNAVAILABLE'):
        raise RuntimeError(prefix+'_STATUS_INVALID')
    status=value['status']
    if status=='UNAVAILABLE':
        if any(value.get(key) is not None for key in fields):
            raise RuntimeError(prefix+'_UNAVAILABLE_VALUES_INVALID')
        return {'status':'UNAVAILABLE','visibleCount':None,'confirmButtonCount':None,
                'confirmButtonDisabled':None,'documentLang':None}
    visible=value.get('visibleCount');buttons=value.get('confirmButtonCount')
    disabled=value.get('confirmButtonDisabled');lang=value.get('documentLang')
    if (type(visible) is not int or visible<0 or type(buttons) is not int or buttons<0
        or (disabled is not None and type(disabled) is not bool)
        or (buttons==0 and disabled is not None) or (buttons>0 and type(disabled) is not bool)
        or (lang is not None and (not isinstance(lang,str) or not re.fullmatch(r'[A-Za-z0-9-]{2,20}',lang)))):
        raise RuntimeError(prefix+'_VALUES_INVALID')
    return {'status':'OBSERVED','visibleCount':visible,'confirmButtonCount':buttons,
            'confirmButtonDisabled':disabled,'documentLang':lang}


def _safe_printer_diagnostics(value, require_complete):
    import re
    if not isinstance(value,dict):raise RuntimeError('PRINTER_BROWSER_DIAGNOSTICS_INVALID')
    result={}
    specifications={
        'document':({'document-request','document-commit','same-document-navigation'},256),
        'vite':({'optimize-start','dependencies-optimized','optimized-dependencies-reload','hmr-full-reload','hmr-update','connected'},256),
        'lifecycle':({'printer-page-before-mount','printer-page-mounted','printer-page-before-unmount','printer-page-unmounted'},128),
    }
    for section,(allowed,limit) in specifications.items():
        item=value.get(section)
        if not isinstance(item,dict) or item.get('status') not in ('OBSERVED','UNAVAILABLE'):
            raise RuntimeError('PRINTER_BROWSER_DIAGNOSTICS_INVALID')
        status=item['status'];events=item.get('events')
        if status=='UNAVAILABLE':
            if events is not None:raise RuntimeError('PRINTER_BROWSER_DIAGNOSTICS_INVALID')
            result[section]={'status':'UNAVAILABLE','events':None}
            continue
        if not isinstance(events,list) or len(events)>limit:
            raise RuntimeError('PRINTER_BROWSER_DIAGNOSTICS_INVALID')
        safe=[]
        for event in events:
            if not isinstance(event,dict) or event.get('type') not in allowed:
                raise RuntimeError('PRINTER_BROWSER_DIAGNOSTICS_INVALID')
            elapsed=event.get('relativeMs');generation=event.get('generation')
            if type(elapsed) is not int or not 0<=elapsed<=600000:
                raise RuntimeError('PRINTER_BROWSER_DIAGNOSTICS_INVALID')
            if section in ('document','lifecycle') and (type(generation) is not int or not 0<=generation<=128):
                raise RuntimeError('PRINTER_BROWSER_DIAGNOSTICS_INVALID')
            if section=='document':
                classification=event.get('classification')
                navigation=event.get('navigation')
                path=event.get('path')
                if event['type']=='same-document-navigation':
                    if classification!='same-document' or navigation is not None:
                        raise RuntimeError('PRINTER_BROWSER_DIAGNOSTICS_INVALID')
                elif classification not in ('expected','unexpected'):
                    raise RuntimeError('PRINTER_BROWSER_DIAGNOSTICS_INVALID')
                if navigation not in (None,'initial-page-load','provider-config-reload'):
                    raise RuntimeError('PRINTER_BROWSER_DIAGNOSTICS_INVALID')
                if path not in (None,'/','/__printer-preview'):
                    raise RuntimeError('PRINTER_BROWSER_DIAGNOSTICS_INVALID')
                safe.append({'type':event['type'],'classification':classification,
                    'navigation':navigation,'path':path,'relativeMs':elapsed,'generation':generation})
            elif section=='lifecycle':
                safe.append({'type':event['type'],'relativeMs':elapsed,'generation':generation})
            else:
                safe.append({'type':event['type'],'relativeMs':elapsed})
        result[section]={'status':'OBSERVED','events':safe}

    steps=value.get('confirmationSteps')
    if not isinstance(steps,list) or len(steps)>len(_PRINTER_CONFIRM_STAGES):
        raise RuntimeError('PRINTER_BROWSER_CONFIRMATION_DIAGNOSTICS_INVALID')
    safe_steps=[];previous=-1
    for step in steps:
        if not isinstance(step,dict) or step.get('stage') not in _PRINTER_CONFIRM_STAGES:
            raise RuntimeError('PRINTER_BROWSER_CONFIRMATION_DIAGNOSTICS_INVALID')
        index=_PRINTER_CONFIRM_STAGES.index(step['stage'])
        if index<=previous or step.get('status') not in ('PASSED','FAILED'):
            raise RuntimeError('PRINTER_BROWSER_CONFIRMATION_DIAGNOSTICS_INVALID')
        previous=index
        elapsed=step.get('relativeMs');generation=step.get('generation')
        if type(elapsed) is not int or not 0<=elapsed<=600000 or type(generation) is not int or not 0<=generation<=128:
            raise RuntimeError('PRINTER_BROWSER_CONFIRMATION_DIAGNOSTICS_INVALID')
        before=_printer_observation(step.get('messageBoxBefore'),'PRINTER_BROWSER_CONFIRMATION_BEFORE')
        after=_printer_observation(step.get('messageBoxAfter'),'PRINTER_BROWSER_CONFIRMATION_AFTER')
        if require_complete and (before['status']!='OBSERVED' or after['status']!='OBSERVED'):
            raise RuntimeError('PRINTER_BROWSER_DIAGNOSTICS_INCOMPLETE')
        safe_steps.append({'stage':step['stage'],'status':step['status'],'relativeMs':elapsed,
            'generation':generation,
            'messageBoxBefore':before,'messageBoxAfter':after})
    if require_complete:
        from collections import Counter
        if any(result[section]['status']!='OBSERVED' for section in specifications):
            raise RuntimeError('PRINTER_BROWSER_DIAGNOSTICS_INCOMPLETE')
        if any(event['type'] in ('document-request','document-commit')
               and event['classification']=='unexpected' for event in result['document']['events']):
            raise RuntimeError('PRINTER_BROWSER_UNEXPECTED_DOCUMENT_NAVIGATION')
        expected_documents=Counter({
            ('document-request','initial-page-load',1):1,
            ('document-commit','initial-page-load',1):1,
            ('document-request','provider-config-reload',2):1,
            ('document-commit','provider-config-reload',2):1,
        })
        actual_documents=Counter((event['type'],event['navigation'],event['generation'])
            for event in result['document']['events']
            if event['type'] in ('document-request','document-commit'))
        if actual_documents!=expected_documents:
            raise RuntimeError('PRINTER_BROWSER_DOCUMENT_LIFECYCLE_INCOMPLETE')
        expected_mounts=Counter({1:1,2:1})
        for lifecycle_type in ('printer-page-before-mount','printer-page-mounted'):
            actual_mounts=Counter(event['generation'] for event in result['lifecycle']['events']
                if event['type']==lifecycle_type)
            if actual_mounts!=expected_mounts:
                raise RuntimeError('PRINTER_BROWSER_LIFECYCLE_MOUNT_COUNT_INVALID')
        for step in safe_steps:
            action=step['stage'].rsplit('.',1)[-1]
            before=step['messageBoxBefore'];after=step['messageBoxAfter']
            if action=='wait-visible' and after['visibleCount']<1:
                raise RuntimeError('PRINTER_BROWSER_CONFIRMATION_NOT_VISIBLE')
            if action=='click-confirm' and (before['visibleCount']<1
                or before['confirmButtonCount']!=1 or before['confirmButtonDisabled'] is not False):
                raise RuntimeError('PRINTER_BROWSER_CONFIRMATION_BUTTON_NOT_READY')
            if action=='wait-hidden' and after['visibleCount']!=0:
                raise RuntimeError('PRINTER_BROWSER_CONFIRMATION_NOT_HIDDEN')
        if ([step['stage'] for step in safe_steps]!=list(_PRINTER_CONFIRM_STAGES)
            or any(step['status']!='PASSED' for step in safe_steps)
            or any(step['generation']!=2 for step in safe_steps)
            or any(step['messageBoxBefore']['status']!='OBSERVED'
                   or step['messageBoxAfter']['status']!='OBSERVED' for step in safe_steps)):
            raise RuntimeError('PRINTER_BROWSER_DIAGNOSTICS_INCOMPLETE')
    result['confirmationSteps']=safe_steps
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
    readiness=receipt.get('optimizerReadinessCheck',{})
    if (startup.get('status')!='PASS' or startup.get('ownerMatched') is not True
        or final_fixture.get('status')!='PASS' or final_fixture.get('ownerMatched') is not True):
        raise RuntimeError('PRINTER_BROWSER_OWNER_CHECK_REQUIRED')
    if readiness.get('status')!='PASS' or readiness.get('optimizerReady') is not True:
        raise RuntimeError('PRINTER_BROWSER_OPTIMIZER_READINESS_REQUIRED')
    if (receipt.get('cleanup')!='PASS' or resources.get('owner')!=owner
        or any(resources.get(key) is not True for key in ('browserClosed','contextClosed','serverExited','portReleased','ownedLoopbackOnly','ownershipHandshakeChecked'))
        or type(resources.get('previewPid')) is not int or resources['previewPid']<=0
        or receipt.get('fixture',{}).get('owner')!=owner
        or type(receipt.get('loopbackPort')) is not int or not 1024<=receipt['loopbackPort']<=65535
        or receipt.get('realProviderRequests')!=0 or receipt.get('blockedExternalRequestCount')!=0):
        raise RuntimeError('PRINTER_BROWSER_OWNED_CLEANUP_REQUIRED')
    diagnostics=_safe_printer_diagnostics(receipt.get('diagnostics'),True)
    artifacts=receipt.get('artifacts',[])
    if not artifacts:raise RuntimeError('PRINTER_BROWSER_ARTIFACTS_REQUIRED')
    for item in artifacts:
        name=item.get('path','')
        if not name or Path(name).name!=name or not name.endswith('.png'):
            raise RuntimeError('PRINTER_BROWSER_ARTIFACT_INVALID')
        file=path.parent/name
        if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest()!=item.get('sha256'):
            raise RuntimeError('PRINTER_BROWSER_ARTIFACT_INVALID')
    return {'tests':len(checks),'exactNamesChecked':True,'cases':checks,'runId':run_id,
            'cleanup':'PASS','owner':owner,'diagnostics':diagnostics}


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
        try:
            accepted=printer_browser_receipt(path,started,identity,run_id,expected,script_hash)
        except RuntimeError as error:
            reason=str(error)
            if not re.fullmatch(r'PRINTER_BROWSER_[A-Z0-9_]+',reason):
                reason='PRINTER_BROWSER_SUCCESS_DIAGNOSTIC_INVALID'
            return {'status':'FAILED','runId':run_id,**identity,
                    'reasonCode':reason,'expectedChecks':len(expected)}
        return {'status':'PASSED','runId':run_id,**identity,'executedChecks':accepted['tests'],
                'expectedChecks':len(expected),'cleanup':'PASS','diagnostics':accepted['diagnostics']}
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
    readiness=receipt.get('optimizerReadinessCheck')
    if (not isinstance(readiness,dict)
        or readiness.get('status') not in ('PASS','FAIL','BYPASSED','NOT_RUN')
        or not (readiness.get('optimizerReady') is True or readiness.get('optimizerReady') is False
                or readiness.get('optimizerReady') is None)):
        raise RuntimeError('PRINTER_BROWSER_OPTIMIZER_READINESS_INVALID')
    ui=receipt.get('failureUiState')
    ui_keys=('visibleDialogs','visibleSelectDropdowns','visibleLoadingMasks','disabledButtons','ariaBusyCount','bindingSelectCount','bindingOptionCount','bindingButtonCount')
    ui_status=ui.get('status') if isinstance(ui,dict) else None
    ui_extra=('visibleMessageBoxes','confirmButtonCount','confirmButtonDisabled','documentLang')
    if ui_status not in ('OBSERVED','UNAVAILABLE'):
        raise RuntimeError('PRINTER_BROWSER_FAILURE_UI_STATE_INVALID')
    if ui_status=='UNAVAILABLE':
        if any(ui.get(k) is not None for k in (*ui_keys,'bindingButtonDisabled',*ui_extra)):
            raise RuntimeError('PRINTER_BROWSER_FAILURE_UI_STATE_INVALID')
        safe_ui={'status':'UNAVAILABLE',**{k:None for k in (*ui_keys,'bindingButtonDisabled',*ui_extra)}}
    else:
        import re
        ui_lang=ui.get('documentLang')
        if (any(type(ui.get(k)) is not int or ui[k]<0 for k in (*ui_keys,'visibleMessageBoxes','confirmButtonCount'))
            or (ui.get('bindingButtonDisabled') is not None and type(ui.get('bindingButtonDisabled')) is not bool)
            or (ui['bindingButtonCount']>0 and type(ui.get('bindingButtonDisabled')) is not bool)
            or (ui.get('confirmButtonDisabled') is not None and type(ui.get('confirmButtonDisabled')) is not bool)
            or (ui['confirmButtonCount']==0 and ui.get('confirmButtonDisabled') is not None)
            or (ui['confirmButtonCount']>0 and type(ui.get('confirmButtonDisabled')) is not bool)
            or (ui_lang is not None and (not isinstance(ui_lang,str) or not re.fullmatch(r'[A-Za-z0-9-]{2,20}',ui_lang)))):
            raise RuntimeError('PRINTER_BROWSER_FAILURE_UI_STATE_INVALID')
        safe_ui={'status':'OBSERVED',**{k:ui[k] for k in (*ui_keys,'bindingButtonDisabled',*ui_extra)}}
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

    diagnostics=_safe_printer_diagnostics(receipt.get('diagnostics'),False)

    counts={}
    for key in ('pageErrorCount','consoleErrorCount','blockedExternalRequestCount','realProviderRequests'):
        value=receipt.get(key)
        if type(value) is not int or value<0:
            raise RuntimeError('PRINTER_BROWSER_FAILURE_COUNTER_INVALID')
        counts[key]=value
    diagnostic_code=receipt.get('diagnosticCode')
    if diagnostic_code is not None and diagnostic_code not in _PRINTER_DIAGNOSTIC_CODES:
        raise RuntimeError('PRINTER_BROWSER_FAILURE_DIAGNOSTIC_CODE_INVALID')
    return {'status':'FAILED','runId':run_id,**identity,'executedChecks':len(checks),
            'expectedChecks':len(expected),'failedStage':stage,'errorType':error_type,
            'reasonCode':'PRINTER_BROWSER_CHECK_FAILED','stageDurationMs':duration,
            'lastPassedCheck':receipt.get('lastPassedCheck'),'cleanup':'PASS',
            'startupOwnerCheck':startup_status,'finalFixtureOwnerCheck':final_status,
            'optimizerReadinessStatus':readiness['status'],
            'diagnosticCode':diagnostic_code,'failureUiState':safe_ui,
            'diagnostics':diagnostics,'apiEvents':safe_events,**counts}
