#!/usr/bin/env python3
"""Pure evidence-consistency failure cases; no provider, database or money changes."""
import importlib.util
from pathlib import Path
import sys
import unittest
from copy import deepcopy
import json
import shutil
import subprocess
import tempfile

SCRIPTS = Path(__file__).resolve().parents[2] / 'scripts/payment'
sys.path.insert(0, str(SCRIPTS))


def module(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / (name + '.py'))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


harness = module('acceptance-harness')
clock = module('clock-evidence')
provision = module('provision-merchant')
deployment = module('verify-deployment')
history = module('match-history-export')
verified_build = module('build-verified-backend')


class Evidence(unittest.TestCase):
    def setUp(self):
        self.db = {'orderId': 'synthetic-order', 'attemptId': 'a' * 32,
                   'providerOrderReference': 'a' * 32, 'transactionId': 'synthetic-tx',
                   'appid': 'synthetic-app', 'mchId': 'synthetic-merchant', 'amountCents': 2,
                   'currency': 'CNY', 'provider': 'WECHAT', 'orderPaid': 1, 'attemptStatus': 'PAID',
                   'paymentStatus': 'SUCCESS', 'successfulPaymentCount': 1, 'billCount': 1,
                   'fulfillmentStatusCount': 1, 'conflictCount': 0, 'reconciliationCount': 0,
                   'walletDebitCount': 0, 'duplicateReceiptCount': 0}
        self.client = {'orderId': 'synthetic-order', 'attemptId': 'a' * 32, 'paymentResult': 'requestPayment:ok'}
        self.provider = {k: self.db[k] for k in harness.BINDING}
        self.provider['tradeState'] = 'SUCCESS'

    def compare(self, **kwargs):
        data = dict(database=self.db, client=self.client, callback=self.provider, query=self.provider)
        data.update(kwargs)
        return harness.compare(**data)

    def test_dedicated_qa_ingress_only_allowed_in_verified_isolation(self):
        self.assertFalse(provision.allowed_admin_origin('https://localhost:48444'))
        self.assertTrue(provision.allowed_admin_origin('https://localhost:48444',synthetic=True))
        self.assertTrue(provision.allowed_admin_origin('https://localhost:48443'))

    def test_synthetic_origin_does_not_allow_public_or_unsafe_urls(self):
        for url in ['http://localhost:48444','https://example.invalid:48444','https://user@localhost:48444','https://localhost:48444?x=1','https://localhost:48444/#fragment','https://localhost:48445']:
            with self.subTest(url=url):self.assertFalse(provision.allowed_admin_origin(url,synthetic=True))

    def test_isolated_report_cannot_replace_operator_report(self):
        import private_support
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);owned=root/'owned';owned.mkdir()
            shared=root/'payment-acceptance-report.json';shared.write_text('preserved-operator-evidence')
            with patch.object(private_support,'PRIVATE',root),patch.object(private_support,'isolated_schema',return_value='yshop_acceptance_phase5h_01234567'):
                dest=private_support.report_path(owned,shared.name)
                private_support.private_json(dest,{'synthetic':True})
            self.assertEqual('preserved-operator-evidence',shared.read_text())
            self.assertEqual(owned/shared.name,dest)
            self.assertEqual(0,dest.stat().st_mode & 0o077)

    def test_complete_synthetic_evidence(self):
        r = self.compare()
        self.assertTrue(r['consistentObservedEvidence'])
        self.assertEqual(0, r['providerCalls'])
        self.assertEqual(0, r['financialWrites'])

    def test_each_callback_binding_mismatch(self):
        for k in harness.BINDING:
            with self.subTest(k=k):
                p = deepcopy(self.provider); p[k] = 'wrong'
                self.assertFalse(self.compare(callback=p)['consistentObservedEvidence'])

    def test_each_query_binding_missing(self):
        for k in harness.BINDING:
            with self.subTest(k=k):
                p = deepcopy(self.provider); p.pop(k)
                self.assertFalse(self.compare(query=p)['consistentObservedEvidence'])

    def test_client_success_cannot_replace_server_evidence(self):
        self.assertFalse(self.compare(query={})['consistentObservedEvidence'])

    def test_no_wallet_or_duplicate_fulfillment(self):
        for key, value in [('walletDebitCount', 1), ('billCount', 2), ('fulfillmentStatusCount', 2),
                           ('conflictCount', 1), ('reconciliationCount', 1), ('duplicateReceiptCount', 1),
                           ('orderPaid', 0), ('attemptStatus', 'CREATED'), ('amountCents', 11)]:
            with self.subTest(key=key):
                d = deepcopy(self.db); d[key] = value
                self.assertFalse(self.compare(database=d)['consistentObservedEvidence'])

    def test_result_does_not_echo_injected_secrets(self):
        p = deepcopy(self.provider); p['privateKey'] = 'synthetic-secret-canary'
        p['rawBody'] = 'synthetic-body-canary'; p['signature'] = 'synthetic-signature-canary'
        p['observedDeliveryCount'] = 'synthetic-secret-canary'
        import json
        r = json.dumps(self.compare(callback=p))
        self.assertNotIn('canary', r)

    def test_measured_ntp_parser(self):
        self.assertEqual((125.0, 100.0), clock.parse_sample('+0.125000 +/- 0.100000 17.253.68.123 17.253.68.123'))
        self.assertEqual((-125.0, 100.0), clock.parse_sample('-0.125000 +/- 0.100000 17.253.68.123 17.253.68.123'))

    def test_missing_ntp_sample_rejected(self):
        for s in ['', 'network timeout', '0.0', 'synthetic offset=0']:
            with self.subTest(s=s):
                self.assertRaises(ValueError, clock.parse_sample, s)


class ReadOnlyOperations(unittest.TestCase):
    def test_deployment_consistency_and_fail_closed(self):
        r={'buildRevision':'a'*40,'schemaComplete':True,'liveEnabled':False,'reconciliationEnabled':False,'masterKeyAvailable':True,'merchantConfigurationReady':True,'callbackRoute':'/app-api/order/notify/wechat-v3/{detailsId}','merchantFingerprint':'b'*64,'audit':{'available':True,'counts':{'UNCERTAIN_ATTEMPTS':0,'PAYMENT_CONFLICT':0,'RECONCILIATION_REQUIRED':0}}}
        self.assertTrue(deployment.compare([r], 'a'*40)['preActivationDeploymentConsistent'])
        for field,value in [('buildRevision','UNVERIFIED'),('schemaComplete',False),('liveEnabled',True),('masterKeyAvailable',False),('merchantFingerprint','UNAVAILABLE')]:
            x=deepcopy(r);x[field]=value
            self.assertFalse(deployment.compare([x], 'a'*40)['preActivationDeploymentConsistent'])
        x=deepcopy(r);x['merchantFingerprint']='c'*64
        self.assertFalse(deployment.compare([r,x], 'a'*40)['preActivationDeploymentConsistent'])

    def test_export_absence_or_unpaid_never_resolves_remote_risk(self):
        orders=[{'orderId':'synthetic-a'},{'orderId':'synthetic-b'}]
        r=history.match(orders,[{'out_trade_no':'synthetic-a','trade_state':'NOTPAY'}])
        self.assertTrue(all(x['requiresReview'] for x in r))
        r=history.match(orders,[{'out_trade_no':'synthetic-a','trade_state':'CLOSED'}])
        self.assertTrue(r[0]['terminalObservation']);self.assertTrue(r[0]['requiresReview']);self.assertTrue(r[1]['requiresReview'])

    def test_dirty_checkout_cannot_emit_verified_build_or_start_maven(self):
        from unittest.mock import patch
        with patch.object(verified_build,'git',return_value=' M synthetic.java'),patch.object(verified_build.subprocess,'run') as process:
            self.assertRaises(ValueError,verified_build.run)
            process.assert_not_called()

    def test_failed_ntp_renewal_invalidates_previous_evidence(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory(prefix='yshop-ntp-test-') as folder:
            dest=Path(folder);(dest/'clock-evidence.properties').write_text('old-evidence')
            with patch.object(clock,'PRIVATE',dest),patch.object(clock.urllib.request,'urlopen',side_effect=OSError()):
                self.assertRaises(OSError,clock.run)
            self.assertFalse((dest/'clock-evidence.properties').exists())
            self.assertFalse(json.loads((dest/'clock-evidence.json').read_text())['ready'])

    def test_duplicate_provider_export_reference_is_rejected(self):
        self.assertRaises(ValueError,history.match,[],[{'out_trade_no':'x'},{'out_trade_no':'x'}])


class Provisioning(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = tempfile.TemporaryDirectory(prefix='yshop-synthetic-keys-')
        p = Path(cls.base.name)
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                        '-set_serial', '0xABC123', '-subj', '/CN=synthetic-only',
                        '-keyout', str(p / 'merchant-private.pem'), '-out', str(p / 'merchant-cert.pem')],
                       check=True, capture_output=True)
        pub = subprocess.run(['openssl', 'pkey', '-in', str(p / 'merchant-private.pem'), '-pubout'],
                             check=True, capture_output=True).stdout
        (p / 'platform-public.pem').write_bytes(pub)
        (p / 'api-v3-key.txt').write_text('synthetic-api-key'.ljust(32, 'x'))
        (p / 'metadata.json').write_text(json.dumps({'detailsId': 'synthetic-test',
                'appid': 'wx0000000000000000', 'mchId': '0000000000',
                'merchantCertificateSerial': 'ABC123', 'platformPublicKeyId': 'PUB_KEY_ID_SYNTHETIC',
                'notifyUrl': 'https://synthetic.invalid/app-api/order/notify/wechat-v3/synthetic-test'}))
        for f in p.iterdir(): f.chmod(0o600)

    @classmethod
    def tearDownClass(cls):
        cls.base.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='yshop-synthetic-stage-')
        self.folder = Path(self.temporary.name)
        for p in Path(self.base.name).iterdir(): shutil.copy2(p, self.folder / p.name)

    def tearDown(self):
        self.temporary.cleanup()

    def metadata(self, **changes):
        p = self.folder / 'metadata.json'
        m = json.loads(p.read_text());m.update(changes);p.write_text(json.dumps(m));p.chmod(0o600)

    def test_complete_generated_material(self):
        self.assertEqual('V3', provision.validate(self.folder)['wechatApiVersion'])

    def test_missing_material_rejected(self):
        (self.folder / 'merchant-private.pem').unlink()
        self.assertRaises(ValueError, provision.validate, self.folder)

    def test_incorrect_serial_rejected(self):
        self.metadata(merchantCertificateSerial='ABC124')
        self.assertRaises(ValueError, provision.validate, self.folder)

    def test_api_key_length_rejected(self):
        (self.folder / 'api-v3-key.txt').write_text('short')
        self.assertRaises(ValueError, provision.validate, self.folder)

    def test_notify_query_rejected(self):
        self.metadata(notifyUrl='https://synthetic.invalid/app-api/order/notify/wechat-v3/synthetic-test?x=1')
        self.assertRaises(ValueError, provision.validate, self.folder)

    def test_wrong_notify_id_rejected(self):
        self.metadata(notifyUrl='https://synthetic.invalid/app-api/order/notify/wechat-v3/other')
        self.assertRaises(ValueError, provision.validate, self.folder)

    def test_private_key_world_readable_rejected(self):
        (self.folder / 'merchant-private.pem').chmod(0o644)
        self.assertRaises(ValueError, provision.validate, self.folder)

    def test_symlink_key_rejected(self):
        key = self.folder / 'merchant-private.pem';key.unlink()
        key.symlink_to(Path(self.base.name) / 'merchant-private.pem')
        self.assertRaises(ValueError, provision.validate, self.folder)

    def test_malformed_platform_key_rejected(self):
        (self.folder / 'platform-public.pem').write_text('invalid public key')
        self.assertRaises(ValueError, provision.validate, self.folder)

    def test_no_secret_error_output(self):
        self.metadata(appid='synthetic-secret-canary')
        from contextlib import redirect_stdout
        import io
        out = io.StringIO()
        with redirect_stdout(out):
            self.assertEqual(1, provision.guarded_main(lambda: provision.validate(self.folder)))
        self.assertNotIn('canary', out.getvalue())


if __name__ == '__main__':
    unittest.main()
