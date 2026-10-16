"""CI publication must exclude private context, raw responses and credentials."""
import json
from pathlib import Path
import tempfile
import unittest

class E2ePublishTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)/'run';self.root.mkdir()
        self.destination=Path(self.temp.name)/'public'
        self.report={'runId':'owned','sourceSha':'head','status':'FAILED','profile':'ci'}
        self.write()

    def write(self):
        (self.root/'report.json').write_text(json.dumps(self.report))
        for name in ['summary.md','report.html','junit.xml']:(self.root/name).write_text('safe summary')

    def publish(self):
        from e2e.publish import safe_bundle
        return safe_bundle(self.root,self.destination)

    def test_failed_safe_summaries_remain_publishable(self):
        self.publish()
        self.assertEqual('FAILED',json.loads((self.destination/'report.json').read_text())['status'])

    def test_only_four_summaries_are_copied(self):
        (self.root/'context-private.json').write_text('private')
        (self.root/'trace.zip').write_text('private')
        self.publish()
        self.assertEqual({'report.json','report.html','summary.md','junit.xml'},set(p.name for p in self.destination.iterdir()))

    def test_nested_credential_field_rejected_before_copy(self):
        self.report['nested']=[{'accessToken':'synthetic-value'}];self.write()
        with self.assertRaisesRegex(ValueError,'UNSAFE_PUBLIC_ARTIFACT'):self.publish()
        self.assertFalse(self.destination.exists())

    def test_private_context_keys_rejected(self):
        self.report['commandDir']='/private/mailbox';self.write()
        with self.assertRaisesRegex(ValueError,'UNSAFE_PUBLIC_ARTIFACT'):self.publish()

    def test_bearer_value_in_html_rejected(self):
        (self.root/'report.html').write_text('<p>Bearer synthetic-secret</p>')
        with self.assertRaisesRegex(ValueError,'UNSAFE_PUBLIC_ARTIFACT'):self.publish()

    def test_secret_assignment_in_summary_rejected(self):
        (self.root/'summary.md').write_text('password=synthetic-secret')
        with self.assertRaisesRegex(ValueError,'UNSAFE_PUBLIC_ARTIFACT'):self.publish()

    def test_symlink_summary_rejected(self):
        target=self.root/'summary.md';target.unlink();target.symlink_to(self.root/'report.html')
        with self.assertRaisesRegex(ValueError,'PUBLIC_ARTIFACT_REQUIRED'):self.publish()

    def test_missing_summary_rejected(self):
        (self.root/'junit.xml').unlink()
        with self.assertRaisesRegex(ValueError,'PUBLIC_ARTIFACT_REQUIRED'):self.publish()

if __name__=='__main__':unittest.main()
