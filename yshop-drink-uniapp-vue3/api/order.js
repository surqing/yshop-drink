import api from './api'
import { submissionKey, PAYMENT_FROZEN } from '@/utils/ordering-context'

/**
 * 订单列表  
 */
export function orderTakeFoods(data) {
  return api.get('/order/list', data, { login: true })
}

/**
 * 订单创建  
 */
export function orderSubmit(data) {
  const idempotencyKey = data.idempotencyKey || submissionKey(uni, data)
  return api.post(`/order/create`, { ...data, idempotencyKey }, { login: true })
}



/**
 * 订单列表  
 */
export function orderGetOrders(data) {
  return api.get(`/order/list`, data, { login: true })
}


/**
 * 计算详情 
 */
export function orderDetail(data) {
  return api.get(`/order/detail/${data}`, data, { login: true })
}



/**
 * 订单收货 
 */
export function orderReceive(data) {
  return api.post(`/order/take`, data, { login: true })
}

/**
 * 订单退款 
 */
export function orderRefund(data) {
  return api.post(`/order/refund`, data, { login: true })
}


/**
 * 订单支付 
 */
export function payUnify(data) {
  if (PAYMENT_FROZEN) return Promise.reject(new Error('支付暂未开放，订单保持待支付'))
  return api.post(`/order/pay`, data, { login: true })
}

/**
 * getWechatConfig 	
 */
export function getWechatConfig() {
  return api.get(`/member/wx-mp/create-jsapi-signature`, { url: location.href }, { login: true })
}

/** Cancel only an authenticated customer's own unpaid order. */
export function orderCancel(id) {
  return api.post('/order/cancel', { id }, { login: true })
}
