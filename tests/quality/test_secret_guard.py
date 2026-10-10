import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from secret_guard import scan,unsafe

class SecretGuardTest(unittest.TestCase):
    def test_private_key_detected_without_echo(self):
        self.assertTrue(unsafe('value="-----BEGIN '+'PRIVATE KEY-----"'))
        self.assertFalse(unsafe('password="synthetic-only-value-123456"'))

    def test_untracked_and_committed_literal_secret_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            def git(*argv):return subprocess.check_output(['git',*argv],cwd=root,stderr=subprocess.DEVNULL)
            git('init','-q');git('-c','user.name=Synthetic','-c','user.email=synthetic@example.invalid','commit','--allow-empty','-qm','base')
            base=git('rev-parse','HEAD').decode().strip()
            (root/'config.properties').write_text('password="'+'A9x2Q7z6N1r8J5t4'+'"\n')
            self.assertEqual(['config.properties'],scan(root,base))
            git('add','.');git('-c','user.name=Synthetic','-c','user.email=synthetic@example.invalid','commit','-qm','fixture')
            self.assertEqual(['config.properties'],scan(root,base))

    def test_actual_exact_value_cli_detects_injected_log_without_echo(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'tests/smoke').mkdir(parents=True)
            source=Path(__file__).parents[1]/'smoke/secret-scan.py'
            scanner=root/'tests/smoke/secret-scan.py';scanner.write_bytes(source.read_bytes())
            subprocess.run(['git','init','-q'],cwd=root,check=True,stdout=subprocess.DEVNULL)
            subprocess.run(['git','-c','user.name=Synthetic','-c','user.email=synthetic@example.invalid','commit','--allow-empty','-qm','base'],cwd=root,check=True,stdout=subprocess.DEVNULL)
            log=root/'synthetic.log';canary='synthetic-QA-log-canary-ONLY-91';log.write_text(canary)
            result=subprocess.run([sys.executable,str(scanner),'--base','HEAD','--stdin-identities','--runtime',str(log)],input=json.dumps({'canary':canary}),text=True,capture_output=True,check=False)
            self.assertEqual(1,result.returncode)
            self.assertEqual('FAIL',json.loads(result.stdout)['result'])
            self.assertNotIn(canary,result.stdout+result.stderr)

if __name__=='__main__':unittest.main()
