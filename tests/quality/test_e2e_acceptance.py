"""Frozen-source acceptance requires two independent Mini runs and one Linux CI run."""
import copy
import time
import unittest
from e2e.registry import listing

class E2eAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.identity={'sourceSha':'head','sourceDigest':'content'}
        self.reports=[self.report('local','1','4'),self.report('local','2','5'),self.report('ci','3','6')]

    def report(self,profile,run,owner):
        plan=listing(profile)
        cases=[]
        for scenario in plan['scenarios']:
            case={'id':scenario['id'],'variant':scenario['variant'],'status':'PASSED','retry':0,
                  'driversCleanup':'PASSED','checks':[{'id':check,'status':'PASSED','executed':True} for check in scenario['expectedCheckIds']]}
            if profile=='local':case['miniEvidence']={'officialConnected':True,'compiledHashesVerified':True,
                'compiledSource':self.identity,'compiledFileCount':2,'paymentRequests':0,'blockedExternalRequests':0}
            cases.append(case)
        return {**plan,**self.identity,'planned':plan['scenarios'],'runId':run*32,'owner':owner*32,'scenarios':cases,
            'hostPlatform':'darwin' if profile=='local' else 'linux','miniStatus':'PASSED' if profile=='local' else 'NOT_RUN',
            'threeEndCorePassed':profile=='local','status':'PASSED','result':'PASS','complete':True,
            'receiptValidated':True,'sourceUnchanged':True,'cleanup':'PASSED',
            'cleanupErrors':[],
            'cleanupReceipt':{'status':'PASSED','owner':owner*32,'runId':run*32,
                'ownershipChecked':True,'containersRemoved':2,'anonymousVolumesRemoved':2,'errors':[]},
            'startedAt':time.time()-2,'endedAt':time.time()-1,
            'backendArtifact':{**self.identity,'artifactHash':'a'*64,'result':'PASS'},
            'testSupport':{**self.identity,'productionArtifactHash':'a'*64,'testSupportExcludedFromProductionJar':True},
            'providerBoundariesVerified':True}

    def certify(self):
        from e2e.acceptance import certify
        return certify(self.reports,self.identity)

    def reject(self):
        with self.assertRaises(ValueError):self.certify()

    def test_two_complete_local_runs_and_linux_ci_pass(self):
        result=self.certify();self.assertEqual('PASSED',result['status'])
        self.assertTrue(result['threeEndCorePassed']);self.assertFalse(result['qualityGateReady'])

    def test_source_mismatch_cannot_be_aggregated(self):
        self.reports[2]['sourceSha']='other';self.reject()

    def test_old_backend_artifact_rejected(self):
        self.reports[0]['backendArtifact']['sourceDigest']='old';self.reject()

    def test_substituted_declared_plan_rejected(self):
        self.reports[0]['planned']=[];self.reject()

    def test_reused_run_id_rejected(self):
        self.reports[1]['runId']=self.reports[0]['runId'];self.reject()

    def test_reused_owner_rejected(self):
        self.reports[1]['owner']=self.reports[0]['owner'];self.reject()

    def test_one_local_run_is_insufficient(self):
        self.reports.pop(0);self.reject()

    def test_local_subset_is_not_full_acceptance(self):
        self.reports[0]['selection']='subset';self.reject()

    def test_macos_ci_profile_is_not_linux_evidence(self):
        self.reports[2]['hostPlatform']='darwin';self.reject()

    def test_missing_cleanup_rejected(self):
        del self.reports[1]['cleanup'];self.reject()

    def test_missing_business_check_rejected(self):
        self.reports[0]['scenarios'][0]['checks'].pop();self.reject()

    def test_failed_driver_cleanup_rejected(self):
        self.reports[0]['scenarios'][0]['driversCleanup']='FAILED';self.reject()

    def test_foreign_mini_compilation_rejected(self):
        self.reports[0]['scenarios'][0]['miniEvidence']['compiledSource']={'sourceSha':'old','sourceDigest':'old'};self.reject()

    def test_missing_resource_cleanup_receipt_rejected(self):
        del self.reports[0]['cleanupReceipt'];self.reject()

    def test_contradictory_resource_cleanup_rejected(self):
        self.reports[0]['cleanupReceipt']['status']='FAILED';self.reject()

    def test_missing_ownership_verification_rejected(self):
        self.reports[0]['cleanupReceipt']['ownershipChecked']=False;self.reject()

    def test_foreign_cleanup_receipt_rejected(self):
        self.reports[0]['cleanupReceipt']['owner']='f'*32;self.reject()

    def test_wrong_cleanup_run_rejected(self):
        self.reports[0]['cleanupReceipt']['runId']='f'*32;self.reject()

    def test_incomplete_resource_removal_rejected(self):
        self.reports[0]['cleanupReceipt']['containersRemoved']=0;self.reject()

    def test_underlying_cleanup_error_rejected(self):
        self.reports[0]['cleanupReceipt']['errors']=['RESOURCE_CLEANUP_FAILED'];self.reject()

    def test_outer_cleanup_error_rejected(self):
        self.reports[0]['cleanupErrors']=['MINI_PROJECT_CLOSE_FAILED'];self.reject()

if __name__=='__main__':unittest.main()
