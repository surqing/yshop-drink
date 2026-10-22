const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto')
const { launchOfficial } = require('./official-launch.cjs')
const { confirmWithAccessibility, confirmationPath } = require('./native-confirmation.cjs')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
function saveNativeConfirmationRoute(output, route) {
  const file = path.join(output, 'native-confirmation-route-private.json')
  fs.writeFileSync(file, JSON.stringify(route, null, 2) + '\n', { mode: 0o600 })
  try { fs.chmodSync(file, 0o600) } catch (_) { /* Creation mode remains the fallback. */ }
}
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
    driver.context = context
    driver.lifecycle('sdk-connected')
    driver.evidence = { officialConnected: true, compiledHashesVerified: true, compiledSource: context.compiledSource, compiledFileCount: Object.keys(context.compiledHashes).length }
    try {
      const info = await mini.send('Tool.getInfo')
      driver.evidence.officialToolVersion = info.version
      driver.evidence.officialSdkVersion = info.SDKVersion
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
  async agree(page) {
    const radio = await page.$('radio')
    if (![true, 'true'].includes(await radio.property('checked'))) await radio.tap()
    await until(async () => [true, 'true'].includes(await radio.property('checked')))
  }
  async authorize(page, principal = 1) {
    this.lifecycle('phone-agreement')
    const before = await this.token()
    await this.agree(page)
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
  async scoreCheckout(product, address) {
    const detail = await this.mini.navigateTo(`/pages-score/scoreproduct/detail?id=${product.id}`)
    await until(async () => (await (await detail.$('.score-product-detail__title'))?.text()) === product.title)
    await (await detail.$('.score-product-detail__btn')).tap()
    let page = await until(async () => { const value=await this.mini.currentPage(); return value.path==='pages-score/scoreproduct/confirm'?value:null })
    await until(() => page.$('.score-product-confirm'))
    const addresses = await this.mini.navigateTo('/pages-user/address/address?is_choose=true&scene=scoreShop')
    const card = await until(async () => {
      for (const value of await addresses.$$('.address-page__card')) if ((await value.text()).includes(address.detail)) return value
      return null
    })
    await card.tap()
    page=await until(async () => {const value=await this.mini.currentPage();return value.path==='pages-score/scoreproduct/confirm'?value:null})
    await until(async () => (await (await page.$('.score-product-confirm')).text()).includes(address.phone))
    return page
  }
  async scoreSubmit(page, { loseResponse=false }={}) {
    if(loseResponse) await this.mini.evaluate(()=>wx.setStorageSync('__e2eFault',{dropNextScoreResponse:true}))
    const before=(await this.state()).requests.filter(r=>r.path==='/app-api/score-order/submit').length
    const component=await until(()=>page.$('.score-product-confirm__submit'))
    const button=await until(()=>component.$('button'))
    await until(async()=>!(await component.$('.uv-button-wrapper--dis')))
    await button.tap()
    let request
    try {request=await until(async()=>{
      const rows=(await this.state()).requests.filter(r=>r.path==='/app-api/score-order/submit')
      return rows.length>before && rows.at(-1).responseCode!==undefined?rows.at(-1):null
    })} catch(error) {
      // Keep diagnostics private and bounded; never call a page/component submit handler.
      const read=async fn=>{try{return await fn()}catch(failure){return `UNAVAILABLE:${failure.message}`}}
      const probe={pageId:page.id,currentPageId:(await this.mini.currentPage())?.id,
        footer:await read(async()=>(await page.$('.fixed-bottom'))?.outerWxml()),
        button:await read(()=>button.outerWxml()),
        requestCount:(await this.state()).requests.filter(r=>r.path==='/app-api/score-order/submit').length}
      fs.writeFileSync(path.join(this.output,'score-submit-private.json'),JSON.stringify(probe,null,2),{mode:0o600})
      throw error
    }
    if(loseResponse) await until(async()=>(await this.state()).scoreDropped)
    else if(request.responseCode===0) await until(async()=>(await this.mini.currentPage()).path==='pages-score/scoreproduct/order')
    return request
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
    this.lifecycle('product-visible')
    await (await row.$('.property_btn')).tap()
    await until(() => page.$('.good-detail-modal'))
    // Slot children belong to the page; querying inside the custom modal misses them.
    const sku = await until(() => page.$('.properties .values .value'))
    await sku.tap()
    this.lifecycle('sku-selected')
    if (topping) {
      const option = await until(async () => {
        for (const element of await page.$$('.customization-option')) if ((await element.text()).includes('珍珠')) return element
        return null
      })
      await option.tap()
      await until(async () => (await option.text()).includes('1'))
      this.lifecycle('topping-selected')
    }
    for (let i = 1; i < quantity; i++) {
      const buttons = await page.$$('.action .btn-group button')
      await buttons[1].tap()
      await until(async () => Number(await (await page.$('.action .number')).text()) === i + 1)
    }
    this.lifecycle('quantity-selected')
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
    const before = await this.state()
    const route = {
      runId: this.context.runId,
      project: this.context.project,
      officialMethod: 'miniprogram-automator.native().confirmModal()',
      officialAttempted: true,
      officialReturned: false,
      officialFailureCode: null,
      callbackCountBefore: before.nativeModalsConfirmed,
      callbackCountAfterOfficial: null,
      callbackCountAfterAccessibility: null,
      callbackObserved: false,
      adapter: this.context.nativeAdapter || 'disabled',
      accessibilityAttempted: false,
      accessibilityActionCompleted: false,
      accessibilityFailureCode: null,
      accessibilityReceipt: 'native-confirmation-accessibility-private.json',
      accessibilityDiagnostics: {
        collection: 'NOT_COLLECTED',
        pid: null,
        processMatchCount: null,
        axWindowsStatusCode: null,
        windowTotal: null,
        windows: null,
        ownedWindowCount: null,
        webAreaCount: null,
        webAreaUrls: null,
        projectPathMatchCount: null,
        dialogScopeCount: null,
        confirmationNodeMatchCount: null
      },
      confirmationPath: 'NO_CONFIRMED_PATH',
      result: 'IN_PROGRESS',
      failureCode: null
    }
    const component = await until(() => page.$('.order-cancel-button'))
    const button = await until(() => component.$('button'))
    const offset = await button.offset()
    await this.mini.pageScrollTo(Number(offset.top))
    this.lifecycle('cancel-button-visible')
    await button.tap()
    await until(async () => (await this.state()).nativeModalsOpened > before.nativeModalsOpened)
    saveNativeConfirmationRoute(this.output, route)
    try {
      await this.mini.native().confirmModal()
      route.officialReturned = true
    } catch (error) {
      route.officialFailureCode = error && (error.code || error.name) || 'OFFICIAL_NATIVE_CONFIRM_FAILED'
      route.failureCode = 'OFFICIAL_NATIVE_CONFIRM_FAILED'
      route.result = 'BLOCKED'
      saveNativeConfirmationRoute(this.output, route)
      throw error
    }
    await sleep(300)
    let state = await this.state()
    route.callbackCountAfterOfficial = state.nativeModalsConfirmed
    route.callbackObserved = state.nativeModalsConfirmed > before.nativeModalsConfirmed
    if (!route.callbackObserved && this.context.nativeAdapter === 'applescript') {
      route.accessibilityAttempted = true
      route.accessibilityDiagnostics.collection = 'IN_PROGRESS'
      saveNativeConfirmationRoute(this.output, route)
      try {
        const accessibility = await confirmWithAccessibility(this.context, this.output)
        route.accessibilityActionCompleted = accessibility.result === 'AXPRESS_COMPLETED'
        route.accessibilityDiagnostics = { collection: 'COLLECTED', ...accessibility }
        this.evidence.nativeConfirmationAdapter = 'macos-applescript-accessibility'
        this.evidence.nativeAccessibilityPresses = (this.evidence.nativeAccessibilityPresses || 0) + 1
      } catch (error) {
        route.accessibilityFailureCode = error && error.code || 'ACCESSIBILITY_CONFIRMATION_FAILED'
        if (error && error.diagnostics) route.accessibilityDiagnostics = { collection: 'COLLECTED', ...error.diagnostics }
        route.failureCode = 'MINI_NATIVE_CONFIRMATION_UNAVAILABLE'
        route.confirmationPath = confirmationPath({
          accessibilityAttempted: true,
          accessibilityActionCompleted: false,
          callbackObserved: false
        })
        route.result = 'BLOCKED'
        saveNativeConfirmationRoute(this.output, route)
        throw Error('MINI_NATIVE_CONFIRMATION_UNAVAILABLE')
      }
      state = await this.state()
      route.callbackCountAfterAccessibility = state.nativeModalsConfirmed
      route.callbackObserved = state.nativeModalsConfirmed > before.nativeModalsConfirmed
    }
    if (!route.callbackObserved) {
      try {
        await until(async () => (await this.state()).nativeModalsConfirmed > before.nativeModalsConfirmed)
        state = await this.state()
        route.callbackObserved = state.nativeModalsConfirmed > before.nativeModalsConfirmed
        if (route.accessibilityAttempted) route.callbackCountAfterAccessibility = state.nativeModalsConfirmed
      } catch {
        route.failureCode = 'MINI_NATIVE_CONFIRMATION_UNAVAILABLE'
        route.confirmationPath = confirmationPath({
          accessibilityAttempted: route.accessibilityAttempted,
          accessibilityActionCompleted: route.accessibilityActionCompleted,
          callbackObserved: false
        })
        route.result = 'BLOCKED'
        saveNativeConfirmationRoute(this.output, route)
        throw Error('MINI_NATIVE_CONFIRMATION_UNAVAILABLE')
      }
    }
    route.confirmationPath = confirmationPath({
      nativeCallbackObserved: route.callbackCountAfterOfficial > before.nativeModalsConfirmed,
      accessibilityAttempted: route.accessibilityAttempted,
      accessibilityActionCompleted: route.accessibilityActionCompleted,
      callbackObserved: route.callbackObserved
    })
    route.result = 'CONFIRMED_BY_REAL_CALLBACK'
    saveNativeConfirmationRoute(this.output, route)
    this.lifecycle('cancel-confirmed')
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
  async orderDetail(orderId) {
    const page = await this.mini.navigateTo('/pages-order/orders/detail?id=' + encodeURIComponent(orderId))
    await until(() => page.$('.order-detail-page'))
    return page
  }
  async pickupStatus(page) {
    return (await until(() => page.$('.order-detail-page'))).text()
  }
  async collectPickupOrder(page) {
    const before = (await this.state()).requests.filter(row => row.path === '/app-api/order/take').length
    const component = await until(async () => {
      for (const candidate of await page.$$('.fixed-bottom uv-button')) {
        if ((await candidate.text()).includes('确认取餐')) return candidate
      }
      return null
    })
    const button = await until(() => component.$('button.uv-button'))
    await until(async () => !(await component.$('.uv-button-wrapper--dis')))
    let request
    try {
      await button.tap()
      request = await until(async () => {
        const rows = (await this.state()).requests.filter(row => row.path === '/app-api/order/take')
        return rows.length > before && rows.at(-1).responseCode !== undefined ? rows.at(-1) : null
      })
    } catch (error) {
      const read = async fn => { try { return await fn() } catch (failure) { return `UNAVAILABLE:${failure.message}` } }
      const state = await this.state()
      const probe = {
        currentPage: (await this.mini.currentPage())?.path,
        pageId: page.id,
        footer: await read(async () => (await page.$('.fixed-bottom'))?.outerWxml()),
        pickupButton: await read(() => component.outerWxml()),
        nativeButton: await read(() => button.outerWxml()),
        nativeButtonSize: await read(() => button.size()),
        nativeButtonOffset: await read(() => button.offset()),
        requestCountBefore: before,
        requestCountAfter: state.requests.filter(row => row.path === '/app-api/order/take').length,
      }
      fs.writeFileSync(path.join(this.output, 'pickup-collect-private.json'), JSON.stringify(probe, null, 2), { mode: 0o600 })
      throw error
    }
    await until(async () => (await this.pickupStatus(page)).includes('已取餐'))
    return request
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
        for (const key of ['accessToken', 'userinfo', 'selectedStore', 'cart', 'checkoutSubmission', 'checkoutSubmissionByMember', 'checkoutLegacySubmission', 'mycoupon', 'score_product', '__e2eState', '__e2eFault']) wx.removeStorageSync(key)
        for (const key of wx.getStorageInfoSync().keys) if (key.startsWith('scoreSubmission:v1:')) wx.removeStorageSync(key)
      })
      await this.mini.close()
    } finally { this.mini = null }
  }
}
module.exports = { MiniDriver, until }
