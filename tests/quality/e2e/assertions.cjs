const assert = require('node:assert/strict')
function assertCatalogSnapshot(lines, expected) {
  assert.equal(lines.length, 1)
  const row = lines[0], snapshot = JSON.parse(row.cartInfo)
  for (const field of ['productId', 'title']) assert.equal(row[field], expected[field], field)
  assert.equal(row.spec, expected.sku); assert.equal(Number(row.number), 2); assert.equal(Number(row.price), 21)
  for (const field of ['productId', 'shopId', 'skuId', 'sku', 'title', 'catalogVersion']) assert.equal(snapshot[field], expected[field], field)
  for (const [field, value] of Object.entries({ version: 1, basePrice: 19, optionExtra: 2, unitPrice: 21, quantity: 2, lineTotal: 42 })) assert.equal(Number(snapshot[field]), value, field)
  assert.equal(snapshot.options.length, 1)
  const option = snapshot.options[0]
  for (const field of ['groupId', 'groupName', 'optionId']) assert.equal(option[field], expected[field], field)
  assert.equal(option.name, expected.optionName)
  assert.equal(option.kind, 'TOPPING'); assert.equal(option.quantity, 1); assert.equal(Number(option.surcharge), 2)
}
function assertFinancialDetails(rows, orderId, memberId, amountCents) {
  assert.equal(rows.length, 3)
  function one(kind) { const selected = rows.filter(row => row.kind === kind); assert.equal(selected.length, 1); return selected[0] }
  const payment = one('payment'), attempt = one('attempt'), bill = one('bill')
  for (const row of rows) assert.equal(row.orderId, orderId)
  assert.equal(payment.status, 'SUCCESS'); assert.equal(payment.successOrderId, orderId)
  assert.equal(attempt.status, 'PAID'); assert.equal(attempt.currency, 'CNY')
  assert.equal(payment.attemptId, attempt.id); assert.equal(attempt.eventId, payment.id)
  for (const row of [payment, attempt]) assert.equal(Number(row.amountCents), amountCents)
  for (const row of [attempt, bill]) assert.equal(row.memberId, memberId)
  assert.equal(Number(bill.amount), amountCents / 100)
  assert.equal(bill.category, 'now_money'); assert.equal(bill.type, 'pay_product'); assert.equal(bill.pm, 0)
}
module.exports = { assertCatalogSnapshot, assertFinancialDetails }
