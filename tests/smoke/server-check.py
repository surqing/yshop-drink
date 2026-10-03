#!/usr/bin/env python3
"""Read-only checks against the established local dev database; no credentials in output."""
import importlib.util
import json
from pathlib import Path
import re
import sys

workspace = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('local_database', workspace / '.local-dev/database.py')
db = importlib.util.module_from_spec(spec)
spec.loader.exec_module(db)
try:
    args = json.load(sys.stdin)
    if args['action'] == 'marker':
        result = {'marker': db.mysql('SELECT COALESCE(MAX(id),0) FROM infra_api_access_log;').strip()}
    elif args['action'] == 'checkpoint':
        order_id = str(args['orderId'])
        if not re.fullmatch(r'\d{1,24}', order_id):
            raise ValueError('Invalid business ID')
        rows = db.mysql("SELECT paid,CAST(deleted AS UNSIGNED) FROM yshop_store_order WHERE order_id='" + order_id + "';").splitlines()
        result = {'found': len(rows) == 1}
        if result['found']:
            paid, deleted = rows[0].split('\t')
            result.update({'paid': int(paid), 'deleted': int(deleted) == 1})
    else:
        marker = str(args['marker'])
        order_id = str(args['orderId'])
        if not re.fullmatch(r'\d{1,24}', marker) or not re.fullmatch(r'\d{1,24}', order_id):
            raise ValueError('Invalid business ID')
        result = {'paymentRequests': int(db.mysql(
            "SELECT COUNT(*) FROM infra_api_access_log WHERE id>" + marker +
            " AND (request_url LIKE '%/order/pay%' OR request_url LIKE '%/pay/order/submit%');").strip()),
            'unpaidOrderRows': int(db.mysql(
                "SELECT COUNT(*) FROM yshop_store_order WHERE deleted=b'0' AND order_id='" + order_id + "' AND paid=0;").strip())}
    print(json.dumps(result))
except Exception:
    print(json.dumps({'verificationFailed': True}))
    sys.exit(1)
