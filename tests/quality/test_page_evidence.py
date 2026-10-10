import copy
import json
import os
from pathlib import Path
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

if __name__=='__main__':unittest.main()
