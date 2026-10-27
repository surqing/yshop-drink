import copy
import json
import os
from pathlib import Path
import re
import tempfile
import time
import unittest
from page_evidence import browser_receipt, mini_receipt, overall_status

class PageEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.path=Path(self.temp.name)/'receipt.json';self.started=time.time()
        self.identity={'sourceSha':'current-sha','sourceDigest':'current-content'}
        self.report={**self.identity,'runId':'run','result':'passed','startedAt':self.started,'endedAt':self.started+1,
                     'planned':['one'], 'cases':[{'name':'one','status':'passed','expectedStatus':'passed','retry':0}]}
    def receipt(self):
        self.path.write_text(json.dumps(self.report))
        return browser_receipt(self.path,self.started,self.identity,'run',['one'])
    def reject(self):
        with self.assertRaises(RuntimeError):self.receipt()
    def test_backend_ports_avoid_ephemeral_docker_range(self):
        from loopback_ports import backend_pair
        port=backend_pair()
        self.assertTrue(10000<=port<30000)
    def test_backend_ports_retry_occupied_pair(self):
        import socket
        from unittest.mock import patch
        from loopback_ports import backend_pair
        with socket.socket() as occupied:
            occupied.bind(('127.0.0.1',0))
            port=occupied.getsockname()[1]
            with patch('loopback_ports.candidate',side_effect=[port,21001]):
                self.assertEqual(21001,backend_pair())
    def test_cross_end_rejects_zero_browser_tests(self):
        self.cross_reject({'tests':0})
    def test_cross_end_rejects_foreign_browser_run(self):
        self.cross_reject({'runId':'old'})
    def test_cross_end_rejects_unpassed_browser(self):
        self.cross_reject({'status':'FAILED'})
    def cross_reject(self,change):
        from cross_end import cross_receipt, SQL_CHECKS, REPO
        names=json.loads((REPO/'tests/quality/cross-end-manifest.json').read_text())
        browser={'cases':names,'tests':len(names),'runId':'run','status':'PASSED'};browser.update(change)
        report={**self.identity,'runId':'run','crossEnd':{'result':'PASSED','browser':browser,
            'sqlChecks':[{'name':n,'result':'PASS','executed':True} for n in SQL_CHECKS],'scope':'owned'}}
        with self.assertRaises(RuntimeError):cross_receipt(report,self.identity)
    def test_backend_certificate_accepts_current_artifact(self):
        self.artifact_fixture()
        from backend_artifact import verify_artifact
        self.assertTrue(verify_artifact(self.jar,self.cert,self.identity))
    def test_backend_certificate_rejects_old_source(self):
        self.artifact_fixture();r=json.loads(self.cert.read_text());r['sourceDigest']='old';self.cert.write_text(json.dumps(r))
        from backend_artifact import verify_artifact
        with self.assertRaises(RuntimeError):verify_artifact(self.jar,self.cert,self.identity)
    def test_backend_certificate_rejects_replaced_jar(self):
        self.artifact_fixture();self.jar.write_bytes(b'old binary')
        from backend_artifact import verify_artifact
        with self.assertRaises(RuntimeError):verify_artifact(self.jar,self.cert,self.identity)
    def test_backend_certificate_missing_is_blocked(self):
        from backend_artifact import verify_artifact
        with self.assertRaises(RuntimeError):verify_artifact(self.path,self.path,self.identity)
    def artifact_fixture(self):
        import hashlib
        self.jar=Path(self.temp.name)/'server.jar';self.jar.write_bytes(b'current binary')
        self.cert=Path(self.temp.name)/'build.json';self.cert.write_text(json.dumps({**self.identity,
            'result':'PASS','runId':'build-run','modules':55,'artifactHash':hashlib.sha256(self.jar.read_bytes()).hexdigest()}))
    def test_missing_mini_environment_dispatches_blocked(self):
        from unittest.mock import patch
        from run import Runner
        r=Runner(Path(self.temp.name)/'runs')
        with patch('backend_artifact.verify_artifact',return_value={'result':'PASS'}), patch('mini_pages.mini_prerequisite',return_value='OFFICIAL_TOOL_OR_OWN_APPID_MISSING'), patch.object(r,'step') as launch, patch('builtins.print'):
            r.cross_end(mini=True)
        launch.assert_not_called()
        self.assertEqual('BLOCKED',r.save()['status'])
    def test_timeout_waits_for_controlled_cleanup(self):
        import sys
        from evidence import execute
        child=Path(self.temp.name)/'controlled.py';marker=Path(self.temp.name)/'cleaned'
        child.write_text("import signal,time,sys\nfrom pathlib import Path\ndef stop(s,f): raise RuntimeError('stop')\nsignal.signal(signal.SIGTERM,stop)\ntry: time.sleep(10)\nfinally:\n time.sleep(.15)\n Path(sys.argv[1]).write_text('cleaned')\n")
        with self.assertRaisesRegex(RuntimeError,'TEST_PROCESS_TIMEOUT'):
            execute([sys.executable,str(child),str(marker)],self.temp.name,os.environ.copy(),Path(self.temp.name)/'output.log',timeout=.5,termination_grace=2)
        self.assertEqual('cleaned',marker.read_text())
    def test_report_aggregation_rejects_in_progress_run(self):
        from run import aggregate_reports
        r={**self.identity,'runId':'run','result':'PASS','complete':False,'sourceUnchanged':True,
           'steps':[{'name':'finished-first-scope','result':'PASS','evidence':{'tests':1}}]}
        self.path.write_text(json.dumps(r))
        with self.assertRaises(RuntimeError):aggregate_reports([self.path],self.identity)
    def test_runner_prefix_cannot_certify_until_finalized(self):
        from run import Runner, aggregate_reports, source_identity
        r=Runner(Path(self.temp.name)/'runs')
        r.steps=[{'name':'finished-first-scope','result':'PASS','evidence':{'tests':1}}]
        partial=r.save(final=False)
        self.assertFalse(partial['complete'])
        self.assertEqual('NOT_READY',partial['result'])
        self.assertEqual('INCONCLUSIVE',partial['status'])
        with self.assertRaises(RuntimeError):aggregate_reports([r.root/'report.json'],source_identity())
        self.assertTrue(r.save()['complete'])
        self.assertEqual('PASS',aggregate_reports([r.root/'report.json'],source_identity())['result'])
    def test_tls_only_cannot_certify_full_integration(self):
        from unittest.mock import patch
        from run import Runner
        r=Runner(Path(self.temp.name)/'runs')
        out=r.root/'controlled';out.mkdir()
        (out/'report.json').write_text(json.dumps({'sourceSha':r.source_sha,'sourceDigest':r.digest,
            'runId':'owned','result':'PASS','cleanup':'PASS','sourceUnchanged':True,
            'selectedScope':'synthetic-tls','steps':[{'name':'synthetic-tls','result':'PASS','evidence':{'tests':11}}]}))
        def check(*args,**kw):return kw['validator'](self.path)
        with patch.object(r,'step',side_effect=check):
            with self.assertRaisesRegex(RuntimeError,'CONTROLLED_REPORT_FAILED_OR_INCOMPLETE'):r.integration()
    def test_current_exact_plan_passes(self):self.assertEqual(1,self.receipt()['tests'])
    def test_missing_report_rejected(self):
        with self.assertRaises(RuntimeError):browser_receipt(self.path,self.started,self.identity,'run',['one'])
    def test_stale_file_rejected(self):
        self.path.write_text(json.dumps(self.report));os.utime(self.path,(1,1))
        with self.assertRaises(RuntimeError):browser_receipt(self.path,self.started,self.identity,'run',['one'])
    def test_old_source_rejected(self):self.report['sourceSha']='old';self.reject()
    def test_dirty_source_mismatch_rejected(self):self.report['sourceDigest']='old';self.reject()
    def test_foreign_run_rejected(self):self.report['runId']='foreign';self.reject()
    def test_zero_tests_rejected(self):self.report['cases']=[];self.report['planned']=[];self.reject()
    def test_omitted_case_rejected(self):self.report['cases']=[];self.reject()
    def test_extra_case_rejected(self):self.report['cases'].append(dict(self.report['cases'][0],name='extra'));self.reject()
    def test_duplicate_case_rejected(self):self.report['cases']*=2;self.reject()
    def test_skipped_case_rejected(self):self.report['cases'][0]['status']='skipped';self.reject()
    def test_expected_failure_is_not_pass(self):self.report['cases'][0]['expectedStatus']='failed';self.reject()
    def test_retry_cannot_hide_flake(self):self.report['cases'][0]['retry']=1;self.reject()
    def test_failed_runner_rejected(self):self.report['result']='failed';self.reject()
    def test_old_execution_rejected(self):self.report['startedAt']=1;self.reject()
    def test_invalid_end_time_rejected(self):self.report['endedAt']=1;self.reject()
    def test_discovery_plan_mismatch_rejected(self):self.report['planned']=['other'];self.reject()
    def test_statuses_remain_distinct(self):
        for value in ['PASSED','FAILED','SKIPPED','BLOCKED','NOT_RUN','INCONCLUSIVE']:
            self.assertEqual(value,overall_status([{'result':value}]))
    def test_no_steps_is_not_run(self):self.assertEqual('NOT_RUN',overall_status([]))
    def test_changed_source_is_inconclusive(self):self.assertEqual('INCONCLUSIVE',overall_status([{'result':'PASS'}],False))
    def test_blocked_does_not_become_pass(self):self.assertEqual('BLOCKED',overall_status([{'result':'PASS'},{'result':'BLOCKED'}]))
    def test_unknown_result_is_inconclusive(self):self.assertEqual('INCONCLUSIVE',overall_status([{'result':'UNKNOWN'}]))
    def test_mini_requires_compiled_identity_and_no_payment(self):
        r={**self.identity,'runId':'run','result':'PASS','cleanup':'PASS','sourceUnchanged':True,
           'checks':[{'name':'one','result':'PASS','executed':True}], 'compiledSource':self.identity,
           'compiledHashesVerified':True,'transport':'owned-loopback','paymentRequests':0,'blockedFinancialRequests':0}
        self.path.write_text(json.dumps(r))
        self.assertEqual(1,mini_receipt(self.path,self.started,self.identity,'run',['one'])['tests'])
        for key,value in [('compiledSource',{}),('compiledHashesVerified',False),('transport','shared'),('paymentRequests',1),('blockedFinancialRequests',1)]:
            bad=copy.deepcopy(r);bad[key]=value;self.path.write_text(json.dumps(bad))
            with self.assertRaises(RuntimeError):mini_receipt(self.path,self.started,self.identity,'run',['one'])

class PrinterBrowserReceiptTest(unittest.TestCase):
    def setUp(self):
        import hashlib
        self.folder=tempfile.TemporaryDirectory();self.addCleanup(self.folder.cleanup)
        self.root=Path(self.folder.name);self.path=self.root/'report.json';self.started=time.time()-1
        self.identity={'sourceSha':'sha','sourceDigest':'digest'};self.owner='a'*32
        self.cache_dir=self.root/'private-vite-cache'
        self.cache_dir.mkdir(mode=0o700)
        self.cache_owner='b'*32
        self.marker_path=self.cache_dir/'.r4-printer-cache-owner.json'
        self.metadata_path=self.cache_dir/'deps'/'_metadata.json'
        self.metadata_path.parent.mkdir()
        self.metadata={
            'hash':'vite-hash','configHash':'vite-config-hash',
            'lockfileHash':'vite-lockfile-hash','browserHash':'vite-browser-hash',
            'optimized':{},'chunks':{}
        }
        self.metadata_path.write_text(json.dumps(self.metadata))
        self.marker={**self.identity,'cacheOwnerId':self.cache_owner,'cacheDir':str(self.cache_dir.resolve())}
        self.marker_path.write_text(json.dumps(self.marker))
        self.marker_path.chmod(0o600)
        (self.root/'page.png').write_bytes(b'synthetic screenshot')
        self.value={**self.identity,'runId':'run','owner':self.owner,'result':'PASS','cleanup':'PASS',
            'complete':True,'sourceUnchanged':True,'startedAt':self.started+.1,'endedAt':self.started+.2,
            'checks':['one','two'],'fixture':{'owner':self.owner},'loopbackPort':12345,
            'startupOwnerCheck':{'status':'PASS','ownerMatched':True},
            'finalFixtureOwnerCheck':{'status':'PASS','ownerMatched':True},
            'optimizerReadinessCheck':{'status':'PASS','optimizerReady':True},
            'cacheHandshakeCheck':{'status':'PASS','cacheOwnerMatched':True,'resolvedPathMatched':True,
                'metadataMatched':True,'previewPidMatched':True,'startupObserved':True,'finalObserved':True},
            'resources':{'owner':self.owner,'browserClosed':True,'contextClosed':True,'serverExited':True,'portReleased':True,'previewPid':1234,'ownedLoopbackOnly':True,'ownershipHandshakeChecked':True},
            'cache':{},
            'scriptSha256':'script','realProviderRequests':0,'blockedExternalRequestCount':0,
            'artifacts':[{'path':'page.png','sha256':hashlib.sha256(b'synthetic screenshot').hexdigest()}],
            'diagnostics':self.valid_diagnostics()}
        self.write_cache_evidence()
    def write_cache_evidence(self, mode='RUN_PRIVATE_DEFAULT', state='COLD', before_present=False, before_sha=None):
        import hashlib
        after_sha=hashlib.sha256(self.metadata_path.read_bytes()).hexdigest()
        marker_sha=hashlib.sha256(self.marker_path.read_bytes()).hexdigest()
        private={
            'schemaVersion':1,'cacheMode':mode,'cacheState':state,
            'cacheOwnerId':self.cache_owner,'cacheDir':str(self.cache_dir.resolve()),
            'metadataPath':str(self.metadata_path.resolve()),'markerPath':str(self.marker_path.resolve()),
            'markerSha256':marker_sha,'sourceIdentity':self.identity,
            'metadataBeforePresent':before_present,'metadataBeforeSha256':before_sha,
            'metadataAfterPresent':True,'metadataAfterSha256':after_sha,
            'metadataAfter':self.metadata,
            'preview':{'pid':1234,'cacheOwnerId':self.cache_owner,
                'resolvedCacheDir':str(self.cache_dir.resolve()),
                'configCacheDir':str(self.cache_dir.resolve()),
                'metadataPath':str(self.metadata_path.resolve()),'metadataSha256':after_sha,
                'metadata':self.metadata}
        }
        private_path=self.root/'cache-private.json'
        private_path.write_text(json.dumps(private,sort_keys=True))
        private_path.chmod(0o600)
        self.value['cache']={
            'mode':mode,'state':state,'cacheOwnerId':self.cache_owner,
            'metadataPresentBefore':before_present,'metadataSha256Before':before_sha,
            'metadataPresentAfter':True,'metadataSha256After':after_sha,
            'privateEvidenceSha256':hashlib.sha256(private_path.read_bytes()).hexdigest()
        }
    def valid_diagnostics(self):
        confirmations=[]
        for operation in ['printer.reconnect.confirm','printer.test-print.confirm',
                          'printer.test-print.lookup-confirm','printer.binding.unbind-confirm',
                          'printer.deauthorize.confirm','printer.delete.confirm']:
            for action,before,after in [
                ('wait-visible',(0,0,None),(1,1,False)),
                ('click-confirm',(1,1,False),(0,0,None)),
                ('wait-hidden',(0,0,None),(0,0,None))]:
                visible,buttons,disabled=before
                after_visible,after_buttons,after_disabled=after
                confirmations.append({'stage':f'{operation}.{action}','status':'PASSED',
                    'relativeMs':10+len(confirmations),'generation':2,
                    'messageBoxBefore':{'status':'OBSERVED','visibleCount':visible,
                        'confirmButtonCount':buttons,'confirmButtonDisabled':disabled,'documentLang':'zh-CN'},
                    'messageBoxAfter':{'status':'OBSERVED','visibleCount':after_visible,
                        'confirmButtonCount':after_buttons,'confirmButtonDisabled':after_disabled,'documentLang':'zh-CN'}})
        return {
            'document':{'status':'OBSERVED','events':[
                {'type':'document-request','classification':'expected','navigation':'initial-page-load',
                 'path':'/__printer-preview','relativeMs':1,'generation':1},
                {'type':'document-commit','classification':'expected','navigation':'initial-page-load',
                 'path':'/__printer-preview','relativeMs':2,'generation':1},
                {'type':'document-request','classification':'expected','navigation':'provider-config-reload',
                 'path':'/__printer-preview','relativeMs':101,'generation':2},
                {'type':'document-commit','classification':'expected','navigation':'provider-config-reload',
                 'path':'/__printer-preview','relativeMs':102,'generation':2}]},
            'vite':{'status':'OBSERVED','events':[
                {'type':'optimize-start','relativeMs':3},
                {'type':'optimized-dependencies-reload','relativeMs':4}]},
            'lifecycle':{'status':'OBSERVED','events':[
                {'type':'printer-page-before-mount','relativeMs':4,'generation':1},
                {'type':'printer-page-mounted','relativeMs':5,'generation':1},
                {'type':'printer-page-before-mount','relativeMs':103,'generation':2},
                {'type':'printer-page-mounted','relativeMs':104,'generation':2}]},
            'confirmationSteps':confirmations}
    def verify(self,value=None):
        from page_evidence import printer_browser_receipt
        self.path.write_text(json.dumps(value or self.value))
        return printer_browser_receipt(self.path,self.started,self.identity,'run',['one','two'],'script')
    def test_printer_receipt_accepts_exact_fresh_source_and_owned_cleanup(self):
        self.assertEqual(2,self.verify()['tests'])
    def test_printer_receipt_rejects_default_or_missing_cache_ownership_evidence(self):
        for cache in [
            {'state':'DEFAULT','metadataPresentBefore':None,'metadataSha256Before':None},
            {},
        ]:
            bad=copy.deepcopy(self.value);bad['cache']=cache
            with self.assertRaises(RuntimeError):self.verify(bad)
        private=self.root/'cache-private.json';private.unlink()
        with self.assertRaises(RuntimeError):self.verify()
    def test_printer_receipt_accepts_owned_explicit_warm_cache(self):
        import hashlib
        before=hashlib.sha256(self.metadata_path.read_bytes()).hexdigest()
        self.write_cache_evidence('EXPLICIT_EXPERIMENT','WARM',True,before)
        self.assertEqual(2,self.verify()['tests'])
    def test_printer_receipt_rejects_cache_sidecar_marker_or_metadata_tampering(self):
        import hashlib
        sidecar=self.root/'cache-private.json'
        original=sidecar.read_bytes()
        sidecar.write_bytes(original+b' ')
        with self.assertRaises(RuntimeError):self.verify()
        sidecar.write_bytes(original)
        self.marker_path.write_text(json.dumps({**self.marker,'sourceDigest':'foreign'}))
        self.marker_path.chmod(0o600)
        with self.assertRaises(RuntimeError):self.verify()
        self.marker_path.write_text(json.dumps(self.marker));self.marker_path.chmod(0o600)
        self.metadata_path.write_text(json.dumps({**self.metadata,'configHash':'foreign'}))
        self.write_cache_evidence()
        with self.assertRaises(RuntimeError):self.verify()
    def test_printer_receipt_requires_default_cache_inside_private_output(self):
        self.cache_dir.rename(self.root/'renamed-cache')
        self.cache_dir=self.root/'renamed-cache'
        self.marker_path=self.cache_dir/'.r4-printer-cache-owner.json'
        self.metadata_path=self.cache_dir/'deps'/'_metadata.json'
        self.marker={**self.identity,'cacheOwnerId':self.cache_owner,'cacheDir':str(self.cache_dir.resolve())}
        self.marker_path.write_text(json.dumps(self.marker));self.marker_path.chmod(0o600)
        self.write_cache_evidence()
        with self.assertRaises(RuntimeError):self.verify()
    def test_printer_driver_and_preview_require_an_owned_private_cache(self):
        repo=Path(__file__).resolve().parents[2]
        driver=(repo/'tests/printer/browser-acceptance.cjs').read_text()
        preview=(repo/'yshop-drink-vue3/tests/printer/preview.mjs').read_text()
        self.assertIn("path.join(root, 'private-vite-cache')",driver)
        self.assertIn('YSHOP_PRINTER_BROWSER_CACHE_DIR',driver)
        self.assertIn('cacheDir: expectedCacheDir',preview)
        self.assertIn('PRINTER_PREVIEW_CACHE_DIR_REQUIRED',preview)
        self.assertIn('server.config.cacheDir',preview)
        self.assertNotIn('|| undefined',preview)
    def test_printer_driver_serializes_metadata_presence_from_the_observed_value(self):
        driver=(Path(__file__).resolve().parents[2]/'tests/printer/browser-acceptance.cjs').read_text()
        self.assertRegex(driver,r'metadataAfterPresent:\s*metadataPresentAfter')
        self.assertRegex(driver,r'metadataAfterSha256:\s*metadataSha256After')
    def test_printer_receipt_rejects_claimed_cleanup_without_owned_resource_receipts(self):
        for field,value in [('resources',{}),('cleanup','FAIL'),('sourceUnchanged',False),('complete',False)]:
            bad=copy.deepcopy(self.value);bad[field]=value
            with self.assertRaises(RuntimeError):self.verify(bad)
        for key in ['browserClosed','contextClosed','serverExited','portReleased']:
            bad=copy.deepcopy(self.value);bad['resources'][key]=False
            with self.assertRaises(RuntimeError):self.verify(bad)
    def test_printer_receipt_rejects_omissions_duplicates_wrong_source_or_owner(self):
        for field,value in [('checks',['one']),('checks',['one','one']),('sourceDigest','old'),('runId','foreign'),('fixture',{'owner':'b'*32}),('scriptSha256','old-script'),('realProviderRequests',1),('blockedExternalRequestCount',1)]:
            bad=copy.deepcopy(self.value);bad[field]=value
            with self.assertRaises(RuntimeError):self.verify(bad)
    def test_printer_receipt_rejects_tampered_or_escaping_artifacts(self):
        (self.root/'page.png').write_bytes(b'tampered')
        with self.assertRaises(RuntimeError):self.verify()
        bad=copy.deepcopy(self.value);bad['artifacts'][0]['path']='../page.png'
        with self.assertRaises(RuntimeError):self.verify(bad)
    def test_printer_receipt_requires_executed_startup_and_final_owner_checks(self):
        for field, value in [('startupOwnerCheck', {'status':'NOT_RUN'}),
                             ('startupOwnerCheck', {'status':'FAIL','ownerMatched':False}),
                             ('finalFixtureOwnerCheck', {'status':'NOT_RUN'}),
                             ('finalFixtureOwnerCheck', {'status':'FAIL','ownerMatched':False})]:
            bad=copy.deepcopy(self.value);bad[field]=value
            with self.assertRaises(RuntimeError):self.verify(bad)
        bad=copy.deepcopy(self.value)
        bad['optimizerReadinessCheck']={'status':'BYPASSED','optimizerReady':False}
        with self.assertRaisesRegex(RuntimeError,'OPTIMIZER_READINESS_REQUIRED'):self.verify(bad)
    def test_failed_printer_receipt_has_specific_nonpass_reason(self):
        bad=copy.deepcopy(self.value);bad.update(result='FAIL',checks=['one'],failedStage='printer.binding.open-shop-dropdown',
            reasonType='TimeoutError',reasonCode='PRINTER_BROWSER_CHECK_FAILED',stageDurationMs=250,lastPassedCheck='one')
        with self.assertRaisesRegex(RuntimeError,'PRINTER_BROWSER_EXECUTION_FAILED'):self.verify(bad)
    def test_printer_success_receipt_requires_complete_observation_contract(self):
        expected=[f'{operation}.{action}' for operation in [
            'printer.reconnect.confirm','printer.test-print.confirm',
            'printer.test-print.lookup-confirm','printer.binding.unbind-confirm',
            'printer.deauthorize.confirm','printer.delete.confirm']
            for action in ['wait-visible','click-confirm','wait-hidden']]
        self.assertEqual(expected,[step['stage'] for step in self.value['diagnostics']['confirmationSteps']])
        def make_confirmations_unavailable(d):
            for step in d['confirmationSteps']:
                for field in ('messageBoxBefore','messageBoxAfter'):
                    step[field]={'status':'UNAVAILABLE','visibleCount':None,
                        'confirmButtonCount':None,'confirmButtonDisabled':None,'documentLang':None}
        def claim_confirmation_without_visible_dialog(d):
            d['confirmationSteps'][0]['messageBoxAfter']['visibleCount']=0
            d['confirmationSteps'][0]['messageBoxAfter']['confirmButtonCount']=0
            d['confirmationSteps'][0]['messageBoxAfter']['confirmButtonDisabled']=None
        def add_duplicate_mount_in_same_generation(d):
            d['lifecycle']['events'].append({'type':'printer-page-mounted','relativeMs':6,'generation':1})
        def add_unexpected_document_navigation(d):
            d['document']['events'].append({'type':'document-commit','classification':'unexpected',
                'navigation':None,'path':'/__printer-preview','relativeMs':20,'generation':3})
        for mutate in [
            lambda d:d['document'].update(status='UNAVAILABLE',events=None),
            lambda d:d['vite'].update(status='UNAVAILABLE',events=None),
            lambda d:d['lifecycle'].update(status='UNAVAILABLE',events=None),
            add_duplicate_mount_in_same_generation,
            make_confirmations_unavailable,
            claim_confirmation_without_visible_dialog,
            add_unexpected_document_navigation,
        ]:
            bad=copy.deepcopy(self.value);mutate(bad['diagnostics'])
            with self.assertRaises(RuntimeError):self.verify(bad)
            from page_evidence import printer_browser_diagnostics
            self.path.write_text(json.dumps(bad))
            summary=printer_browser_diagnostics(self.path,self.started,self.identity,'run',
                ['one','two'],'script')
            self.assertEqual('FAILED',summary['status'])
    def test_printer_success_receipt_rejects_unexpected_document_navigation(self):
        bad=copy.deepcopy(self.value)
        bad['diagnostics']['document']['events'].append({
            'type':'document-commit','classification':'unexpected','navigation':None,
            'path':'/__printer-preview','relativeMs':20,'generation':2})
        with self.assertRaises(RuntimeError):self.verify(bad)
    def test_failure_diagnostics_preserve_safe_navigation_vite_and_confirm_steps(self):
        from page_evidence import printer_browser_diagnostics
        checks=['one']
        failed={**self.value,'result':'FAIL','checks':checks,
            'failedStage':'printer.reconnect.confirm.wait-visible','reasonType':'Error',
            'reasonCode':'PRINTER_BROWSER_CHECK_FAILED','diagnosticCode':'UNEXPECTED_DOCUMENT_NAVIGATION_DURING_CONFIRM',
            'stageDurationMs':24,'lastPassedCheck':'one','startupOwnerCheck':{'status':'PASS','ownerMatched':True},
            'finalFixtureOwnerCheck':{'status':'NOT_RUN'},
            'optimizerReadinessCheck':{'status':'PASS','optimizerReady':True},
            'failureUiState':{'status':'UNAVAILABLE','visibleDialogs':None,
                'visibleSelectDropdowns':None,'visibleLoadingMasks':None,'disabledButtons':None,
                'ariaBusyCount':None,'bindingSelectCount':None,'bindingOptionCount':None,
                'bindingButtonCount':None,'bindingButtonDisabled':None,
                'visibleMessageBoxes':None,'confirmButtonCount':None,'confirmButtonDisabled':None,
                'documentLang':None},
            'apiEvents':[{'method':'PUT','path':'/admin-api/store/printer/update','status':200}],
            'pageErrorCount':0,'consoleErrorCount':0,'blockedExternalRequestCount':0,
            'privateStack':'DO_NOT_PUBLISH_SENTINEL'}
        failed['diagnostics']['document']['events'].append({
            'type':'document-request','classification':'unexpected','navigation':None,
            'path':'/__printer-preview','relativeMs':20,'generation':2})
        failed['diagnostics']['document']['events'].append({
            'type':'document-commit','classification':'unexpected','navigation':None,
            'path':'/__printer-preview','relativeMs':21,'generation':2})
        failed['diagnostics']['vite']['events'].append({'type':'hmr-full-reload','relativeMs':19})
        failed['diagnostics']['confirmationSteps']=failed['diagnostics']['confirmationSteps'][:1]
        step=failed['diagnostics']['confirmationSteps'][0]
        step.update(stage='printer.reconnect.confirm.wait-visible',status='FAILED',relativeMs=18,generation=1)
        step['messageBoxBefore']={'status':'OBSERVED','visibleCount':0,'confirmButtonCount':0,
            'confirmButtonDisabled':None,'documentLang':'zh-CN'}
        step['messageBoxAfter']={'status':'UNAVAILABLE','visibleCount':None,'confirmButtonCount':None,
            'confirmButtonDisabled':None,'documentLang':None}
        self.path.write_text(json.dumps(failed))
        summary=printer_browser_diagnostics(self.path,self.started,self.identity,'run',
            ['one','two'],'script')
        self.assertEqual('UNEXPECTED_DOCUMENT_NAVIGATION_DURING_CONFIRM',summary['diagnosticCode'])
        self.assertEqual(['document-request','document-commit','document-request','document-commit',
                          'document-request','document-commit'],
            [event['type'] for event in summary['diagnostics']['document']['events']])
        self.assertEqual('hmr-full-reload',summary['diagnostics']['vite']['events'][-1]['type'])
        self.assertEqual('UNAVAILABLE',summary['failureUiState']['status'])
        self.assertIsNone(summary['failureUiState']['visibleMessageBoxes'])
        self.assertNotIn('DO_NOT_PUBLISH_SENTINEL',json.dumps(summary))
    def test_failure_diagnostics_are_safe_attributable_and_structural(self):
        from page_evidence import printer_browser_diagnostics
        checks=['one']
        failed={**self.value,'result':'FAIL','checks':checks,'failedStage':'printer.binding.open-shop-dropdown',
            'reasonType':'TimeoutError','reasonCode':'PRINTER_BROWSER_CHECK_FAILED','stageDurationMs':250,
            'lastPassedCheck':'one','startupOwnerCheck':{'status':'PASS','ownerMatched':True},
            'finalFixtureOwnerCheck':{'status':'NOT_RUN'},
            'optimizerReadinessCheck':{'status':'PASS','optimizerReady':True},
            'failureUiState':{'status':'OBSERVED','visibleDialogs':1,
                'visibleSelectDropdowns':0,'visibleLoadingMasks':0,'disabledButtons':0,'ariaBusyCount':0,
                'bindingSelectCount':1,'bindingOptionCount':0,'bindingButtonCount':1,'bindingButtonDisabled':False,
                'visibleMessageBoxes':0,'confirmButtonCount':0,'confirmButtonDisabled':None,'documentLang':'zh-CN'},
            'apiEvents':[{'method':'PUT','path':'/admin-api/store/printer/update','status':200}],
            'pageErrorCount':0,'consoleErrorCount':0,'blockedExternalRequestCount':0,
            'diagnostics':self.valid_diagnostics(),'privateStack':'DO_NOT_PUBLISH_SENTINEL'}
        failed['resources']['ownershipHandshakeChecked']=False
        self.path.write_text(json.dumps(failed))
        summary=printer_browser_diagnostics(self.path,self.started,self.identity,'run',['one','two'],'script')
        self.assertEqual('FAILED',summary['status'])
        self.assertEqual('printer.binding.open-shop-dropdown',summary['failedStage'])
        self.assertEqual(1,summary['executedChecks'])
        self.assertEqual('PASS',summary['startupOwnerCheck'])
        self.assertEqual('NOT_RUN',summary['finalFixtureOwnerCheck'])
        self.assertNotIn('DO_NOT_PUBLISH_SENTINEL',json.dumps(summary))

class BrowserWorkflowIndependenceTest(unittest.TestCase):
    def setUp(self):
        self.workflow=(Path(__file__).resolve().parents[2]/'.github/workflows/test-quality.yml').read_text()
        job=re.search(r'(?ms)^  browser-smoke:\n(?P<body>.*?)(?=^  [\w-]+:\n|\Z)',self.workflow)
        self.assertIsNotNone(job)
        self.job=job.group('body')

    def test_coupon_regression_runs_after_printer_failure_unless_cancelled(self):
        coupon=re.search(r'(?ms)^      - name: Coupon load failure cannot update the previously opened coupon\n(?P<body>.*?)(?=^      - name: |\Z)',self.job)
        self.assertIsNotNone(coupon)
        self.assertRegex(coupon.group('body'),r'(?m)^        if: \$\{\{ !cancelled\(\) \}\}$')
        self.assertIn('python3 tests/defects/browser_red.py --output',coupon.group('body'))
        self.assertNotIn('continue-on-error',coupon.group('body'))

    def test_printer_failure_stays_fatal_and_safe_artifact_upload_always_runs(self):
        self.assertNotIn('continue-on-error',self.job)
        upload=re.search(r'(?ms)^      - name: Upload safe receipts only; screenshots and traces remain private\n(?P<body>.*?)(?=^  [\w-]+:\n|\Z)',self.job)
        self.assertIsNotNone(upload)
        self.assertRegex(upload.group('body'),r'(?m)^        if: always\(\)$')
        self.assertIn('browser/**/report.json',upload.group('body'))

if __name__=='__main__':unittest.main()
