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

    def test_root_auto_configuration_resources_are_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);jar=root/'app.jar'
            with zipfile.ZipFile(jar,'w') as bundle:
                bundle.writestr('META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports','example.RealConfiguration')
                bundle.writestr('BOOT-INF/classes/example/Application.class','class')
            extract_application(jar,root/'extracted')
            resource=root/'extracted/classes/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports'
            self.assertTrue(resource.is_file(),'application auto-configuration metadata was dropped')
            self.assertEqual('example.RealConfiguration',resource.read_text())

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

    def test_order_print_poll_bridge_action_is_run_scoped(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as folder:
            owner='a'*32;mailbox=Path(folder)/('commands-'+owner);mailbox.mkdir(mode=0o700)
            (mailbox/'requests').mkdir();(mailbox/'responses').mkdir()
            context={'owner':owner,'commandDir':str(mailbox)}
            def respond(_):
                requests=list((mailbox/'requests').glob('*.json'))
                if not requests:return
                command=json.loads(requests[0].read_text())
                self.assertEqual('order-print-poll',command['action'])
                (mailbox/'responses'/requests[0].name).write_text(json.dumps({
                    'owner':owner,'commandId':command['commandId'],'status':'PASSED','printCalls':3,'statusCalls':1}))
            with patch('e2e.bridge.time.sleep',side_effect=respond):
                reply=invoke(context,'order-print-poll',{},timeout=1)
            self.assertEqual(3,reply['printCalls']);self.assertEqual(1,reply['statusCalls'])

    def test_crash_fixture_requires_a_positive_owned_job_id(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as folder:
            owner='a'*32;mailbox=Path(folder)/('commands-'+owner);mailbox.mkdir(mode=0o700)
            (mailbox/'requests').mkdir();(mailbox/'responses').mkdir()
            context={'owner':owner,'commandDir':str(mailbox)}
            for value in (0,-1,'12 OR 1=1',True):
                with self.subTest(value=value), self.assertRaisesRegex(ValueError,'ENTITY_ID_INVALID'):
                    invoke(context,'simulate-print-crash',{'jobId':value})
            self.assertEqual([],list((mailbox/'requests').iterdir()))

if __name__=='__main__':unittest.main()
