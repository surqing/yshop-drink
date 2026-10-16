import { randomBytes } from 'node:crypto'
import { readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { createServer } from 'node:http'
import { test, expect, context, Flow } from './flow'
const key = () => randomBytes(16).toString('hex')
const local = context.profile === 'local'

async function rights(f: Flow, coupon: any, token: string) {
  const rows = await f.api('GET', '/app-api/coupon/my?shopId=101&type=3&page=1&pagesize=100', undefined, token)
  const right = rows.find((row: any) => row.title === coupon.title)
  expect(right).toBeTruthy()
  return right
}
async function issue(f: Flow, coupon: any, token: string) {
  if (local) await f.mini.claimCoupon(coupon.title)
  else await f.api('POST', '/app-api/coupon/receive', { id: coupon.id, requestKey: key() }, token)
  return rights(f, coupon, token)
}
async function orderFor(f: Flow, product: any, token: string, coupon?: any, extra: any = {}) {
  if (!local) return f.createOrder(product, token, extra)
  await f.mini.selectStore(Number(product.shopId) === 102 ? 'QA Shop B' : 'QA Shop A')
  await f.mini.addProduct(product.name)
  let page = await f.mini.checkout()
  if (coupon) page = await f.mini.selectCoupon(page, coupon.title)
  const order = await f.mini.submit(page)
  const state = await f.mini.state()
  return { ...order, body: state.requests.filter((r: any) => r.path === '/app-api/order/create').at(-1).data }
}
async function loseResponse(f: Flow, body: any, token: string) {
  let committed = false
  const server = createServer(async (request, response) => {
    if (request.method !== 'POST' || request.url !== '/owned-order') { response.writeHead(404); response.end(); return }
    try {
      const result = await fetch(`${context.backend}/app-api/order/create`, { method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body: JSON.stringify(body) })
      const data: any = await result.json(); expect(data.code).toBe(0); committed = true
    } finally { response.destroy() }
  })
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    const address: any = server.address()
    await expect(f.request.post(`http://127.0.0.1:${address.port}/owned-order`, { data: body, timeout: 30000 })).rejects.toThrow()
    expect(committed).toBe(true)
  } finally { await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve())) }
}

async function authSession(f: Flow) {
  await f.check('admin-login', async () => {
    expect((await f.api('GET', '/admin-api/system/auth/get-permission-info')).user.id).toBe(101)
    await f.page.goto('/mall/products'); await expect(f.page.getByPlaceholder('请输入商品名称')).toBeVisible()
  })
  if (local) {
    await f.check('mini-visitor-login', async () => {
      expect(await f.mini.token()).toBeFalsy()
      await f.mini.mini.navigateTo('/pages-user/address/address')
      await expect.poll(async () => (await f.mini.mini.currentPage()).path).toBe('pages-user/login/login')
      expect((await f.mini.state()).requests.some((r: any) => r.path === '/app-api/address/list' && r.responseCode === 401)).toBe(true)
      const page = await f.mini.loginPage(3)
      expect(await page.$('.login-page')).toBeTruthy()
      expect(await f.mini.token()).toBeFalsy()
    })
    await f.check('mini-denied-authorization', async () => {
      const page = await f.mini.mini.currentPage()
      await (await page.$('.login-page__wechat-btn')).trigger('getphonenumber', { errMsg: 'getPhoneNumber:fail user deny' })
      expect(await f.mini.token()).toBeFalsy()
    })
    await f.check('mini-phone-authorization', async () => {
      await f.mini.authorize(await f.mini.mini.currentPage(), 3)
      await expect.poll(async () => (await f.mini.mini.currentPage()).path).not.toBe('pages-user/login/login')
      const token = await f.mini.token()
      const member = await f.api('GET', '/app-api/member/user/get', undefined, token)
      expect(member.mobile).toBe('13800000003')
    })
  }
  await f.check('invalid-provider-proof', async () => {
    const rejected = await f.api('POST', '/app-api/member/auth/weixin-mini-app-login',
      { loginCode: 'expired-proof', phoneCode: 'invalid-proof' }, '', false)
    expect(rejected.code).not.toBe(0)
    expect(rejected.data?.accessToken).toBeFalsy()
  })
  let smsCode = '', smsToken = ''
  await f.check('sms-login', async () => {
    let page: any
    if (local) {
      page = await f.mini.loginPage(1)
      await (await page.$('.login-page__input')).input('13800000001')
      await (await page.$('.login-page__btn--captcha')).tap()
    } else await f.api('POST', '/app-api/member/auth/send-sms-code', { mobile: '13800000001', scene: 1 }, '')
    const outbox = join(context.smsOutbox, '13800000001.json')
    await expect.poll(() => existsSync(outbox), { timeout: 30000 }).toBe(true)
    const delivery = JSON.parse(readFileSync(outbox, 'utf8')); expect(delivery.owner).toBe(context.owner); smsCode = delivery.code
    expect((await f.api('POST', '/app-api/member/auth/sms-login', { mobile: '13800000001', code: '000000' }, '', false)).code).not.toBe(0)
    if (local) {
      const before = await f.mini.token()
      await (await page.$('.login-page__input--captcha')).input(smsCode)
      await f.mini.agree(page)
      await (await page.$('.login-page__btn--submit')).tap()
      await expect.poll(() => f.mini.token()).not.toBe(before)
      smsToken = await f.mini.token()
    } else smsToken = (await f.api('POST', '/app-api/member/auth/sms-login', { mobile: '13800000001', code: smsCode }, '')).accessToken
    expect((await f.api('GET', '/app-api/member/user/get', undefined, smsToken)).id).toBe(101)
  })
  await f.check('sms-invalid-and-reused', async () => {
    const rejected = await f.api('POST', '/app-api/member/auth/sms-login', { mobile: '13800000001', code: smsCode }, '', false)
    expect(rejected.code).not.toBe(0); expect(rejected.data?.accessToken).toBeFalsy()
  })
  await f.check('expired-session', async () => {
    f.bridge('token-lifetime', { seconds: 2 })
    try {
      const token = await f.member(2)
      expect((await f.api('GET', '/app-api/member/user/get', undefined, token)).id).toBe(102)
      await expect.poll(async () => (await f.api('GET', '/app-api/member/user/get', undefined, token, false)).code).toBe(401)
    } finally { f.bridge('token-lifetime', { seconds: 3600 }) }
  })
  if (local) await f.check('mini-expired-session', async () => {
    f.bridge('token-lifetime', { seconds: 2 })
    try {
      const page = await f.mini.loginPage(1)
      const token = await f.mini.authorize(page, 1)
      expect((await f.api('GET', '/app-api/member/user/get', undefined, token)).id).toBe(101)
      await expect.poll(async () => (await f.api('GET', '/app-api/member/user/get', undefined, token, false)).code).toBe(401)
      await f.mini.mini.navigateTo('/pages-user/address/address')
      await expect.poll(async () => (await f.mini.mini.currentPage()).path).toBe('pages-user/login/login')
      expect((await f.mini.state()).requests.some((r: any) => r.path === '/app-api/address/list' && r.responseCode === 401)).toBe(true)
    } finally { f.bridge('token-lifetime', { seconds: 3600 }) }
  })
  await f.check('logout-revokes-access', async () => {
    if (local) {
      await f.mini.login(1)
      const token = await f.mini.token()
      const page = await f.mini.mini.navigateTo('/pages-user/login/logout')
      await (await page.$('.logout-page__btn')).tap()
      await expect.poll(() => f.mini.token()).toBeFalsy()
      expect((await f.api('GET', '/app-api/member/user/get', undefined, token, false)).code).toBe(401)
    } else {
      await f.api('POST', '/app-api/member/auth/logout', undefined, smsToken)
      expect((await f.api('GET', '/app-api/member/user/get', undefined, smsToken, false)).code).toBe(401)
    }
    await f.page.locator('.el-dropdown').filter({ hasText: 'synthetic-hq' }).click()
    await f.page.getByText('退出系统', { exact: true }).click()
    await f.page.getByRole('button', { name: '确定', exact: true }).click()
    await f.page.waitForURL(url => url.pathname.includes('/login'))
    expect((await f.api('GET', '/admin-api/system/auth/get-permission-info', undefined, f.adminToken, false)).code).toBe(401)
  })
  await f.check('identity-sql', () => {
    const row = f.oracle('identity', { memberId: 101 })[0]
    expect(row.mobile).toBe('13800000001'); expect(row.providerId).toBe('synthetic-mini-101'); expect(row.status).toBe(0)
  })
}

async function catalogOrder(f: Flow) {
  let product: any, order: any
  await f.check('admin-create-product', async () => { product = await f.createProductUI(); expect(product.id).toBeTruthy() })
  await f.check('admin-configure-sku', () => { expect(Number(product.skus[0].stock)).toBe(10); expect(Number(product.skus[0].price)).toBe(19) })
  await f.check('admin-configure-topping', async () => {
    product = await f.configureToppingUI(product)
    expect(product.configuration.groups[0].kind).toBe('TOPPING')
    expect(Number(product.configuration.groups[0].options[0].surcharge)).toBe(2)
  })
  const token = await f.member()
  if (local) {
    await f.check('mini-sku-and-topping', async () => {
      await f.mini.selectStore('QA Shop A')
      const cart = await f.mini.addProduct(product.name, { topping: true, quantity: 2 })
      expect(Number(cart[0].number)).toBe(2); expect(cart[0].selections).toHaveLength(1)
      expect(Number(cart[0].selections[0].quantity)).toBe(1)
    })
    await f.check('mini-order-submitted', async () => {
      const page = await f.mini.checkout(); expect(await f.mini.amount(page)).toContain('42.00')
      order = await f.mini.submit(page)
    })
  } else {
    const group = product.configuration.groups[0]
    order = await f.createOrder(product, token, { number: ['2'], choices: [{ version: product.version,
      selections: [{ groupId: group.id, optionId: group.options[0].id, quantity: 1 }] }] })
  }
  await f.check('order-price-and-choices', async () => {
    const detail = await f.api('GET', `/app-api/order/detail/${order.orderId}`, undefined, token)
    expect(Number(detail.payPrice)).toBe(42)
    expect(f.oracle('order', { orderId: order.orderId })[0].quantity).toBe(2)
  })
  await f.check('admin-order-visible', async () => { await f.viewOrder(order.orderId, '未支付') })
  await f.check('catalog-edit-keeps-snapshot', async () => {
    await f.changePriceUI(product, 25)
    expect(Number(f.oracle('order', { orderId: order.orderId })[0].amount)).toBe(42)
    expect(Number(f.oracle('inventory', { skuId: product.skus[0].id })[0].price)).toBe(25)
  })
  const current = { ...product, ...(await f.productData(product.id)) }
  const invalidBase = { shopId: '101', mobile: '13800000001', couponId: '0', orderType: 'takein', productId: [String(product.id)],
    spec: [current.skus[0].sku], number: ['1'], payType: 'weixin', choices: [{ version: current.version, selections: [] }] }
  await f.check('invalid-sku-denied', async () => {
    const rejected = await f.api('POST', '/app-api/order/create', { ...invalidBase, spec: ['nonexistent-sku'], idempotencyKey: key() }, token, false)
    expect(rejected.code).not.toBe(0)
    expect(f.oracle('inventory', { skuId: product.skus[0].id })[0].stock).toBe(8)
  })
  await f.check('insufficient-stock-denied', async () => {
    const rejected = await f.api('POST', '/app-api/order/create', { ...invalidBase, number: ['100'], idempotencyKey: key() }, token, false)
    expect(rejected.code).not.toBe(0)
    expect(f.oracle('inventory', { skuId: product.skus[0].id })[0].stock).toBe(8)
  })
  await f.check('order-sql', () => {
    const row = f.oracle('order', { orderId: order.orderId })[0]
    expect(row.shopId).toBe(101); expect(row.paid).toBe(0)
    expect(f.oracle('inventory', { skuId: product.skus[0].id })[0].stock).toBe(8)
  })
}

async function storeIsolation(f: Flow) {
  const a = await f.createProduct(101, 'isolation-a'), b = await f.createProduct(102, 'isolation-b', 20)
  const member = await f.member(), other = await f.member(2)
  let first: any, second: any
  await f.check('store-a-order', async () => { first = await orderFor(f, a, member); expect(f.oracle('order', { orderId: first.orderId })[0].shopId).toBe(101) })
  if (local) await f.check('mini-switch-clears-context', async () => {
    await f.mini.selectStore('QA Shop A'); await f.mini.addProduct(a.name)
    await f.mini.selectStore('QA Shop B')
    const state = await f.mini.mini.evaluate(() => ({ cart: wx.getStorageSync('cart'), checkout: wx.getStorageSync('checkoutSubmission'), coupon: wx.getStorageSync('mycoupon') }))
    expect(state.cart || []).toHaveLength(0); expect(state.checkout).toBeFalsy(); expect(state.coupon?.id).toBeFalsy()
  })
  await f.check('store-b-order', async () => { second = await orderFor(f, b, member); expect(f.oracle('order', { orderId: second.orderId })[0].shopId).toBe(102) })
  await f.check('cross-store-product-denied', async () => {
    const denied = await f.api('POST', '/app-api/order/create', { ...second.body, shopId: '101', idempotencyKey: key() }, member, false)
    expect(denied.code).not.toBe(0)
  })
  const coupon = await f.createCouponUI('isolation-coupon')
  await f.api('POST', '/app-api/coupon/receive', { id: coupon.id, requestKey: key() }, member)
  const right = await rights(f, coupon, member)
  await f.check('cross-store-coupon-denied', async () => {
    const denied = await f.api('POST', '/app-api/order/create', { ...second.body, couponId: String(right.id), idempotencyKey: key() }, member, false)
    expect(denied.code).not.toBe(0)
  })
  await f.check('staff-cannot-read-other-store', async () => {
    const staffContext = await f.browser.newContext({ baseURL: f.baseURL, serviceWorkers: 'block' })
    try {
      const page = await staffContext.newPage(); const token = await f.login(page, context.accounts.staff)
      await page.goto('/mall/orders'); await page.getByPlaceholder('请输入订单号').fill(second.orderId)
      const pending = page.waitForResponse(r => r.url().includes('/admin-api/order/store-order/page') && r.url().includes(second.orderId))
      await page.getByRole('button', { name: '搜索', exact: true }).click()
      expect((await (await pending).json()).data.list).toEqual([])
      await expect(page.getByRole('cell', { name: `自取 ${second.orderId}`, exact: true })).toHaveCount(0)
      const id = f.oracle('order', { orderId: second.orderId })[0].id
      expect((await f.api('GET', `/admin-api/order/store-order/get?id=${id}`, undefined, token, false)).code).not.toBe(0)
      await f.viewOrder(first.orderId, undefined, page)
    } finally { await staffContext.close() }
    const storeBContext = await f.browser.newContext({ baseURL: f.baseURL, serviceWorkers: 'block' })
    try {
      const page = await storeBContext.newPage(); const token = await f.login(page, context.accounts.staffB)
      const id = f.oracle('order', { orderId: first.orderId })[0].id
      expect((await f.api('GET', `/admin-api/order/store-order/get?id=${id}`, undefined, token, false)).code).not.toBe(0)
      await f.viewOrder(second.orderId, undefined, page)
    } finally { await storeBContext.close() }
  })
  await f.check('other-member-denied', async () => { expect((await f.api('GET', `/app-api/order/detail/${first.orderId}`, undefined, other, false)).code).not.toBe(0) })
  await f.check('other-member-address-denied', async () => {
    const address = { realName: 'Synthetic owner', address: 'Synthetic street', detail: 'Synthetic unit', phone: '13800000001', isDefault: 1 }
    const id = await f.api('POST', '/app-api/address/addAndEdit', address, member)
    const before = f.oracle('address', { addressId: id })[0]
    const list = await f.api('GET', '/app-api/address/list?page=1&limit=100', undefined, other)
    expect(list.some((row: any) => String(row.id) === String(id))).toBe(false)
    for (const path of [`/app-api/address/default/set/${id}`, `/app-api/address/del/${id}`]) {
      expect((await f.api('POST', path, undefined, other, false)).code).toBe(1004004000)
    }
    expect((await f.api('POST', '/app-api/address/addAndEdit', { ...address, id: String(id), detail: 'forbidden change' }, other, false)).code).toBe(1004004000)
    expect(f.oracle('address', { addressId: id })[0]).toEqual(before)
  })
  await f.check('isolation-sql', () => {
    expect(f.oracle('inventory', { skuId: a.skus[0].id })[0].stock).toBe(9)
    expect(f.oracle('inventory', { skuId: b.skus[0].id })[0].stock).toBe(9)
  })
}

async function orderRetry(f: Flow) {
  const product = await f.createProduct(), token = await f.member()
  let order: any, page: any, body: any
  await f.check('first-response-lost', async () => {
    if (local) {
      await f.mini.selectStore('QA Shop A'); await f.mini.addProduct(product.name); page = await f.mini.checkout()
      order = await f.mini.submit(page, { loseResponse: true })
      const state = await f.mini.state(); body = state.requests.filter((r: any) => r.path === '/app-api/order/create').at(-1).data
      expect(state.dropped).toBe(true)
    } else {
      body = { shopId: '101', mobile: '13800000001', couponId: '0', orderType: 'takein', productId: [String(product.id)],
        spec: [product.skus[0].sku], number: ['1'], payType: 'weixin', idempotencyKey: key(), choices: [{ version: product.version, selections: [] }] }
      await loseResponse(f, body, token)
    }
    expect(f.oracle('submission', { memberId: 101, key: body.idempotencyKey })[0].count).toBe(1)
  })
  if (local) await f.check('mini-retry-state', async () => {
    const state = await f.mini.mini.evaluate(() => ({ cart: wx.getStorageSync('cart'), submission: wx.getStorageSync('checkoutSubmission') }))
    expect(state.cart).toHaveLength(1); expect(state.submission).toBeTruthy()
  })
  await f.check('same-key-retry', async () => {
    if (local) {
      const original = order.orderId; order = await f.mini.retry(page); expect(order.orderId).toBe(original)
      const requests = (await f.mini.state()).requests.filter((r: any) => r.path === '/app-api/order/create')
      expect(requests).toHaveLength(2); expect(requests[0].data.idempotencyKey).toBe(requests[1].data.idempotencyKey)
    } else order = await f.api('POST', '/app-api/order/create', body, token)
  })
  await f.check('admin-single-order', async () => { const row = await f.viewOrder(order.orderId); await expect(row).toHaveCount(1) })
  await f.check('single-order-and-stock-sql', () => {
    const submission = f.oracle('submission', { memberId: 101, key: body.idempotencyKey })[0]
    expect(submission.count).toBe(1); expect(submission.orderId).toBe(order.orderId)
    expect(f.oracle('inventory', { skuId: product.skus[0].id })[0].stock).toBe(9)
  })
}

async function orderCancel(f: Flow) {
  const product = await f.createProduct(), token = await f.member()
  let order: any
  await f.check('order-created', async () => { order = await orderFor(f, product, token); expect(f.oracle('inventory', { skuId: product.skus[0].id })[0].stock).toBe(9) })
  if (local) await f.check('mini-cancel-page', async () => { await f.mini.cancel() })
  await f.check('order-cancelled', async () => {
    if (!local) await f.api('POST', '/app-api/order/cancel', { id: order.orderId }, token)
    expect(f.oracle('order', { orderId: order.orderId })[0].deleted).toBe(1)
    const detail = await f.api('GET', `/app-api/order/detail/${order.orderId}`, undefined, token, false)
    expect(detail.code).toBe(1008007000)
  })
  await f.check('admin-cancelled-order', async () => {
    await f.page.goto('/mall/orders')
    await f.page.getByPlaceholder('请输入订单号').fill(order.orderId)
    const pending = f.page.waitForResponse(r => r.url().includes('/admin-api/order/store-order/page') && r.url().includes(order.orderId))
    await f.page.getByRole('button', { name: '搜索', exact: true }).click()
    expect((await (await pending).json()).data.list).toEqual([])
    await expect(f.page.getByRole('cell', { name: `自取 ${order.orderId}`, exact: true })).toHaveCount(0)
    expect(f.oracle('order', { orderId: order.orderId })[0].deleted).toBe(1)
  })
  await f.check('repeat-cancel', async () => { await f.api('POST', '/app-api/order/cancel', { id: order.orderId }, token) })
  await f.check('stock-restored-once-sql', () => { expect(f.oracle('inventory', { skuId: product.skus[0].id })[0].stock).toBe(10) })
  await f.check('no-financial-records-sql', () => { expect(f.oracle('financial-records', { orderId: order.orderId })[0]).toEqual({ payments: 0, attempts: 0, bills: 0 }) })
}

async function couponFlow(f: Flow, complete: boolean) {
  const product = await f.createProduct(), token = await f.member()
  let coupon: any, right: any, order: any
  await f.check('admin-create-coupon', async () => { coupon = await f.createCouponUI(); expect(coupon.id).toBeTruthy() })
  if (local) await f.check('mini-claim-and-select', async () => {
    await f.mini.selectStore('QA Shop A'); right = await issue(f, coupon, token)
    await f.mini.selectStore('QA Shop A'); await f.mini.addProduct(product.name)
    let page = await f.mini.checkout(); page = await f.mini.selectCoupon(page, coupon.title)
    expect(await f.mini.amount(page)).toContain('14.00'); order = await f.mini.submit(page)
  })
  const claimCheck = complete ? 'coupon-claimed' : 'claim-limit'
  await f.check(claimCheck, async () => {
    if (!local) right = await issue(f, coupon, token)
    const rejected = await f.api('POST', '/app-api/coupon/receive', { id: coupon.id, requestKey: key() }, token, false)
    expect(rejected.code).not.toBe(0)
    expect(f.oracle('coupon-count', { couponId: coupon.id, memberId: 101 })[0].count).toBe(1)
  })
  await f.check(complete ? 'discounted-order' : 'coupon-discount', async () => {
    if (!local) order = await f.createOrder(product, token, { couponId: String(right.id) })
    expect(Number(f.oracle('order', { orderId: order.orderId })[0].amount)).toBe(14)
  })
  if (complete) {
    await f.check('synthetic-completion', () => { expect(f.bridge('complete', { orderId: order.orderId }).result).toBe('FIRST_SUCCESS') })
    await f.check('duplicate-completion', () => { expect(f.bridge('complete', { orderId: order.orderId }).result).toBe('IDEMPOTENT_DUPLICATE') })
    await f.check('admin-paid-order', async () => { const row = await f.viewOrder(order.orderId); await expect(row).not.toContainText('未支付'); await expect(row).toContainText('14') })
    await f.check('coupon-redeemed', () => { const row = f.oracle('coupon-right', { rightId: right.id })[0]; expect(row.status).toBe(1); expect(row.redeemedOrderId).toBe(order.orderId); expect(row.redeemedAt).toBeTruthy() })
    if (local) await f.check('mini-redeemed-coupon', async () => { expect(await f.mini.couponText(coupon.title)).toContain('已核销') })
    await f.check('completion-ledger-sql', () => {
      const record = f.oracle('financial-records', { orderId: order.orderId })[0]
      expect(record).toEqual({ payments: 1, attempts: 1, bills: 1 })
      const operations = f.oracle('coupon-operations', { couponId: coupon.id })
      expect(operations.find((r: any) => r.kind === 'REDEEM').count).toBe(1)
      expect(operations.find((r: any) => r.kind === 'RELEASE')).toBeUndefined()
      expect(f.oracle('inventory', { skuId: product.skus[0].id })[0].stock).toBe(9)
    })
  } else {
    await f.check('coupon-reserved-sql', () => { const row = f.oracle('coupon-right', { rightId: right.id })[0]; expect(row.status).toBe(1); expect(row.reservedOrderId).toBe(order.orderId) })
    await f.check('cancel-releases-once', async () => {
      if (local) await f.mini.cancel(); else await f.api('POST', '/app-api/order/cancel', { id: order.orderId }, token)
      await f.api('POST', '/app-api/order/cancel', { id: order.orderId }, token)
      expect(f.oracle('coupon-right', { rightId: right.id })[0].status).toBe(0)
      expect(f.oracle('coupon-operations', { couponId: coupon.id }).find((r: any) => r.kind === 'RELEASE').count).toBe(1)
    })
    if (local) await f.check('mini-released-coupon', async () => { expect(await f.mini.couponText(coupon.title)).toContain('立即使用') })
    await f.check('admin-disable-coupon', async () => { await f.disableCouponUI(coupon) })
    await f.check('issued-rights-preserved', () => { expect(f.oracle('coupon-right', { rightId: right.id })[0].status).toBe(0) })
    await f.check('new-claim-denied', async () => { expect((await f.api('POST', '/app-api/coupon/receive', { id: coupon.id, requestKey: key() }, await f.member(2), false)).code).not.toBe(0) })
  }
}

const scenarios: Record<string, (flow: Flow) => Promise<void>> = {
  'auth-session': authSession, 'catalog-order': catalogOrder, 'store-isolation': storeIsolation,
  'order-retry': orderRetry, 'order-cancel': orderCancel,
  'coupon-release': flow => couponFlow(flow, false), 'coupon-complete': flow => couponFlow(flow, true)
}
for (const selected of context.planned) test(selected.id, async ({ flow }) => { await scenarios[selected.id](flow) })
