const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto')
const automator = require('./node_modules/miniprogram-automator')
const ctx = JSON.parse(fs.readFileSync(process.env.YSHOP_MINI_CONTEXT, 'utf8'))
const report = { runId: ctx.runId, sourceSha: ctx.sourceSha, sourceDigest: ctx.sourceDigest,
  compiledSource: ctx.compiledSource, compiledHashesVerified: false, transport: 'owned-loopback',
  result: 'FAIL', cleanup: 'NOT_RUN', sourceUnchanged: true, checks: [] }
const save = () => fs.writeFileSync(process.env.YSHOP_MINI_REPORT, JSON.stringify(report, null, 2), { mode: 0o600 })
const wait = ms => new Promise(resolve => setTimeout(resolve, ms))
let mini
async function until(fn) { for (let i = 0; i < 100; i++) { const result = await fn(); if (result) return result; await wait(150) } throw Error('PAGE_CONDITION_TIMEOUT') }
async function check(name, fn) { report.stage = name; save(); const ok = !!await fn(); report.checks.push({ name, result: ok ? 'PASS' : 'FAIL', executed: true }); save(); if (!ok) throw Error('PAGE_ASSERTION_FAILED') }
async function selectStore(name) {
  const page = await mini.navigateTo('/pages-checkout/shop/shop')
  const items = await until(async () => { const e = await page.$$('.shop-page__item'); return e.length ? e : null })
  for (const item of items) if ((await item.text()).includes(name)) { await (await item.$('.shop-page__body')).tap(); return until(async () => { const p = await mini.currentPage(); return await p.$('.good') ? p : null }) }
  throw Error('OWNED_STORE_ELEMENT_MISSING')
}
async function add(page) { await (await until(() => page.$('.property_btn'))).tap(); await (await until(() => page.$('.add-to-cart-btn'))).tap() }
;(async () => {
  save()
  for (const [name, expected] of Object.entries(ctx.compiledHashes)) if (crypto.createHash('sha256').update(fs.readFileSync(path.join(ctx.project, name))).digest('hex') !== expected) throw Error('COMPILED_HASH_MISMATCH')
  report.compiledHashesVerified = true
  report.stage = 'official-launch'; save()
  mini = await automator.launch({ projectPath: ctx.project, cliPath: ctx.cli, port: ctx.port, timeout: 90000 })
  report.officialConnected = !!mini; save()
  let page = await mini.reLaunch('/pages/index/index')
  await check('current compiled app starts', async () => !!await until(() => page.$('.index-page')))
  page = await mini.navigateTo('/pages-checkout/shop/shop')
  await check('actual store page loads', async () => (await until(async () => { const items = await page.$$('.shop-page__item'); return items.length ? items : null })).length === 2)
  page = await selectStore('QA Shop A')
  await check('store A actual product and SKU render', async () => !!await page.$('.property_btn'))
  await add(page)
  await check('tap adds store A cart', () => until(() => mini.evaluate(() => { const c = wx.getStorageSync('cart'); return c && c.length === 1 && Number(c[0].shopId) === 101 })))
  page = await selectStore('QA Shop B')
  await check('switch to B clears cart and checkout context', () => mini.evaluate(() => { const c = wx.getStorageSync('cart') || []; return c.length === 0 && Number(wx.getStorageSync('selectedStore').id) === 102 && !wx.getStorageSync('checkoutSubmission') }))
  page = await selectStore('QA Shop A'); await add(page)
  await check('switch back to A and add current server price', () => until(() => mini.evaluate(() => { const c = wx.getStorageSync('cart') || []; return c.length === 1 && Number(c[0].price) === 19 && Number(c[0].number) === 1 })))
  page = await mini.navigateTo('/pages-checkout/pay/pay')
  await check('checkout actual page and amount', async () => (await (await until(() => page.$('.pay-page__footer-amount'))).text()).includes('19.00'))
  await (await page.$('.pay-page__footer-btn')).tap()
  await check('committed response loss preserves checkout', () => until(() => mini.evaluate(() => wx.getStorageSync('__qualityCounts').dropped && wx.getStorageSync('cart').length === 1 && !!wx.getStorageSync('checkoutSubmission'))))
  await until(async () => (await (await page.$('.pay-page__footer-btn')).text()).includes('提交订单'))
  await (await page.$('.pay-page__footer-btn')).tap()
  await check('same key page retry opens unpaid order', () => until(async () => { const p = await mini.currentPage(); return p.path === 'pages-order/orders/detail' }))
  const state = await mini.evaluate(() => ({ ...wx.getStorageSync('__qualityCounts'), orderId: wx.getStorageSync('__qualityOrderId') }))
  await check('local APIs responded and financial admission remained zero', async () => state.localSuccess > 0 && state.orders === 2 && state.paymentRequests === 0 && state.blockedFinancialRequests === 0)
  report.orderId = state.orderId; report.paymentRequests = state.paymentRequests; report.blockedFinancialRequests = state.blockedFinancialRequests; report.result = 'PASS'
})().catch(async error => {
  fs.writeFileSync(path.join(path.dirname(process.env.YSHOP_MINI_REPORT), 'failure-private.log'), String(error.stack || error), { mode: 0o600 })
  report.reasonType = error.name
  report.reasonCode = /^(PAGE_|OWNED_|COMPILED_)/.test(error.message) ? error.message : 'OFFICIAL_AUTOMATION_FAILED'
  if (mini) { try { await mini.screenshot({ path: path.join(path.dirname(process.env.YSHOP_MINI_REPORT), 'failure.png') }); report.failureScreenshot = 'failure.png' } catch {} }
  process.exitCode = 1
}).finally(async () => {
  try {
    if (mini) {
      await mini.evaluate(() => { for (const key of ['accessToken', 'userinfo', 'selectedStore', 'cart', 'checkoutSubmission', '__qualityCounts', '__qualityOrderId']) wx.removeStorageSync(key) })
      await mini.close()
    }
    report.cleanup = mini ? 'PASS' : 'NOT_RUN'
  } catch { report.cleanup = 'FAIL'; report.result = 'FAIL'; process.exitCode = 1 }
  save(); console.log(JSON.stringify({ result: report.result, checks: report.checks.length, stage: report.stage, cleanup: report.cleanup }))
})
