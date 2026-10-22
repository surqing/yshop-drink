"""Named read-only SQL observations, under a SELECT-only run-owned account."""
import argparse
import datetime
import json
from pathlib import Path
import re
import subprocess
import sys


def number(value):
    if isinstance(value,bool) or not re.fullmatch(r'[1-9][0-9]{0,17}',str(value)):
        raise ValueError('ENTITY_ID_INVALID')
    return str(value)


def order_id(value):
    if not isinstance(value,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',value):
        raise ValueError('ENTITY_ID_INVALID')
    return "'"+value+"'"


def date_range(ids):
    values=[]
    for key in ('from','to'):
        value=ids.get(key)
        if not isinstance(value,str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}',value):
            raise ValueError('DATE_RANGE_INVALID')
        try: parsed=datetime.date.fromisoformat(value)
        except ValueError: raise ValueError('DATE_RANGE_INVALID') from None
        if parsed.isoformat()!=value: raise ValueError('DATE_RANGE_INVALID')
        values.append(parsed)
    start,end=values
    if end<=start or (end-start).days>366:
        raise ValueError('DATE_RANGE_INVALID')
    return start.isoformat()+' 00:00:00',end.isoformat()+' 00:00:00'


def shop_filter(ids):
    value=ids.get('shopId')
    return '' if value is None else ' AND shop_id='+number(value)


def query_for(name, ids):
    if name in ('store-summary','store-summary-details'):
        start,end=date_range(ids);shop=shop_filter(ids)
        scope='shop_id IS NOT NULL AND COALESCE(tenant_id,0)=0'+shop
        paid=scope+" AND paid=1 AND pay_time>='"+start+"' AND pay_time<'"+end+"' AND refund_status IN (0,1,2) AND is_system_del=0 AND deleted=0"
        if name=='store-summary-details':
            return ("SELECT JSON_OBJECT('id',id,'orderId',order_id,'shopId',shop_id,'payTime',DATE_FORMAT(pay_time,'%Y-%m-%d %H:%i:%s'),"
                    "'payPrice',pay_price,'refundStatus',refund_status,'refundPrice',refund_price,'totalNum',total_num,'fulfillmentStatus',fulfillment_status) "
                    "FROM yshop_store_order WHERE "+paid+" ORDER BY pay_time DESC,id DESC LIMIT 100;")
        queue=scope+" AND paid=1 AND refund_status=0 AND is_system_del=0 AND deleted=0 AND order_type='takein' AND fulfillment_status IN ('WAIT_ACCEPT','ACCEPTED','PREPARING','READY')"
        return ("SELECT JSON_OBJECT('paidOrderCount',(SELECT COUNT(*) FROM yshop_store_order WHERE "+paid+"),"
                "'grossPaidAmount',(SELECT COALESCE(SUM(pay_price),0) FROM yshop_store_order WHERE "+paid+"),"
                "'soldQuantity',(SELECT COALESCE(SUM(total_num),0) FROM yshop_store_order WHERE "+paid+"),"
                "'refundOrderCount',(SELECT COUNT(*) FROM yshop_store_order WHERE "+paid+" AND refund_status IN (1,2)),"
                "'refundPendingCount',(SELECT COUNT(*) FROM yshop_store_order WHERE "+paid+" AND refund_status=1),"
                "'refundCompletedCount',(SELECT COUNT(*) FROM yshop_store_order WHERE "+paid+" AND refund_status=2),"
                "'refundAmount',(SELECT COALESCE(SUM(refund_price),0) FROM yshop_store_order WHERE "+paid+" AND refund_status IN (1,2)),"
                "'waitAcceptQueueCount',(SELECT COUNT(*) FROM yshop_store_order WHERE "+queue+" AND fulfillment_status='WAIT_ACCEPT'),"
                "'acceptedQueueCount',(SELECT COUNT(*) FROM yshop_store_order WHERE "+queue+" AND fulfillment_status='ACCEPTED'),"
                "'preparingQueueCount',(SELECT COUNT(*) FROM yshop_store_order WHERE "+queue+" AND fulfillment_status='PREPARING'),"
                "'readyQueueCount',(SELECT COUNT(*) FROM yshop_store_order WHERE "+queue+" AND fulfillment_status='READY'));")
    if name=='fulfillment-state':
        oid=order_id(ids.get('orderId'))
        return ("SELECT JSON_OBJECT('id',o.id,'orderId',o.order_id,'memberId',o.uid,'shopId',o.shop_id,'paid',o.paid,"
                "'fulfillmentStatus',o.fulfillment_status,'fulfillmentVersion',o.fulfillment_version,'rewardPoints',o.gain_integral,'memberPoints',u.integral,"
                "'collectActions',(SELECT COUNT(*) FROM yshop_order_fulfillment_log l WHERE l.order_id=o.id AND l.action='COLLECT'),"
                "'rewardBillCount',(SELECT COUNT(*) FROM yshop_user_bill b WHERE b.uid=o.uid AND b.link_id=CAST(o.id AS CHAR) AND b.title='购买商品赠送积分' AND b.pm=1 AND b.deleted=0),"
                "'rewardBillPoints',(SELECT COALESCE(SUM(b.number),0) FROM yshop_user_bill b WHERE b.uid=o.uid AND b.link_id=CAST(o.id AS CHAR) AND b.title='购买商品赠送积分' AND b.pm=1 AND b.deleted=0)) "
                "FROM yshop_store_order o JOIN yshop_user u ON u.id=o.uid WHERE o.order_id="+oid+" AND o.deleted=0;")
    if name=='print-jobs':
        oid=order_id(ids.get('orderId'))
        order="(SELECT id FROM yshop_store_order WHERE order_id="+oid+")"
        return ("SELECT row_json FROM ("
                "SELECT id AS sort_id,JSON_OBJECT('kind','JOB','id',id,'eventId',event_id,'orderId',order_id,'shopId',shop_id,'printerId',printer_id,"
                "'purpose',purpose,'state',state,'errorCode',last_error,'attempts',attempts,'parentJobId',parent_job_id,'reprintReason',reprint_reason,'createdBy',created_by,'payloadSnapshot',payload_snapshot) AS row_json "
                "FROM yshop_order_print_job WHERE order_id="+order+" UNION ALL "
                "SELECT -id AS sort_id,JSON_OBJECT('kind','EVENT','id',-id,'eventId',id,'orderId',order_id,'shopId',shop_id,'printerId',NULL,"
                "'purpose',NULL,'state',state,'errorCode',last_error,'attempts',attempts,'parentJobId',NULL,'reprintReason',NULL,'createdBy',NULL,'payloadSnapshot',payload_snapshot) AS row_json "
                "FROM yshop_order_fulfillment_print_event WHERE order_id="+order+") AS print_rows ORDER BY sort_id;")
    if name=='order-contact':
        return "SELECT JSON_OBJECT('memberId',uid,'userPhone',user_phone) FROM yshop_store_order WHERE order_id="+order_id(ids.get('orderId'))+';'
    if name=='score-state':
        member=number(ids.get('memberId'));product=number(ids.get('productId'))
        return ("SELECT JSON_OBJECT('points',(SELECT integral FROM yshop_user WHERE id="+member+"),'stock',p.stock,'sales',p.sales,"
                "'orderId',(SELECT MAX(order_id) FROM yshop_score_order WHERE uid="+member+" AND product_id=p.id),'orders',(SELECT COUNT(*) FROM yshop_score_order WHERE uid="+member+" AND product_id=p.id),"
                "'submissions',(SELECT COUNT(*) FROM yshop_score_submission s JOIN yshop_score_order o ON o.order_id=s.order_id AND o.uid=s.uid WHERE s.uid="+member+" AND o.product_id=p.id),"
                "'debit',(SELECT COALESCE(SUM(b.number),0) FROM yshop_user_bill b JOIN yshop_score_order o ON o.order_id=b.extend_field AND o.uid=b.uid WHERE o.uid="+member+" AND o.product_id=p.id),"
                "'ledgerBalance',(SELECT MIN(b.balance) FROM yshop_user_bill b JOIN yshop_score_order o ON o.order_id=b.extend_field AND o.uid=b.uid WHERE o.uid="+member+" AND o.product_id=p.id),"
                "'totalScore',(SELECT COALESCE(SUM(total_score),0) FROM yshop_score_order WHERE uid="+member+" AND product_id=p.id)) FROM yshop_score_product p WHERE p.id="+product+';')
    if name=='printer-state':
        device=number(ids.get('printerId'))
        return ("SELECT JSON_OBJECT('state',p.device_state,'checkedAt',p.state_checked_at,'authorization',p.authorization,"
                "'jobs',(SELECT COUNT(*) FROM yshop_printer_test_job WHERE printer_id=p.id),"
                "'jobState',(SELECT MAX(state) FROM yshop_printer_test_job WHERE printer_id=p.id)) FROM yshop_printer p WHERE id="+device+';')
    if name=='order':
        return "SELECT JSON_OBJECT('id',id,'memberId',uid,'shopId',shop_id,'amount',pay_price,'paid',paid,'status',status,'deleted',CAST(deleted AS UNSIGNED),'couponId',coupon_id,'quantity',total_num,'remark',remark) FROM yshop_store_order WHERE order_id="+order_id(ids.get('orderId'))+';'
    if name=='order-lines':
        return "SELECT JSON_OBJECT('productId',product_id,'title',title,'number',number,'spec',spec,'price',price,'cartInfo',cart_info) FROM yshop_store_order_cart_info WHERE order_id="+order_id(ids.get('orderId'))+' ORDER BY id;'
    if name=='financial-details':
        oid=order_id(ids.get('orderId'))
        return ("SELECT JSON_OBJECT('kind','payment','id',id,'orderId',order_id,'attemptId',attempt_id,'amountCents',amount_cents,'status',status,'successOrderId',success_order_id) FROM yshop_order_payment WHERE order_id="+oid+
                " UNION ALL SELECT JSON_OBJECT('kind','attempt','id',attempt_id,'orderId',order_id,'memberId',uid,'amountCents',amount_cents,'currency',currency,'status',status,'eventId',payment_event_id) FROM yshop_order_payment_attempt WHERE order_id="+oid+
                " UNION ALL SELECT JSON_OBJECT('kind','bill','id',id,'orderId',extend_field,'memberId',uid,'amount',number,'category',category,'type',type,'pm',pm) FROM yshop_user_bill WHERE extend_field="+oid+';')
    if name=='inventory':
        return "SELECT JSON_OBJECT('stock',stock,'skuId',id,'productId',product_id,'price',price) FROM yshop_store_product_attr_value WHERE id="+number(ids.get('skuId'))+';'
    if name=='product':
        return "SELECT JSON_OBJECT('id',id,'shopId',shop_id,'version',catalog_version,'name',store_name,'stock',stock,'giveIntegral',give_integral) FROM yshop_store_product WHERE id="+number(ids.get('productId'))+';'
    if name=='coupon-right':
        return "SELECT JSON_OBJECT('id',id,'couponId',coupon_id,'memberId',user_id,'status',status,'reservedOrderId',reserved_order_id,'redeemedOrderId',redeemed_order_id,'redeemedAt',redeemed_at) FROM yshop_coupon_user WHERE id="+number(ids.get('rightId'))+';'
    if name=='coupon-count':
        return "SELECT JSON_OBJECT('count',COUNT(*)) FROM yshop_coupon_user WHERE coupon_id="+number(ids.get('couponId'))+' AND user_id='+number(ids.get('memberId'))+';'
    if name=='coupon-operations':
        return "SELECT JSON_OBJECT('kind',kind,'count',COUNT(*)) FROM yshop_coupon_operation WHERE coupon_id="+number(ids.get('couponId'))+' GROUP BY kind ORDER BY kind;'
    if name=='submission':
        key=order_id(ids.get('key'))
        return "SELECT JSON_OBJECT('count',COUNT(*),'orderId',MAX(order_id)) FROM yshop_order_submission WHERE uid="+number(ids.get('memberId'))+' AND idempotency_key='+key+';'
    if name=='financial-records':
        oid=order_id(ids.get('orderId'))
        return "SELECT JSON_OBJECT('payments',(SELECT COUNT(*) FROM yshop_order_payment WHERE order_id="+oid+"),'attempts',(SELECT COUNT(*) FROM yshop_order_payment_attempt WHERE order_id="+oid+"),'bills',(SELECT COUNT(*) FROM yshop_user_bill WHERE extend_field="+oid+"));"
    if name=='address':
        return "SELECT JSON_OBJECT('id',id,'memberId',uid,'name',real_name,'detail',detail,'isDefault',is_default,'deleted',CAST(deleted AS UNSIGNED)) FROM yshop_user_address WHERE id="+number(ids.get('addressId'))+';'
    if name=='identity':
        return "SELECT JSON_OBJECT('id',id,'mobile',mobile,'providerId',routine_openid,'status',status) FROM yshop_user WHERE id="+number(ids.get('memberId'))+';'
    raise ValueError('ORACLE_UNKNOWN')


def read_oracle(context, name, ids):
    owner=context.get('owner','');container=context.get('mysql');schema=context.get('schema')
    if (not re.fullmatch(r'[a-f0-9]{32}',owner) or container!='yshop-quality-business-mysql-'+owner
        or schema!='yshop_quality_business_'+owner[:16] or context.get('oracleConfig')!='/run/oracle.cnf'):
        raise ValueError('ORACLE_CONTEXT_INVALID')
    query=query_for(name,ids)
    inspected=subprocess.run(['docker','inspect',container],text=True,capture_output=True,timeout=15,check=True)
    info=json.loads(inspected.stdout)[0]
    if info['Config'].get('Labels',{}).get('yshop.quality.owner')!=owner:
        raise ValueError('ORACLE_OWNERSHIP_MISMATCH')
    result=subprocess.run(['docker','exec','-i',container,'mysql','--defaults-extra-file=/run/oracle.cnf',schema,'-N','--raw'],
                          input=query,text=True,capture_output=True,timeout=30)
    if result.returncode:raise OracleQueryFailed(result.stderr)
    return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]


class OracleQueryFailed(RuntimeError):
    def __init__(self, private_diagnostic):
        super().__init__('ORACLE_QUERY_FAILED')
        self.private_diagnostic=private_diagnostic


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--context',required=True);parser.add_argument('--check',required=True);parser.add_argument('--ids',required=True)
    args=parser.parse_args()
    try:
        print(json.dumps(read_oracle(json.loads(Path(args.context).read_text()),args.check,json.loads(args.ids))))
        return 0
    except Exception as error:
        # Never emit DB stderr, connection details or input data to public reporter output.
        if isinstance(error,OracleQueryFailed):
            private_log=Path(args.context).with_name('oracle-private.log')
            private_log.write_text(error.private_diagnostic)
            private_log.chmod(0o600)
        print(json.dumps({'status':'FAILED','reasonType':type(error).__name__}),file=sys.stderr)
        return 1

if __name__=='__main__':sys.exit(main())
