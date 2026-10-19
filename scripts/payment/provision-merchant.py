#!/usr/bin/env python3
"""Server-private offline staging validation and existing authenticated encrypted write path.

No provider client. No secret CLI arguments, temporary plaintext, raw errors or response dumps.
"""
import argparse
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from private_support import PRIVATE, guarded_main, private_json, isolated_schema, report_path

STAGE = 'INPUT_VALIDATION'


def protected_file(folder, name):
    p = folder / name
    if p.is_symlink() or not p.is_file() or p.stat().st_mode & 0o077 or p.stat().st_size > 65536:
        raise ValueError()
    return p.read_bytes()


def crypto(args, value):
    p = subprocess.run(['openssl'] + args, input=value, capture_output=True, timeout=10)
    if p.returncode:
        raise ValueError()
    return p.stdout


def validate(folder):
    folder = Path(folder)
    if folder.is_symlink() or folder.stat().st_mode & 0o077:
        raise ValueError()
    m = json.loads(protected_file(folder, 'metadata.json'))
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,32}', m.get('detailsId', '')):
        raise ValueError()
    if not re.fullmatch(r'wx[a-f0-9]{16}', m.get('appid', '')) or not re.fullmatch(r'[0-9]{8,20}', m.get('mchId', '')):
        raise ValueError()
    if not re.fullmatch(r'[A-Fa-f0-9]{1,64}', m.get('merchantCertificateSerial', '')) or not re.fullmatch(r'PUB_KEY_ID_[A-Za-z0-9_-]{1,64}', m.get('platformPublicKeyId', '')):
        raise ValueError()
    u = urllib.parse.urlsplit(m.get('notifyUrl', ''))
    if (u.scheme != 'https' or not u.hostname or u.username or u.password or u.query or u.fragment
            or u.port not in (None, 443) or u.hostname in ('localhost', '127.0.0.1', '::1')
            or u.path != '/app-api/order/notify/wechat-v3/' + m['detailsId']):
        raise ValueError()
    key = protected_file(folder, 'merchant-private.pem')
    cert = protected_file(folder, 'merchant-cert.pem')
    public = protected_file(folder, 'platform-public.pem')
    api = protected_file(folder, 'api-v3-key.txt').rstrip(b'\r\n')
    if len(api) != 32 or len(api.decode('utf-8').encode('utf-8')) != 32:
        raise ValueError()
    crypto(['rsa', '-check', '-noout'], key)
    pub = crypto(['pkey', '-pubout', '-outform', 'DER'], key)
    public_pem = crypto(['pkey', '-pubout'], key)
    if not re.search(rb'(?:RSA )?Public-Key: \(2048 bit\)', crypto(['rsa', '-pubin', '-text', '-noout'], public_pem)):
        raise ValueError()
    cert_pub = crypto(['x509', '-pubkey', '-noout'], cert)
    if pub != crypto(['pkey', '-pubin', '-outform', 'DER'], cert_pub):
        raise ValueError()
    serial = crypto(['x509', '-serial', '-noout'], cert).decode().strip().split('=', 1)[1]
    if serial.lstrip('0').upper() != m['merchantCertificateSerial'].lstrip('0').upper():
        raise ValueError()
    crypto(['x509', '-checkend', '0', '-noout'], cert)
    start = crypto(['x509', '-startdate', '-noout'], cert).decode().strip().split('=', 1)[1]
    if ssl.cert_time_to_seconds(start) > time.time():
        raise ValueError()
    crypto(['pkey', '-pubin', '-pubout'], public)
    if not re.search(rb'(?:RSA )?Public-Key: \(2048 bit\)', crypto(['rsa', '-pubin', '-text', '-noout'], public)):
        raise ValueError()
    payload = {k: m[k] for k in ['detailsId', 'appid', 'mchId', 'merchantCertificateSerial', 'platformPublicKeyId', 'notifyUrl']}
    payload.update(payType='wxPay', wechatApiVersion='V3', isTest=0, signType='RSA',
                   inputCharset='UTF-8', certStoreType='STR', keyPrivate=key.decode(),
                   keyPublic=public.decode(), apiV3Key=api.decode())
    return payload


def api(origin, path, token, context, data=None, method='GET'):
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise ValueError()
    request = urllib.request.Request(origin + '/admin-api/pay/' + path,
             data=None if data is None else json.dumps(data).encode(), method=method,
             headers={'Authorization': 'Bearer ' + token, 'tenant-id': '1', 'Content-Type': 'application/json'})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
             NoRedirect(), urllib.request.HTTPSHandler(context=context))
    with opener.open(request, timeout=15) as r:
        reply = json.load(r)
    if reply.get('code') != 0:
        raise ValueError()
    return reply.get('data')


def allowed_admin_origin(origin, synthetic=False):
    u = urllib.parse.urlsplit(origin)
    return (u.scheme == 'https' and u.hostname in ('localhost','127.0.0.1')
            and u.port in ((48443,48444) if synthetic else (48443,))
            and not (u.username or u.password or u.query or u.fragment)
            and u.path in ('','/'))


def run():
    global STAGE
    p = argparse.ArgumentParser()
    p.add_argument('--private-dir', default=str(PRIVATE / 'live-merchant'))
    p.add_argument('--apply', action='store_true')
    p.add_argument('--restore-empty-placeholder', action='store_true')
    # The operator config contains only the admin origin, CA path and private token file path.
    p.add_argument('--connection-file')
    a = p.parse_args()
    original = Path(a.private_dir)
    folder = original.resolve()
    if original.is_symlink() or PRIVATE.resolve() not in folder.parents:
        raise ValueError()
    if not (folder / 'metadata.json').exists():
        print(json.dumps({'result': 'HUMAN_REQUIRED', 'blocker': 'REAL_MERCHANT_PROVISIONING'}))
        return 2
    payload = validate(folder)
    report = {'result': 'OFFLINE_INPUT_VALID', 'certificateKeyMatch': True, 'providerCalls': 0,
              'merchantWritten': False, 'paymentActivated': False, 'temporaryPlaintextCreated': False,
              'scope': 'ISOLATED_SYNTHETIC_ACCEPTANCE' if os.environ.get('YSHOP_ACCEPTANCE_CONFIG') else 'SERVER_PRIVATE_CONFIGURATION'}
    if a.apply:
        STAGE = 'CONNECTION_VALIDATION'
        if not a.connection_file:
            raise ValueError()
        cf = Path(a.connection_file)
        if cf.is_symlink() or PRIVATE.resolve() not in cf.resolve().parents or cf.stat().st_mode & 0o077:
            raise ValueError()
        c = json.loads(cf.read_text())
        origin = c['adminOrigin']
        if not allowed_admin_origin(origin, synthetic=isolated_schema() is not None):
            raise ValueError()
        token_file = Path(c['tokenFile'])
        if PRIVATE.resolve() not in token_file.resolve().parents:
            raise ValueError()
        token = protected_file(token_file.parent, token_file.name).decode().strip()
        context = ssl.create_default_context(cafile=c.get('caFile'))
        id = payload['detailsId']
        STAGE = 'OFFLINE_GATES'
        preflight = api(origin, 'live-preflight/check?detailsId=' + id, token, context)
        for key in ['LEGACY_EXTERNAL_LIVE_GATE', 'RECONCILIATION_ENABLED']:
            if preflight.get('checks', {}).get(key) is not False:
                raise ValueError()
        audit = api(origin, 'live-preflight/audit', token, context)
        if not audit.get('available') or any(audit['counts'].get(k) != 0 for k in ['ACTIVE_ATTEMPTS', 'UNCERTAIN_ATTEMPTS', 'PAYMENT_CONFLICT', 'RECONCILIATION_REQUIRED']):
            raise ValueError()
        STAGE = 'MERCHANT_LOOKUP'
        existing = api(origin, 'merchant-details/get?id=' + id, token, context)
        if existing and (existing.get('privateKeyConfigured') or existing.get('apiV3KeyConfigured')):
            # No unattended key rotation/transplant into a configured merchant.
            raise ValueError()
        STAGE = 'ENCRYPTED_SERVER_WRITE'
        if a.restore_empty_placeholder:
            if existing or id not in ('wx_miniapp', 'wx_wechat', 'wx_h5'):
                raise ValueError()
            payload['restoreEmptyPlaceholder'] = True
        api(origin, 'merchant-details/' + ('update' if existing else 'create'), token, context,
            payload, 'PUT' if existing else 'POST')
        STAGE = 'FINAL_OFFLINE_PREFLIGHT'
        result = api(origin, 'live-preflight/check?detailsId=' + id, token, context)
        if result.get('configurationReady') is not True:
            raise ValueError()
        report.update(result='SERVER_OFFLINE_PREFLIGHT_PASS', merchantWritten=True)
    private_json(report_path(folder, 'merchant-provisioning-report.json'), report)
    print(json.dumps(report))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(run())
    except Exception:
        print(json.dumps({'result': 'FAIL', 'stage': STAGE, 'reason': 'Private input or local evidence unavailable'}))
        sys.exit(1)
