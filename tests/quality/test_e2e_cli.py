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
    def invoke(self, *arguments):
        return subprocess.run([sys.executable, str(REPO/'tests/quality/run.py'), *arguments],
                              cwd=REPO, text=True, capture_output=True, timeout=20)

    def test_local_lists_seven_mini_variants_without_creating_output(self):
        with tempfile.TemporaryDirectory() as folder:
            output=Path(folder)/'must-not-exist'
            result=self.invoke('E2E','--profile','local','--list','--output',str(output))
            self.assertEqual(0,result.returncode,result.stderr)
            report=json.loads(result.stdout)
            self.assertEqual('local',report['profile'])
            self.assertEqual(['auth-session','catalog-order','store-isolation','order-retry',
                              'order-cancel','coupon-release','coupon-complete'],
                             [s['id'] for s in report['scenarios']])
            self.assertTrue(all(s['variant']=='mini-admin' for s in report['scenarios']))
            self.assertFalse(output.exists())

    def test_ci_lists_api_variants_and_marks_mini_not_run(self):
        result=self.invoke('E2E','--profile','ci','--list')
        self.assertEqual(0,result.returncode,result.stderr)
        report=json.loads(result.stdout)
        self.assertEqual('NOT_RUN',report['miniStatus'])
        self.assertEqual(7,len(report['scenarios']))
        self.assertTrue(all(s['variant']=='api-admin' for s in report['scenarios']))

    def test_filtered_list_contains_only_requested_scenario(self):
        result=self.invoke('E2E','--profile','local','--scenario','order-retry','--list')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual(['order-retry'],[s['id'] for s in json.loads(result.stdout)['scenarios']])

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
