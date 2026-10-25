"""The CLI must list exact profile variants without provisioning anything."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

REPO = Path(os.environ.get('YSHOP_E2E_TEST_REPO', Path(__file__).resolve().parents[2]))

class E2eCliTest(unittest.TestCase):
    def test_order_fulfillment_suite_uses_the_separate_mysql_acceptance_gate(self):
        quality_dir = str(REPO / 'tests/quality')
        sys.path.insert(0, quality_dir)
        try:
            from run import CONDITIONAL
            self.assertIn('OrderFulfillmentDatabaseTest', CONDITIONAL)
        finally:
            sys.path.remove(quality_dir)

    def test_complete_capability_blockage_is_not_business_failure(self):
        from e2e.runner import scenario_failure
        cases=[{'id':'a','status':'PASSED'},{'id':'b','status':'BLOCKED'}]
        self.assertEqual('BLOCKED',scenario_failure(cases,[{'id':'a'},{'id':'b'}]))

    def test_business_failure_remains_failed_alongside_blockage(self):
        from e2e.runner import scenario_failure
        self.assertEqual('FAILED',scenario_failure([{'id':'a','status':'FAILED'},{'id':'b','status':'BLOCKED'}],[{'id':'a'},{'id':'b'}]))

    def test_incomplete_executor_output_is_inconclusive(self):
        from e2e.runner import scenario_failure
        self.assertEqual('INCONCLUSIVE',scenario_failure([{'id':'a','status':'BLOCKED'}],[{'id':'a'},{'id':'b'}]))

    def test_nonzero_executor_cannot_promote_passed_prefix(self):
        from e2e.runner import scenario_failure
        self.assertEqual('INCONCLUSIVE',scenario_failure([{'id':'a','status':'PASSED'}],[{'id':'a'}]))

    def test_first_and_repeated_signals_during_cleanup_finish_owned_cleanup_and_report(self):
        import signal
        from unittest.mock import patch, MagicMock
        from e2e import runner
        identity={'sourceSha':'sha','sourceDigest':'digest'}
        finished=[]
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);support=MagicMock();support.root=root/'support';support.root.mkdir()
            support.prepare.return_value={};support.context.return_value={}
            environment=MagicMock();environment.context.return_value={}
            def construct_support(repo,path,ident,owner):
                (support.root/'support-ready.json').write_text(json.dumps({'owner':owner,'status':'PASSED','unknownProviderOperationsDenied':True}))
                return support
            def close():
                for _ in range(2):signal.getsignal(signal.SIGTERM)(signal.SIGTERM,None)
                finished.append(True)
                return {'status':'PASSED'}
            environment.close.side_effect=close
            with patch.object(runner,'source_identity',return_value=identity), patch.object(runner,'preflight'), \
                 patch.object(runner,'source_workspace',return_value=root),patch.object(runner,'verify_artifact',return_value={}), \
                 patch('e2e.support.TestSupport',side_effect=construct_support),patch('e2e.environment.OwnedEnvironment',return_value=environment), \
                 patch.object(runner,'execute',side_effect=RuntimeError('CONTROLLED_SCENARIO_FAILURE')):
                self.assertEqual(1,runner.run(root/'repo',root/'out','ci'))
            self.assertEqual([True],finished)
            report=json.loads(next((root/'out').glob('*/report.json')).read_text())
            self.assertTrue(report['complete']);self.assertEqual('PASSED',report['cleanup'])
            self.assertNotEqual('PASSED',report['status'])
            self.assertEqual('FINISHED',report['stage'])

    def invoke(self, *arguments):
        return subprocess.run([sys.executable, str(REPO/'tests/quality/run.py'), *arguments],
                              cwd=REPO, text=True, capture_output=True, timeout=20)

    def test_local_lists_registered_mini_variants_without_creating_output(self):
        with tempfile.TemporaryDirectory() as folder:
            output=Path(folder)/'must-not-exist'
            result=self.invoke('E2E','--profile','local','--list','--output',str(output))
            self.assertEqual(0,result.returncode,result.stderr)
            report=json.loads(result.stdout)
            self.assertEqual('local',report['profile'])
            self.assertEqual(['score-redemption','score-retry','printer-unknown','pickup-contact','auth-session','catalog-order','store-isolation','order-retry',
                              'order-cancel','coupon-release','coupon-complete','pickup-fulfillment','store-statistics','order-print-jobs',
                              'printer-operation-recovery','printer-operation-recovery-tenant-enabled'],
                             [s['id'] for s in report['scenarios']])
            for scenario in report['scenarios']:
                if scenario['id'] in ('printer-unknown','store-statistics','order-print-jobs','printer-operation-recovery',
                                      'printer-operation-recovery-tenant-enabled'):
                    self.assertEqual('api-admin',scenario['variant'])
                    self.assertNotIn('mini',scenario['requiredCapabilities'])
                else:
                    self.assertEqual('mini-admin',scenario['variant'])
                    self.assertIn('mini',scenario['requiredCapabilities'])
            self.assertFalse(output.exists())

    def test_ci_lists_api_variants_and_marks_mini_not_run(self):
        result=self.invoke('E2E','--profile','ci','--list')
        self.assertEqual(0,result.returncode,result.stderr)
        report=json.loads(result.stdout)
        self.assertEqual('NOT_RUN',report['miniStatus'])
        self.assertEqual(16,len(report['scenarios']))
        self.assertTrue(all(s['variant']=='api-admin' for s in report['scenarios']))

    def test_f3_recovery_order_and_quick_contracts_are_registered(self):
        from e2e.registry import listing
        checks=listing('ci','order-print-jobs')['scenarios'][0]['expectedCheckIds']
        self.assertEqual(['unbound-order-waits-on-real-page','offline-device-waits-on-real-page','order-history-on-real-page',
                          'unknown-refresh-readonly','recovery-confirmation-gate','unknown-recovery-audited',
                          'manual-reprint-audited','printed-confirmation-closes-without-resend',
                          'cross-store-print-denied','print-job-sql-audit'],checks)
        self.assertLess(checks.index('unknown-recovery-audited'),checks.index('manual-reprint-audited'))
        quick=json.loads((REPO/'tests/quality/quick-manifest.json').read_text())
        self.assertEqual(10,quick['tests/quality/test_e2e_oracle_contract.py'])
        self.assertEqual(13,quick['tests/quality/test_e2e_support.py'])
        recovery=listing('ci','printer-operation-recovery')['scenarios'][0]
        self.assertEqual('api-admin',recovery['variant'])
        self.assertIn('browser',recovery['requiredCapabilities'])
        self.assertNotIn('mini',recovery['requiredCapabilities'])
        self.assertEqual(['pending-fixture-created','hq-pending-read-is-side-effect-free','query-only-headquarters-page',
                          'permission-and-tenant-denials','invalid-resolution-requests-no-side-effects','page-resolution-records-audit',
                          'two-operator-race-single-audit','same-token-role-revocation','undelivered-command-needs-explicit-retry',
                          'lost-response-with-empty-pending-is-not-success','new-token-before-reread-resets-confirmation',
                          'database-business-and-provider-invariants'], recovery['expectedCheckIds'])
        enabled=listing('ci','printer-operation-recovery-tenant-enabled')['scenarios'][0]
        self.assertEqual('api-admin',enabled['variant'])
        self.assertEqual(['tenant-enabled-same-tenant-pending-read',
                          'tenant-enabled-rejects-missing-and-mismatched-context',
                          'tenant-enabled-cross-tenant-resource-and-recovery-denied'], enabled['expectedCheckIds'])

    def test_filtered_list_contains_only_requested_scenario(self):
        result=self.invoke('E2E','--profile','local','--scenario','order-retry','--list')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual(['order-retry'],[s['id'] for s in json.loads(result.stdout)['scenarios']])
        printer=self.invoke('E2E','--profile','local','--scenario','printer-unknown','--list')
        self.assertEqual(0,printer.returncode,printer.stderr)
        plan=json.loads(printer.stdout)
        self.assertEqual('NOT_RUN',plan['miniStatus'])
        self.assertEqual('backend-admin-subset',plan['scope'])
        self.assertEqual('api-admin',plan['scenarios'][0]['variant'])

    def test_missing_official_tools_blocks_local_without_ci_fallback(self):
        with tempfile.TemporaryDirectory() as folder:
            env=os.environ.copy();env['YSHOP_HBUILDER_CLI']=str(Path(folder)/'missing-hbuilder')
            result=subprocess.run([sys.executable,str(REPO/'tests/quality/run.py'),'E2E','--profile','local','--output',folder],
                                  cwd=REPO,text=True,capture_output=True,env=env,timeout=20)
            self.assertEqual(1,result.returncode,result.stderr)
            summary=json.loads(result.stdout)
            report=json.loads(Path(summary['report']).read_text())
            self.assertEqual('BLOCKED',report['status'])
            self.assertEqual('OFFICIAL_TOOL_OR_OWN_APPID_MISSING',report['reasonCode'])
            self.assertEqual('local',report['profile'])
            self.assertEqual('PASSED',report['cleanup'])
            self.assertTrue(report['complete'])
            self.assertTrue(all(s['status']=='NOT_RUN' for s in report['scenarios']))

    def test_profile_is_required(self):
        result=self.invoke('E2E','--list')
        self.assertEqual(2,result.returncode)
        self.assertIn('--profile',result.stderr)

    def test_unknown_scenario_is_rejected(self):
        result=self.invoke('E2E','--profile','local','--scenario','invented','--list')
        self.assertEqual(2,result.returncode)
        self.assertIn('UNKNOWN_E2E_SCENARIO',result.stderr)

    def test_e2e_options_cannot_silently_change_legacy_modes(self):
        result=self.invoke('QUICK','--profile','ci','--list')
        self.assertEqual(2,result.returncode)
        self.assertIn('E2E_OPTIONS_REQUIRE_E2E_MODE',result.stderr)

if __name__=='__main__':unittest.main()
