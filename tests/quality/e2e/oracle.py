"""Named read-only SQL observations, under a SELECT-only run-owned account."""
import argparse
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


def query_for(name, ids):
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
        return "SELECT JSON_OBJECT('id',id,'shopId',shop_id,'version',catalog_version,'name',store_name,'stock',stock) FROM yshop_store_product WHERE id="+number(ids.get('productId'))+';'
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
    result=subprocess.run(['docker','exec','-i',container,'mysql','--defaults-extra-file=/run/oracle.cnf',schema,'-N'],
                          input=query,text=True,capture_output=True,timeout=30)
    if result.returncode:raise RuntimeError('ORACLE_QUERY_FAILED')
    return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--context',required=True);parser.add_argument('--check',required=True);parser.add_argument('--ids',required=True)
    args=parser.parse_args()
    try:
        print(json.dumps(read_oracle(json.loads(Path(args.context).read_text()),args.check,json.loads(args.ids))))
        return 0
    except Exception as error:
        # Never emit DB stderr, connection details or input data to public reporter output.
        print(json.dumps({'status':'FAILED','reasonType':type(error).__name__}),file=sys.stderr)
        return 1

if __name__=='__main__':sys.exit(main())
