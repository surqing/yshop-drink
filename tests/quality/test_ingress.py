import tempfile
import unittest
from pathlib import Path
from subprocess import CompletedProcess
import hashlib
import json
import os
import socket
import ssl
from unittest.mock import MagicMock, patch

import ingress_target
from ingress import allocate_proxy_port, classify_proxy_exit, linux_proxy_config
from ingress_target import CALLBACK_UPSTREAM_PORT, expected_proxy_config, is_owned_synthetic_ingress


class SyntheticIngressTests(unittest.TestCase):
    @staticmethod
    def claim(owner, port, *, upstream=CALLBACK_UPSTREAM_PORT, platform=None, config=None, cert=b'SYNTHETIC'):
        live_config = config if config is not None else expected_proxy_config(port, platform=platform)
        return {'owner': owner, 'port': port,
                'serverCertSha256': hashlib.sha256(cert).hexdigest(),
                'upstreamPort': upstream,
                'configSha256': hashlib.sha256(live_config).hexdigest()}

    @staticmethod
    def proxy_config(config):
        return CompletedProcess(['docker', 'exec'], 0, config, b'')

    @staticmethod
    def proxy_inspect(owner, port=49124, *, label=None, running=True, network='bridge'):
        return CompletedProcess([], 0, json.dumps([{
            'Config': {
                'Image': 'nginx@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94',
                'Entrypoint': ['nginx'], 'Cmd': ['-c', '/tls/nginx.conf', '-g', 'daemon off;'],
                'Labels': {'quality.owner': label if label is not None else owner},
            },
            'State': {'Running': running},
            'HostConfig': {
                'NetworkMode': network,
                'PortBindings': {'8443/tcp': [{'HostIp': '127.0.0.1', 'HostPort': str(port)}]},
            },
            'Mounts': [{'Type': 'volume', 'Destination': '/tls', 'RW': False}],
        }]), '')

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
            server_file = Path(folder) / 'server.pem'
            server_file.write_text('-----BEGIN CERTIFICATE-----\nU1lOVEhFVElD\n-----END CERTIFICATE-----\n')
            owner = 'yshop-quality-ingress-012345abcdef'
            config = expected_proxy_config(49124)
            (Path(folder) / 'ingress-target.json').write_text(json.dumps(self.claim(owner, 49124)))
            tls_socket = MagicMock()
            tls_socket.getpeercert.return_value = b'SYNTHETIC'
            context = MagicMock()
            context.wrap_socket.return_value.__enter__.return_value = tls_socket
            with patch('ingress_target.subprocess.run', side_effect=[CompletedProcess(
                    [], 0, 'subject= CN=yshop synthetic local CA\n', ''), self.proxy_inspect(owner),
                    self.proxy_config(config)]) as commands, \
                    patch('ssl.create_default_context', return_value=context) as tls_context, \
                    patch('socket.create_connection') as connect:
                self.assertTrue(is_owned_synthetic_ingress('https://localhost:49124', ca_file, folder, owner))
            self.assertEqual(['openssl', 'docker', 'docker'], [call.args[0][0] for call in commands.call_args_list])
            self.assertEqual(['docker', 'inspect', owner], commands.call_args_list[1].args[0])
            tls_context.assert_called_once_with(cafile=str(ca_file.resolve()))
            connect.assert_called_once_with(('127.0.0.1', 49124), timeout=2)
            context.wrap_socket.assert_called_once_with(connect.return_value.__enter__.return_value, server_hostname='localhost')

    def test_owned_synthetic_ingress_rejects_missing_run_owner_claim(self):
        with tempfile.TemporaryDirectory() as folder:
            ca_file = Path(folder) / 'ca.pem'
            ca_file.write_text('synthetic test certificate')
            with patch('ingress_target.subprocess.run') as openssl, \
                    patch('socket.create_connection') as connect:
                self.assertFalse(is_owned_synthetic_ingress('https://localhost:49124', ca_file, folder, None))
            openssl.assert_not_called()
            connect.assert_not_called()

    def test_owned_synthetic_ingress_rejects_port_or_certificate_mismatch(self):
        with tempfile.TemporaryDirectory() as folder:
            ca_file = Path(folder) / 'ca.pem'
            ca_file.write_text('synthetic test certificate')
            server_file = Path(folder) / 'server.pem'
            server_file.write_text('-----BEGIN CERTIFICATE-----\nU1lOVEhFVElD\n-----END CERTIFICATE-----\n')
            owner = 'yshop-quality-ingress-012345abcdef'
            claim = self.claim(owner, 49124, cert=b'OTHER')
            (Path(folder) / 'ingress-target.json').write_text(json.dumps(claim))
            with patch('ingress_target.subprocess.run') as openssl, \
                    patch('socket.create_connection') as connect:
                self.assertFalse(is_owned_synthetic_ingress('https://localhost:49125', ca_file, folder, owner))
                self.assertFalse(is_owned_synthetic_ingress('https://localhost:49124', ca_file, folder, owner))
            openssl.assert_not_called()
            connect.assert_not_called()

    def test_owned_synthetic_ingress_rejects_live_peer_with_another_certificate(self):
        with tempfile.TemporaryDirectory() as folder:
            ca_file = Path(folder) / 'ca.pem'
            ca_file.write_text('synthetic test certificate')
            (Path(folder) / 'server.pem').write_text(
                '-----BEGIN CERTIFICATE-----\nU1lOVEhFVElD\n-----END CERTIFICATE-----\n')
            owner = 'yshop-quality-ingress-012345abcdef'
            (Path(folder) / 'ingress-target.json').write_text(json.dumps(self.claim(owner, 49124)))
            tls_socket = MagicMock()
            tls_socket.getpeercert.return_value = b'OTHER'
            context = MagicMock()
            context.wrap_socket.return_value.__enter__.return_value = tls_socket
            with patch('ingress_target.subprocess.run', side_effect=[CompletedProcess(
                    [], 0, 'subject= CN=yshop synthetic local CA\n', ''), self.proxy_inspect(owner),
                    self.proxy_config(expected_proxy_config(49124))]), \
                    patch('ssl.create_default_context', return_value=context), \
                    patch('socket.create_connection'):
                self.assertFalse(is_owned_synthetic_ingress('https://localhost:49124', ca_file, folder, owner))

    def test_owned_synthetic_ingress_rejects_target_not_published_by_owned_proxy(self):
        with tempfile.TemporaryDirectory() as folder:
            ca_file = Path(folder) / 'ca.pem'
            ca_file.write_text('synthetic test certificate')
            (Path(folder) / 'server.pem').write_text(
                '-----BEGIN CERTIFICATE-----\nU1lOVEhFVElD\n-----END CERTIFICATE-----\n')
            owner = 'yshop-quality-ingress-012345abcdef'
            (Path(folder) / 'ingress-target.json').write_text(json.dumps(self.claim(owner, 49124)))
            fake_container = self.proxy_inspect(owner, port=49125, label='another-owner')
            with patch('ingress_target.subprocess.run', side_effect=[CompletedProcess(
                    [], 0, 'subject= CN=yshop synthetic local CA\n', ''), fake_container]), \
                    patch('socket.create_connection') as connect:
                self.assertFalse(is_owned_synthetic_ingress('https://localhost:49124', ca_file, folder, owner))
            connect.assert_not_called()

    def test_owned_proxy_accepts_host_network_only_on_linux(self):
        from ingress_target import _owned_proxy_publishes
        owner = 'yshop-quality-ingress-012345abcdef'
        host_proxy = self.proxy_inspect(owner, network='host')
        linux_config = expected_proxy_config(49124, platform='linux')
        with patch('ingress_target.subprocess.run', side_effect=[host_proxy, self.proxy_config(linux_config)]):
            with patch('ingress_target.sys.platform', 'linux'):
                self.assertTrue(_owned_proxy_publishes(owner, 49124, linux_config))
        with patch('ingress_target.subprocess.run', return_value=host_proxy):
            with patch('ingress_target.sys.platform', 'darwin'):
                self.assertFalse(_owned_proxy_publishes(owner, 49124, expected_proxy_config(49124)))

    def test_managed_local_proxy_requires_its_fixed_loopback_port_and_project_label(self):
        from ingress_target import _owned_proxy_publishes, MANAGED_INGRESS
        owner = MANAGED_INGRESS
        managed = json.loads(self.proxy_inspect(owner, port=48443).stdout)[0]
        managed['Config']['Labels'] = {'project': 'yshop-drink-phase5h'}
        managed_config = expected_proxy_config(48443)
        with patch('ingress_target.subprocess.run', side_effect=[
                CompletedProcess([], 0, json.dumps([managed]), ''), self.proxy_config(managed_config)]):
            self.assertTrue(_owned_proxy_publishes(owner, 48443, managed_config))
        with patch('ingress_target.subprocess.run', return_value=CompletedProcess([], 0, json.dumps([managed]), '')):
            self.assertFalse(_owned_proxy_publishes(owner, 48444, expected_proxy_config(48444)))
        managed['Config']['Labels'] = {'project': 'another-project'}
        with patch('ingress_target.subprocess.run', return_value=CompletedProcess([], 0, json.dumps([managed]), '')):
            self.assertFalse(_owned_proxy_publishes(owner, 48443, managed_config))

    def test_managed_local_proxy_passes_end_to_end_target_validation(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            ca_file = root / 'ca.pem'
            ca_file.write_text('synthetic test certificate')
            (root / 'server.pem').write_text(
                '-----BEGIN CERTIFICATE-----\nU1lOVEhFVElD\n-----END CERTIFICATE-----\n')
            owner = 'yshop-drink-local-ingress-streamed'
            managed_config = expected_proxy_config(48443)
            (root / 'ingress-target.json').write_text(json.dumps(self.claim(owner, 48443)))
            managed = json.loads(self.proxy_inspect(owner, port=48443).stdout)[0]
            managed['Config']['Labels'] = {'project': 'yshop-drink-phase5h'}
            tls_socket = MagicMock()
            tls_socket.getpeercert.return_value = b'SYNTHETIC'
            context = MagicMock()
            context.wrap_socket.return_value.__enter__.return_value = tls_socket
            with patch('ingress_target.subprocess.run', side_effect=[CompletedProcess(
                    [], 0, 'subject= CN=yshop synthetic local CA\n', ''),
                    CompletedProcess([], 0, json.dumps([managed]), ''),
                    self.proxy_config(managed_config)]), \
                    patch('ssl.create_default_context', return_value=context), \
                    patch('socket.create_connection'):
                self.assertTrue(is_owned_synthetic_ingress(
                    'https://localhost:48443', ca_file, root, owner))

    def test_owned_proxy_rejects_live_config_that_routes_to_regular_backend(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            ca_file = root / 'ca.pem'
            ca_file.write_text('synthetic test certificate')
            (root / 'server.pem').write_text(
                '-----BEGIN CERTIFICATE-----\nU1lOVEhFVElD\n-----END CERTIFICATE-----\n')
            owner = 'yshop-quality-ingress-012345abcdef'
            expected = expected_proxy_config(49124)
            wrong = expected.replace(b'host.docker.internal:48881', b'host.docker.internal:48081')
            (root / 'ingress-target.json').write_text(json.dumps(self.claim(owner, 49124)))
            with patch('ingress_target.subprocess.run', side_effect=[CompletedProcess(
                    [], 0, 'subject= CN=yshop synthetic local CA\n', ''),
                    self.proxy_inspect(owner), self.proxy_config(wrong)]), \
                    patch('socket.create_connection') as connect:
                self.assertFalse(is_owned_synthetic_ingress(
                    'https://localhost:49124', ca_file, root, owner))
            connect.assert_not_called()

    def test_managed_proxy_regular_backend_claim_is_not_callback_proof(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'ca.pem').write_text('synthetic test certificate')
            (root / 'server.pem').write_text(
                '-----BEGIN CERTIFICATE-----\nU1lOVEhFVElD\n-----END CERTIFICATE-----\n')
            owner = 'yshop-drink-local-ingress-streamed'
            regular = expected_proxy_config(48443).replace(
                b'host.docker.internal:48881', b'host.docker.internal:48081')
            (root / 'ingress-target.json').write_text(json.dumps(
                self.claim(owner, 48443, upstream=48081, config=regular)))
            with patch('ingress_target.subprocess.run') as docker, \
                    patch('socket.create_connection') as connect:
                self.assertFalse(is_owned_synthetic_ingress(
                    'https://localhost:48443', root / 'ca.pem', root, owner))
            docker.assert_not_called()
            connect.assert_not_called()

    def test_proxy_config_digest_claim_must_match_exact_expected_file(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            ca_file = root / 'ca.pem'
            ca_file.write_text('synthetic test certificate')
            (root / 'server.pem').write_text(
                '-----BEGIN CERTIFICATE-----\nU1lOVEhFVElD\n-----END CERTIFICATE-----\n')
            owner = 'yshop-quality-ingress-012345abcdef'
            claim = self.claim(owner, 49124)
            claim['configSha256'] = hashlib.sha256(b'wrong config').hexdigest()
            (root / 'ingress-target.json').write_text(json.dumps(claim))
            with patch('ingress_target.subprocess.run') as docker, \
                    patch('socket.create_connection') as connect:
                self.assertFalse(is_owned_synthetic_ingress(
                    'https://localhost:49124', ca_file, root, owner))
            docker.assert_not_called()
            connect.assert_not_called()

    def test_cli_gate_uses_the_same_environment_target_verifier(self):
        env = {
            'YSHOP_INGRESS_BASE_URL': 'https://localhost:49124',
            'YSHOP_INGRESS_CA': '/tmp/ca.pem',
            'YSHOP_SYNTHETIC_TLS_DIR': '/tmp',
            'YSHOP_INGRESS_OWNER': 'yshop-quality-ingress-012345abcdef',
        }
        with patch.dict(os.environ, env, clear=True), \
                patch.object(ingress_target, 'is_owned_synthetic_ingress', return_value=True) as verify:
            self.assertEqual(0, ingress_target.main(['--check-env']))
        verify.assert_called_once_with(env['YSHOP_INGRESS_BASE_URL'], env['YSHOP_INGRESS_CA'],
                                       env['YSHOP_SYNTHETIC_TLS_DIR'], env['YSHOP_INGRESS_OWNER'])
        with patch.dict(os.environ, {}, clear=True):
            self.assertNotEqual(0, ingress_target.main(['--check-env']))

    def test_java_direct_maven_entrypoint_checks_proxy_before_fixture_or_network(self):
        java = (Path(__file__).resolve().parents[2] /
                'yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz/src/test/java/'
                'co/yixiang/yshop/module/order/payment/CallbackIngressEndToEndTest.java').read_text()
        self.assertIn('verifySyntheticIngressTarget();', java)
        self.assertLess(java.index('verifySyntheticIngressTarget();'), java.index('diagnosticsFixture.setup();'))
        self.assertIn('ingress_target.py', java)

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
