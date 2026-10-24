import tempfile
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from unittest.mock import patch

from ingress import allocate_proxy_port, classify_proxy_exit, linux_proxy_config
from ingress_target import is_owned_synthetic_ingress


class SyntheticIngressTests(unittest.TestCase):
    def test_proxy_uses_an_available_ephemeral_loopback_port(self):
        with patch('ingress.socket.socket') as socket_factory:
            probe = socket_factory.return_value.__enter__.return_value
            probe.getsockname.return_value = ('127.0.0.1', 49124)
            port = allocate_proxy_port()
            probe.bind.assert_called_once_with(('127.0.0.1', 0))
        self.assertGreater(port, 0)
        self.assertLess(port, 65536)

    def test_linux_proxy_config_binds_both_loopbacks_to_selected_port(self):
        config = linux_proxy_config('server { listen 8443 ssl; }', 49123)
        self.assertIn('listen 127.0.0.1:49123 ssl;', config)
        self.assertIn('listen [::1]:49123 ssl;', config)
        self.assertNotIn('listen 8443 ssl;', config)

    def test_linux_proxy_config_rejects_invalid_port(self):
        with self.assertRaises(ValueError):
            linux_proxy_config('server { listen 8443 ssl; }', 0)

    def test_proxy_exit_diagnostic_classifies_port_conflict_without_raw_log(self):
        self.assertEqual('OWNED_TLS_PORT_CONFLICT', classify_proxy_exit('bind() failed (98: Address already in use)'))

    def test_proxy_exit_diagnostic_uses_generic_code_for_unknown_log(self):
        self.assertEqual('OWNED_TLS_PROXY_EXITED', classify_proxy_exit('unexpected nginx failure'))

    def test_owned_synthetic_ingress_accepts_its_dynamic_loopback_port(self):
        with tempfile.TemporaryDirectory() as folder:
            ca_file = Path(folder) / 'ca.pem'
            ca_file.write_text('synthetic test certificate')
            with patch('ingress_target.subprocess.run', return_value=CompletedProcess(
                    [], 0, 'subject= CN=yshop synthetic local CA\n', '')) as openssl:
                self.assertTrue(is_owned_synthetic_ingress('https://localhost:49124', ca_file, folder))
            openssl.assert_called_once()

    def test_owned_synthetic_ingress_rejects_nonlocal_or_unowned_targets(self):
        with tempfile.TemporaryDirectory() as folder:
            ca_file = Path(folder) / 'ca.pem'
            ca_file.write_text('synthetic test certificate')
            with patch('ingress_target.subprocess.run') as openssl:
                self.assertFalse(is_owned_synthetic_ingress('https://example.com:49124', ca_file, folder))
                self.assertFalse(is_owned_synthetic_ingress('https://localhost:49124/path', ca_file, folder))
                self.assertFalse(is_owned_synthetic_ingress('https://localhost:49124', Path(folder) / 'other.pem', folder))
            openssl.assert_not_called()


if __name__ == '__main__':
    unittest.main()
