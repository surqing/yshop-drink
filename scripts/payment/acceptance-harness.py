#!/usr/bin/env python3
"""Read-only four-way consistency report. Never obtains prepay/query/close or pays."""
import argparse
import json
import re
import sys
from private_support import PRIVATE, guarded_main, local_rows, private_json

BINDING = ('orderId', 'attemptId', 'providerOrderReference', 'transactionId', 'appid', 'mchId', 'amountCents', 'currency', 'provider')


def safe_count(value):
    return value if type(value) is int and value >= 0 else None


def compare(database, client, callback, query):
    checks = {}
    for name, evidence in [('CALLBACK', callback), ('QUERY', query)]:
        checks[name + '_BINDING'] = all(database.get(k) is not None and database[k] == evidence.get(k) for k in BINDING)
        checks[name + '_SUCCESS'] = evidence.get('tradeState') == 'SUCCESS'
    checks['CLIENT_ORDER'] = client.get('orderId') == database.get('orderId') and client.get('attemptId') == database.get('attemptId')
    checks['CLIENT_RESULT'] = client.get('paymentResult') == 'requestPayment:ok'
    checks['AMOUNT_BOUND'] = type(database.get('amountCents')) is int and 1 <= database['amountCents'] <= 10 and database.get('currency') == 'CNY'
    checks['WECHAT_ONLY'] = database.get('provider') == 'WECHAT'
    checks['IDENTITY_COMPLETE'] = all(isinstance(database.get(k), str) and bool(database[k].strip()) for k in BINDING if k != 'amountCents')
    checks['TYPED_PROVIDER_AMOUNTS'] = type(callback.get('amountCents')) is int and type(query.get('amountCents')) is int
    checks['PAID_STATE'] = database.get('orderPaid') == 1 and database.get('attemptStatus') == 'PAID'
    checks['ONE_PAYMENT'] = database.get('successfulPaymentCount') == 1 and database.get('paymentStatus') == 'SUCCESS'
    checks['ONE_FULFILLMENT'] = database.get('billCount') == 1 and database.get('fulfillmentStatusCount') == 1
    checks['NO_CONFLICT'] = database.get('conflictCount') == 0 and database.get('reconciliationCount') == 0
    checks['NO_SECOND_FUNDING'] = database.get('walletDebitCount') == 0
    checks['NO_DUPLICATE_RECEIPTS'] = database.get('duplicateReceiptCount') == 0
    return {'consistentObservedEvidence': all(checks.values()), 'checks': checks,
            'duplicateReceiptCount': safe_count(database.get('duplicateReceiptCount')),
            'callbackDeliveryCount': safe_count(callback.get('observedDeliveryCount')),
            'limitation': 'Input files must come from independently verified client/callback/official-query observations. This tool compares evidence; it cannot authenticate a manually supplied export or prove unobserved wire replay counts. It never authorizes or changes payments.',
            'providerCalls': 0, 'financialWrites': 0}


def snapshot(order_id):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', order_id):
        raise ValueError()
    rows = local_rows("""SELECT JSON_OBJECT('orderId',o.order_id,'attemptId',a.attempt_id,
      'providerOrderReference',a.provider_order_reference,'transactionId',a.provider_transaction_id,
      'appid',a.appid,'mchId',a.merchant_identity,'amountCents',a.amount_cents,'currency',a.currency,'provider',a.provider,
      'orderPaid',o.paid,'attemptStatus',a.status,'paymentStatus',p.status,
      'successfulPaymentCount',(SELECT COUNT(*) FROM yshop_order_payment x WHERE x.order_id=o.order_id AND x.status='SUCCESS'),
      'billCount',(SELECT COUNT(*) FROM yshop_user_bill b WHERE b.extend_field=o.order_id AND b.category='now_money' AND b.type='pay_product'),
      'fulfillmentStatusCount',(SELECT COUNT(*) FROM yshop_store_order_status s WHERE s.oid=o.id AND s.change_type='pay_success'),
      'conflictCount',(SELECT COUNT(*) FROM yshop_order_payment_conflict c WHERE c.claimed_order_id=o.order_id),
      'reconciliationCount',(SELECT COUNT(*) FROM yshop_order_payment x WHERE x.order_id=o.order_id AND x.status IN ('PAYMENT_CONFLICT','RECONCILIATION_REQUIRED')),
      'walletDebitCount',(SELECT COUNT(*) FROM yshop_member_wallet_transaction w WHERE w.business_id=o.order_id AND w.type='ORDER_PAYMENT'),
      'duplicateReceiptCount',(SELECT GREATEST(COUNT(*)-1,0) FROM yshop_order_payment x WHERE x.order_id=o.order_id AND x.provider_transaction_id=a.provider_transaction_id)
    ) FROM yshop_store_order o JOIN yshop_order_payment_attempt a ON a.order_id=o.order_id
      JOIN yshop_order_payment p ON p.attempt_id=a.attempt_id AND p.status='SUCCESS'
      WHERE o.order_id='""" + order_id + "' AND a.status='PAID'")
    if len(rows) != 1:
        raise ValueError()
    return rows[0]


def run():
    p = argparse.ArgumentParser()
    p.add_argument('--evidence-dir', required=True)
    a = p.parse_args()
    from pathlib import Path
    original = Path(a.evidence_dir)
    folder = original.resolve()
    if original.is_symlink() or not folder.is_dir() or folder.stat().st_mode & 0o077 or PRIVATE.resolve() not in folder.parents:
        raise ValueError()
    evidence = {}
    for name in ['client', 'callback', 'query']:
        f = folder / (name + '.json')
        if f.is_symlink() or f.stat().st_mode & 0o077:
            raise ValueError()
        evidence[name] = json.loads(f.read_text())
    database = snapshot(evidence['client']['orderId'])
    report = compare(database, **evidence)
    private_json(PRIVATE / 'payment-acceptance-report.json', {'database': database, **report})
    print(json.dumps(report))
    return 0 if report['consistentObservedEvidence'] else 1


if __name__ == '__main__':
    sys.exit(guarded_main(run))
