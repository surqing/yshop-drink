import unittest
from unittest.mock import patch

from ingress import allocate_proxy_port, classify_proxy_exit, linux_proxy_config


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


if __name__ == '__main__':
    unittest.main()
