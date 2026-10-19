import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { assertCatalogSnapshot, assertFinancialDetails } = createRequire(import.meta.url)('./quality/e2e/assertions.cjs')
const expected = { productId: 77, shopId: 101, skuId: 78, sku: '默认', title: 'Synthetic tea', catalogVersion: 2,
  groupId: 'topping', optionId: 'pearl', groupName: '加料', optionName: '珍珠' }
function line() { return { productId: 77, title: 'Synthetic tea', spec: '默认', number: 2, price: 21,
  cartInfo: JSON.stringify({ version: 1, productId: 77, shopId: 101, skuId: 78, sku: '默认', title: 'Synthetic tea', catalogVersion: 2,
    basePrice: 19, optionExtra: 2, unitPrice: 21, quantity: 2, lineTotal: 42,
    options: [{ groupId: 'topping', optionId: 'pearl', groupName: '加料', name: '珍珠', kind: 'TOPPING', quantity: 1, surcharge: 2 }] }) } }
function records() { return [
  { kind: 'payment', id: 'event', orderId: 'owned', amountCents: 1400, status: 'SUCCESS', successOrderId: 'owned', attemptId: 'attempt' },
  { kind: 'attempt', id: 'attempt', orderId: 'owned', memberId: 101, amountCents: 1400, currency: 'CNY', status: 'PAID', eventId: 'event' },
  { kind: 'bill', id: 99, orderId: 'owned', memberId: 101, amount: 14, category: 'now_money', type: 'pay_product', pm: 0 }
] }
test('persisted catalog line accepts authoritative SKU topping and exact prices', () => assertCatalogSnapshot([line()], expected))
test('persisted line rejects wrong product SKU title quantity or unit price', () => {
  for (const [field, value] of [['productId', 88], ['title', 'wrong'], ['spec', 'wrong'], ['number', 1], ['price', 19]]) {
    const row = line(); row[field] = value; assert.throws(() => assertCatalogSnapshot([row], expected), field)
  }
})
test('persisted choices reject omitted topping or corrupt historical snapshot fields', () => {
  for (const patch of [{ options: [] }, { unitPrice: 25 }, { lineTotal: 50 }, { basePrice: 25 }, { skuId: 999 }, { catalogVersion: 3 }, { quantity: 1 }]) {
    const row = line(); row.cartInfo = JSON.stringify({ ...JSON.parse(row.cartInfo), ...patch })
    assert.throws(() => assertCatalogSnapshot([row], expected))
  }
})
test('financial details accept one successful exact linked completion', () => assertFinancialDetails(records(), 'owned', 101, 1400))
test('financial details reject incorrect amount state or member/order/event linkage', () => {
  for (const [index, patch] of [[0, { amountCents: 1 }], [0, { status: 'RECEIVED' }], [1, { status: 'CREATED' }], [1, { memberId: 102 }], [1, { eventId: 'wrong' }], [2, { amount: 1 }], [2, { orderId: 'foreign' }], [2, { pm: 1 }]]) {
    const rows = records(); Object.assign(rows[index], patch); assert.throws(() => assertFinancialDetails(rows, 'owned', 101, 1400))
  }
})
test('financial details reject duplicate or missing side effects', () => {
  assert.throws(() => assertFinancialDetails(records().slice(0, 2), 'owned', 101, 1400))
  assert.throws(() => assertFinancialDetails([...records(), records()[2]], 'owned', 101, 1400))
})
