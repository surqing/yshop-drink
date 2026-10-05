#!/usr/bin/env python3
"""Measure Apple NTP directly; never set the OS clock or invent preflight attestations."""
from datetime import datetime, timezone
import ipaddress
import json
import re
import statistics
import subprocess
import sys
import urllib.request
from private_support import PRIVATE, guarded_main, local_rows, private_json


def parse_sample(text):
    m = re.search(r'^([+-]\d+(?:\.\d+)?)\s+\+/-\s+(\d+(?:\.\d+)?)\s+', text.strip())
    if not m:
        raise ValueError()
    return float(m[1]) * 1000, float(m[2]) * 1000


def run():
    # A failed renewal must never leave an older attestation appearing current.
    (PRIVATE / 'clock-evidence.properties').unlink(missing_ok=True)
    private_json(PRIVATE / 'clock-evidence.json', {'ready': False, 'measurementPending': True,
                 'observedAt': datetime.now(timezone.utc).isoformat(), 'clockModified': False})
    with urllib.request.urlopen('https://dns.google/resolve?name=time.apple.com&type=A', timeout=15) as r:
        answer = json.load(r)
    ips = [x['data'] for x in answer.get('Answer', []) if x.get('type') == 1]
    if answer.get('Status') != 0 or not ips:
        raise ValueError()
    samples = []
    peers = list(dict.fromkeys(ips))[:8]
    for ip in peers:
        if not ipaddress.ip_address(ip).is_global or not ip.startswith('17.'):
            raise ValueError()
    # Retry transient UDP loss; distinct verified peers are still required.
    for retry in range(2):
        for ip in peers:
            if any(sample['ip'] == ip for sample in samples):
                continue
            try:
                r = subprocess.run(['/usr/bin/sntp', '-t', '5', ip], capture_output=True, text=True, timeout=10)
                if r.returncode:
                    continue
                offset, uncertainty = parse_sample(r.stdout)
                samples.append({'ip': ip, 'offsetMillis': offset, 'uncertaintyMillis': uncertainty})
            except (subprocess.TimeoutExpired, ValueError):
                continue
        if len(samples) >= 2:
            break
    if not samples:
        raise ValueError()
    offset = statistics.median(x['offsetMillis'] for x in samples)
    ready = len(samples) >= 2 and abs(offset) <= 1000 and max(x['uncertaintyMillis'] for x in samples) <= 500
    observed = datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')
    row = local_rows("SELECT JSON_OBJECT('utc',UTC_TIMESTAMP(6),'epochMillis',UNIX_TIMESTAMP(CURRENT_TIMESTAMP(6))*1000)")[0]
    db_difference = float(row['epochMillis']) - datetime.now(timezone.utc).timestamp() * 1000
    ready = ready and abs(db_difference) <= 5000
    report = {'observedAt': observed, 'ntpSource': 'time.apple.com', 'sourceResolution': 'HTTPS DNS, direct Apple 17.0.0.0/8 peers',
              'samples': samples, 'offsetMillis': offset, 'databaseOffsetMillis': db_difference,
              'ready': ready, 'clockModified': False,
              'limitation': 'Operational SNTP source evidence, not cryptographically authenticated NTS. Renew within 300 seconds per instance; no OS synchronization-setting claim.'}
    private_json(PRIVATE / 'clock-evidence.json', report)
    if ready:
        p = PRIVATE / 'clock-evidence.properties'
        p.write_text('yshop.pay.preflight.clock.observed-at=' + observed + '\n'
                     + 'yshop.pay.preflight.clock.ntp-offset-millis=' + str(round(offset)) + '\n')
        p.chmod(0o600)
    else:
        (PRIVATE / 'clock-evidence.properties').unlink(missing_ok=True)
    print(json.dumps(report))
    return 0 if ready else 1


if __name__ == '__main__':
    sys.exit(guarded_main(run))
