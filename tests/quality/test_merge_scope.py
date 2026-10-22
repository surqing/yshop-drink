"""Regression scope never relabels known product failures as passing tests."""
import copy
import time
import unittest
from merge_scope import compare_defects, LEGACY_IDS, SECURITY_IDS
from defect_contract import ASSERTION_FINGERPRINTS, OPEN_FAILURES, load_contract, validate_contract, assertion_fingerprints, stable_ast_dump, HISTORICAL_SOURCE
from pathlib import Path
import tempfile
import subprocess
from defect_contract import migrations_for_source

class MergeScopeTest(unittest.TestCase):
    def setUp(self):
        self.base_identity={'sourceSha':'a'*40,'sourceDigest':'b'*64}
        self.identity={'sourceSha':'c'*40,'sourceDigest':'d'*64}
        common={'status':'FAILED','exitCode':1,'assertionFingerprints':ASSERTION_FINGERPRINTS.copy(),
                'complete':True,'sourceUnchanged':True,'cleanup':'PASSED',
                'startedAt':time.time()-2,'endedAt':time.time()-1,'backendArtifactHash':'e'*64}
        self.base={**common,**self.base_identity,'runId':'1'*32,'owner':'2'*32,
                   'harnessSource':copy.deepcopy(self.identity),'harnessUnchanged':True,'cases':[]}
        self.candidate={**common,**self.identity,'runId':'3'*32,'owner':'4'*32,'cases':[]}
        for report in (self.base,self.candidate):
            report['defectContract']={'version':1,'defects':dict.fromkeys(LEGACY_IDS,'OPEN'),'scoreIdempotencyRequired':False}
            report['cleanupReceipt']={'status':'PASSED','owner':report['owner'],'runId':report['runId'],
                'ownershipChecked':True,'containersRemoved':2,'anonymousVolumesRemoved':2,'errors':[]}
        for case in LEGACY_IDS:
            for repetition in (1,2):
                assertion,actual=OPEN_FAILURES[case]
                row={'id':case,'repetition':repetition,'status':'FAILED','actual':copy.deepcopy(actual),'assertion':assertion}
                self.base['cases'].append(copy.deepcopy(row));self.candidate['cases'].append(copy.deepcopy(row))
        for case in SECURITY_IDS:
            for repetition in (1,2):
                self.candidate['cases'].append({'id':case,'repetition':repetition,'status':'PASSED','actual':{'ownSucceeded':True}})
        self.base_contract=copy.deepcopy(self.base['defectContract'])
        self.candidate_contract=copy.deepcopy(self.candidate['defectContract'])
    def check(self):
        return compare_defects(self.base,self.candidate,self.base_identity,self.identity,
                               self.base_contract,self.candidate_contract)

    def repair(self, report, ids):
        report['defectContract']['defects']={name:('FIXED' if name in ids else 'OPEN') for name in LEGACY_IDS}
        if report is self.base:self.base_contract=copy.deepcopy(report['defectContract'])
        else:self.candidate_contract=copy.deepcopy(report['defectContract'])
        for row in report['cases']:
            if row['id'] in ids:
                row['status']='PASSED';row.pop('assertion',None)
        report['status']='PASSED' if len(ids)==len(LEGACY_IDS) else 'FAILED'
        report['exitCode']=0 if report['status']=='PASSED' else 1

    def test_approved_repairs_must_pass_instead_of_matching_old_failures(self):
        self.repair(self.candidate, {'D002','D003','D005','D006','D007'})
        try:result=self.check()
        except ValueError as error:self.fail('Approved repairs were rejected: '+str(error))
        self.assertEqual(['D008'],result['retainedFailures'])

    def test_fully_repaired_candidate_requires_raw_success(self):
        self.repair(self.candidate,set(LEGACY_IDS))
        try:result=self.check()
        except ValueError as error:self.fail('Full raw success was rejected: '+str(error))
        self.assertEqual('PASSED',result['rawCandidateStatus'])
        self.assertEqual([],result['retainedFailures'])

    def test_already_repaired_baseline_is_not_required_to_fail_again(self):
        self.repair(self.base,set(LEGACY_IDS));self.repair(self.candidate,set(LEGACY_IDS))
        try:result=self.check()
        except ValueError as error:self.fail('Repaired base was rejected: '+str(error))
        self.assertEqual('PASSED',result['rawBaselineStatus'])

    def test_repaired_case_cannot_regain_an_exception(self):
        self.repair(self.base,{'D002'})
        self.reject()

    def test_reverting_any_fixed_business_behavior_is_rejected(self):
        for name in LEGACY_IDS:
            with self.subTest(name=name):
                self.setUp();self.repair(self.candidate,set(LEGACY_IDS))
                next(row for row in self.candidate['cases'] if row['id']==name)['status']='FAILED'
                self.candidate['status']='FAILED';self.reject()
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

    def test_report_cannot_forge_source_repair_contract(self):
        self.candidate['defectContract']['defects']['D002']='FIXED';self.reject()
    def test_changed_passed_assertion_fingerprint_rejected(self):
        self.repair(self.candidate,set(LEGACY_IDS))
        self.candidate['assertionFingerprints']['quantity']='f'*64;self.reject()
    def test_wrong_raw_exit_rejected(self):
        self.candidate['exitCode']=0;self.reject()
    def test_unknown_contract_version_rejected(self):
        with self.assertRaises(ValueError):validate_contract({**self.base_contract,'version':2})
    def test_unknown_or_missing_defect_in_contract_rejected(self):
        contract=copy.deepcopy(self.base_contract);contract['defects'].pop('D008')
        with self.assertRaises(ValueError):validate_contract(contract)
    def test_unknown_source_without_contract_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):load_contract(folder,self.identity)
    def test_historical_contract_requires_exact_sha_and_digest(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual(self.base_contract,load_contract(folder,HISTORICAL_SOURCE))
            with self.assertRaises(ValueError):load_contract(folder,{**HISTORICAL_SOURCE,'sourceDigest':'f'*64})
    def test_current_original_business_assertions_are_pinned(self):
        path=Path(__file__).resolve().parents[1]/'defects/http_red.py'
        self.assertEqual(ASSERTION_FINGERPRINTS,assertion_fingerprints(path))
        with tempfile.TemporaryDirectory() as folder:
            bad=Path(folder)/'bad.py';bad.write_text(path.read_text().replace("o['points']=='800.00'","o['points']=='900.00'"))
            with self.assertRaises(ValueError):assertion_fingerprints(bad)

    def test_ast_fingerprint_ignores_empty_python312_type_params(self):
        import ast
        node=ast.parse('def sample():\n return 1\n').body[0]
        original=stable_ast_dump(node)
        node.type_params=[]
        self.assertEqual(original,stable_ast_dump(node))
        if 'type_params' in node._fields:
            node.type_params=[ast.Name(id='T',ctx=ast.Load())]
            self.assertNotEqual(original,stable_ast_dump(node))

    def test_baseline_and_candidate_business_assertions_stay_identical(self):
        from legacy_baseline import assertion_equivalence
        root=Path(__file__).resolve().parents[2]
        self.assertEqual(ASSERTION_FINGERPRINTS, assertion_equivalence(
            root/'tests/defects/http_red.py', root/'tests/quality/legacy_baseline.py'))
    def test_baseline_ref_is_required_before_any_resources(self):
        script=Path(__file__).with_name('legacy_baseline.py')
        result=subprocess.run([__import__('sys').executable,str(script),'--output','/private/tmp/not-created-missing-base'],
                              capture_output=True,text=True)
        self.assertEqual(2,result.returncode)
        self.assertIn('--base',result.stderr)
    def test_migrations_are_read_from_baseline_source_and_must_exist(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'tests/quality/e2e').mkdir(parents=True)
            config=root/'tests/quality/e2e/environment.py'
            config.write_text("MIGRATIONS = ['baseline-only.sql']\n")
            with self.assertRaises(ValueError):migrations_for_source(root)
            migrations=root/'yshop-drink-boot3/sql/migrations';migrations.mkdir(parents=True)
            (migrations/'baseline-only.sql').write_text('SELECT 1;')
            self.assertEqual(['baseline-only.sql'],migrations_for_source(root))
            config.write_text("MIGRATIONS = ['../candidate.sql']\n")
            with self.assertRaises(ValueError):migrations_for_source(root)

    def test_observation_helper_cannot_drift_between_harnesses(self):
        from legacy_baseline import assertion_equivalence
        root=Path(__file__).resolve().parents[2]
        candidate=root/'tests/defects/http_red.py';baseline=root/'tests/quality/legacy_baseline.py'
        with tempfile.TemporaryDirectory() as folder:
            changed=Path(folder)/'candidate.py'
            changed.write_text(candidate.read_text().replace(
                "points=scalar('SELECT integral FROM yshop_user WHERE id=101')",
                "points=scalar('SELECT integral FROM yshop_user WHERE id=102')"))
            with self.assertRaises(ValueError):assertion_equivalence(changed,baseline)

    def test_identical_tampering_of_both_observation_helpers_is_rejected(self):
        from legacy_baseline import assertion_equivalence
        root=Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as folder:
            paths=[]
            for index,name in enumerate(['tests/defects/http_red.py','tests/quality/legacy_baseline.py']):
                path=Path(folder)/str(index)
                path.write_text((root/name).read_text().replace(
                    "points=scalar('SELECT integral FROM yshop_user WHERE id=101')",
                    "points=scalar('SELECT integral FROM yshop_user WHERE id=102')"))
                paths.append(path)
            with self.assertRaises(ValueError):assertion_equivalence(*paths)

    def test_fixture_failure_cannot_certify_an_approved_repair(self):
        self.repair(self.candidate,set(LEGACY_IDS))
        for row in self.base['cases']:
            if row['id']=='D007':
                row['assertion']='single reward fixture must debit points'
                row['actual']={'code':500,'orders':0,'stock':10,'points':'1000.00','ledgerBalance':'','ledgerDebit':''}
        self.reject()

if __name__=='__main__':unittest.main()
