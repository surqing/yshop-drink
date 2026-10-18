"""Regression scope never relabels known product failures as passing tests."""
import copy
import time
import unittest
from merge_scope import compare_defects, LEGACY_IDS, SECURITY_IDS

class MergeScopeTest(unittest.TestCase):
    def setUp(self):
        self.base_identity={'sourceSha':'a'*40,'sourceDigest':'b'*64}
        self.identity={'sourceSha':'c'*40,'sourceDigest':'d'*64}
        common={'status':'FAILED','complete':True,'sourceUnchanged':True,'cleanup':'PASSED',
                'startedAt':time.time()-2,'endedAt':time.time()-1,'backendArtifactHash':'e'*64}
        self.base={**common,**self.base_identity,'runId':'1'*32,'owner':'2'*32,
                   'harnessSource':copy.deepcopy(self.identity),'harnessUnchanged':True,'cases':[]}
        self.candidate={**common,**self.identity,'runId':'3'*32,'owner':'4'*32,'cases':[]}
        for report in (self.base,self.candidate):
            report['cleanupReceipt']={'status':'PASSED','owner':report['owner'],'runId':report['runId'],
                'ownershipChecked':True,'containersRemoved':2,'anonymousVolumesRemoved':2,'errors':[]}
        for case in LEGACY_IDS:
            for repetition in (1,2):
                row={'id':case,'repetition':repetition,'status':'FAILED','actual':{'code':0,'points':'900.00'},'assertion':'Correct business expectation'}
                self.base['cases'].append(copy.deepcopy(row));self.candidate['cases'].append(copy.deepcopy(row))
        for case in SECURITY_IDS:
            for repetition in (1,2):
                self.candidate['cases'].append({'id':case,'repetition':repetition,'status':'PASSED','actual':{'ownSucceeded':True}})
    def check(self):
        return compare_defects(self.base,self.candidate,self.base_identity,self.identity)
    def reject(self):
        with self.assertRaises(ValueError):self.check()
    def test_unchanged_legacy_failures_are_separate_from_passed_security_checks(self):
        result=self.check()
        self.assertEqual('PASSED',result.get('regressionScopeStatus'))
        self.assertEqual('FAILED',result.get('rawCandidateStatus'))
        self.assertEqual(list(LEGACY_IDS),result.get('retainedFailures'))
        self.assertFalse(result.get('qualityGateReady',True))
        self.assertEqual(32,result.get('securityChecks'))
    def test_missing_baseline_case_rejected(self):
        self.base['cases'].pop();self.reject()
    def test_missing_candidate_positive_control_rejected(self):
        self.candidate['cases'].pop();self.reject()
    def test_duplicate_case_rejected(self):
        self.candidate['cases'].append(self.candidate['cases'][0]);self.reject()
    def test_new_failure_id_rejected(self):
        self.candidate['cases'].append({'id':'D010','repetition':1,'status':'FAILED','actual':{'code':500}});self.reject()
    def test_security_failure_rejected(self):
        self.candidate['cases'][-1]['status']='FAILED';self.reject()
    def test_inconclusive_cannot_match_legacy_failure(self):
        self.base['cases'][0]['status']=self.candidate['cases'][0]['status']='INCONCLUSIVE';self.reject()
    def test_equal_failure_count_with_worse_business_state_rejected(self):
        self.candidate['cases'][0]['actual']['points']='800.00';self.reject()
    def test_removed_business_observation_rejected(self):
        self.candidate['cases'][0]['actual'].pop('points');self.reject()
    def test_changed_assertion_rejected(self):
        self.candidate['cases'][0]['assertion']='Different expectation';self.reject()
    def test_relabelled_raw_failure_report_rejected(self):
        self.candidate['status']='PASSED';self.reject()
    def test_wrong_candidate_source_rejected(self):
        self.candidate['sourceDigest']='f'*64;self.reject()
    def test_wrong_baseline_source_rejected(self):
        self.base['sourceSha']='f'*40;self.reject()
    def test_old_baseline_harness_rejected(self):
        self.base['harnessSource']['sourceDigest']='old';self.reject()
    def test_source_drift_rejected(self):
        self.candidate['sourceUnchanged']=False;self.reject()
    def test_cleanup_failure_rejected(self):
        self.base['cleanup']='FAILED';self.reject()
    def test_shared_owner_rejected(self):
        self.candidate['owner']=self.base['owner'];self.reject()
    def test_duplicate_run_rejected(self):
        self.candidate['runId']=self.base['runId'];self.reject()
    def test_missing_artifact_binding_rejected(self):
        del self.base['backendArtifactHash'];self.reject()
    def test_empty_observations_rejected(self):
        self.base['cases'][0]['actual']=self.candidate['cases'][0]['actual']={};self.reject()
    def test_missing_expected_repetition_rejected(self):
        self.base['cases'][0]['repetition']=3;self.reject()
    def test_incomplete_report_rejected(self):
        self.candidate['complete']=False;self.reject()
    def test_reversed_timestamps_rejected(self):
        self.base['startedAt']=self.base['endedAt']+10;self.reject()
    def test_missing_detailed_cleanup_rejected(self):
        del self.base['cleanupReceipt'];self.reject()
    def test_contradictory_cleanup_rejected(self):
        self.candidate['cleanupReceipt']['status']='FAILED';self.reject()
    def test_unverified_resource_ownership_rejected(self):
        self.base['cleanupReceipt']['ownershipChecked']=False;self.reject()
    def test_foreign_cleanup_rejected(self):
        self.base['cleanupReceipt']['owner']='f'*32;self.reject()
    def test_missing_removed_resources_rejected(self):
        self.base['cleanupReceipt']['containersRemoved']=0;self.reject()
    def test_cleanup_errors_rejected(self):
        self.base['cleanupReceipt']['errors']=['RESOURCE_CLEANUP_FAILED'];self.reject()

if __name__=='__main__':unittest.main()
