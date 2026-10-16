"""Test-support artifacts and command mailboxes must belong to this source/run."""
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from e2e.support import extract_application, TestSupport
from e2e.bridge import validate_context, invoke

class TestSupportBoundaryTest(unittest.TestCase):
    def test_extracts_current_boot_classes_and_libraries_only(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);jar=root/'app.jar'
            with zipfile.ZipFile(jar,'w') as bundle:
                bundle.writestr('BOOT-INF/classes/application.yaml','config')
                bundle.writestr('BOOT-INF/lib/dependency.jar','library')
                bundle.writestr('META-INF/MANIFEST.MF','ignored')
            cp=extract_application(jar,root/'extracted')
            self.assertEqual('config',(root/'extracted/classes/application.yaml').read_text())
            self.assertEqual('library',(root/'extracted/lib/dependency.jar').read_text())
            self.assertEqual(2,len(cp))
            self.assertFalse((root/'extracted/META-INF').exists())

    def test_zip_traversal_is_rejected_before_writing(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);jar=root/'app.jar'
            with zipfile.ZipFile(jar,'w') as bundle:
                bundle.writestr('BOOT-INF/classes/../../escaped','unsafe')
            with self.assertRaisesRegex(ValueError,'ARTIFACT_PATH_INVALID'):
                extract_application(jar,root/'extracted')
            self.assertFalse((root/'escaped').exists())

    def test_bridge_rejects_owner_mismatch(self):
        with self.assertRaisesRegex(ValueError,'BRIDGE_CONTEXT_INVALID'):
            validate_context({'owner':'invalid','commandDir':'/tmp/anywhere'})

    def test_bridge_requires_run_specific_mailbox(self):
        with self.assertRaisesRegex(ValueError,'BRIDGE_CONTEXT_INVALID'):
            validate_context({'owner':'a'*32,'commandDir':'/tmp/shared'})

    def test_unknown_action_never_writes_a_command(self):
        with tempfile.TemporaryDirectory() as folder:
            mailbox=Path(folder)/('commands-'+'a'*32);mailbox.mkdir(mode=0o700)
            context={'owner':'a'*32,'commandDir':str(mailbox)}
            with self.assertRaisesRegex(ValueError,'BRIDGE_ACTION_UNKNOWN'):
                invoke(context,'execute-sql',{},timeout=.01)
            self.assertEqual([],list(mailbox.iterdir()))

    def test_foreign_reply_cannot_certify_completion(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as folder:
            mailbox=Path(folder)/('commands-'+'a'*32);mailbox.mkdir(mode=0o700)
            (mailbox/'requests').mkdir();(mailbox/'responses').mkdir()
            context={'owner':'a'*32,'commandDir':str(mailbox)}
            def response(_):
                requests=list((mailbox/'requests').glob('*.json'))
                if requests:
                    command=json.loads(requests[0].read_text())
                    (mailbox/'responses'/requests[0].name).write_text(json.dumps({'owner':'b'*32,'commandId':command['commandId'],'status':'PASSED'}))
            with patch('e2e.bridge.time.sleep',side_effect=response):
                with self.assertRaisesRegex(ValueError,'BRIDGE_REPLY_FOREIGN'):
                    invoke(context,'complete',{'orderId':'owned-123'},timeout=1)

if __name__=='__main__':unittest.main()
