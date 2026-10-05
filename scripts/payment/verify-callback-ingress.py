#!/usr/bin/env python3
"""Only loopback synthetic ingress; never sends notifications to a public callback."""
import os
import subprocess
import sys
from private_support import PRIVATE, REPO, guarded_main


def run():
    subprocess.run([sys.executable, str(REPO / 'scripts/payment/local-ingress.py'),
                    'start', '--upstream-port', '48881'], check=True)
    env = os.environ.copy()
    env['YSHOP_INGRESS_BASE_URL'] = 'https://localhost:48443'
    env['YSHOP_INGRESS_CA'] = str(PRIVATE / 'ingress/ca.pem')
    try:
        subprocess.run([sys.executable, str(REPO / 'tests/payment/mysql-acceptance.py'), '--prepayment'],
                       env=env, check=True)
    finally:
        # Restore normal local backend ingress; no payment flags or credential changes.
        subprocess.run([sys.executable, str(REPO / 'scripts/payment/local-ingress.py'), 'start'], check=True)
    return 0


if __name__ == '__main__':
    sys.exit(guarded_main(run))
