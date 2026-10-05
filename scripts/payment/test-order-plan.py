#!/usr/bin/env python3
"""Read-only selection of existing low-price SKU/order; no price changes or creation."""
import argparse
import json
import re
from private_support import PRIVATE, guarded_main, local_rows, private_json


def run():
    p=argparse.ArgumentParser();p.add_argument('--order-id');a=p.parse_args()
    products=local_rows("SELECT JSON_OBJECT('productId',id,'shopId',shop_id,'price',price,'stock',stock) FROM yshop_store_product WHERE deleted=0 AND is_show=1 AND stock>0 AND price BETWEEN 0.01 AND 0.10")
    order=None
    if a.order_id:
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',a.order_id):raise ValueError()
        rows=local_rows("SELECT JSON_OBJECT('orderId',order_id,'amountCents',pay_price*100,'paid',paid,'status',status,'refundStatus',refund_status,'deleted',CAST(deleted AS UNSIGNED),'systemDeleted',is_system_del,'attemptCount',(SELECT COUNT(*) FROM yshop_order_payment_attempt a WHERE a.order_id=o.order_id),'walletDebitCount',(SELECT COUNT(*) FROM yshop_member_wallet_transaction w WHERE w.business_id=o.order_id AND w.type='ORDER_PAYMENT')) FROM yshop_store_order o WHERE order_id='"+a.order_id+"'")
        if len(rows)!=1:raise ValueError()
        order=rows[0]
    valid=order is not None and 1<=order['amountCents']<=10 and all(order[k]==0 for k in ['paid','status','refundStatus','deleted','systemDeleted','attemptCount','walletDebitCount'])
    report={'existingLowPriceCandidates':products,'candidateCount':len(products),'selectedOrder':order,
            'serverOrderAmountVerified':valid,'financialWrites':0,'providerCalls':0,
            'plan':'Use a dedicated access-controlled acceptance deployment and explicitly authorized test account. Select existing SKU through normal cart/order flow; no coupons/points/discount modifications or wallet funding. Verify final server order total 1–10 cents before any payment authorization. A real transaction requires separate authorization; this tool never creates or pays.',
            'productionScope':'Local seed products do not prove production inventory or account authorization. Production instance access is required to verify its independent configuration.'}
    private_json(PRIVATE/'first-payment-order-plan.json',report)
    print(json.dumps({k:v for k,v in report.items() if k not in ('existingLowPriceCandidates','selectedOrder')}))
    return 0 if products and (not a.order_id or valid) else 1


if __name__=='__main__':
    import sys
    sys.exit(guarded_main(run))
