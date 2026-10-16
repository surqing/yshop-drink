"""Reject evidence that could incorrectly certify incomplete end-to-end runs."""
import copy
from pathlib import Path
import tempfile
import time
import unittest
from e2e.contract import validate_receipt, final_status, write_reports

class E2eContractTest(unittest.TestCase):
    def setUp(self):
        self.started=time.time()-2
        self.identity={'sourceSha':'current','sourceDigest':'content'}
        self.planned=[{'id':'order-retry','variant':'mini-admin','expectedCheckIds':['page-order','sql-single-order']}]
        self.mini={'officialConnected':True,'compiledSource':self.identity,'compiledHashesVerified':True,
                   'compiledFileCount':5,'paymentRequests':0,'blockedExternalRequests':0}
        self.receipt={**self.identity,'runId':'run','owner':'owner','complete':True,'status':'PASSED',
                      'startedAt':self.started+0.1,'endedAt':time.time(),'driversCleanup':'PASSED',
                      'planned':[{'id':'order-retry','variant':'mini-admin'}],
                      'scenarios':[{'id':'order-retry','variant':'mini-admin','status':'PASSED','retry':0,
                                    'miniEvidence':self.mini,'checks':[{'id':'page-order','status':'PASSED','executed':True},
                                              {'id':'sql-single-order','status':'PASSED','executed':True}]}]}

    def validate(self):
        return validate_receipt(self.receipt,self.planned,self.identity,'run','owner',self.started)

    def reject(self):
        with self.assertRaises(ValueError):self.validate()

    def test_exact_current_evidence_is_accepted(self):
        self.assertEqual(2,self.validate()['checks'])

    def test_zero_checks_are_rejected(self):
        self.receipt['scenarios'][0]['checks']=[];self.reject()

    def test_missing_check_is_rejected(self):
        self.receipt['scenarios'][0]['checks'].pop();self.reject()

    def test_duplicate_check_is_rejected(self):
        self.receipt['scenarios'][0]['checks']*=2;self.reject()

    def test_extra_check_is_rejected(self):
        self.receipt['scenarios'][0]['checks'].append({'id':'extra','status':'PASSED','executed':True});self.reject()

    def test_foreign_run_is_rejected(self):
        self.receipt['runId']='other';self.reject()

    def test_foreign_owner_is_rejected(self):
        self.receipt['owner']='other';self.reject()

    def test_old_source_is_rejected(self):
        self.receipt['sourceDigest']='old';self.reject()

    def test_incomplete_receipt_is_rejected(self):
        self.receipt['complete']=False;self.reject()

    def test_unexecuted_check_is_rejected(self):
        self.receipt['scenarios'][0]['checks'][0]['executed']=False;self.reject()

    def test_wrong_variant_is_rejected(self):
        self.receipt['scenarios'][0]['variant']='api-admin';self.reject()

    def test_skipped_scenario_is_rejected(self):
        self.receipt['scenarios'][0]['status']='SKIPPED';self.reject()

    def test_retried_scenario_is_rejected(self):
        self.receipt['scenarios'][0]['retry']=1;self.reject()

    def test_missing_scenario_is_rejected(self):
        self.receipt['scenarios']=[];self.reject()

    def test_duplicate_scenario_is_rejected(self):
        self.receipt['scenarios']*=2;self.reject()

    def test_discovery_cannot_omit_planned_scenario(self):
        self.receipt['planned']=[];self.reject()

    def test_stale_execution_is_rejected(self):
        self.receipt['startedAt']=self.started-10;self.reject()

    def test_invalid_execution_end_is_rejected(self):
        self.receipt['endedAt']=self.started-10;self.reject()

    def test_local_case_requires_official_compiled_runtime_evidence(self):
        self.receipt['scenarios'][0].pop('miniEvidence',None);self.reject()

    def test_local_case_rejects_foreign_compilation(self):
        self.receipt['scenarios'][0]['miniEvidence']={**self.mini,'compiledSource':{'sourceSha':'old','sourceDigest':'old'}};self.reject()

    def test_local_case_rejects_zero_compiled_files(self):
        self.receipt['scenarios'][0]['miniEvidence']={**self.mini,'compiledFileCount':0};self.reject()

    def test_local_case_rejects_financial_request(self):
        self.receipt['scenarios'][0]['miniEvidence']={**self.mini,'paymentRequests':1};self.reject()

    def test_local_case_rejects_external_request(self):
        self.receipt['scenarios'][0]['miniEvidence']={**self.mini,'blockedExternalRequests':1};self.reject()

    def test_api_variant_does_not_fabricate_mini_evidence(self):
        self.planned[0]['variant']='api-admin';self.receipt['planned'][0]['variant']='api-admin'
        self.receipt['scenarios'][0]['variant']='api-admin';self.receipt['scenarios'][0].pop('miniEvidence',None)
        self.assertEqual(2,self.validate()['checks'])

    def test_missing_driver_cleanup_is_rejected(self):
        del self.receipt['driversCleanup'];self.reject()

    def final_report(self):
        return {**self.identity,'complete':True,'sourceUnchanged':True,'cleanup':'PASSED',
                'receiptValidated':True,'scenarios':copy.deepcopy(self.receipt['scenarios'])}

    def test_current_final_report_can_pass_after_cleanup(self):
        self.assertEqual('PASSED',final_status(self.final_report(),self.identity))

    def test_cleanup_failure_overrides_successful_business(self):
        report=self.final_report();report['cleanup']='FAILED'
        self.assertEqual('INCONCLUSIVE',final_status(report,self.identity))

    def test_missing_cleanup_never_passes(self):
        report=self.final_report();del report['cleanup']
        self.assertEqual('INCONCLUSIVE',final_status(report,self.identity))

    def test_changed_source_is_inconclusive(self):
        report=self.final_report();report['sourceDigest']='changed'
        self.assertEqual('INCONCLUSIVE',final_status(report,self.identity))

    def test_successful_prefix_is_not_complete(self):
        report=self.final_report();report['complete']=False
        self.assertEqual('INCONCLUSIVE',final_status(report,self.identity))

    def test_business_failure_is_preserved(self):
        report=self.final_report();report['scenarios'][0]['status']='FAILED'
        self.assertEqual('FAILED',final_status(report,self.identity))

    def test_reports_escape_html_and_preserve_failure_in_junit(self):
        import xml.etree.ElementTree as ET
        with tempfile.TemporaryDirectory() as folder:
            report={**self.final_report(),'runId':'run','profile':'local','status':'FAILED',
                    'startedAt':1,'endedAt':2,'scope':'subset','miniStatus':'FAILED'}
            report['scenarios'][0]['status']='FAILED'
            report['scenarios'][0]['checks'][0].update(status='FAILED',reasonCode='<failed & unsafe>')
            write_reports(Path(folder),report)
            self.assertIn('&lt;failed &amp; unsafe&gt;',(Path(folder)/'report.html').read_text())
            root=ET.parse(Path(folder)/'junit.xml').getroot()
            self.assertEqual('1',root.get('failures'))
            self.assertTrue((Path(folder)/'report.json').is_file())
            self.assertTrue((Path(folder)/'summary.md').is_file())

if __name__=='__main__':unittest.main()
