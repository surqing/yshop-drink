#!/usr/bin/env python3
"""Aggregate-only read verification for the local unpaid-order UI smoke."""
import importlib.util,json,re,sys
from pathlib import Path
root=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('local_database',root/'.local-dev/database.py');db=importlib.util.module_from_spec(spec);spec.loader.exec_module(db)
try:
    args=json.load(sys.stdin)
    if args['action']=='marker':
        result={'marker':int(db.mysql('SELECT COALESCE(MAX(id),0) FROM infra_api_access_log;').strip())}
    elif args['action']=='catalog':
        from decimal import Decimal
        oid=str(args['orderId']);pid=str(args['productId'])
        if not re.fullmatch('[a-f0-9]{32}',oid) or not re.fullmatch('[0-9]{1,20}',pid):raise ValueError()
        snapshot=json.loads(db.mysql("SELECT cart_info FROM yshop_store_order_cart_info WHERE order_id='"+oid+"' AND product_id="+pid+";").strip())
        unit=Decimal(str(args['unitPrice']));quantity=int(args['quantity'])
        reserved=int(db.mysql("SELECT SUM(quantity) FROM yshop_order_inventory_reservation WHERE order_id='"+oid+"' AND product_id="+pid+" AND released_at IS NULL;").strip())
        result={'ok':snapshot['productId']==int(pid) and Decimal(str(snapshot['unitPrice']))==unit and snapshot['quantity']==quantity and Decimal(str(snapshot['lineTotal']))==unit*quantity and reserved==quantity and any(x['optionId']=='pearl' and x['quantity']==2 for x in snapshot['options']) and not any(x['groupId']=='ice' for x in snapshot['options']), 'snapshotVersion':snapshot['version'],'reservedQuantity':reserved}
    else:
        oid=str(args['orderId']);marker=str(args['marker'])
        if not re.fullmatch('[a-f0-9]{32}',oid) or not re.fullmatch('[0-9]{1,24}',marker):raise ValueError()
        def count(sql):return int(db.mysql(sql).strip())
        result={'unpaidCanceledRows':count("SELECT COUNT(*) FROM yshop_store_order WHERE order_id='"+oid+"' AND paid=0 AND deleted=1 AND ordering_version=1;"),
                'cancelRecords':count("SELECT COUNT(*) FROM yshop_store_order_status s JOIN yshop_store_order o ON o.id=s.oid WHERE o.order_id='"+oid+"' AND s.change_type='cancel_order';"),
                'unreleasedInventory':count("SELECT COUNT(*) FROM yshop_order_inventory_reservation WHERE order_id='"+oid+"' AND released_at IS NULL;"),
                'submissionRows':count("SELECT COUNT(*) FROM yshop_order_submission WHERE order_id='"+oid+"';"),
                'paymentRequests':count("SELECT COUNT(*) FROM infra_api_access_log WHERE id>"+marker+" AND (request_url LIKE '%/order/pay%' OR request_url LIKE '%/pay/order/submit%' OR request_url LIKE '%/wechat-v3/prepay%');")}
    print(json.dumps(result))
except Exception:
    print(json.dumps({'verificationFailed':True}));sys.exit(1)
