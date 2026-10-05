#!/usr/bin/env python3
"""Match one operator-provided export to all local candidates; never queries a provider."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
from private_support import PRIVATE, guarded_main, private_json


def match(orders, exported):
    index = {}
    for row in exported:
        ref = row.get('out_trade_no', '')
        if not ref or ref in index:
            raise ValueError()
        index[ref] = row
    result = []
    for order in orders:
        row = index.get(order['orderId'])
        state = row.get('trade_state') if row else None
        result.append({'orderId': order['orderId'], 'matched': row is not None,
                       'observedState': state if state in ('SUCCESS', 'NOTPAY', 'USERPAYING', 'CLOSED', 'REVOKED', 'PAYERROR', 'TRADE_CLOSED', 'TRADE_SUCCESS', 'TRADE_FINISHED', 'WAIT_BUYER_PAY') else 'UNKNOWN',
                       'terminalObservation': bool(row) and state in ('CLOSED', 'REVOKED', 'PAYERROR', 'TRADE_CLOSED'),
                       'requiresReview': True})
    return result


def run():
    p = argparse.ArgumentParser();p.add_argument('--export', required=True);a=p.parse_args()
    f=Path(a.export)
    if f.is_symlink() or PRIVATE.resolve() not in f.resolve().parents or f.stat().st_mode & 0o077:
        raise ValueError()
    with f.open(newline='',encoding='utf-8-sig') as src:
        reader=csv.DictReader(src)
        if not set(['out_trade_no','transaction_id','trade_state','amount','currency','appid','mchid','create_time','success_time']).issubset(reader.fieldnames or []):
            raise ValueError()
        rows=[{k:r.get(k,'') for k in reader.fieldnames} for r in reader]
    review=json.loads((PRIVATE/'payment-history-review.json').read_text())
    items=match(review['orders'],rows)
    report={'exportSha256':hashlib.sha256(f.read_bytes()).hexdigest(),'matches':items,
            'matchedCount':sum(r['matched'] for r in items),'candidateCount':len(items),
            'providerReviewStillRequired':sum(r['requiresReview'] for r in items),
            'exportAuthenticityAndScope':'Operator must confirm merchant, timezone and complete order coverage; absence from settled-only CSV is not proof',
            'automaticFinancialChanges':False,'providerCalls':0}
    private_json(PRIVATE/'history-export-matching.json',report)
    print(json.dumps({k:v for k,v in report.items() if k not in ('matches','exportSha256')}))
    return 0


if __name__=='__main__':
    import sys
    sys.exit(guarded_main(run))
