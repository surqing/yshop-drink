const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto')
const { launchOfficial } = require('./official-launch.cjs')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
async function until(read, timeout = 15000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) { const result = await read(); if (result) return result; await sleep(150) }
  throw Error('MINI_PAGE_CONDITION_TIMEOUT')
}
class MiniDriver {
  static async launch(context, output) {
    fs.mkdirSync(output, { recursive: true, mode: 0o700 })
    for (const [name, hash] of Object.entries(context.compiledHashes)) {
      if (name.split(/[\\/]/).includes('..')) throw Error('COMPILED_PATH_INVALID')
      if (crypto.createHash('sha256').update(fs.readFileSync(path.join(context.project, name))).digest('hex') !== hash) throw Error('COMPILED_HASH_MISMATCH')
    }
    const mini = await launchOfficial(context, output)
    const driver = new MiniDriver(mini, output)
    driver.lifecycle('sdk-connected')
    driver.evidence = { officialConnected: true, compiledHashesVerified: true, compiledSource: context.compiledSource, compiledFileCount: Object.keys(context.compiledHashes).length }
    try {
      await until(async () => (await mini.pageStack()).length > 0, 90000)
      driver.lifecycle('page-stack-ready')
      // The simulator can restore the previous route; each case owns a fresh root stack.
      const page = await mini.reLaunch('/pages/index/index')
      driver.lifecycle('root-routed')
      await until(() => page.$('.index-page'))
      driver.lifecycle('root-rendered')
      return driver
    }
    catch (error) {
      await driver.diagnostics().catch(() => {})
      await driver.close(); throw error
    }
  }
  constructor(mini, output) {
    this.mini = mini; this.output = output
    mini.on('console', message => {
      fs.appendFileSync(path.join(output, 'console-private.jsonl'), JSON.stringify(message) + '\n', { mode: 0o600 })
    })
  }
  lifecycle(stage) { fs.writeFileSync(path.join(this.output, 'lifecycle-private.json'), JSON.stringify({ stage, at: Date.now() }), { mode: 0o600 }) }
  async state() {
    const state = await this.mini.evaluate(() => wx.getStorageSync('__e2eState'))
    if (!state) throw Error('MINI_INSTRUMENTATION_NOT_VISIBLE')
    return state
  }
  async token() { return this.mini.evaluate(() => wx.getStorageSync('accessToken')) }
  async proof(principal) {
    await this.mini.mockWxMethod('login', { code: `e2e-login-p${principal}-${crypto.randomBytes(16).toString('hex')}` })
  }
  async loginPage(principal = 1) {
    await this.proof(principal)
    const before = (await this.state()).authResponses
    const current = await this.mini.currentPage()
    const page = current?.path === 'pages-user/login/login'
      ? await this.mini.redirectTo('/pages-user/login/login')
      : await this.mini.navigateTo('/pages-user/login/login')
    await until(() => page.$('.login-page'))
    await until(async () => (await this.state()).authResponses > before)
    return page
  }
  async authorize(page, principal = 1) {
    this.lifecycle('phone-agreement')
    const before = await this.token()
    const radio = await page.$('radio'); await radio.tap()
    await this.proof(principal)
    const button = await page.$('.login-page__wechat-btn')
    await button.trigger('getphonenumber', { code: `e2e-phone-p${principal}-${crypto.randomBytes(16).toString('hex')}`, errMsg: 'getPhoneNumber:ok' })
    this.lifecycle('phone-event-triggered')
    try { return await until(async () => { const token = await this.token(); return token && token !== before ? token : null }) }
    catch { throw Error('MINI_PHONE_TOKEN_TIMEOUT') }
  }
  async login(principal = 1) {
    const page = await this.loginPage(principal)
    await this.authorize(page, principal)
    this.lifecycle('phone-token-issued')
    try { await until(async () => (await this.mini.currentPage()).path !== 'pages-user/login/login') }
    catch { throw Error('MINI_LOGIN_RETURN_TIMEOUT') }
  }
  async selectStore(name) {
    const page = await this.mini.navigateTo('/pages-checkout/shop/shop')
    const items = await until(async () => { const values = await page.$$('.shop-page__item'); return values.length ? values : null })
    for (const item of items) {
      if ((await item.text()).includes(name)) {
        await (await item.$('.shop-page__body')).tap()
        return until(async () => { const current = await this.mini.currentPage(); return await current.$('.goods') ? current : null })
      }
    }
    throw Error('MINI_OWNED_STORE_MISSING')
  }
  async addProduct(name, { topping = false, quantity = 1 } = {}) {
    const page = await this.mini.currentPage()
    const row = await until(async () => {
      for (const item of await page.$$('.good')) if ((await item.text()).includes(name)) return item
      return null
    })
    await (await row.$('.property_btn')).tap()
    const modal = await until(() => page.$('.good-detail-modal'))
    const sku = await until(() => modal.$('.properties .values .value'))
    await sku.tap()
    if (topping) {
      const option = await until(async () => {
        for (const element of await page.$$('.customization-option')) if ((await element.text()).includes('珍珠')) return element
        return null
      })
      await option.tap()
    }
    for (let i = 1; i < quantity; i++) {
      const buttons = await modal.$$('.action .btn-group button')
      await buttons[1].tap()
    }
    await (await page.$('.add-to-cart-btn')).tap()
    return until(() => this.mini.evaluate(() => { const cart = wx.getStorageSync('cart'); return cart && cart.length ? cart : null }))
  }
  async checkout() {
    const page = await this.mini.navigateTo('/pages-checkout/pay/pay')
    await until(() => page.$('.pay-page__footer-amount'))
    return page
  }
  async amount(page) { return (await (await page.$('.pay-page__footer-amount')).text()) }
  async submit(page, { loseResponse = false } = {}) {
    if (loseResponse) await this.mini.evaluate(() => { wx.setStorageSync('__e2eFault', { dropNextOrderResponse: true }) })
    const before = (await this.state()).orderIds.length
    await (await page.$('.pay-page__footer-btn')).tap()
    if (loseResponse) {
      await until(async () => (await this.state()).dropped)
      return { orderId: (await this.state()).orderIds.at(-1), page }
    }
    await until(async () => (await this.mini.currentPage()).path === 'pages-order/orders/detail')
    const state = await this.state()
    if (state.orderIds.length <= before) throw Error('MINI_ORDER_RESPONSE_MISSING')
    return { orderId: state.orderIds.at(-1), page: await this.mini.currentPage() }
  }
  async retry(page) {
    await until(async () => (await (await page.$('.pay-page__footer-btn')).text()).includes('提交订单'))
    return this.submit(page)
  }
  async cancel() {
    const page = await this.mini.currentPage()
    await (await until(() => page.$('.order-cancel-button'))).tap()
    await this.mini.native().confirmModal()
    await until(async () => (await this.mini.currentPage()).path === 'pages/order/order')
  }
  async couponRow(page, title, selector = '.coupons-item') {
    return until(async () => { for (const row of await page.$$(selector)) if ((await row.text()).includes(title)) return row; return null })
  }
  async claimCoupon(title) {
    const page = await this.mini.navigateTo('/pages-user/coupons/coupons')
    const tabs = await until(async () => { const values = await page.$$('.coupons-tab'); return values.length > 1 ? values : null })
    await tabs[1].tap()
    const row = await this.couponRow(page, title)
    await (await row.$('.immediate-use')).tap()
    await until(async () => (await (await this.couponRow(page, title)).text()).includes('已达每人领取上限'))
    return page
  }
  async selectCoupon(page, title) {
    const link = await until(() => page.getElementByXpath('//view[text()="优惠券"]'))
    await link.tap()
    const selection = await until(async () => { const current = await this.mini.currentPage(); return current.path === 'pages-checkout/packages/index' ? current : null })
    const row = await this.couponRow(selection, title, '.packages-item')
    await (await row.$('.immediate-use')).tap()
    return until(async () => { const current = await this.mini.currentPage(); return current.path === 'pages-checkout/pay/pay' ? current : null })
  }
  async couponText(title) {
    const page = await this.mini.navigateTo('/pages-user/coupons/coupons')
    const row = await this.couponRow(page, title)
    return row.text()
  }
  async screenshot(name = 'failure.png') { await this.mini.screenshot({ path: path.join(this.output, name) }) }
  async diagnostics() {
    const pages = await this.mini.pageStack()
    const state = await this.state()
    fs.writeFileSync(path.join(this.output, 'navigation-private.json'), JSON.stringify({
      pages: pages.map(page => page.path), tokenPresent: !!await this.token(),
      unpreparedLogins: state.unpreparedLogins,
      requests: state.requests.map(({ path, method, responseCode }) => ({ path, method, responseCode }))
    }, null, 2), { mode: 0o600 })
  }
  async close() {
    if (!this.mini) return
    try {
      await this.mini.evaluate(() => {
        for (const key of ['accessToken', 'userinfo', 'selectedStore', 'cart', 'checkoutSubmission', 'mycoupon', '__e2eState', '__e2eFault']) wx.removeStorageSync(key)
      })
      await this.mini.close()
    } finally { this.mini = null }
  }
}
module.exports = { MiniDriver, until }
