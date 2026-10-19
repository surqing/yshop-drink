#!/usr/bin/env python3
"""Independent RED collection; never convert expected failures into successful tests."""
import argparse
import hashlib
import json
import os
import socket
from pathlib import Path
import sys
import time
import uuid

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'tests/quality'))
from evidence import execute, source_identity


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root = Path(args.output).resolve()
    if root.is_relative_to(REPO):
        raise RuntimeError('PRIVATE_OUTPUT_OUTSIDE_SOURCE_REQUIRED')
    os.umask(0o077)
    root.mkdir(mode=0o700, parents=True, exist_ok=False)
    identity = source_identity(REPO)
    report = {**identity, 'runId': uuid.uuid4().hex, 'startedAt': time.time(),
              'status': 'INCONCLUSIVE', 'complete': False, 'cases': [],
              'scope': 'actual Chromium Vue page; explicit synthetic API responses; no real identity/provider'}
    assets = [Path(__file__), *sorted((Path(__file__).parent / 'browser').glob('*.ts'))]
    report['testAssets'] = {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest() for p in assets}
    env = dict(os.environ, YSHOP_DEFECT_OUTPUT=str(root),
               YSHOP_PAY_WECHAT_V3_ENABLED='false', YSHOP_PAY_WECHAT_V3_RECONCILIATION_ENABLED='false')
    try:
        code = execute(['pnpm', 'exec', 'playwright', 'test', '--config',
                        str(Path(__file__).parent / 'browser/playwright.config.ts')],
                       REPO / 'yshop-drink-vue3', env, root / 'browser-private.log', 240)
        raw = json.loads((root / 'playwright-private.json').read_text())
        def walk(suite):
            for spec in suite.get('specs', []):
                for test in spec['tests']:
                    for result in test['results']:
                        message = result.get('error', {}).get('message', '')
                        status = 'PASSED' if result['status'] == 'passed' else (
                            'FAILED' if result['status'] == 'failed' and
                            'loading coupon 12 failed; saving must not mutate previously viewed coupon 11' in message
                            else 'INCONCLUSIVE')
                        report['cases'].append({'id': 'D009', 'name': spec['title'], 'status': status,
                                                'durationMs': result['duration'], 'rawStatus': result['status']})
            for child in suite.get('suites', []):
                walk(child)
        walk(raw)
        report['exitCode'] = code
        statuses = {case['status'] for case in report['cases']}
        if len(report['cases']) == 2 and not raw.get('errors') and 'INCONCLUSIVE' not in statuses:
            report['status'] = 'FAILED' if 'FAILED' in statuses else ('PASSED' if code == 0 else 'INCONCLUSIVE')
        # The framework awaits context teardown; independently check its owned server port.
        with socket.socket() as probe:
            probe.settimeout(2)
            closed = probe.connect_ex(('127.0.0.1', 4197)) != 0
        report['cleanup'] = 'PASSED' if closed and len(report['cases']) == 2 else 'INCONCLUSIVE'
        if report['cleanup'] != 'PASSED':
            report['status'] = 'INCONCLUSIVE'
    except Exception as error:
        report['errorType'] = type(error).__name__
    finally:
        report.update(complete=True, endedAt=time.time(), sourceUnchanged=source_identity(REPO) == identity)
        if not report['sourceUnchanged']:
            report['status'] = 'INCONCLUSIVE'
        (root / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'cases': len(report['cases']), 'report': str(root / 'report.json')}))
    return 0 if report['status'] == 'PASSED' else 1


if __name__ == '__main__':
    sys.exit(main())
