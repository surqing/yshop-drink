"""Use disposable Git histories to prove clean committed leaks cannot pass."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('secret_scan', Path(__file__).with_name('secret-scan.py'))
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


class SecretScanTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Scan regression test')
        (self.repo / 'base.txt').write_text('safe baseline')
        self.git('add', '.')
        self.git('commit', '-qm', 'baseline')
        self.base = self.git('rev-parse', 'HEAD').decode().strip()
        self.value = b'synthetic-private-regression-marker'

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, stderr=subprocess.DEVNULL)

    def test_clean_committed_leak_fails_and_explicit_base_is_honored(self):
        (self.repo / 'new.txt').write_bytes(self.value)
        self.git('add', '.')
        self.git('commit', '-qm', 'committed leak fixture')
        self.assertEqual(self.git('status', '--porcelain'), b'')
        failed, coverage = scanner.scan_repository(self.repo, [self.value], self.base)
        self.assertIn('Committed Git diff', failed)
        self.assertEqual(coverage['committedFiles'], 1)
        self.assertEqual(coverage['workingFiles'], 0)
        self.assertEqual(scanner.scan_repository(self.repo, [self.value], 'HEAD')[0], [])
        # A clean local edit cannot conceal what is still committed in the PR.
        (self.repo / 'new.txt').write_text('safe working copy')
        self.assertIn('Committed Git diff', scanner.scan_repository(self.repo, [self.value], self.base)[0])

    def test_staged_unstaged_and_untracked_leaks_are_checked(self):
        (self.repo / 'base.txt').write_bytes(self.value)
        (self.repo / 'staged.txt').write_bytes(self.value)
        self.git('add', 'staged.txt')
        (self.repo / 'untracked.txt').write_bytes(self.value)
        failed, coverage = scanner.scan_repository(self.repo, [self.value], self.base)
        self.assertEqual(coverage['workingFiles'], 2)
        self.assertEqual(coverage['untrackedFiles'], 1)
        for name in ['base.txt', 'staged.txt', 'untracked.txt']:
            self.assertIn(str(self.repo / name), failed)

    def test_safe_change_passes_and_invalid_base_fails(self):
        (self.repo / 'new.txt').write_text('ordinary business ID')
        self.git('add', '.')
        self.git('commit', '-qm', 'safe change')
        self.assertEqual(scanner.scan_repository(self.repo, [self.value], self.base)[0], [])
        with self.assertRaises(subprocess.CalledProcessError):
            scanner.scan_repository(self.repo, [self.value], 'missing-base-ref')


if __name__ == '__main__':
    unittest.main()
