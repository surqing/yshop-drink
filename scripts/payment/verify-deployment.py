#!/usr/bin/env python3
"""Authenticated read-only per-instance checks. No provider clients or gate writes."""
import argparse
import json
from pathlib import Path
import re
import ssl
import urllib.parse
from private_support import PRIVATE, guarded_main, private_json
import importlib.util
spec = importlib.util.spec_from_file_location('provision_private', Path(__file__).with_name('provision-merchant.py'))
provision = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provision)


def compare(instances, expected):
    if not re.fullmatch(r'[a-f0-9]{40}', expected) or not instances:
        raise ValueError()
    fingerprints = {r.get('merchantFingerprint') for r in instances}
    checks = {
        'BUILD_REVISION': all(r.get('buildRevision') == expected for r in instances),
        'SCHEMA': all(r.get('schemaComplete') is True for r in instances),
        'GATES_OFF': all(r.get('liveEnabled') is False and r.get('reconciliationEnabled') is False for r in instances),
        'MASTER_KEY_AVAILABLE': all(r.get('masterKeyAvailable') is True for r in instances),
        'MERCHANT_CONFIG': all(r.get('merchantConfigurationReady') is True for r in instances),
        'CALLBACK_ROUTE': all(r.get('callbackRoute') == '/app-api/order/notify/wechat-v3/{detailsId}' for r in instances),
        'MERCHANT_CONSISTENT': len(fingerprints) == 1 and all(isinstance(f, str) and re.fullmatch(r'[a-f0-9]{64}', f) for f in fingerprints),
        'AUDIT_AVAILABLE': all(r.get('audit', {}).get('available') is True for r in instances),
        'NO_UNCERTAINTY': all(all(r.get('audit', {}).get('counts', {}).get(k) == 0 for k in ['UNCERTAIN_ATTEMPTS', 'PAYMENT_CONFLICT', 'RECONCILIATION_REQUIRED']) for r in instances)
    }
    return {'preActivationDeploymentConsistent': all(checks.values()), 'checks': checks,
            'observedInstanceCount': len(instances), 'inventoryCompleteness': 'Operator must attest every production instance is included',
            'providerCalls': 0, 'financialWrites': 0}


def run():
    p = argparse.ArgumentParser()
    p.add_argument('--inventory', required=True)
    p.add_argument('--expected-sha', required=True)
    a = p.parse_args()
    inventory = Path(a.inventory)
    if inventory.is_symlink() or PRIVATE.resolve() not in inventory.resolve().parents:
        raise ValueError()
    data = json.loads(provision.protected_file(inventory.parent, inventory.name))
    if not isinstance(data, list) or not 1 <= len(data) <= 100:
        raise ValueError()
    reports = []
    for c in data:
        # Remote instances are inspected through independently authenticated local forwards.
        u = urllib.parse.urlsplit(c['adminOrigin'])
        if u.scheme != 'https' or u.hostname not in ('localhost', '127.0.0.1') or not u.port or u.username or u.password or u.query or u.fragment or u.path not in ('', '/'):
            raise ValueError()
        id = c['detailsId']
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,32}', id):
            raise ValueError()
        token = Path(c['tokenFile'])
        if PRIVATE.resolve() not in token.resolve().parents:
            raise ValueError()
        context = ssl.create_default_context(cafile=c['caFile'])
        reports.append(provision.api(c['adminOrigin'], 'live-preflight/deployment?detailsId=' + id,
                      provision.protected_file(token.parent, token.name).decode().strip(), context))
    result = compare(reports, a.expected_sha)
    # Aggregate diagnostics only, no tokens, merchant identity or envelopes in output.
    private_json(PRIVATE / 'deployment-verification.json', {'instances': reports, **result})
    print(json.dumps(result))
    return 0 if result['preActivationDeploymentConsistent'] else 1


if __name__ == '__main__':
    import sys
    sys.exit(guarded_main(run))
