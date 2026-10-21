import test from 'node:test'
import assert from 'node:assert/strict'

const statusModule = await import('../../yshop-drink-uniapp-vue3/utils/pickup-order-status.mjs').catch(() => ({}))

test('unpaid pickup status keeps payment state instead of displaying legacy fulfillment', () => {
  assert.equal(typeof statusModule.pickupStatusView, 'function')
  assert.deepEqual(statusModule.pickupStatusView({
    paid: 0,
    refundStatus: 0,
    fulfillmentStatus: 'LEGACY',
    statusDto: { title: '未支付' }
  }), { primary: '未支付', fulfillment: '', showFulfillmentSeparately: false })
})

test('pending refund remains primary while active fulfillment is shown separately', () => {
  assert.deepEqual(statusModule.pickupStatusView({
    paid: 1,
    refundStatus: 1,
    fulfillmentStatus: 'READY',
    statusDto: { title: '申请退款中' }
  }), { primary: '申请退款中', fulfillment: '待取餐', showFulfillmentSeparately: true })
})

test('completed refund status is not masked by the former pickup stage', () => {
  assert.deepEqual(statusModule.pickupStatusView({
    paid: 1,
    refundStatus: 2,
    fulfillmentStatus: 'READY',
    statusDto: { title: '已退款' }
  }), { primary: '已退款', fulfillment: '', showFulfillmentSeparately: false })
})

test('active paid pickup uses fulfillment as its primary status without duplicating it', () => {
  assert.deepEqual(statusModule.pickupStatusView({
    paid: 1,
    refundStatus: 0,
    fulfillmentStatus: 'READY',
    statusDto: { title: '待取餐' }
  }), { primary: '待取餐', fulfillment: '待取餐', showFulfillmentSeparately: false })
})

test('paid legacy pickup preserves its existing order status', () => {
  assert.deepEqual(statusModule.pickupStatusView({
    paid: 1,
    refundStatus: 0,
    fulfillmentStatus: 'LEGACY',
    statusDto: { title: '交易完成' }
  }), { primary: '交易完成', fulfillment: '', showFulfillmentSeparately: false })
})
