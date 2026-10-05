#!/usr/bin/env python3
"""Investigate all legacy candidates in one read-only snapshot; no provider I/O."""
import json
import hashlib
import sys
from datetime import datetime, timezone
from private_support import PRIVATE, WORKSPACE, guarded_main, local_rows, private_json


def smoke_provenance(order_ids):
    # Only named project order/smoke artifacts, never arbitrary historical credential logs.
    folder = WORKSPACE / '.uniapp-dev/logs'
    files = list(folder.glob('baseline-smoke-order*.json'))
    files += [folder / n for n in ['phase3-order.json', 'phase3-order-created.json',
                                    'phase3-final.json', 'baseline-smoke.json']]
    evidence = {id: [] for id in order_ids}
    for p in files:
        if not p.is_file():
            continue
        try:
            data = json.loads(p.read_text())
        except (ValueError, OSError):
            continue
        found = set()
        def walk(value):
            if isinstance(value, dict):
                for k, v in value.items():
                    if k in ('orderId', 'order_id', 'id', 'uni') and isinstance(v, (str, int)) and str(v) in evidence:
                        found.add(str(v))
                    if isinstance(v, (dict, list)):
                        walk(v)
            elif isinstance(value, list):
                for child in value:
                    walk(child)
        walk(data)
        for id in found:
            evidence[id].append({'artifact': str(p.relative_to(WORKSPACE)),
                                 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()})
    return evidence


def run():
    rows = local_rows("""SELECT JSON_OBJECT(
      'orderId',o.order_id,'createdAt',o.create_time,'payType',o.pay_type,
      'paid',o.paid,'status',o.status,'refundStatus',o.refund_status,
      'deleted',o.deleted,'systemDeleted',o.is_system_del,
      'paymentAttemptCount',(SELECT COUNT(*) FROM yshop_order_payment_attempt a WHERE a.order_id=o.order_id),
      'paymentRecordCount',(SELECT COUNT(*) FROM yshop_order_payment p WHERE p.order_id=o.order_id),
      'providerTransactionPresent',EXISTS(SELECT 1 FROM yshop_order_payment p WHERE p.order_id=o.order_id AND p.provider_transaction_id IS NOT NULL),
      'callbackReceiptCount',(SELECT COUNT(*) FROM yshop_order_payment p WHERE p.order_id=o.order_id AND p.provider IN ('WECHAT','ALIPAY')),
      'conflictCount',(SELECT COUNT(*) FROM yshop_order_payment_conflict c WHERE c.claimed_order_id=o.order_id),
      'reconciliationCount',(SELECT COUNT(*) FROM yshop_order_payment p WHERE p.order_id=o.order_id AND p.status IN ('PAYMENT_CONFLICT','RECONCILIATION_REQUIRED'))
    ) FROM yshop_store_order o WHERE o.paid=0 AND o.pay_type IN ('weixin','alipay')
      AND NOT EXISTS(SELECT 1 FROM yshop_order_payment_attempt a WHERE a.order_id=o.order_id)
      ORDER BY o.create_time,o.order_id""")
    provenance = smoke_provenance({r['orderId'] for r in rows})
    for r in rows:
        # Local paid=0 is evidence of local noncompletion, never proof of remote nonpayability.
        r.update(classification='PROVIDER_REVIEW_REQUIRED',
                 localCompletionAbsent=r['paid'] == 0 and r['paymentRecordCount'] == 0,
                 provenSynthetic=False,
                 devSmokeEvidence=provenance[r['orderId']],
                 devSmokeProvenanceFound=bool(provenance[r['orderId']]),
                 evidence='Local order and durable receipts only; no historical prepay registry. Remote state not proven.')
    dates = [r['createdAt'] for r in rows if r['createdAt']]
    summary = {'candidates': len(rows), 'LOCAL_RESOLVED': 0,
               'PROVIDER_REVIEW_REQUIRED': len(rows), 'localHistoryReview': 'COMPLETE',
               'devSmokeProvenanceFound': sum(bool(provenance[r['orderId']]) for r in rows),
               'providerHistoryReview': 'HUMAN REQUIRED' if rows else 'NO LOCAL CANDIDATES',
               'exportStart': min(dates) if dates else None,
               'exportEnd': datetime.now(timezone.utc).isoformat(),
               'exportTimezone': 'Preserve original timezone; include UTC offset',
               'exportFields': ['out_trade_no', 'transaction_id', 'trade_state', 'amount',
                                'currency', 'appid', 'mchid', 'create_time', 'success_time'],
               'exportScope': 'One export per provider/merchant covering the interval, including unpaid/closed requests. Settlement-only export cannot prove remote nonpayability.'}
    private_json(PRIVATE / 'payment-history-review.json',
                 {'observedAt': datetime.now(timezone.utc).isoformat(), 'summary': summary, 'orders': rows})
    print(json.dumps({'result': 'PASS', **summary, 'providerCalls': 0, 'financialWrites': 0}))
    return 0


if __name__ == '__main__':
    sys.exit(guarded_main(run))
