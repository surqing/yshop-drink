import { randomBytes } from 'node:crypto'
import { readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { createServer } from 'node:http'
import { test, expect, context, Flow } from './flow'
const key = () => randomBytes(16).toString('hex')
const local = context.profile === 'local'
const { assertCatalogSnapshot, assertFinancialDetails } = require(join(context.repo, 'tests/quality/e2e/assertions.cjs'))

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
  const displayedAmount = await f.mini.amount(page)
  const order = await f.mini.submit(page)
  const state = await f.mini.state()
  return { ...order, displayedAmount, body: state.requests.filter((r: any) => r.path === '/app-api/order/create').at(-1).data }
}
async function loseResponse(f: Flow, body: any, token: string, path = '/app-api/order/create') {
  let committed = false
  const server = createServer(async (request, response) => {
    if (request.method !== 'POST' || request.url !== '/owned-order') { response.writeHead(404); response.end(); return }
    try {
      const result = await fetch(`${context.backend}${path}`, { method: 'POST',
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
      // Token issuance precedes the login page's delayed navigateBack. Let that
      // real transition finish before starting a competing protected navigation.
      await expect.poll(async () => (await f.mini.mini.currentPage()).path).not.toBe('pages-user/login/login')
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
  let product: any, order: any, historicalLines: any
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
    const group = product.configuration.groups[0], option = group.options[0]
    const expected = { productId: product.id, shopId: 101, skuId: product.skus[0].id, sku: product.skus[0].sku,
      title: product.name, catalogVersion: product.version, groupId: group.id, groupName: group.name, optionId: option.id, optionName: option.name }
    assertCatalogSnapshot(detail.cartInfo, expected)
    historicalLines = f.oracle('order-lines', { orderId: order.orderId })
    assertCatalogSnapshot(historicalLines, expected)
    expect(detail.cartInfo.map((line: any) => JSON.parse(line.cartInfo))).toEqual(historicalLines.map((line: any) => JSON.parse(line.cartInfo)))
  })
  await f.check('admin-order-visible', async () => { await f.viewOrder(order.orderId, '未支付') })
  await f.check('catalog-edit-keeps-snapshot', async () => {
    await f.changePriceUI(product, 25)
    expect(Number(f.oracle('order', { orderId: order.orderId })[0].amount)).toBe(42)
    expect(Number(f.oracle('inventory', { skuId: product.skus[0].id })[0].price)).toBe(25)
    expect(f.oracle('order-lines', { orderId: order.orderId })).toEqual(historicalLines)
    const detail = await f.api('GET', `/app-api/order/detail/${order.orderId}`, undefined, token)
    expect(detail.cartInfo.map((line: any) => JSON.parse(line.cartInfo))).toEqual(historicalLines.map((line: any) => JSON.parse(line.cartInfo)))
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

async function batchStockOperations(f: Flow) {
  let product: any, skuId = 0, committed: any, forwarded: any, beforeFinancial: any
  let accessProduct: any, accessSkuId = 0, accessToken = '', accessKey = '', accessBatchId = ''
  let assignmentProduct: any, assignmentSkuId = 0, assignmentKey = '', assignmentBatchId = ''
  let peerToken = '', assignmentToken = '', queryToken = ''
  let commitMode: 'reject-business' | 'drop-response' | 'forward' = 'forward'
  let droppedResponseApplyRequests = 0
  let pendingStorageKey: string | null = null
  let pendingRequestBody: any
  const stockLogin = async (account: any) => {
    const response = await f.request.post(`${context.backend}/admin-api/system/auth/login`, {
      data: { username: account.username, password: account.password }
    })
    expect(response.status()).toBe(200)
    const body = await response.json()
    expect(body.code, JSON.stringify({ code: body.code, msg: body.msg })).toBe(0)
    return body.data.accessToken as string
  }
  // GET callers pass the token as the third argument; POST callers pass
  // (data, token). Keep that established call convention explicit here.
  const stockHttp = async (method: string, path: string, dataOrToken: unknown, token?: string) => {
    const requestToken = token ?? String(dataOrToken)
    const data = token === undefined ? undefined : dataOrToken
    const response = await f.request.fetch(`${context.backend}${path}`, {
      method, data, headers: { Authorization: `Bearer ${requestToken}` }, timeout: 30000
    })
    const raw = await response.text()
    let body: any = null
    try { body = JSON.parse(raw) } catch { /* A missing JSON body is rejected below for success cases. */ }
    return { status: response.status(), body, raw }
  }
  const expectStockDenied = (response: { status: number; body: any; raw: string }, names: string[] = []) => {
    expect(response.status === 401 || response.status === 403 ||
      (Number.isInteger(response.body?.code) && response.body.code !== 0 && response.body.code !== 500),
      JSON.stringify({ status: response.status, code: response.body?.code, msg: response.body?.msg })).toBe(true)
    expect(response.body?.code).not.toBe(0)
    expect(response.body?.code).not.toBe(500)
    for (const name of names.filter(Boolean)) expect(response.raw).not.toContain(name)
  }
  const deniedStockOperations = async (token: string, key: string, target: any, names: string[],
                                      resultPolicy: 'deny' | 'masked-or-deny' = 'masked-or-deny') => {
    const line = { productId: target.id, skuId: Number(target.skus[0].id), expectedStock: 10, delta: 1 }
    const body = { shopId: 101, lines: [line], reason: 'R4 authorization boundary', key }
    const preview = await stockHttp('POST', '/admin-api/product/catalog/stock-batches/preview', body, token)
    const replay = await stockHttp('POST', '/admin-api/product/catalog/stock-batches', body, token)
    const newKey = await stockHttp('POST', '/admin-api/product/catalog/stock-batches', { ...body, key: key + '_new' }, token)
    const result = await stockHttp('GET', `/admin-api/product/catalog/stock-batches/result?key=${encodeURIComponent(key)}`, token)
    for (const response of [preview, replay, newKey]) expectStockDenied(response, names)
    // Result lookup is either method-denied (query-only) or masks another
    // account's key as unconfirmed (an account with batch-operation access).
    if (resultPolicy === 'deny' || result.status === 401 || result.status === 403 ||
        (Number.isInteger(result.body?.code) && result.body.code !== 0 && result.body.code !== 500)) {
      expectStockDenied(result, names)
    } else {
      expect(result.status).toBe(200)
      expect(result.body).toMatchObject({ code: 0, data: { status: 'UNCONFIRMED', batch: null } })
    }
    for (const name of names.filter(Boolean)) expect(result.raw).not.toContain(name)
  }
  await f.check('batch-stock-fixture-created', async () => {
    product = await f.createProduct(101, 'batch-stock', 19)
    skuId = Number(product.skus[0].id)
    expect(f.oracle('batch-stock-product', { productId: product.id, skuId })[0]).toMatchObject({
      shopId: 101, productStock: 10, skuStock: 10, batchCount: 0, batchOperationCount: 0
    })
    beforeFinancial = f.oracle('batch-stock-financial', {})[0]
  })

  await f.check('batch-stock-api-rejects-non-integer-quantities', async () => {
    const invalidLines = [
      { expectedStock: '10', delta: 1 },
      { expectedStock: true, delta: 1 },
      { expectedStock: 10.5, delta: 1 },
      { expectedStock: 2147483648, delta: 1 },
      { expectedStock: 10, delta: '1' },
      { expectedStock: 10, delta: false },
      { expectedStock: 10, delta: 1.5 },
      { expectedStock: 10, delta: 2147483648 }
    ]
    for (const invalid of invalidLines) {
      const response = await f.api('POST', '/admin-api/product/catalog/stock-batches/preview', {
        shopId: 101, lines: [{ productId: product.id, skuId, ...invalid }], reason: 'strict API integers'
      }, f.adminToken, false)
      expect(response.code).not.toBe(0)
    }
    expect(f.oracle('batch-stock-product', { productId: product.id, skuId })[0]).toMatchObject({
      productStock: 10, skuStock: 10, batchCount: 0, batchOperationCount: 0
    })
    expect(f.oracle('batch-stock-financial', {})[0]).toEqual(beforeFinancial)
  })

  await f.check('batch-stock-preview-on-real-page', async () => {
    await f.page.goto('/mall/products')
    const shopItem = f.page.locator('.el-form-item').filter({ hasText: '门店' }).first()
    await shopItem.locator('.el-select').click()
    await f.page.getByRole('option', { name: 'QA Shop A', exact: true }).click()
    await f.page.getByPlaceholder('请输入商品名称', { exact: true }).fill(product.name)
    const pageLoad = f.page.waitForResponse(response => response.url().includes('/admin-api/product/store-product/page?'))
    await f.page.getByRole('button', { name: '搜索', exact: true }).click()
    await pageLoad
    const productRow = f.page.getByRole('row').filter({ hasText: product.name })
    await expect(productRow).toBeVisible()
    await productRow.locator('.el-checkbox__inner').click()
    await f.page.getByRole('button', { name: '批量库存调整', exact: true }).click()
    const dialog = f.page.locator('.el-dialog:visible').filter({ hasText: '批量库存调整' })
    await expect(dialog).toBeVisible()
    const skuRow = dialog.getByRole('row').filter({ hasText: product.skus[0].sku })
    await skuRow.locator('.el-input-number input').fill('2')
    await dialog.getByPlaceholder('填写本批库存调整原因').fill('R4 E2 restock')
    const previewWait = f.page.waitForResponse(response => response.url().includes('/admin-api/product/catalog/stock-batches/preview') && response.request().method() === 'POST')
    await dialog.getByRole('button', { name: '预览调整结果', exact: true }).click()
    const response = await previewWait
    expect(response.status()).toBe(200)
    const body = await response.json()
    expect(body.code).toBe(0)
    expect(body.data).toMatchObject({ eligible: true, rows: [{ productId: product.id, skuId, currentStock: 10, expectedStock: 10, delta: 2, afterStock: 12, conflictCode: null }] })
    await expect(dialog).toContainText('可提交')
  })

  await f.check('batch-stock-http-200-business-error-is-not-success', async () => {
    const dialog = f.page.locator('.el-dialog:visible').filter({ hasText: '批量库存调整' })
    const endpoint = '**/admin-api/product/catalog/stock-batches'
    await f.page.route(endpoint, async route => {
      if (route.request().method() !== 'POST' || commitMode !== 'reject-business') return route.fallback()
      commitMode = 'drop-response'
      const rejected = { code: 500, msg: 'Synthetic business rejection', data: null }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rejected) })
    })
    const responseWait = f.page.waitForResponse(response => response.url().endsWith('/admin-api/product/catalog/stock-batches') && response.request().method() === 'POST')
    commitMode = 'reject-business'
    await dialog.getByRole('button', { name: '确认批量调整', exact: true }).click()
    const response = await responseWait
    expect(response.status()).toBe(200)
    expect((await response.json()).code).toBe(500)
    await expect(dialog).toContainText('服务端已拒绝本次调整')
    await expect(dialog).not.toContainText('已提交，共')
    expect(f.oracle('batch-stock-product', { productId: product.id, skuId })[0]).toMatchObject({
      productStock: 10, skuStock: 10, batchCount: 0, batchOperationCount: 0
    })
    await dialog.locator('.el-input-number input').fill('3')
    const previewWait = f.page.waitForResponse(response => response.url().includes('/admin-api/product/catalog/stock-batches/preview') && response.request().method() === 'POST')
    await dialog.getByRole('button', { name: '预览调整结果', exact: true }).click()
    const previewResponse = await previewWait
    expect(previewResponse.status()).toBe(200)
    expect((await previewResponse.json()).data).toMatchObject({ eligible: true, rows: [{ delta: 3, afterStock: 13 }] })
  })

  await f.check('batch-stock-lost-response-recovers-by-original-key', async () => {
    const dialog = f.page.locator('.el-dialog:visible').filter({ hasText: '批量库存调整' })
    await f.page.route('**/admin-api/product/catalog/stock-batches', async route => {
      if (route.request().method() !== 'POST' || commitMode !== 'drop-response') return route.fallback()
      droppedResponseApplyRequests++
      const body = route.request().postDataJSON()
      const requestUrl = new URL(route.request().url())
      const response = await route.fetch({ url: `${context.backend}${requestUrl.pathname}${requestUrl.search}` })
      expect(response.status()).toBe(200)
      const result = await response.json()
      expect(result.code).toBe(0)
      expect(result.data.state).toBe('APPLIED')
      forwarded = { key: body.key, result: result.data }
      await route.fulfill({ status: 200, contentType: 'application/json', body: '' })
    })
    const applyRequest = f.page.waitForRequest(request => request.url().endsWith('/admin-api/product/catalog/stock-batches') && request.method() === 'POST')
    await dialog.getByRole('button', { name: '确认批量调整', exact: true }).click()
    const submitted = await applyRequest
    expect(submitted.postDataJSON().lines).toEqual([{ productId: product.id, skuId, expectedStock: 10, delta: 3 }])
    await expect(dialog).toContainText('提交响应未确认')
    await expect(dialog).not.toContainText('已提交，共')
    expect(forwarded?.key).toBe(submitted.postDataJSON().key)
    committed = forwarded.result
    expect(f.oracle('batch-stock-state', { batchId: forwarded.result.batchId, productId: product.id, skuId })[0]).toMatchObject({
      batchId: forwarded.result.batchId, shopId: 101, state: 'APPLIED', skuStock: 13, productStock: 13, batchRows: 1,
      operationRows: 1, operationDelta: 3, beforeStock: 10, afterStock: 13
    })

    pendingStorageKey = await f.page.evaluate(() => {
      for (let index = 0; index < sessionStorage.length; index++) {
        const candidate = sessionStorage.key(index)
        if (candidate?.startsWith('batch-stock-pending-v1:')) return candidate
      }
      return null
    })
    expect(pendingStorageKey).toBeTruthy()
    const savedRequest = JSON.parse(await f.page.evaluate(key => sessionStorage.getItem(key!), pendingStorageKey!))
    expect(savedRequest).toMatchObject({ key: submitted.postDataJSON().key, userId: 101, shopId: 101,
      body: { shopId: 101, lines: [{ productId: product.id, skuId, expectedStock: 10, delta: 3 }], reason: 'R4 E2 restock' } })
    pendingRequestBody = savedRequest.body
    await f.page.evaluate(key => {
      const request = JSON.parse(sessionStorage.getItem(key!)!)
      request.savedAt = Date.now() + 60_000
      sessionStorage.setItem(key!, JSON.stringify(request))
    }, pendingStorageKey!)
    const rollbackPending = JSON.parse(await f.page.evaluate(key => sessionStorage.getItem(key!), pendingStorageKey!))
    expect(rollbackPending.key).toBe(submitted.postDataJSON().key)
    expect(rollbackPending.body).toEqual(pendingRequestBody)
    expect(rollbackPending.savedAt).toBeGreaterThan(Date.now())
  })

  await f.check('batch-stock-reopen-after-clock-rollback-recovers-original-key', async () => {
    expect(pendingStorageKey).toBeTruthy()
    const storageKey = pendingStorageKey!
    const pendingBeforeReload = JSON.parse(await f.page.evaluate(key => sessionStorage.getItem(key!), storageKey))
    expect(pendingBeforeReload.key).toBe(forwarded.key)
    expect(pendingBeforeReload.body).toEqual(pendingRequestBody)
    await f.page.reload()
    const shopItem = f.page.locator('.el-form-item').filter({ hasText: '门店' }).first()
    await shopItem.locator('.el-select').click()
    await f.page.getByRole('option', { name: 'QA Shop A', exact: true }).click()
    await f.page.getByPlaceholder('请输入商品名称', { exact: true }).fill(product.name)
    const pageLoad = f.page.waitForResponse(response => response.url().includes('/admin-api/product/store-product/page?'))
    await f.page.getByRole('button', { name: '搜索', exact: true }).click()
    await pageLoad
    const productRow = f.page.getByRole('row').filter({ hasText: product.name })
    await expect(productRow).toBeVisible()
    await productRow.locator('.el-checkbox__inner').click()
    const resultWait = f.page.waitForResponse(response => response.url().includes('/admin-api/product/catalog/stock-batches/result?') && response.request().method() === 'GET')
    await f.page.getByRole('button', { name: '批量库存调整', exact: true }).click()
    const recovered = await resultWait
    expect(recovered.status()).toBe(200)
    const recoveredBody = await recovered.json()
    expect(recoveredBody.code).toBe(0)
    expect(recoveredBody.data).toMatchObject({ status: 'CONFIRMED', batch: { batchId: forwarded.result.batchId, state: 'APPLIED' } })
    expect(droppedResponseApplyRequests).toBe(1)
    await expect(f.page.locator('.el-dialog:visible').filter({ hasText: '批量库存调整' }))
      .toContainText(`批次 ${forwarded.result.batchId} 已提交`)
    expect(await f.page.evaluate(key => sessionStorage.getItem(key!), storageKey)).toBeNull()
    expect(f.oracle('batch-stock-state', { batchId: forwarded.result.batchId, productId: product.id, skuId })[0]).toMatchObject({
      batchId: forwarded.result.batchId, shopId: 101, state: 'APPLIED', skuStock: 13, productStock: 13, batchRows: 1,
      operationRows: 1, operationDelta: 3, beforeStock: 10, afterStock: 13
    })
  })

  await f.check('batch-stock-history-page-links-batch-and-line-delta', async () => {
    const dialog = f.page.locator('.el-dialog:visible').filter({ hasText: '批量库存调整' })
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
    const productRow = f.page.getByRole('row').filter({ hasText: product.name })
    const historyWait = f.page.waitForResponse(response => response.url().includes('/admin-api/product/catalog/history?') && response.request().method() === 'GET')
    await productRow.getByRole('button', { name: '经营设置', exact: true }).click()
    const catalogDialog = f.page.locator('.el-dialog:visible').filter({ hasText: '商品经营设置' })
    await catalogDialog.getByText('规格价格与库存', { exact: true }).click()
    const historyResponse = await historyWait
    expect(historyResponse.status()).toBe(200)
    const history = await historyResponse.json()
    expect(history.code).toBe(0)
    expect(history.data).toEqual(expect.arrayContaining([expect.objectContaining({ batch_id: forwarded.result.batchId, delta_value: 3, reason: 'R4 E2 restock' })]))
    await expect(catalogDialog).toContainText(forwarded.result.batchId)
    await expect(catalogDialog).toContainText('R4 E2 restock')
  })

  await f.check('batch-stock-shop-switch-ignores-late-preview', async () => {
    const catalogDialog = f.page.locator('.el-dialog:visible').filter({ hasText: '商品经营设置' })
    await catalogDialog.locator('.el-dialog__header .cursor-pointer').last().click()
    const shopBProduct = await f.createProduct(102, 'shop-b-stock', 19)
    const shopFilter = f.page.locator('.el-form-item').filter({ hasText: '门店' }).first()
    const searchFor = async (shopName: string, productName: string) => {
      await shopFilter.locator('.el-select').click()
      await f.page.getByRole('option', { name: shopName, exact: true }).click()
      await f.page.getByPlaceholder('请输入商品名称', { exact: true }).fill(productName)
      const pageLoad = f.page.waitForResponse(response => response.url().includes('/admin-api/product/store-product/page?'))
      await f.page.getByRole('button', { name: '搜索', exact: true }).click()
      await pageLoad
    }
    await searchFor('QA Shop A', product.name)
    const rowA = f.page.getByRole('row').filter({ hasText: product.name })
    await expect(rowA).toBeVisible()
    await rowA.locator('.el-checkbox__inner').click()
    await f.page.getByRole('button', { name: '批量库存调整', exact: true }).click()

    let releaseSlow: () => void = () => {}
    let signalSlowReady: () => void = () => {}
    let signalSlowFinished: () => void = () => {}
    const slowGate = new Promise<void>(resolve => { releaseSlow = resolve })
    const slowReady = new Promise<void>(resolve => { signalSlowReady = resolve })
    const slowFinished = new Promise<void>(resolve => { signalSlowFinished = resolve })
    const previewEndpoint = '**/admin-api/product/catalog/stock-batches/preview'
    let held = false
    await f.page.route(previewEndpoint, async route => {
      if (held || route.request().method() !== 'POST') return route.fallback()
      held = true
      const requestUrl = new URL(route.request().url())
      const response = await route.fetch({ url: `${context.backend}${requestUrl.pathname}${requestUrl.search}` })
      signalSlowReady()
      await slowGate
      await route.fulfill({ response })
      signalSlowFinished()
    })
    try {
      const dialogA = f.page.locator('.el-dialog:visible').filter({ hasText: '批量库存调整' })
      await dialogA.locator('.el-input-number input').fill('1')
      await dialogA.getByPlaceholder('填写本批库存调整原因').fill('late preview A')
      await dialogA.getByRole('button', { name: '预览调整结果', exact: true }).click()
      await slowReady
      await dialogA.getByRole('button', { name: '关闭', exact: true }).click()

      await searchFor('QA Shop B', shopBProduct.name)
      const rowB = f.page.getByRole('row').filter({ hasText: shopBProduct.name })
      await expect(rowB).toBeVisible()
      await rowB.locator('.el-checkbox__inner').click()
      await f.page.getByRole('button', { name: '批量库存调整', exact: true }).click()
      const dialogB = f.page.locator('.el-dialog:visible').filter({ hasText: '批量库存调整' })
      const skuRowB = dialogB.getByRole('row').filter({ hasText: shopBProduct.skus[0].sku })
      await expect(skuRowB).toBeVisible()
      await skuRowB.locator('.el-input-number input').fill('1')
      await dialogB.getByPlaceholder('填写本批库存调整原因').fill('active preview B')
      const previewB = f.page.waitForResponse(response => response.url().includes('/admin-api/product/catalog/stock-batches/preview') && response.request().method() === 'POST')
      await dialogB.getByRole('button', { name: '预览调整结果', exact: true }).click()
      const responseB = await previewB
      expect(responseB.status()).toBe(200)
      expect((await responseB.json()).data.rows).toMatchObject([{ productId: shopBProduct.id, skuId: shopBProduct.skus[0].id, currentStock: 10, expectedStock: 10, delta: 1, afterStock: 11 }])
      await expect(dialogB).toContainText('可提交')

      const lateResponse = f.page.waitForResponse(response => {
        if (!response.url().includes('/admin-api/product/catalog/stock-batches/preview') || response.request().method() !== 'POST') return false
        return response.request().postDataJSON().lines?.[0]?.productId === product.id
      })
      releaseSlow()
      const responseA = await lateResponse
      expect(responseA.status()).toBe(200)
      expect((await responseA.json()).data.rows).toMatchObject([{ productId: product.id, skuId, currentStock: 13, expectedStock: 13, delta: 1, afterStock: 14 }])
      await slowFinished
      const previewRowsB = dialogB.locator('.el-table').last().locator('.el-table__row')
      await expect(previewRowsB).toHaveCount(1)
      await expect(previewRowsB.first()).toContainText(shopBProduct.skus[0].sku)
      await expect(previewRowsB.first()).toContainText(shopBProduct.name)
      await expect(previewRowsB.first()).not.toContainText(product.name)
      expect(f.oracle('batch-stock-product', { productId: shopBProduct.id, skuId: shopBProduct.skus[0].id })[0]).toMatchObject({
        shopId: 102, productStock: 10, skuStock: 10, batchCount: 0, batchOperationCount: 0
      })
    } finally {
      releaseSlow()
      await f.page.unroute(previewEndpoint)
    }
  })

  await f.check('batch-stock-http-writer-authorized-preview-apply-result', async () => {
    accessProduct = await f.createProduct(101, 'batch-stock-authorized-user', 19)
    accessSkuId = Number(accessProduct.skus[0].id)
    const initial = f.oracle('batch-stock-product', { productId: accessProduct.id, skuId: accessSkuId })[0]
    expect(initial).toMatchObject({ shopId: 101, productStock: 10, skuStock: 10, batchCount: 0, batchOperationCount: 0 })
    accessToken = await stockLogin(context.accounts.stockWriter)
    const permissionInfo = await f.api('GET', '/admin-api/system/auth/get-permission-info', undefined, accessToken)
    expect(permissionInfo.user.id).toBe(910120)
    expect(permissionInfo.permissions).toContain('shop:store-product:query')
    expect(permissionInfo.permissions).toContain('shop:store-product:update')
    const request = { shopId: 101, lines: [{ productId: accessProduct.id, skuId: accessSkuId, expectedStock: 10, delta: 1 }],
      reason: 'R4 authorized HTTP acceptance' }
    const preview = await stockHttp('POST', '/admin-api/product/catalog/stock-batches/preview', request, accessToken)
    expect(preview.status).toBe(200)
    expect(preview.body).toMatchObject({ code: 0, data: { eligible: true,
      rows: [{ productId: accessProduct.id, skuId: accessSkuId, currentStock: 10, expectedStock: 10, delta: 1, afterStock: 11 }] } })
    accessKey = key()
    const applied = await stockHttp('POST', '/admin-api/product/catalog/stock-batches', { ...request, key: accessKey }, accessToken)
    expect(applied.status).toBe(200)
    expect(applied.body).toMatchObject({ code: 0, data: { state: 'APPLIED', shopId: 101,
      lines: [{ productId: accessProduct.id, skuId: accessSkuId, beforeStock: 10, delta: 1, afterStock: 11 }] } })
    accessBatchId = applied.body.data.batchId
    expect(accessBatchId).toBeTruthy()
    const result = await stockHttp('GET', `/admin-api/product/catalog/stock-batches/result?key=${encodeURIComponent(accessKey)}`, accessToken)
    expect(result.status).toBe(200)
    expect(result.body).toMatchObject({ code: 0, data: { status: 'CONFIRMED', batch: { batchId: accessBatchId, state: 'APPLIED' } } })
    expect(f.oracle('batch-stock-product', { productId: accessProduct.id, skuId: accessSkuId })[0]).toMatchObject({
      shopId: 101, productStock: 11, skuStock: 11, batchCount: 1, batchOperationCount: 1
    })
  })

  await f.check('batch-stock-http-same-token-revocation-denies-all-batch-operations', async () => {
    const before = f.oracle('batch-stock-product', { productId: accessProduct.id, skuId: accessSkuId })[0]
    const financialBefore = f.oracle('batch-stock-financial', {})[0]
    await f.api('POST', '/admin-api/system/permission/assign-role-menu', { roleId: 910120, menuIds: [3111] }, f.adminToken)
    const line = { productId: accessProduct.id, skuId: accessSkuId, expectedStock: 11, delta: 1 }
    const probe = { shopId: 101, lines: [line], reason: 'R4 revoked HTTP acceptance' }
    expectStockDenied(await stockHttp('POST', '/admin-api/product/catalog/stock-batches/preview', probe, accessToken), [accessProduct.name, accessBatchId])
    expectStockDenied(await stockHttp('POST', '/admin-api/product/catalog/stock-batches', { ...probe, key: accessKey }, accessToken), [accessProduct.name, accessBatchId])
    expectStockDenied(await stockHttp('POST', '/admin-api/product/catalog/stock-batches', { ...probe, key: key() }, accessToken), [accessProduct.name, accessBatchId])
    expectStockDenied(await stockHttp('GET', `/admin-api/product/catalog/stock-batches/result?key=${encodeURIComponent(accessKey)}`, accessToken), [accessProduct.name, accessBatchId])
    const history = await stockHttp('GET', `/admin-api/product/catalog/history?productId=${accessProduct.id}`, accessToken)
    expect(history.status).toBe(200)
    expect(history.body).toMatchObject({ code: 0 })
    expect(history.body.data).toEqual(expect.arrayContaining([expect.objectContaining({ batch_id: accessBatchId })]))
    expect(f.oracle('batch-stock-product', { productId: accessProduct.id, skuId: accessSkuId })[0]).toEqual(before)
    expect(f.oracle('batch-stock-financial', {})[0]).toEqual(financialBefore)
  })

  await f.check('batch-stock-http-shop-assignment-revocation-denies-all-batch-operations', async () => {
    assignmentProduct = await f.createProduct(101, 'batch-stock-assignment-revocation', 19)
    assignmentSkuId = Number(assignmentProduct.skus[0].id)
    assignmentToken = await stockLogin(context.accounts.stockAssignment)
    expect((await f.api('GET', '/admin-api/system/auth/get-permission-info', undefined, assignmentToken)).user.id).toBe(910123)
    assignmentKey = key()
    const assignmentApply = await stockHttp('POST', '/admin-api/product/catalog/stock-batches', {
      shopId: 101, lines: [{ productId: assignmentProduct.id, skuId: assignmentSkuId, expectedStock: 10, delta: 1 }],
      reason: 'R4 assignment revocation old result', key: assignmentKey
    }, assignmentToken)
    expect(assignmentApply.status).toBe(200)
    expect(assignmentApply.body).toMatchObject({ code: 0, data: { state: 'APPLIED', shopId: 101,
      lines: [{ productId: assignmentProduct.id, skuId: assignmentSkuId, beforeStock: 10, delta: 1, afterStock: 11 }] } })
    assignmentBatchId = assignmentApply.body.data.batchId
    expect(assignmentBatchId).toBeTruthy()
    const shop = await f.api('GET', '/admin-api/store/shop/get?id=101')
    expect(shop.adminId.map(String)).toContain('910123')
    const updatedAdmins = shop.adminId.map(String).filter((id: string) => id !== '910123')
    await f.api('PUT', '/admin-api/store/shop/update', { ...shop, id: 101, adminId: updatedAdmins }, f.adminToken)
    const updatedShop = await f.api('GET', '/admin-api/store/shop/get?id=101')
    expect(updatedShop.adminId.map(String)).not.toContain('910123')
    const before = f.oracle('batch-stock-product', { productId: assignmentProduct.id, skuId: assignmentSkuId })[0]
    const financialBefore = f.oracle('batch-stock-financial', {})[0]
    await deniedStockOperations(assignmentToken, assignmentKey, assignmentProduct,
      [assignmentProduct.name, assignmentBatchId], 'deny')
    expect(f.oracle('batch-stock-product', { productId: assignmentProduct.id, skuId: assignmentSkuId })[0]).toEqual(before)
    expect(f.oracle('batch-stock-financial', {})[0]).toEqual(financialBefore)
  })

  await f.check('batch-stock-http-query-only-cannot-use-batch-operations', async () => {
    queryToken = await stockLogin(context.accounts.stockQuery)
    expect((await f.api('GET', '/admin-api/system/auth/get-permission-info', undefined, queryToken)).user.id).toBe(910121)
    const history = await stockHttp('GET', `/admin-api/product/catalog/history?productId=${accessProduct.id}`, queryToken)
    expect(history.status).toBe(200)
    expect(history.body).toMatchObject({ code: 0 })
    await deniedStockOperations(queryToken, accessKey, accessProduct, [accessProduct.name, accessBatchId])
    expect(f.oracle('batch-stock-product', { productId: accessProduct.id, skuId: accessSkuId })[0]).toMatchObject({
      shopId: 101, productStock: 11, skuStock: 11, batchCount: 1, batchOperationCount: 1
    })
  })

  await f.check('batch-stock-http-other-account-cannot-read-batch-result', async () => {
    peerToken = await stockLogin(context.accounts.stockPeer)
    expect((await f.api('GET', '/admin-api/system/auth/get-permission-info', undefined, peerToken)).user.id).toBe(910122)
    const result = await stockHttp('GET', `/admin-api/product/catalog/stock-batches/result?key=${encodeURIComponent(accessKey)}`, peerToken)
    expect(result.status).toBe(200)
    expect(result.body).toMatchObject({ code: 0, data: { status: 'UNCONFIRMED', batch: null } })
    expect(result.raw).not.toContain(accessBatchId)
    expect(result.raw).not.toContain(accessProduct.name)
  })

  await f.check('batch-stock-http-cross-store-mixed-request-is-denied', async () => {
    const foreign = await f.createProduct(102, 'batch-stock-cross-store-target', 19)
    peerToken ||= await stockLogin(context.accounts.stockPeer)
    const beforeLocal = f.oracle('batch-stock-product', { productId: accessProduct.id, skuId: accessSkuId })[0]
    const beforeForeign = f.oracle('batch-stock-product', { productId: foreign.id, skuId: Number(foreign.skus[0].id) })[0]
    const mixed = { shopId: 101, reason: 'R4 cross-store authorization', lines: [
      { productId: accessProduct.id, skuId: accessSkuId, expectedStock: 11, delta: 1 },
      { productId: foreign.id, skuId: Number(foreign.skus[0].id), expectedStock: 10, delta: 1 }
    ] }
    expectStockDenied(await stockHttp('POST', '/admin-api/product/catalog/stock-batches/preview', mixed, peerToken), [foreign.name])
    expectStockDenied(await stockHttp('POST', '/admin-api/product/catalog/stock-batches', { ...mixed, key: key() }, peerToken), [foreign.name])
    expect(f.oracle('batch-stock-product', { productId: accessProduct.id, skuId: accessSkuId })[0]).toEqual(beforeLocal)
    expect(f.oracle('batch-stock-product', { productId: foreign.id, skuId: Number(foreign.skus[0].id) })[0]).toEqual(beforeForeign)
  })

  await f.check('batch-stock-sql-and-financial-invariants', () => {
    const row = f.oracle('batch-stock-state', { batchId: committed.batchId, productId: product.id, skuId })
    expect(row[0].state).toBe('APPLIED')
    expect(row[0]).toMatchObject({ batchRows: 1, operationRows: 1, operationDelta: 3, beforeStock: 10, afterStock: 13, skuStock: 13, productStock: 13 })
    expect(f.oracle('batch-stock-financial', {})[0]).toEqual(beforeFinancial)
  })
}

async function storeIsolation(f: Flow) {
  const a = await f.createProduct(101, 'isolation-a'), b = await f.createProduct(102, 'isolation-b', 20)
  const member = await f.member(), other = await f.member(2)
  let first: any, second: any, coupon: any, right: any
  await f.check('store-a-order', async () => { first = await orderFor(f, a, member); expect(f.oracle('order', { orderId: first.orderId })[0].shopId).toBe(101) })
  if (local) await f.check('mini-switch-clears-context', async () => {
    await f.mini.selectStore('QA Shop A')
    coupon = await f.createCouponUI('isolation-coupon'); right = await issue(f, coupon, member)
    await f.mini.selectStore('QA Shop A'); await f.mini.addProduct(a.name)
    let checkout = await f.mini.checkout(); checkout = await f.mini.selectCoupon(checkout, coupon.title)
    expect(await f.mini.amount(checkout)).toContain('14.00')
    await f.mini.selectStore('QA Shop B')
    const state = await f.mini.mini.evaluate(() => ({ cart: wx.getStorageSync('cart'), checkout: wx.getStorageSync('checkoutSubmission') }))
    expect(state.cart || []).toHaveLength(0); expect(state.checkout).toBeFalsy()
  })
  await f.check('store-b-order', async () => {
    second = await orderFor(f, b, member)
    const row = f.oracle('order', { orderId: second.orderId })[0]
    expect(row.shopId).toBe(102); expect(Number(row.amount)).toBe(20)
    if (local) expect(second.displayedAmount).toContain('20.00')
  })
  await f.check('cross-store-product-denied', async () => {
    const denied = await f.api('POST', '/app-api/order/create', { ...second.body, shopId: '101', idempotencyKey: key() }, member, false)
    expect(denied.code).not.toBe(0)
  })
  if (!local) {
    coupon = await f.createCouponUI('isolation-coupon')
    await f.api('POST', '/app-api/coupon/receive', { id: coupon.id, requestKey: key() }, member)
    right = await rights(f, coupon, member)
  }
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
      const ownId = f.oracle('order', { orderId: first.orderId })[0].id
      await f.api('PUT', '/admin-api/order/store-order/update', { id: ownId, orderId: first.orderId, uid: 101, updateType: 'remark', remark: 'owned-store-a' }, token)
      expect(f.oracle('order', { orderId: first.orderId })[0].remark).toBe('owned-store-a')
      const foreignBefore = f.oracle('order', { orderId: second.orderId })[0]
      expect((await f.api('PUT', '/admin-api/order/store-order/update', { id, orderId: second.orderId, uid: 101, updateType: 'remark', remark: 'forbidden-a-to-b' }, token, false)).code).toBe(403)
      expect(f.oracle('order', { orderId: second.orderId })[0]).toEqual(foreignBefore)
      await f.viewOrder(first.orderId, undefined, page)
    } finally { await staffContext.close() }
    const storeBContext = await f.browser.newContext({ baseURL: f.baseURL, serviceWorkers: 'block' })
    try {
      const page = await storeBContext.newPage(); const token = await f.login(page, context.accounts.staffB)
      const id = f.oracle('order', { orderId: first.orderId })[0].id
      expect((await f.api('GET', `/admin-api/order/store-order/get?id=${id}`, undefined, token, false)).code).not.toBe(0)
      const ownId = f.oracle('order', { orderId: second.orderId })[0].id
      await f.api('PUT', '/admin-api/order/store-order/update', { id: ownId, orderId: second.orderId, uid: 101, updateType: 'remark', remark: 'owned-store-b' }, token)
      expect(f.oracle('order', { orderId: second.orderId })[0].remark).toBe('owned-store-b')
      const foreignBefore = f.oracle('order', { orderId: first.orderId })[0]
      expect((await f.api('PUT', '/admin-api/order/store-order/update', { id, orderId: first.orderId, uid: 101, updateType: 'remark', remark: 'forbidden-b-to-a' }, token, false)).code).toBe(403)
      expect(f.oracle('order', { orderId: first.orderId })[0]).toEqual(foreignBefore)
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
  let coupon: any, right: any, order: any, firstFinancial: any
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
    await f.check('synthetic-completion', () => {
      expect(f.bridge('complete', { orderId: order.orderId }).result).toBe('FIRST_SUCCESS')
      firstFinancial = f.oracle('financial-details', { orderId: order.orderId })
      assertFinancialDetails(firstFinancial, order.orderId, 101, 1400)
    })
    await f.check('duplicate-completion', () => {
      expect(f.bridge('complete', { orderId: order.orderId }).result).toBe('IDEMPOTENT_DUPLICATE')
      expect(f.oracle('financial-details', { orderId: order.orderId })).toEqual(firstFinancial)
    })
    await f.check('admin-paid-order', async () => { const row = await f.viewOrder(order.orderId); await expect(row).not.toContainText('未支付'); await expect(row).toContainText('14') })
    await f.check('coupon-redeemed', () => { const row = f.oracle('coupon-right', { rightId: right.id })[0]; expect(row.status).toBe(1); expect(row.redeemedOrderId).toBe(order.orderId); expect(row.redeemedAt).toBeTruthy() })
    if (local) await f.check('mini-redeemed-coupon', async () => {
      const page = await f.mini.mini.navigateTo(`/pages-order/orders/detail?id=${order.orderId}`)
      await expect.poll(async () => (await page.$('.order-detail-page')).text()).toContain('实付金额')
      expect(await page.$('.order-cancel-button')).toBeFalsy()
      expect(await f.mini.couponText(coupon.title)).toContain('已核销')
    })
    await f.check('completion-ledger-sql', () => {
      const orderRow = f.oracle('order', { orderId: order.orderId })[0]
      expect(orderRow.paid).toBe(1); expect(Number(orderRow.amount)).toBe(14); expect(orderRow.deleted).toBe(0)
      const record = f.oracle('financial-records', { orderId: order.orderId })[0]
      expect(record).toEqual({ payments: 1, attempts: 1, bills: 1 })
      assertFinancialDetails(f.oracle('financial-details', { orderId: order.orderId }), order.orderId, 101, 1400)
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

async function pickupContact(f: Flow) {
  const product = await f.createProduct(), token = await f.member(), contact = '13800000999'
  const before = f.oracle('identity', { memberId: 101 })[0]
  let page: any, order: any, body: any
  if (local) await f.check('mini-contact-edited', async () => {
    await f.mini.selectStore('QA Shop A'); await f.mini.addProduct(product.name)
    page = await f.mini.checkout()
    const input = await page.$('.pay-page__contact-input'); expect(input).toBeTruthy()
    await input.input(contact)
    await expect.poll(async () => (await page.$('.pay-page__contact-input')).property('value')).toBe(contact)
    await f.mini.mini.navigateTo('/pages-checkout/remark/remark')
    page = await f.mini.mini.navigateBack()
    await expect.poll(async () => (await page.$('.pay-page__contact-input')).property('value')).toBe(contact)
  })
  await f.check('pickup-created', async () => {
    if (local) {
      order = await f.mini.submit(page)
      body = (await f.mini.state()).requests.filter((r: any) => r.path === '/app-api/order/create').at(-1).data
      expect(body.mobile).toBe(contact)
    } else { order = await f.createOrder(product, token, { mobile: contact }); body = order.body }
    expect(order.orderId).toBeTruthy()
  })
  await f.check('contact-sql', () => {
    expect(f.oracle('order-contact', { orderId: order.orderId })[0]).toEqual({ memberId: 101, userPhone: contact })
    expect(f.oracle('inventory', { skuId: product.skus[0].id })[0].stock).toBe(9)
  })
  await f.check('admin-contact', async () => {
    const row = await f.viewOrder(order.orderId)
    await row.getByRole('button', { name: '更多' }).hover()
    await f.page.getByRole('menuitem', { name: '订单详情', exact: true }).click()
    const drawer = f.page.locator('.el-drawer').filter({ visible: true } as any)
    await expect(drawer).toContainText('联系电话')
    await expect(drawer).toContainText(contact)
  })
  await f.check('same-key-contact-conflict', async () => {
    const denied = await f.api('POST', '/app-api/order/create', { ...body, mobile: '13800000888' }, token, false)
    expect(denied.msg).toBe('ORDER_IDEMPOTENCY_CONFLICT')
    expect(f.oracle('submission', { memberId: 101, key: body.idempotencyKey })[0].count).toBe(1)
    expect(f.oracle('order-contact', { orderId: order.orderId })[0].userPhone).toBe(contact)
  })
  await f.check('confirmed-submission', async () => {
    const result = await f.api('GET', '/app-api/order/submission-result?idempotencyKey=' + body.idempotencyKey, undefined, token)
    expect(result.orderId).toBe(order.orderId)
    const other = await f.member(2)
    expect((await f.api('GET', '/app-api/order/submission-result?idempotencyKey=' + body.idempotencyKey, undefined, other, false)).msg).toBe('ORDER_SUBMISSION_UNCONFIRMED')
    expect((await f.api('GET', '/app-api/order/submission-result?idempotencyKey=' + body.idempotencyKey, undefined, '', false)).code).not.toBe(0)
    expect(f.oracle('submission', { memberId: 101, key: body.idempotencyKey })[0].count).toBe(1)
  })
  await f.check('cancel-restores-once', async () => {
    await f.api('POST', '/app-api/order/cancel', { id: order.orderId }, token)
    await f.api('POST', '/app-api/order/cancel', { id: order.orderId }, token)
    expect(f.oracle('inventory', { skuId: product.skus[0].id })[0].stock).toBe(10)
  })
  await f.check('identity-sql', () => {
    expect(f.oracle('identity', { memberId: 101 })[0]).toEqual(before)
    expect(f.oracle('financial-records', { orderId: order.orderId })[0]).toEqual({ payments: 0, attempts: 0, bills: 0 })
  })
}

function shanghaiDate(date = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' })
    .formatToParts(date).reduce((values: Record<string, string>, part) => { values[part.type] = part.value; return values }, {})
  return `${parts.year}-${parts.month}-${parts.day}`
}
function nextIsoDate(value: string) {
  const [year, month, day] = value.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, day + 1)).toISOString().slice(0, 10)
}
async function openWorkPage(f: Flow, page: any, account: any) {
  await f.login(page, account)
  await page.goto('/mall/store-order-work')
  await expect(page.getByText('订单实付额（毛额）')).toBeVisible()
}
async function workOrder(page: any, orderId: string, tabLabel?: string) {
  if (tabLabel) {
    const tab = page.getByRole('tab', { name: tabLabel, exact: true })
    if (await tab.getAttribute('aria-selected') !== 'true') {
      const pendingTab = page.waitForResponse(r => r.url().includes('/admin-api/order/store-order/page') && r.request().method() === 'GET')
      await tab.click(); await pendingTab
    }
  }
  await page.getByPlaceholder('请输入订单号').fill(orderId)
  const pending = page.waitForResponse(r => r.url().includes('/admin-api/order/store-order/page') && r.request().method() === 'GET')
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  const result = await (await pending).json(); expect(result.code).toBe(0)
  const card = page.locator('.box-card').first()
  await expect(card).toBeVisible()
  return card
}
function fulfillmentState(f: Flow, orderId: string) { return f.oracle('fulfillment-state', { orderId })[0] }

async function pickupFulfillment(f: Flow) {
  let product: any, order: any, memberToken = '', collectRequest: any, beforeCollect: any
  const staffContexts: any[] = []
  let pageA: any, pageB: any, workOwner: any
  try {
    await f.check('product-created-for-points', async () => {
      product = await f.createProductUI(5)
      expect(Number(f.oracle('product', { productId: product.id })[0].giveIntegral)).toBeGreaterThan(0)
    })
    await f.check('pickup-order-created', async () => {
      memberToken = await f.member()
      if (local) {
        await f.mini.selectStore('QA Shop A'); await f.mini.addProduct(product.name)
        const checkout = await f.mini.checkout(); order = await f.mini.submit(checkout)
      } else order = await f.createOrder(product, memberToken)
      expect(order.orderId).toBeTruthy()
      expect(Number(f.oracle('order', { orderId: order.orderId })[0].paid)).toBe(0)
    })
    await f.check('payment-confirmed', () => {
      f.bridge('complete', { orderId: order.orderId })
      const state = fulfillmentState(f, order.orderId)
      expect(state.paid).toBe(1); expect(state.fulfillmentStatus).toBe('WAIT_ACCEPT'); expect(state.fulfillmentVersion).toBe(1)
    })
    if (local) await f.check('mini-initial-status', async () => {
      const detail = await f.mini.orderDetail(order.orderId)
      await expect.poll(async () => f.mini.pickupStatus(detail)).toContain('待接单')
    })
    const contextA = await f.browser.newContext({ baseURL: f.baseURL, serviceWorkers: 'block' }); staffContexts.push(contextA)
    const contextB = await f.browser.newContext({ baseURL: f.baseURL, serviceWorkers: 'block' }); staffContexts.push(contextB)
    pageA = await contextA.newPage(); pageB = await contextB.newPage()
    await f.check('work-page-staff-a', () => openWorkPage(f, pageA, context.accounts.staff))
    await f.check('work-page-staff-c', () => openWorkPage(f, pageB, context.accounts.staffC))
    await f.check('work-page-waiting', async () => {
      const card = await workOrder(pageA, order.orderId, '待接单')
      await expect(card).toContainText('待接单'); await expect(card.getByRole('button', { name: '接单', exact: true })).toBeVisible()
    })
    await f.check('two-staff-conflict', async () => {
      const cardA = await workOrder(pageA, order.orderId, '待接单')
      const cardB = await workOrder(pageB, order.orderId, '待接单')
      const responses = [pageA, pageB].map(page => page.waitForResponse(r => r.url().includes('/admin-api/order/store-order/fulfillment/transition') && r.request().method() === 'POST'))
      await Promise.all([cardA.getByRole('button', { name: '接单', exact: true }).click(), cardB.getByRole('button', { name: '接单', exact: true }).click()])
      const results = await Promise.all(responses.map(async response => (await (await response).json()).code))
      expect(results.filter(code => code === 0)).toHaveLength(1)
      expect(results.filter(code => code !== 0)).toHaveLength(1)
      const state = fulfillmentState(f, order.orderId)
      expect(state.fulfillmentStatus).toBe('ACCEPTED'); expect(state.fulfillmentVersion).toBe(2)
      const printRows = f.oracle('print-jobs', { orderId: order.orderId })
      const events = printRows.filter((row: any) => row.kind === 'EVENT')
      const jobs = printRows.filter((row: any) => row.kind === 'JOB')
      expect(events).toHaveLength(1)
      expect(jobs.length).toBeLessThanOrEqual(events.length)
      if (jobs.length) expect(jobs[0].eventId).toBe(events[0].eventId)
    })
    await f.check('accept-through-work-page', async () => {
      const card = await workOrder(pageA, order.orderId, '已接单')
      await expect(card).toContainText('已接单')
      expect(fulfillmentState(f, order.orderId).fulfillmentStatus).toBe('ACCEPTED')
      workOwner = pageA
    })
    if (local) await f.check('mini-accepted-status', async () => {
      const detail = await f.mini.orderDetail(order.orderId)
      await expect.poll(async () => f.mini.pickupStatus(detail)).toContain('已接单')
    })
    await f.check('prepare-through-work-page', async () => {
      const card = await workOrder(workOwner, order.orderId, '已接单')
      const pending = workOwner.waitForResponse((r: any) => r.url().includes('/fulfillment/transition') && r.request().method() === 'POST')
      await card.getByRole('button', { name: '开始制作', exact: true }).click()
      expect((await (await pending).json()).code).toBe(0)
      expect(fulfillmentState(f, order.orderId).fulfillmentStatus).toBe('PREPARING')
    })
    await f.check('ready-through-work-page', async () => {
      const card = await workOrder(workOwner, order.orderId, '制作中')
      const pending = workOwner.waitForResponse((r: any) => r.url().includes('/fulfillment/transition') && r.request().method() === 'POST')
      await card.getByRole('button', { name: '完成制作', exact: true }).click()
      expect((await (await pending).json()).code).toBe(0)
      expect(fulfillmentState(f, order.orderId).fulfillmentStatus).toBe('READY')
      beforeCollect = fulfillmentState(f, order.orderId)
    })
    if (local) await f.check('mini-ready-and-collect', async () => {
      await f.mini.mini.navigateTo('/pages-order/orders/orders')
      const detail = await f.mini.orderDetail(order.orderId)
      await expect.poll(async () => f.mini.pickupStatus(detail)).toContain('待取餐')
      collectRequest = await f.mini.collectPickupOrder(detail)
      expect(collectRequest.responseCode).toBe(0)
      expect(collectRequest.data.expectedVersion).toBe(4)
    })
    await f.check('collect-replay', async () => {
      if (local) await f.api('POST', '/app-api/order/take', collectRequest.data, memberToken)
      else {
        collectRequest = { uni: order.orderId, expectedVersion: 4, requestKey: `e2e-collect-${key()}` }
        await f.api('POST', '/app-api/order/take', collectRequest, memberToken)
        await f.api('POST', '/app-api/order/take', collectRequest, memberToken)
      }
      const state = fulfillmentState(f, order.orderId)
      expect(state.fulfillmentStatus).toBe('COLLECTED'); expect(state.fulfillmentVersion).toBe(5)
      expect(state.collectActions).toBe(1)
    })
    await f.check('collected-reward-sql', () => {
      const state = fulfillmentState(f, order.orderId)
      expect(state.rewardBillCount).toBe(1)
      expect(Number(state.rewardBillPoints)).toBe(Number(state.rewardPoints))
      expect(Number(state.memberPoints) - Number(beforeCollect.memberPoints)).toBe(Number(state.rewardPoints))
    })
    if (local) await f.check('mini-collected-status', async () => {
      await f.mini.mini.navigateTo('/pages-order/orders/orders')
      const detail = await f.mini.orderDetail(order.orderId)
      await expect.poll(async () => f.mini.pickupStatus(detail)).toContain('已取餐')
    })
  } finally {
    await Promise.all(staffContexts.map(c => c.close()))
  }
}

async function loadWorkSummary(page: any) {
  const pending = page.waitForResponse((r: any) => r.url().includes('/admin-api/order/store-order/store-summary?') && r.request().method() === 'GET')
  await page.goto('/mall/store-order-work')
  const response = await pending; const result = await response.json(); expect(result.code).toBe(0)
  await expect(page.getByText('订单实付额（毛额）')).toBeVisible()
  return { data: result.data, url: response.url() }
}
function summaryParams(url: string) {
  const params = new URL(url).searchParams
  return { from: params.get('from') || '', to: params.get('to') || '', shopId: params.get('shopId') || undefined }
}
function assertSummaryMatchesSql(actual: any, expected: any) {
  for (const field of ['paidOrderCount', 'soldQuantity', 'refundOrderCount', 'refundPendingCount', 'refundCompletedCount',
    'waitAcceptQueueCount', 'acceptedQueueCount', 'preparingQueueCount', 'readyQueueCount']) {
    expect(Number(actual[field]), field).toBe(Number(expected[field]))
  }
  for (const field of ['grossPaidAmount', 'refundAmount']) expect(Number(actual[field]).toFixed(2), field).toBe(Number(expected[field]).toFixed(2))
}
function statsCard(page: any, title: string) {
  return page.locator('.el-statistic').filter({ has: page.locator('.el-statistic__head').getByText(title, { exact: true }) })
    .locator('.el-statistic__content')
}
async function chooseStatsShop(page: any, name: string) {
  const formItem = page.locator('.el-form-item').filter({ hasText: '统计门店' })
  await formItem.locator('.el-select').click()
  const pending = page.waitForResponse((r: any) => r.url().includes('/admin-api/order/store-order/store-summary?') && r.request().method() === 'GET')
  await page.getByRole('option', { name, exact: true }).click()
  const response = await pending; const result = await response.json(); expect(result.code).toBe(0)
  return { data: result.data, url: response.url() }
}
async function storeStatistics(f: Flow) {
  let memberA = '', memberB = ''
  let orderA: any, orderB: any, boundaryStartOrder: any, boundaryEndOrder: any, summaryAll: any, allParams: any
  const branchContexts: any[] = []
  const branchTokens: Record<number, string> = {}
  try {
    await f.check('two-store-orders-paid', async () => {
      memberA = await f.member(); memberB = await f.member(2)
      const [productA, productB] = await Promise.all([f.createProduct(101, 'stats-a', 19), f.createProduct(102, 'stats-b', 23)])
      orderA = await f.createOrder(productA, memberA); orderB = await f.createOrder(productB, memberB)
      boundaryStartOrder = await f.createOrder(productA, memberA)
      boundaryEndOrder = await f.createOrder(productB, memberB)
      for (const order of [orderA, orderB, boundaryStartOrder, boundaryEndOrder]) f.bridge('complete', { orderId: order.orderId })
      expect(f.oracle('order', { orderId: orderA.orderId })[0]).toMatchObject({ shopId: 101, paid: 1 })
      expect(f.oracle('order', { orderId: orderB.orderId })[0]).toMatchObject({ shopId: 102, paid: 1 })
      expect(f.oracle('order', { orderId: boundaryStartOrder.orderId })[0]).toMatchObject({ shopId: 101, paid: 1 })
      expect(f.oracle('order', { orderId: boundaryEndOrder.orderId })[0]).toMatchObject({ shopId: 102, paid: 1 })
    })
    await f.check('date-window-is-half-open', async () => {
      const initial = await loadWorkSummary(f.page), initialParams = summaryParams(initial.url)
      const today = shanghaiDate()
      expect(initialParams.from).toMatch(/^\d{4}-\d{2}-\d{2}$/)
      expect(initialParams.to).toBe(nextIsoDate(today))
      expect(initialParams.from <= today).toBe(true)
      expect(initialParams.shopId).toBeUndefined()
      const fixture = f.bridge('stats-boundary-times', { startOrderId: boundaryStartOrder.orderId,
        endOrderId: boundaryEndOrder.orderId, fromDate: initialParams.from, toDate: initialParams.to })
      expect(fixture.startPayTime.startsWith(`${initialParams.from}T00:00`)).toBe(true)
      expect(fixture.endPayTime.startsWith(`${initialParams.to}T00:00`)).toBe(true)
      const loaded = await loadWorkSummary(f.page); summaryAll = loaded.data; allParams = summaryParams(loaded.url)
      expect(allParams).toEqual(initialParams)
      const sql = f.oracle('store-summary', allParams)[0]
      assertSummaryMatchesSql(summaryAll, sql)
      const boundaryRows = f.oracle('store-summary-details', allParams)
      expect(boundaryRows.some((row: any) => row.orderId === boundaryStartOrder.orderId)).toBe(true)
      expect(boundaryRows.some((row: any) => row.orderId === boundaryEndOrder.orderId)).toBe(false)
      const fromMillis = Date.parse(`${allParams.from}T00:00:00+08:00`)
      const toMillis = Date.parse(`${allParams.to}T00:00:00+08:00`)
      expect(toMillis - fromMillis).toBeGreaterThan(0)
      expect(allParams.to).toBe(nextIsoDate(today))
    })
    await f.check('headquarters-summary-page', async () => {
      expect(summaryAll.headquarters).toBe(true); expect(summaryAll.authorizedShopIds).toBeNull()
      const shops = await f.api('GET', '/admin-api/order/store-order/store-summary/shops')
      expect(shops.map((shop: any) => Number(shop.id))).toEqual(expect.arrayContaining([101, 102]))
      const expected = f.oracle('store-summary', allParams)[0]
      assertSummaryMatchesSql(summaryAll, expected)
      await expect(statsCard(f.page, '已付订单数')).toContainText(String(summaryAll.paidOrderCount))
    })
    let shopA: any, shopB: any
    await f.check('store-a-summary-reconcile', async () => {
      shopA = await chooseStatsShop(f.page, 'QA Shop A')
      const params = summaryParams(shopA.url); expect(params.shopId).toBe('101')
      expect(shopA.data.headquarters).toBe(true)
      assertSummaryMatchesSql(shopA.data, f.oracle('store-summary', { ...params, shopId: 101 })[0])
      await expect(statsCard(f.page, '已付订单数')).toContainText(String(shopA.data.paidOrderCount))
    })
    await f.check('store-b-summary-reconcile', async () => {
      shopB = await chooseStatsShop(f.page, 'QA Shop B')
      const params = summaryParams(shopB.url); expect(params.shopId).toBe('102')
      expect(shopB.data.headquarters).toBe(true)
      assertSummaryMatchesSql(shopB.data, f.oracle('store-summary', { ...params, shopId: 102 })[0])
      await expect(statsCard(f.page, '已付订单数')).toContainText(String(shopB.data.paidOrderCount))
    })
    await f.check('details-reconcile-sql', async () => {
      const loaded = await loadWorkSummary(f.page), params = summaryParams(loaded.url)
      const pending = f.page.waitForResponse((r: any) => r.url().includes('/admin-api/order/store-order/store-summary/details?') && r.request().method() === 'GET')
      await f.page.getByText('查看明细', { exact: true }).click()
      const result = await (await pending).json(); expect(result.code).toBe(0)
      const actualRows = result.data.list
      const sqlRows = f.oracle('store-summary-details', params)
      expect(result.data.total).toBe(sqlRows.length)
      expect(actualRows.map((row: any) => row.orderId)).toEqual(sqlRows.slice(0, actualRows.length).map((row: any) => row.orderId))
      for (const order of [orderA, orderB]) expect(sqlRows.some((row: any) => row.orderId === order.orderId)).toBe(true)
      expect(actualRows.some((row: any) => row.orderId === boundaryStartOrder.orderId)).toBe(true)
      expect(actualRows.some((row: any) => row.orderId === boundaryEndOrder.orderId)).toBe(false)
      expect(actualRows.map((row: any) => [Number(row.shopId), Number(row.payPrice), Number(row.totalNum), row.fulfillmentStatus]))
        .toEqual(sqlRows.slice(0, actualRows.length).map((row: any) => [Number(row.shopId), Number(row.payPrice), Number(row.totalNum), row.fulfillmentStatus]))
    })
    await f.check('shop-switch-ignores-stale-summary', async () => {
      await loadWorkSummary(f.page)
      let started!: () => void, release!: () => void
      const startedA = new Promise<void>(resolve => { started = resolve })
      const gate = new Promise<void>(resolve => { release = resolve })
      let dataA: any
      const slowPath = '**/admin-api/order/store-order/store-summary*'
      const handler = async (route: any) => {
        const url = new URL(route.request().url())
        if (url.searchParams.get('shopId') !== '101') return route.fallback()
        const backendUrl = `${context.backend}${url.pathname}${url.search}`
        const response = await route.fetch({ url: backendUrl })
        expect(response.status()).toBe(200)
        expect(response.ok()).toBe(true)
        const result = await response.json()
        expect(result.code).toBe(0)
        dataA = result.data
        const paramsA = summaryParams(backendUrl)
        expect(paramsA.shopId).toBe('101')
        assertSummaryMatchesSql(dataA, f.oracle('store-summary', { ...paramsA, shopId: 101 })[0])
        started(); await gate; await route.fulfill({ response })
      }
      await f.page.route(slowPath, handler)
      try {
        const selector = f.page.locator('.el-form-item').filter({ hasText: '统计门店' }).locator('.el-select')
        await selector.click()
        const requestA = f.page.waitForRequest((r: any) => r.url().includes('/store-summary?') && new URL(r.url()).searchParams.get('shopId') === '101')
        await f.page.getByRole('option', { name: 'QA Shop A', exact: true }).click()
        await startedA
        const storeB = await chooseStatsShop(f.page, 'QA Shop B')
        const paramsB = summaryParams(storeB.url); expect(paramsB.shopId).toBe('102')
        const amountB = Number(storeB.data.grossPaidAmount).toFixed(2)
        const amountA = Number(dataA.grossPaidAmount).toFixed(2)
        expect(amountA).not.toBe(amountB)
        await expect(statsCard(f.page, '订单实付额（毛额）')).toContainText(amountB)
        const finishedA = requestA.then((request: any) => f.page.waitForEvent('requestfinished', (finished: any) => finished === request))
        release()
        await finishedA
        await f.page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
        await expect(statsCard(f.page, '订单实付额（毛额）'), 'late Shop A response must not replace Shop B statistics').toContainText(amountB)
        assertSummaryMatchesSql(storeB.data, f.oracle('store-summary', { ...paramsB, shopId: 102 })[0])
      } finally {
        release(); await f.page.unroute(slowPath, handler)
      }
    })
    for (const [name, account, ownShop] of [['branch-a-page-scope', context.accounts.staff, 101], ['branch-b-page-scope', context.accounts.staffB, 102]] as any[]) {
      await f.check(name, async () => {
        const browserContext = await f.browser.newContext({ baseURL: f.baseURL, serviceWorkers: 'block' }); branchContexts.push(browserContext)
        const page = await browserContext.newPage()
        const loaded = await loadWorkSummaryAfterLogin(f, page, account)
        branchTokens[ownShop] = loaded.token
        expect(loaded.data.headquarters).toBe(false)
        expect(loaded.data.authorizedShopIds.map(Number)).toEqual([ownShop])
        assertSummaryMatchesSql(loaded.data, f.oracle('store-summary', { ...summaryParams(loaded.url), shopId: ownShop })[0])
        const shops = await f.api('GET', '/admin-api/order/store-order/store-summary/shops', undefined, loaded.token)
        expect(shops.map((shop: any) => Number(shop.id))).toEqual([ownShop])
        await expect(statsCard(page, '已付订单数')).toContainText(String(loaded.data.paidOrderCount))
      })
    }
    await f.check('branch-cannot-read-foreign-shop', async () => {
      for (const [ownShop, foreignShop] of [[101, 102], [102, 101]]) {
        const token = branchTokens[ownShop]
        const denied = await f.api('GET', `/admin-api/order/store-order/store-summary?from=${allParams.from}&to=${allParams.to}&shopId=${foreignShop}`, undefined, token, false)
        expect(denied.code).not.toBe(0)
        const deniedDetails = await f.api('GET', `/admin-api/order/store-order/store-summary/details?from=${allParams.from}&to=${allParams.to}&shopId=${foreignShop}`, undefined, token, false)
        expect(deniedDetails.code).not.toBe(0)
      }
    })
  } finally {
    await Promise.all(branchContexts.map(c => c.close()))
  }
}

async function loadWorkSummaryAfterLogin(f: Flow, page: any, account: any) {
  const token = await f.login(page, account)
  const pending = page.waitForResponse((r: any) => r.url().includes('/admin-api/order/store-order/store-summary?') && r.request().method() === 'GET')
  await page.goto('/mall/store-order-work')
  const response = await pending; const result = await response.json(); expect(result.code).toBe(0)
  await expect(page.getByText('订单实付额（毛额）')).toBeVisible()
  return { data: result.data, url: response.url(), token }
}

async function scoreFixture(f: Flow, score = 100) {
  const token = await f.member()
  const address = { realName: 'Synthetic score', phone: '13800000001', address: 'Owned score street', detail: f.title('address'), isDefault: 1, latitude: '33', longitude: '114' }
  const addressId = await f.api('POST', '/app-api/address/addAndEdit', address, token)
  const product = { title: f.title(`score-${score}`), image: 'data:image/svg+xml;base64,PHN2Zy8+', images: ['data:image/svg+xml;base64,PHN2Zy8+'], desc: 'Owned synthetic redemption', score, weigh: 0, stock: 10, sales: 0, isSwitch: 1 }
  const productId = await f.api('POST', '/admin-api/score/product/create', product)
  return { token, address: { ...address, id: addressId }, product: { ...product, id: productId }, body: { productId: String(productId), addressId: String(addressId), num: '1', idempotencyKey: key() } }
}
function scoreState(f: Flow, productId: number) { return f.oracle('score-state', { memberId: 101, productId })[0] }
function assertScoreOnce(before: any, after: any) {
  expect(after.orders).toBe(1); expect(after.submissions).toBe(1)
  expect(after.stock).toBe(9); expect(after.sales).toBe(1)
  expect(Number(after.points)).toBe(Number(before.points) - 100)
  expect(Number(after.debit)).toBe(100); expect(Number(after.totalScore)).toBe(100)
  expect(Number(after.ledgerBalance)).toBe(Number(after.points))
}
async function assertScoreMiniRefusal(f: Flow, page: any, request: any, message: string) {
  await expect.poll(async () => (await page.$('.score-product-confirm__feedback'))?.text()).toContain(message)
  await expect.poll(async () => (await (await page.$('.score-product-confirm__submit'))?.text())).toContain('重试兑换')
  const component = await page.$('.score-product-confirm__submit')
  expect(await component.$('.uv-button-wrapper--dis')).toBeNull()
  const saved = await f.mini.mini.evaluate(() => wx.getStorageSync('scoreSubmission:v1:101'))
  expect(saved.state).toBe('failed')
  expect(saved.idempotencyKey).toBe(request.data.idempotencyKey)
  expect((await f.mini.mini.currentPage()).path).toBe('pages-score/scoreproduct/confirm')
}
async function scoreRedemption(f: Flow) {
  let fixture: any, body: any, before: any, page: any
  await f.check('score-fixture', async () => { fixture = await scoreFixture(f); before = scoreState(f, fixture.product.id); expect(before.orders).toBe(0) })
  if (local) await f.check('mini-score-page', async () => {
    page = await f.mini.scoreCheckout(fixture.product, fixture.address)
    const text = await (await page.$('.score-product-confirm')).text()
    expect(text).toContain('本次消耗'); expect(text).toContain('100'); expect(text).not.toContain('￥')
  })
  await f.check('score-submitted', async () => {
    if (local) { const request = await f.mini.scoreSubmit(page); expect(request.responseCode).toBe(0); body = request.data }
    else { body = fixture.body; expect(await f.api('POST', '/app-api/score-order/submit', body, fixture.token)).toBe(true) }
    expect(String(body.num)).toBe('1'); expect(body.idempotencyKey).toMatch(/^[A-Za-z0-9_-]{16,64}$/)
  })
  await f.check('score-ledger-sql', async () => {
    const state = scoreState(f, fixture.product.id); assertScoreOnce(before, state)
    await f.page.goto('/mall/score-orders')
    await f.page.getByPlaceholder('请输入订单号').fill(state.orderId)
    await f.page.getByRole('button', { name: '搜索', exact: true }).click()
    await expect(f.page.getByRole('row').filter({ hasText: state.orderId })).toContainText('100')
  })
  await f.check('same-key-replay', async () => { expect(await f.api('POST', '/app-api/score-order/submit', body, fixture.token)).toBe(true) })
  await f.check('different-content-conflict', async () => {
    expect((await f.api('POST', '/app-api/score-order/submit', { ...body, num: '2' }, fixture.token, false)).code).toBe(1008018005)
  })
  await f.check('insufficient-points', async () => {
    const expensive = await scoreFixture(f, 2000), unchanged = scoreState(f, expensive.product.id)
    if (local) {
      const refusalPage = await f.mini.scoreCheckout(expensive.product, expensive.address)
      const request = await f.mini.scoreSubmit(refusalPage); expect(request.responseCode).toBe(1008018002)
      await assertScoreMiniRefusal(f, refusalPage, request, '积分不足')
    } else expect((await f.api('POST', '/app-api/score-order/submit', expensive.body, expensive.token, false)).msg).toBe('积分不足')
    expect(scoreState(f, expensive.product.id)).toEqual(unchanged)
    if (local) {
      const soldOut = await scoreFixture(f), refusalPage = await f.mini.scoreCheckout(soldOut.product, soldOut.address)
      await f.api('PUT', '/admin-api/score/product/update', { ...soldOut.product, stock: 0 })
      const stockBefore = scoreState(f, soldOut.product.id)
      const request = await f.mini.scoreSubmit(refusalPage); expect(request.responseCode).toBe(1008018002)
      await assertScoreMiniRefusal(f, refusalPage, request, '库存不足')
      expect(scoreState(f, soldOut.product.id)).toEqual(stockBefore)
    } else expect((await f.api('POST', '/app-api/score-order/submit', { ...body, num: '10', idempotencyKey: key() }, fixture.token, false)).msg).toBe('库存不足')
  })
  await f.check('exactly-once-sql', () => { assertScoreOnce(before, scoreState(f, fixture.product.id)) })
}
async function scoreRetry(f: Flow) {
  let fixture: any, before: any, body: any, page: any
  await f.check('score-fixture', async () => { fixture = await scoreFixture(f); before = scoreState(f, fixture.product.id) })
  await f.check('committed-score-response-lost', async () => {
    if (local) {
      page = await f.mini.scoreCheckout(fixture.product, fixture.address)
      body = (await f.mini.scoreSubmit(page, { loseResponse: true })).data
      expect((await f.mini.state()).scoreDropped).toBe(true)
    } else { body = fixture.body; await loseResponse(f, body, fixture.token, '/app-api/score-order/submit') }
    assertScoreOnce(before, scoreState(f, fixture.product.id))
  })
  if (local) await f.check('mini-score-reload', async () => {
    const saved = await f.mini.mini.evaluate(() => wx.getStorageSync('scoreSubmission:v1:101'))
    expect(saved.idempotencyKey).toBe(body.idempotencyKey)
    expect(saved.payload.productId).toBe(String(fixture.product.id))
    page = await f.mini.mini.navigateTo('/pages-score/scoreproduct/confirm')
    await expect.poll(async () => (await (await page.$('.score-product-confirm__submit')).text())).toContain('原请求重试')
  })
  await f.check('original-score-key-replay', async () => {
    if (local) {
      const request = await f.mini.scoreSubmit(page); expect(request.responseCode).toBe(0); expect(request.data.idempotencyKey).toBe(body.idempotencyKey)
      expect(await f.mini.mini.evaluate(() => wx.getStorageSync('scoreSubmission:v1:101'))).toBeFalsy()
    } else expect(await f.api('POST', '/app-api/score-order/submit', body, fixture.token)).toBe(true)
  })
  await f.check('exactly-once-sql', () => { assertScoreOnce(before, scoreState(f, fixture.product.id)) })
}
async function openOrderPrintJobs(page: any) {
  const pending = page.waitForResponse((r: any) => r.url().includes('/admin-api/store/order-print-job/page') && r.request().method() === 'GET')
  await page.goto('/mall/web-print')
  const tab = page.getByRole('tab', { name: '订单打印任务', exact: true })
  await tab.click(); const response = await pending
  expect((await response.json()).code).toBe(0)
  await expect(page.locator('.el-table').filter({ hasText: '订单取餐信息' })).toBeVisible()
}
async function reloadOrderPrintJobs(page: any) {
  await page.reload()
  const pending = page.waitForResponse((r: any) => r.url().includes('/admin-api/store/order-print-job/page') && r.request().method() === 'GET')
  await page.getByRole('tab', { name: '订单打印任务', exact: true }).click()
  await pending
  await expect(page.locator('.el-table').filter({ hasText: '订单取餐信息' })).toBeVisible()
}
function printOrderRow(page: any, orderCode: string, extraText?: string) {
  const rows = page.getByRole('row').filter({ hasText: orderCode })
  return extraText ? rows.filter({ hasText: extraText }) : rows
}
function printRows(f: Flow, orderId: string) { return f.oracle('print-jobs', { orderId }) }
function printCounters(f: Flow) {
  const value = f.bridge('printer-counters', {})
  return { printCalls: value.printCalls, statusCalls: value.statusCalls }
}
async function refreshPrintRow(page: any, row: any) {
  const pending = page.waitForResponse((r: any) => r.url().includes('/admin-api/store/order-print-job/refresh') && r.request().method() === 'POST')
  await row.getByRole('button', { name: '刷新状态', exact: true }).click()
  const result = await (await pending).json(); expect(result.code).toBe(0)
  return result.data
}
async function checkPrintRecoveryBox(dialog: any, label: string) {
  const checkbox = dialog.locator('.el-checkbox').filter({ hasText: label })
  await checkbox.locator('.el-checkbox__inner').click()
  await expect(checkbox.locator('input[type="checkbox"]')).toBeChecked()
}
async function selectPrintRecoveryOption(dialog: any, label: string) {
  const radio = dialog.locator('.el-radio').filter({ hasText: label })
  await radio.locator('.el-radio__inner').click()
  await expect(radio.locator('input[type="radio"]')).toBeChecked()
}
async function orderPrintJobs(f: Flow) {
  const staffContexts: any[] = []
  let memberToken = '', product: any, order: any, staffPage: any, printerId = 0, orderCode = '', jobId = 0, reprintId = 0
  const printerPrefix = '/admin-api/store/printer'
  try {
    await f.check('unbound-order-waits-on-real-page', async () => {
      const countsBefore = printCounters(f)
      // Shop B is independent of the preceding printer-unknown scenario. Its
      // empty binding state gives this test an unbound device precondition
      // without detaching a printer whose UNKNOWN operation is still busy.
      const priorDevices = await f.api('GET', `${printerPrefix}/page?pageNo=1&pageSize=100&shopId=102&name=${encodeURIComponent(f.title('printer'))}`)
      expect(priorDevices.list.filter((value: any) => value.name === f.title('printer'))).toHaveLength(0)
      product = await f.createProduct(102, 'print-jobs', 19)
      memberToken = await f.member()
      order = await f.createOrder(product, memberToken)
      f.bridge('complete', { orderId: order.orderId })
      const staffContext = await f.browser.newContext({ baseURL: f.baseURL, serviceWorkers: 'block' }); staffContexts.push(staffContext)
      staffPage = await staffContext.newPage()
      await openWorkPage(f, staffPage, context.accounts.staffB)
      const card = await workOrder(staffPage, order.orderId, '待接单')
      const pending = staffPage.waitForResponse((r: any) => r.url().includes('/admin-api/order/store-order/fulfillment/transition') && r.request().method() === 'POST')
      await card.getByRole('button', { name: '接单', exact: true }).click()
      expect((await (await pending).json()).code).toBe(0)
      expect(fulfillmentState(f, order.orderId).fulfillmentStatus).toBe('ACCEPTED')
      f.bridge('order-print-poll', {})
      let rows = printRows(f, order.orderId)
      expect(rows).toHaveLength(1); expect(rows[0]).toMatchObject({ kind: 'EVENT', state: 'WAITING_DEVICE', errorCode: 'NO_BOUND_DEVICE' })
      const payload = JSON.parse(rows[0].payloadSnapshot); orderCode = payload.orderCode
      expect(orderCode).toBe(order.orderId)
      expect(payload.contactPhone).toBe('138****0001')
      await openOrderPrintJobs(f.page)
      const row = printOrderRow(f.page, orderCode)
      await expect(row).toContainText('QA Shop B'); await expect(row).toContainText('等待可用设备')
      await expect(row).toContainText('门店尚未绑定打印机')
      expect(printCounters(f)).toEqual(countsBefore)
    })
    await f.check('offline-device-waits-on-real-page', async () => {
      f.bridge('printer-state', { state: 'OFFLINE' })
      printerId = await f.api('POST', printerPrefix + '/create', {
        name: f.title('order-printer'), providerCode: 'OWNED_TEST', deviceCode: f.title('order-printer-device'),
        model: 'Synthetic', connectionType: 'LAN', enabled: true, configuration: {}
      })
      await f.api('PUT', printerPrefix + '/binding', { id: printerId, shopId: 102 })
      expect((await f.api('POST', `${printerPrefix}/status/refresh?id=${printerId}`)).state).toBe('OFFLINE')
      await refreshPrintRow(f.page, printOrderRow(f.page, orderCode))
      await expect(printOrderRow(f.page, orderCode)).toContainText('打印机离线')
      const event = printRows(f, order.orderId).find((row: any) => row.kind === 'EVENT')
      expect(event).toMatchObject({ state: 'WAITING_DEVICE', errorCode: 'DEVICE_OFFLINE' })
      expect(f.oracle('printer-state', { printerId })[0].state).toBe('OFFLINE')
    })
    await f.check('order-history-on-real-page', async () => {
      f.bridge('printer-state', { state: 'ONLINE' })
      await f.api('POST', `${printerPrefix}/status/refresh?id=${printerId}`)
      const before = printCounters(f)
      await refreshPrintRow(f.page, printOrderRow(f.page, orderCode))
      f.bridge('order-print-poll', {})
      const rows = printRows(f, order.orderId)
      const job = rows.find((row: any) => row.kind === 'JOB')
      expect(job).toMatchObject({ shopId: 102, printerId, state: 'UNKNOWN', errorCode: 'PROVIDER_TIMEOUT', purpose: 'ORDER' })
      jobId = Number(job.id)
      await reloadOrderPrintJobs(f.page)
      await expect(printOrderRow(f.page, orderCode)).toContainText('结果待核对')
      await expect(printOrderRow(f.page, orderCode)).toContainText(f.title('order-printer'))
      expect(printCounters(f)).toEqual({ printCalls: before.printCalls + 1, statusCalls: before.statusCalls })
    })
    await f.check('unknown-refresh-readonly', async () => {
      f.bridge('simulate-print-crash', { jobId })
      const beforeRows = printRows(f, order.orderId), beforeCounters = printCounters(f)
      const beforeRecovery = f.oracle('print-recovery', { jobId })[0]
      expect(beforeRecovery).toMatchObject({ state: 'UNKNOWN', lastError: 'WORKER_LEASE_EXPIRED', claimCount: 1, resolutionCount: 0 })
      expect(beforeRecovery.leaseExpiresAt).toBeNull()
      const result = await refreshPrintRow(f.page, printOrderRow(f.page, orderCode))
      expect(result.id).toBe(jobId); expect(result.state).toBe('UNKNOWN')
      expect(printRows(f, order.orderId)).toEqual(beforeRows)
      expect(printCounters(f)).toEqual(beforeCounters)
      await expect(printOrderRow(f.page, orderCode)).toContainText('结果待核对')
      await expect(printOrderRow(f.page, orderCode)).toContainText('发送进程中断，结果待核对')
      expect(f.oracle('print-recovery', { jobId })[0]).toMatchObject({ state: 'UNKNOWN', claimCount: 1, resolutionCount: 0 })
    })
    await f.check('recovery-confirmation-gate', async () => {
      const row = printOrderRow(f.page, orderCode)
      await row.getByRole('button', { name: '核验后释放占用', exact: true }).click()
      const dialog = f.page.getByRole('dialog').filter({ hasText: '核验后释放打印占用' })
      await expect(dialog).toBeVisible()
      await expect(dialog).toContainText('不会自动重发')
      const confirm = dialog.getByRole('button', { name: '确认核验结果并释放占用', exact: true })
      await expect(confirm).toBeDisabled()
      const reason = '已联系值班员确认旧发送进程退出，并核对设备无出纸'
      await dialog.getByLabel('处理说明').fill(reason)
      await expect(confirm).toBeDisabled()
      await checkPrintRecoveryBox(dialog, '我已确认原发送进程已停止')
      await expect(confirm).toBeDisabled()

      const rejectedSender = await f.api('POST', '/admin-api/store/order-print-job/resolve-unknown', {
        jobId, requestKey: `gate_sender_${key()}`, reason, senderStopped: false, notPrintedConfirmed: true
      }, f.adminToken, false)
      const rejectedPaper = await f.api('POST', '/admin-api/store/order-print-job/resolve-unknown', {
        jobId, requestKey: `gate_paper_${key()}`, reason, senderStopped: true, notPrintedConfirmed: false
      }, f.adminToken, false)
      expect(rejectedSender.code).not.toBe(0); expect(rejectedPaper.code).not.toBe(0)
      expect(f.oracle('print-recovery', { jobId })[0]).toMatchObject({ state: 'UNKNOWN', claimCount: 1, resolutionCount: 0 })

      await selectPrintRecoveryOption(dialog, '打印服务或设备确认未出纸')
      await expect(confirm).toBeEnabled()
      await dialog.getByRole('button', { name: '关闭', exact: true }).click()
      await expect(dialog).toBeHidden()
    })
    await f.check('unknown-recovery-audited', async () => {
      const beforeCounters = printCounters(f), reason = '已联系值班员确认旧发送进程退出，并核对设备无出纸'
      const row = printOrderRow(f.page, orderCode)
      await row.getByRole('button', { name: '核验后释放占用', exact: true }).click()
      const dialog = f.page.getByRole('dialog').filter({ hasText: '核验后释放打印占用' })
      await dialog.getByLabel('处理说明').fill(reason)
      await checkPrintRecoveryBox(dialog, '我已确认原发送进程已停止')
      await selectPrintRecoveryOption(dialog, '打印服务或设备确认未出纸')
      const pending = f.page.waitForResponse((r: any) => r.url().includes('/admin-api/store/order-print-job/resolve-unknown') && r.request().method() === 'POST')
      await dialog.getByRole('button', { name: '确认核验结果并释放占用', exact: true }).click()
      const responseEvent = await pending
      expect(responseEvent.request().postDataJSON()).toMatchObject({ notPrintedConfirmed: true, printedConfirmed: false })
      const response = await responseEvent.json(); expect(response.code).toBe(0)
      expect(response.data).toMatchObject({ id: jobId, state: 'FAILED', errorCode: 'OPERATOR_CONFIRMED_NOT_PRINTED' })
      await expect(dialog).toBeHidden()
      const audit = f.oracle('print-recovery', { jobId })[0]
      expect(audit).toMatchObject({ jobId, state: 'FAILED', lastError: 'OPERATOR_CONFIRMED_NOT_PRINTED',
        claimCount: 0, resolutionCount: 1, resolutionOutcome: 'NOT_PRINTED_CONFIRMED_CLAIM_RELEASED',
        claimWasPresent: 1, resolutionReason: reason, actorId: 101, priorError: 'WORKER_LEASE_EXPIRED' })
      expect(audit.leaseExpiresAt).toBeNull()
      expect(printCounters(f)).toEqual(beforeCounters)
      await expect(printOrderRow(f.page, orderCode)).toContainText('已核实未出纸，等待人工重打')
      await expect(printOrderRow(f.page, orderCode).getByRole('button', { name: '人工重打', exact: true })).toBeVisible()
    })
    await f.check('manual-reprint-audited', async () => {
      const beforeCounters = printCounters(f), reason = '顾客反馈未收到小票'
      const row = printOrderRow(f.page, orderCode)
      await row.getByRole('button', { name: '人工重打', exact: true }).click()
      const dialog = f.page.getByRole('dialog').filter({ hasText: '重打会创建关联原任务' })
      await dialog.getByLabel('重打原因').fill(reason)
      const pending = f.page.waitForResponse((r: any) => r.url().includes('/admin-api/store/order-print-job/reprint') && r.request().method() === 'POST')
      await dialog.getByRole('button', { name: '创建重打任务', exact: true }).click()
      const response = await (await pending).json(); expect(response.code).toBe(0)
      reprintId = Number(response.data.id); expect(reprintId).toBeGreaterThan(0)
      await expect(dialog).toBeHidden()
      f.bridge('order-print-poll', {})
      await expect.poll(() => printRows(f, order.orderId).find((value: any) => value.id === reprintId)?.state, { timeout: 20000 }).toBe('UNKNOWN')
      const rows = printRows(f, order.orderId)
      const parent = rows.find((value: any) => value.id === jobId), child = rows.find((value: any) => value.id === reprintId)
      expect(parent).toMatchObject({ state: 'FAILED', errorCode: 'OPERATOR_CONFIRMED_NOT_PRINTED' })
      expect(child).toMatchObject({ kind: 'JOB', purpose: 'REPRINT', parentJobId: jobId, reprintReason: reason, createdBy: 101, errorCode: 'PROVIDER_TIMEOUT' })
      expect(child.payloadSnapshot).toBe(parent.payloadSnapshot)
      expect(printCounters(f)).toEqual({ printCalls: beforeCounters.printCalls + 1, statusCalls: beforeCounters.statusCalls })
      await expect(printOrderRow(f.page, orderCode, reason)).toContainText(reason)
    })
    await f.check('printed-confirmation-closes-without-resend', async () => {
      const beforeCounters = printCounters(f), reason = '现场确认已出纸，旧发送进程已停止'
      await reloadOrderPrintJobs(f.page)
      const row = printOrderRow(f.page, orderCode, '顾客反馈未收到小票')
      await expect(row).toContainText('结果待核对')
      await row.getByRole('button', { name: '核验后释放占用', exact: true }).click()
      const dialog = f.page.getByRole('dialog').filter({ hasText: '核验后释放打印占用' })
      await dialog.getByLabel('处理说明').fill(reason)
      await checkPrintRecoveryBox(dialog, '我已确认原发送进程已停止')
      const confirm = dialog.getByRole('button', { name: '确认核验结果并释放占用', exact: true })
      await expect(confirm).toBeDisabled()
      await selectPrintRecoveryOption(dialog, '设备或现场确认已出纸')
      await expect(confirm).toBeEnabled()
      const pending = f.page.waitForResponse((r: any) => r.url().includes('/admin-api/store/order-print-job/resolve-unknown') && r.request().method() === 'POST')
      await confirm.click()
      const responseEvent = await pending
      expect(responseEvent.request().postDataJSON()).toMatchObject({ notPrintedConfirmed: false, printedConfirmed: true })
      const response = await responseEvent.json(); expect(response.code).toBe(0)
      expect(response.data).toMatchObject({ id: reprintId, state: 'ACCEPTED', errorCode: 'OPERATOR_CONFIRMED_PRINTED' })
      await expect(dialog).toBeHidden()
      const audit = f.oracle('print-recovery', { jobId: reprintId })[0]
      expect(audit).toMatchObject({ state: 'ACCEPTED', lastError: 'OPERATOR_CONFIRMED_PRINTED',
        claimCount: 0, resolutionCount: 1, resolutionOutcome: 'PRINTED_CONFIRMED_CLAIM_RELEASED',
        claimWasPresent: 1, resolutionReason: reason, actorId: 101 })
      expect(audit.leaseExpiresAt).toBeNull()
      expect(printCounters(f)).toEqual(beforeCounters)
      const resolvedRow = printOrderRow(f.page, orderCode, '顾客反馈未收到小票')
      await expect(resolvedRow).toContainText('人工核验确认已出纸')
      await expect(resolvedRow.getByRole('button', { name: '人工重打', exact: true })).toHaveCount(0)
      const denied = await f.api('POST', '/admin-api/store/order-print-job/reprint', {
        jobId: reprintId, requestKey: `printed-recovery-reprint-${key()}`, reason: '已确认出纸，不应重打'
      }, f.adminToken, false)
      expect(denied.code).not.toBe(0)
      expect(printRows(f, order.orderId).filter((value: any) => value.kind === 'JOB')).toHaveLength(2)
      expect(printCounters(f)).toEqual(beforeCounters)
    })
    await f.check('cross-store-print-denied', async () => {
      const beforeRows = printRows(f, order.orderId), beforeCounters = printCounters(f)
      const branchContext = await f.browser.newContext({ baseURL: f.baseURL, serviceWorkers: 'block' }); staffContexts.push(branchContext)
      const branchPage = await branchContext.newPage()
      const token = await f.login(branchPage, context.accounts.staff)
      const list = await f.api('GET', '/admin-api/store/order-print-job/page?pageNo=1&pageSize=20', undefined, token, false)
      expect(list.code).toBe(403); expect(list.msg).toContain('仅总部')
      const get = await f.api('GET', `/admin-api/store/order-print-job/get?id=${jobId}`, undefined, token, false)
      expect(get.code).toBe(403); expect(get.msg).toContain('仅总部')
      const reprint = await f.api('POST', '/admin-api/store/order-print-job/reprint',
        { jobId, requestKey: `cross-store-${key()}`, reason: 'cross-store denial check' }, token, false)
      expect(reprint.code).toBe(403); expect(reprint.msg).toContain('仅总部')
      const recovery = await f.api('POST', '/admin-api/store/order-print-job/resolve-unknown',
        { jobId, requestKey: `cross-recovery-${key()}`, reason: 'cross-store denial check', senderStopped: true, notPrintedConfirmed: true }, token, false)
      expect(recovery.code).toBe(403); expect(recovery.msg).toContain('仅总部')
      expect(printRows(f, order.orderId)).toEqual(beforeRows)
      expect(printCounters(f)).toEqual(beforeCounters)
    })
    await f.check('print-job-sql-audit', () => {
      const rows = printRows(f, order.orderId)
      expect(rows.filter((value: any) => value.kind === 'EVENT')).toHaveLength(1)
      const jobs = rows.filter((value: any) => value.kind === 'JOB')
      expect(jobs).toHaveLength(2)
      const parent = jobs.find((value: any) => value.id === jobId)
      const child = jobs.find((value: any) => value.id === reprintId)
      expect(parent).toMatchObject({ purpose: 'ORDER', parentJobId: null, reprintReason: null, state: 'FAILED', errorCode: 'OPERATOR_CONFIRMED_NOT_PRINTED', attempts: 1 })
      expect(child).toMatchObject({ purpose: 'REPRINT', parentJobId: jobId, state: 'ACCEPTED', errorCode: 'OPERATOR_CONFIRMED_PRINTED' })
      expect(jobs.filter((value: any) => value.purpose === 'REPRINT')).toHaveLength(1)
      const snapshot = JSON.parse(parent.payloadSnapshot)
      expect(snapshot.orderCode).toBe(order.orderId); expect(snapshot.contactPhone).toBe('138****0001')
      expect(snapshot.contactPhone).not.toContain('13800000001')
      expect(f.oracle('fulfillment-state', { orderId: order.orderId })[0].fulfillmentStatus).toBe('ACCEPTED')
      expect(f.oracle('print-recovery', { jobId })[0]).toMatchObject({ claimCount: 0, resolutionCount: 1,
        resolutionOutcome: 'NOT_PRINTED_CONFIRMED_CLAIM_RELEASED', claimWasPresent: 1, actorId: 101 })
      expect(f.oracle('print-recovery', { jobId: reprintId })[0]).toMatchObject({ claimCount: 0, resolutionCount: 1,
        resolutionOutcome: 'PRINTED_CONFIRMED_CLAIM_RELEASED', claimWasPresent: 1, actorId: 101 })
    })
  } finally {
    await Promise.all(staffContexts.map((value: any) => value.close()))
  }
}
async function printerUnknown(f: Flow) {
  let device: number, requestId: string, row: any
  const prefix = '/admin-api/store/printer'
  const counters = () => { const value = f.bridge('printer-counters', {}); return { print: value.printCalls, status: value.statusCalls } }
  const state = () => f.oracle('printer-state', { printerId: device })[0]
  const calls = counters()
  await f.check('device-created', async () => {
    device = await f.api('POST', prefix + '/create', { name: f.title('printer'), providerCode: 'OWNED_TEST', deviceCode: f.title('device'), model: 'Synthetic', connectionType: 'LAN', enabled: true, configuration: {} })
    await f.api('PUT', prefix + '/binding', { id: device, shopId: 101 })
    await f.page.goto('/mall/web-print')
    row = f.page.getByRole('row').filter({ hasText: f.title('printer') }); await expect(row).toBeVisible()
  })
  await f.check('unknown-from-explicit-test', async () => {
    const response = f.page.waitForResponse(r => r.url().includes('/store/printer/test-print') && r.request().method() === 'POST')
    await row.getByRole('button', { name: '测试打印', exact: true }).click()
    await f.page.getByRole('dialog').getByRole('button', { name: /确定|确 定/ }).click()
    const result = await (await response).json(); expect(result.code).toBe(0); expect(result.data.state).toBe('UNKNOWN'); requestId = result.data.requestId
    await expect(row.getByRole('button', { name: '查询测试结果', exact: true })).toBeVisible()
    expect(counters()).toEqual({ print: calls.print + 1, status: calls.status })
  })
  await f.check('query-does-not-send', async () => {
    const before = state(), count = counters()
    const response = f.page.waitForResponse(r => r.url().includes('/store/printer/test-print/get') && r.request().method() === 'GET')
    await row.getByRole('button', { name: '查询测试结果', exact: true }).click()
    await f.page.getByRole('dialog').getByRole('button', { name: /确定|确 定/ }).click()
    expect((await (await response).json()).data.state).toBe('UNKNOWN')
    expect(state()).toEqual(before); expect(counters()).toEqual(count)
    expect((await f.api('GET', `${prefix}/test-print/get?id=${device}&requestId=${requestId}`)).state).toBe('UNKNOWN')
    expect(counters()).toEqual(count)
  })
  await f.check('history-shows-unknown', async () => {
    await row.getByRole('button', { name: '测试记录', exact: true }).click()
    const dialog = f.page.locator('.el-dialog:visible'); await expect(dialog).toContainText(requestId); await expect(dialog).toContainText('待确认')
    await dialog.locator('.el-dialog__header .is-hover').last().click()
  })
  await f.check('staff-denied', async () => {
    const page = await f.browser.newPage()
    try {
      const token = await f.login(page, context.accounts.staff)
      expect((await f.api('GET', `${prefix}/test-print/get?id=${device}&requestId=${requestId}`, undefined, token, false)).code).not.toBe(0)
      expect((await f.api('GET', prefix + '/page?pageNo=1&pageSize=10', undefined, token, false)).code).not.toBe(0)
    } finally { await page.close() }
  })
  await f.check('cached-status-readonly', async () => {
    const before = state(), count = counters()
    await f.api('GET', `${prefix}/status?id=${device}`)
    expect(state()).toEqual(before); expect(counters()).toEqual(count)
  })
  await f.check('unknown-test-print-recovery-audited', async () => {
    const pendingPath = `${prefix}/operation/pending?id=${device}`
    const pending = await f.api('GET', pendingPath)
    expect(pending).toMatchObject({ printerId: device, operationType: 'TEST_PRINT', requestId })
    expect(pending.operationToken).toMatch(/^[0-9a-f-]{36}$/i)
    const before = state(), count = counters()
    expect(before).toMatchObject({ operationType: 'TEST_PRINT', operationRequestId: requestId,
      operationTokenPresent: 1, operationResolutionCount: 0, jobState: 'UNKNOWN' })

    const body = { id: device, operationToken: pending.operationToken,
      reason: '供应商确认发送已停止且打印工作已结清', senderStopped: true, providerWorkSettled: true }
    const denied = await f.api('POST', `${prefix}/operation/resolve`, { ...body, providerWorkSettled: false }, f.adminToken, false)
    expect(denied.code).not.toBe(0)
    expect(state()).toEqual(before)
    expect((await f.api('GET', pendingPath)).operationToken).toBe(pending.operationToken)

    expect(await f.api('POST', `${prefix}/operation/resolve`, body)).toMatchObject({
      printerId: device, operationToken: pending.operationToken, operationType: 'TEST_PRINT', outcome: 'TEST_PRINT_UNKNOWN'
    })
    expect(await f.api('GET', pendingPath)).toBeNull()
    expect(state()).toMatchObject({ operationType: null, operationRequestId: null,
      operationTokenPresent: 0, operationResolutionCount: 1, jobState: 'UNKNOWN' })
    const actorId = (await f.api('GET', '/admin-api/system/auth/get-permission-info')).user.id
    const resolution = f.oracle('printer-operation-resolution', {
      printerId: device, operationToken: pending.operationToken, requestId
    })
    expect(resolution).toHaveLength(1)
    expect(resolution[0]).toEqual({ tenantId: 0, printerId: device, operationType: 'TEST_PRINT', requestId, outcome: 'TEST_PRINT_UNKNOWN',
      senderStopped: 1, providerWorkSettled: 1, actorId, reason: body.reason })
    expect(counters()).toEqual(count)
  })
  await f.check('explicit-status-refresh', async () => {
    const count = counters()
    expect((await f.api('POST', `${prefix}/status/refresh?id=${device}`)).state).toBe('ONLINE')
    expect(counters()).toEqual({ print: count.print, status: count.status + 1 })
    expect(state().jobs).toBe(1); expect(state().jobState).toBe('UNKNOWN')
  })
}

async function printerOperationRecovery(f: Flow) {
  const prefix = '/admin-api/store/printer'
  const pendingPath = (id: number) => `${prefix}/operation/pending?id=${id}`
  const resolvePath = `${prefix}/operation/resolve`
  const counts = () => {
    const value = f.bridge('printer-counters', {})
    return { print: value.printCalls, status: value.statusCalls }
  }
  const printerState = (id: number) => f.oracle('printer-state', { printerId: id })[0]
  const businessFingerprint = () => f.oracle('printer-operation-business-fingerprint', {})[0]
  const forwardCommittedResolveThenDrop = async (route: any, item: any, sessionToken: string,
    beforeBrowserAbort?: () => Promise<void>) => {
    const request = route.request()
    const browserUrl = new URL(request.url())
    const backend = new URL(context.backend)
    expect(request.method()).toBe('POST')
    expect(browserUrl.pathname).toBe(resolvePath)
    expect(browserUrl.origin).toBe(new URL(f.baseURL).origin)
    const backendUrl = new URL(`${browserUrl.pathname}${browserUrl.search}`, `${backend.origin}/`)
    expect(backendUrl.origin).toBe(backend.origin)
    expect(request.headers().authorization).toBe(`Bearer ${sessionToken}`)
    const command = request.postDataJSON()
    expect(command).toMatchObject({ id: item.id, operationToken: item.pending.operationToken,
      senderStopped: true, providerWorkSettled: true })
    expect(typeof command.reason).toBe('string')

    let aborted = false
    try {
      const response = await route.fetch({ url: backendUrl.href, maxRedirects: 0, maxRetries: 0 })
      const bytes = await response.body()
      const payload = JSON.parse(bytes.toString('utf8'))
      expect(response.status()).toBe(200)
      expect(response.headers()['content-type']).toMatch(/^application\/json(?:;|$)/i)
      expect(payload && typeof payload === 'object' && !Array.isArray(payload)).toBe(true)
      expect(payload.code).toBe(0)
      expect(payload.data).toMatchObject({ printerId: item.id,
        operationToken: item.pending.operationToken, operationType: item.pending.operationType })

      const audit = f.oracle('printer-operation-resolution', {
        printerId: item.id, operationToken: item.pending.operationToken, requestId: item.requestId
      })
      expect(audit).toHaveLength(1)
      expect(audit[0]).toMatchObject({ printerId: item.id, operationType: item.pending.operationType,
        requestId: item.requestId, outcome: 'TEST_PRINT_UNKNOWN' })
      expect(printerState(item.id)).toMatchObject({ operationType: null, operationRequestId: null,
        operationTokenPresent: 0, operationResolutionCount: 1, jobState: 'UNKNOWN' })
      console.log('[R2-F01] forwarded recovery response', JSON.stringify({
        address: `${backendUrl.origin}${backendUrl.pathname}`, status: response.status(),
        responseBytes: bytes.length, businessCode: payload.code, printerMatched: payload.data.printerId === item.id,
        tokenMatched: payload.data.operationToken === item.pending.operationToken,
        operationTypeMatched: payload.data.operationType === item.pending.operationType,
        sqlAuditCount: audit.length, sqlPendingCleared: true
      }))
      if (beforeBrowserAbort) await beforeBrowserAbort()
      await route.abort('failed')
      aborted = true
    } finally {
      if (!aborted) await route.abort('failed').catch(() => {})
    }
  }
  const rawApi = async (method: string, path: string, token?: string, data?: unknown, extraHeaders: Record<string, string> = {}) => {
    const response = await f.request.fetch(`${context.backend}${path}`, {
      method, data, timeout: 30000,
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...extraHeaders }
    })
    let body: any = {}
    try { body = await response.json() } catch { body = {} }
    return { status: response.status(), body }
  }
  const assertDenied = (response: { status: number; body: any }) => {
    expect(response.status).not.toBe(500)
    expect(response.status === 401 || response.status === 403 ||
      (Number.isInteger(response.body?.code) && response.body.code !== 0 && response.body.code !== 500),
    JSON.stringify({ status: response.status, code: response.body?.code,
      msg: response.body?.msg, message: response.body?.message })).toBe(true)
    expect(response.body?.code).not.toBe(500)
  }
  const createPending = async (label: string) => {
    const name = f.title(label)
    const id = await f.api('POST', `${prefix}/create`, {
      name, providerCode: 'OWNED_TEST', deviceCode: key(), model: 'Synthetic',
      connectionType: 'LAN', enabled: true, configuration: {}
    })
    await f.api('PUT', `${prefix}/binding`, { id, shopId: 101 })
    const requestId = key()
    const job = await f.api('POST', `${prefix}/test-print`, { id, requestId })
    expect(job).toMatchObject({ printerId: id, state: 'UNKNOWN', requestId })
    expectedExplicitPrints++
    const pending = await f.api('GET', pendingPath(id))
    expect(pending).toMatchObject({ printerId: id, operationType: 'TEST_PRINT', requestId })
    return { id: Number(id), name, requestId, pending }
  }
  const openOperator = async (account: any, deviceName: string) => {
    const browserContext = await f.browser.newContext({ baseURL: f.baseURL, serviceWorkers: 'block' })
    const page = await browserContext.newPage()
    const token = await f.login(page, account)
    await page.goto('/mall/web-print')
    const row = page.getByRole('row').filter({ hasText: deviceName })
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '待处理操作', exact: true }).click()
    const dialog = page.locator('.el-dialog:visible').filter({ hasText: '待处理设备操作核验' })
    await expect(dialog).toBeVisible()
    return { browserContext, page, token, row, dialog }
  }
  const fillReview = async (dialog: any, reason: string) => {
    await dialog.locator('textarea').fill(reason)
    await dialog.getByText('我已确认原发送进程已停止。', { exact: true }).click()
    await dialog.getByText('我已确认厂商侧操作已结束，并已人工核验其结果。', { exact: true }).click()
    await expect(dialog.getByRole('checkbox').nth(0)).toBeChecked()
    await expect(dialog.getByRole('checkbox').nth(1)).toBeChecked()
  }
  const resolveResponse = (page: any) => page.waitForResponse((response: any) =>
    response.url().includes('/admin-api/store/printer/operation/resolve') && response.request().method() === 'POST')

  let expectedExplicitPrints = 0
  let businessBefore: any
  let countersBefore: any
  let primary: any
  await f.check('pending-fixture-created', async () => {
    businessBefore = businessFingerprint()
    countersBefore = counts()
    primary = await createPending('recovery-primary')
    expect(counts()).toEqual({ print: countersBefore.print + 1, status: countersBefore.status })
    expect(printerState(primary.id)).toMatchObject({ tenantId: 0, operationType: 'TEST_PRINT', operationRequestId: primary.requestId,
      operationTokenPresent: 1, operationResolutionCount: 0, jobState: 'UNKNOWN' })
  })
  await f.check('hq-pending-read-is-side-effect-free', async () => {
    const login = await rawApi('POST', '/admin-api/system/auth/login', undefined, {
      username: context.accounts.hq.username, password: context.accounts.hq.password
    })
    expect(login.body.code).toBe(0)
    const token = login.body.data.accessToken
    const before = printerState(primary.id), vendor = counts()
    const pending = await f.api('GET', pendingPath(primary.id), undefined, token)
    expect(pending).toEqual(primary.pending)
    expect(printerState(primary.id)).toEqual(before)
    expect(counts()).toEqual(vendor)
  })
  await f.check('query-only-headquarters-page', async () => {
    const session = await openOperator(context.accounts.hqQuery, primary.name)
    try {
      const pending = await f.api('GET', pendingPath(primary.id), undefined, session.token)
      expect(pending).toMatchObject({ printerId: primary.id, operationType: 'TEST_PRINT', operationToken: primary.pending.operationToken })
      await expect(session.dialog).toContainText('当前账号没有解除权限')
      await expect(session.dialog.getByRole('button', { name: '解除待处理状态', exact: true })).toBeDisabled()
      const before = printerState(primary.id), vendor = counts()
      const denied = await rawApi('POST', resolvePath, session.token, {
        id: primary.id, operationToken: primary.pending.operationToken, reason: '人工核实旧操作已结束',
        senderStopped: true, providerWorkSettled: true
      })
      assertDenied(denied)
      expect(printerState(primary.id)).toEqual(before)
      expect(counts()).toEqual(vendor)
    } finally { await session.browserContext.close() }
  })
  await f.check('permission-and-tenant-denials', async () => {
    const before = printerState(primary.id), vendor = counts()
    const noQueryLogin = await rawApi('POST', '/admin-api/system/auth/login', undefined, {
      username: context.accounts.hqNoQuery.username, password: context.accounts.hqNoQuery.password
    })
    expect(noQueryLogin.body.code).toBe(0)
    assertDenied(await rawApi('GET', pendingPath(primary.id), noQueryLogin.body.data.accessToken))

    const staffLogin = await rawApi('POST', '/admin-api/system/auth/login', undefined, {
      username: context.accounts.staff.username, password: context.accounts.staff.password
    })
    expect(staffLogin.body.code).toBe(0)
    assertDenied(await rawApi('GET', pendingPath(primary.id), staffLogin.body.data.accessToken))

    const crossTenantLogin = await rawApi('POST', '/admin-api/system/auth/login', undefined, {
      username: context.accounts.hqCrossTenant.username, password: context.accounts.hqCrossTenant.password
    }, { 'tenant-id': '901' })
    expect(crossTenantLogin.body.code, JSON.stringify({
      status: crossTenantLogin.status,
      code: crossTenantLogin.body.code,
      msg: crossTenantLogin.body.msg,
      message: crossTenantLogin.body.message
    })).toBe(0)
    const crossTenantToken = crossTenantLogin.body.data.accessToken
    expect(f.oracle('printer-auth-tenant', { userId: 910113 })[0]).toEqual({ userTenantId: 901, accessTokenTenantId: 0 })
    const isolatedRead = await rawApi('GET', pendingPath(primary.id), crossTenantToken, undefined, { 'tenant-id': '901' })
    assertDenied(isolatedRead)
    assertDenied(await rawApi('GET', pendingPath(primary.id), crossTenantToken, undefined, { 'tenant-id': '902' }))
    const beforeCrossTenantResolve = printerState(primary.id), countersBeforeCrossTenantResolve = counts()
    assertDenied(await rawApi('POST', resolvePath, crossTenantToken, {
      id: primary.id, operationToken: primary.pending.operationToken,
      reason: '跨租户越权恢复应拒绝', senderStopped: true, providerWorkSettled: true
    }, { 'tenant-id': '901' }))
    expect(printerState(primary.id)).toEqual(beforeCrossTenantResolve)
    expect(counts()).toEqual(countersBeforeCrossTenantResolve)

    const shortTokenClient = async () => {
      const response = await rawApi('POST', '/admin-api/system/auth/login', undefined, {
        username: context.accounts.hq.username, password: context.accounts.hq.password
      })
      return response.body.data.accessToken
    }
    f.bridge('token-lifetime', { seconds: 2 })
    try {
      const shortToken = await shortTokenClient()
      expect((await rawApi('GET', pendingPath(primary.id), shortToken)).body.code).toBe(0)
      await new Promise(resolve => setTimeout(resolve, 3000))
      assertDenied(await rawApi('GET', pendingPath(primary.id), shortToken))
    } finally { f.bridge('token-lifetime', { seconds: 3600 }) }

    expect(printerState(primary.id)).toEqual(before)
    expect(counts()).toEqual(vendor)
  })
  await f.check('invalid-resolution-requests-no-side-effects', async () => {
    const login = await rawApi('POST', '/admin-api/system/auth/login', undefined, {
      username: context.accounts.hq.username, password: context.accounts.hq.password
    })
    const token = login.body.data.accessToken
    const before = printerState(primary.id), vendor = counts()
    const wrongToken = await rawApi('POST', resolvePath, token, {
      id: primary.id, operationToken: '00000000-0000-4000-8000-000000000001', reason: '人工核实旧操作已结束',
      senderStopped: true, providerWorkSettled: true
    })
    assertDenied(wrongToken)
    assertDenied(await rawApi('GET', pendingPath(999999), token))
    assertDenied(await rawApi('POST', resolvePath, token, {
      id: 999999, operationToken: primary.pending.operationToken, reason: '人工核实旧操作已结束',
      senderStopped: true, providerWorkSettled: true
    }))
    assertDenied(await rawApi('POST', resolvePath, token, {
      id: primary.id, operationToken: primary.pending.operationToken, reason: '人工核实旧操作已结束',
      senderStopped: false, providerWorkSettled: true
    }))
    expect(printerState(primary.id)).toEqual(before)
    expect(counts()).toEqual(vendor)
    expect(f.oracle('printer-operation-resolution', {
      printerId: primary.id, operationToken: primary.pending.operationToken, requestId: primary.requestId
    })).toHaveLength(0)
  })
  await f.check('page-resolution-records-audit', async () => {
    const before = printerState(primary.id), vendor = counts()
    const session = await openOperator(context.accounts.hq, primary.name)
    try {
      await expect(session.dialog).toContainText('测试打印')
      await expect(session.dialog).toContainText(primary.requestId)
      await fillReview(session.dialog, '厂商后台已确认原测试操作结束')
      const responseWait = resolveResponse(session.page)
      await session.dialog.getByRole('button', { name: '解除待处理状态', exact: true }).click()
      const response = await responseWait
      const body = await response.json()
      expect(body.code).toBe(0)
      expect(body.data).toMatchObject({ printerId: primary.id, operationToken: primary.pending.operationToken,
        operationType: 'TEST_PRINT', outcome: 'TEST_PRINT_UNKNOWN' })
      await expect(session.dialog).toContainText('测试打印结果仍待核验')
      const actorId = (await f.api('GET', '/admin-api/system/auth/get-permission-info', undefined, session.token)).user.id
      expect(f.oracle('printer-operation-resolution', {
        printerId: primary.id, operationToken: primary.pending.operationToken, requestId: primary.requestId
      })).toEqual([{ tenantId: 0, printerId: primary.id, operationType: 'TEST_PRINT', requestId: primary.requestId,
        outcome: 'TEST_PRINT_UNKNOWN', senderStopped: 1, providerWorkSettled: 1, actorId,
        reason: '厂商后台已确认原测试操作结束' }])
      expect(await f.api('GET', pendingPath(primary.id), undefined, session.token)).toBeNull()
      expect(printerState(primary.id)).toMatchObject({ operationType: null, operationTokenPresent: 0,
        operationResolutionCount: 1, jobState: 'UNKNOWN' })
      expect(printerState(primary.id).operationResolutionCount).toBe(before.operationResolutionCount + 1)
      expect(counts()).toEqual(vendor)
    } finally { await session.browserContext.close() }
  })
  await f.check('two-operator-race-single-audit', async () => {
    const race = await createPending('recovery-race')
    const vendor = counts()
    const first = await openOperator(context.accounts.hq, race.name)
    const second = await openOperator(context.accounts.hqB, race.name)
    try {
      const pendingA = await f.api('GET', pendingPath(race.id), undefined, first.token)
      const pendingB = await f.api('GET', pendingPath(race.id), undefined, second.token)
      expect(pendingA.operationToken).toBe(pendingB.operationToken)
      await fillReview(first.dialog, '第一操作员已完成独立核验')
      await fillReview(second.dialog, '第二操作员已完成独立核验')
      const firstResponse = resolveResponse(first.page)
      const secondResponse = resolveResponse(second.page)
      await Promise.all([
        first.dialog.getByRole('button', { name: '解除待处理状态', exact: true }).click(),
        second.dialog.getByRole('button', { name: '解除待处理状态', exact: true }).click()
      ])
      const responses = await Promise.all([firstResponse, secondResponse])
      const bodies = await Promise.all(responses.map(response => response.json()))
      expect(bodies.filter(body => body.code === 0)).toHaveLength(1)
      const denied = bodies.find(body => body.code !== 0)
      expect(denied).toBeTruthy(); expect(denied.code).not.toBe(500)
      const displays = [first.dialog, second.dialog]
      await expect.poll(async () => {
        const successes = await Promise.all(displays.map(dialog => dialog.getByText(/已记录处置/).count()))
        return successes.filter(value => value > 0).length
      }, { timeout: 15000 }).toBe(1)
      await expect.poll(async () => {
        const unresolvedNotices = await Promise.all(displays.map(dialog => dialog.getByText(/当前无待处理/).count()))
        return unresolvedNotices.reduce((sum, value) => sum + value, 0)
      }, { timeout: 15000 }).toBeGreaterThan(0)
      const audit = f.oracle('printer-operation-resolution', {
        printerId: race.id, operationToken: pendingA.operationToken, requestId: race.requestId
      })
      expect(audit).toHaveLength(1)
      expect(printerState(race.id).operationResolutionCount).toBe(1)
      expect(counts()).toEqual(vendor)
    } finally {
      await Promise.all([first.browserContext.close(), second.browserContext.close()])
    }
  })
  await f.check('same-token-role-revocation', async () => {
    const revokedDevice = await createPending('recovery-revoke')
    const session = await openOperator(context.accounts.hqRevoked, revokedDevice.name)
    try {
      await fillReview(session.dialog, '页面打开后撤权仍必须服务端拒绝')
      const before = printerState(revokedDevice.id), vendor = counts()
      await f.api('POST', '/admin-api/system/permission/assign-user-role', { userId: 910115, roleIds: [] })
      const responseWait = resolveResponse(session.page)
      await session.dialog.getByRole('button', { name: '解除待处理状态', exact: true }).click()
      const response = await responseWait
      const result = await response.json()
      expect(result.code).toBe(403)
      await expect(session.dialog).toContainText('权限已变化')
      await expect(session.dialog.getByRole('button', { name: '解除待处理状态', exact: true })).toBeDisabled()
      expect(f.oracle('printer-state', { printerId: revokedDevice.id })[0]).toEqual(before)
      expect(f.oracle('printer-operation-resolution', {
        printerId: revokedDevice.id, operationToken: revokedDevice.pending.operationToken, requestId: revokedDevice.requestId
      })).toHaveLength(0)
      expect(counts()).toEqual(vendor)
      const permissionInfo = await f.api('GET', '/admin-api/system/auth/get-permission-info', undefined, session.token)
      expect(permissionInfo.user.id).toBe(910115)
      expect(permissionInfo.roles).not.toContain('business_headquarters')
    } finally { await session.browserContext.close() }
  })
  await f.check('undelivered-command-needs-explicit-retry', async () => {
    const item = await createPending('recovery-undelivered')
    const session = await openOperator(context.accounts.hq, item.name)
    try {
      const vendor = counts()
      await fillReview(session.dialog, '核验原请求未送达后的人工重试')
      let resolveRequests = 0
      session.page.on('request', (request: any) => {
        if (request.url().includes('/admin-api/store/printer/operation/resolve') && request.method() === 'POST') resolveRequests++
      })
      await session.page.route('**/admin-api/store/printer/operation/resolve', (route: any) => route.abort('failed'))
      await session.dialog.getByRole('button', { name: '解除待处理状态', exact: true }).click()
      await expect(session.dialog).toContainText('原操作仍在待处理中')
      expect(resolveRequests).toBe(1)
      expect(await f.api('GET', pendingPath(item.id), undefined, session.token)).toMatchObject({ operationToken: item.pending.operationToken })
      expect(f.oracle('printer-operation-resolution', {
        printerId: item.id, operationToken: item.pending.operationToken, requestId: item.requestId
      })).toHaveLength(0)
      expect(counts()).toEqual(vendor)

      await session.page.unroute('**/admin-api/store/printer/operation/resolve')
      await fillReview(session.dialog, '核验原请求未送达后由操作员明确重试')
      const responseWait = resolveResponse(session.page)
      await session.dialog.getByRole('button', { name: '解除待处理状态', exact: true }).click()
      expect((await responseWait).status()).toBe(200)
      expect(resolveRequests).toBe(2)
      await expect(session.dialog).toContainText('测试打印结果仍待核验')
      expect(counts()).toEqual(vendor)
    } finally { await session.browserContext.close() }
  })
  await f.check('lost-response-with-empty-pending-is-not-success', async () => {
    const item = await createPending('recovery-response-lost')
    const session = await openOperator(context.accounts.hq, item.name)
    try {
      const vendor = counts()
      await fillReview(session.dialog, '后端已提交但浏览器响应将丢失')
      let resolveRequests = 0, pendingReads = 0
      session.page.on('request', (request: any) => {
        if (request.url().includes('/admin-api/store/printer/operation/resolve') && request.method() === 'POST') resolveRequests++
        if (request.url().includes('/admin-api/store/printer/operation/pending') && request.method() === 'GET') pendingReads++
      })
      let handlerTask: Promise<void> | undefined
      await session.page.route('**/admin-api/store/printer/operation/resolve', (route: any) => {
        handlerTask = forwardCommittedResolveThenDrop(route, item, session.token)
        return handlerTask
      })
      await session.dialog.getByRole('button', { name: '解除待处理状态', exact: true }).click()
      await expect(session.dialog).toContainText('当前无待处理，上一请求结果未确认')
      await handlerTask
      expect(resolveRequests).toBe(1); expect(pendingReads).toBe(1)
      expect(await f.api('GET', pendingPath(item.id), undefined, session.token)).toBeNull()
      expect(await session.dialog.getByText(/已记录处置/).count()).toBe(0)
      const audit = f.oracle('printer-operation-resolution', {
        printerId: item.id, operationToken: item.pending.operationToken, requestId: item.requestId
      })
      expect(audit).toHaveLength(1)
      expect(counts()).toEqual(vendor)
    } finally { await session.browserContext.close() }
  })
  await f.check('new-token-before-reread-resets-confirmation', async () => {
    const item = await createPending('recovery-new-token')
    const session = await openOperator(context.accounts.hq, item.name)
    try {
      const vendor = counts()
      await fillReview(session.dialog, '先核验旧操作，再单独测试新操作变化')
      let resolveRequests = 0, pendingReads = 0
      session.page.on('request', (request: any) => {
        if (request.url().includes('/admin-api/store/printer/operation/resolve') && request.method() === 'POST') resolveRequests++
        if (request.url().includes('/admin-api/store/printer/operation/pending') && request.method() === 'GET') pendingReads++
      })
      let nextPending: any, handlerTask: Promise<void> | undefined
      const beforeRecovery = counts()
      await session.page.route('**/admin-api/store/printer/operation/resolve', (route: any) => {
        handlerTask = forwardCommittedResolveThenDrop(route, item, session.token, async () => {
          expect(counts()).toEqual(beforeRecovery)
          const requestId = key()
          const job = await f.api('POST', `${prefix}/test-print`, { id: item.id, requestId })
          expect(job).toMatchObject({ printerId: item.id, state: 'UNKNOWN', requestId })
          expectedExplicitPrints++ // This fake-provider call creates the new-token fixture, not a recovery side effect.
          nextPending = await f.api('GET', pendingPath(item.id))
          expect(nextPending).toMatchObject({ printerId: item.id, operationType: 'TEST_PRINT', requestId })
          expect(nextPending.operationToken).not.toBe(item.pending.operationToken)
          expect(printerState(item.id)).toMatchObject({ operationType: 'TEST_PRINT',
            operationRequestId: requestId, operationTokenPresent: 1, operationResolutionCount: 1, jobState: 'UNKNOWN' })
        })
        return handlerTask
      })
      await session.dialog.getByRole('button', { name: '解除待处理状态', exact: true }).click()
      await expect(session.dialog).toContainText('发现新的待处理操作')
      await handlerTask
      expect(resolveRequests).toBe(1); expect(pendingReads).toBe(1)
      expect(nextPending.operationToken).not.toBe(item.pending.operationToken)
      await expect(session.dialog.locator('textarea')).toHaveValue('')
      await expect(session.dialog.getByRole('checkbox').nth(0)).not.toBeChecked()
      await expect(session.dialog.getByRole('checkbox').nth(1)).not.toBeChecked()
      expect(await session.dialog.getByText(/已记录处置/).count()).toBe(0)
      expect(counts()).toEqual({ print: vendor.print + 1, status: vendor.status })
      expect(f.oracle('printer-operation-resolution', {
        printerId: item.id, operationToken: item.pending.operationToken, requestId: item.requestId
      })).toHaveLength(1)
      expect(printerState(item.id)).toMatchObject({ operationType: 'TEST_PRINT', operationRequestId: nextPending.requestId,
        operationTokenPresent: 1, operationResolutionCount: 1, jobState: 'UNKNOWN' })
    } finally { await session.browserContext.close() }
  })
  await f.check('database-business-and-provider-invariants', async () => {
    expect(businessFingerprint()).toEqual(businessBefore)
    const vendor = counts()
    expect(vendor).toEqual({ print: countersBefore.print + expectedExplicitPrints, status: countersBefore.status })
    for (const item of [primary]) {
      expect(printerState(item.id)).toMatchObject({ operationTokenPresent: 0, operationResolutionCount: 1, jobState: 'UNKNOWN' })
    }
  })
}

async function printerOperationRecoveryTenantEnabled(f: Flow) {
  const backend = context.tenantEnabledBackend
  expect(typeof backend).toBe('string')
  expect(new URL(backend).origin).not.toBe(new URL(context.backend).origin)
  const prefix = '/admin-api/store/printer'
  const pendingPath = (id: number) => `${prefix}/operation/pending?id=${id}`
  const resolvePath = `${prefix}/operation/resolve`
  const counters = () => {
    const value = f.bridge('printer-counters', { targetPort: Number(new URL(backend).port) })
    return { print: value.printCalls, status: value.statusCalls }
  }
  const rawApi = async (method: string, path: string, token?: string, tenantId?: string, data?: unknown) => {
    const headers: Record<string, string> = {}
    if (token) headers.Authorization = `Bearer ${token}`
    if (tenantId !== undefined) headers['tenant-id'] = tenantId
    const response = await f.request.fetch(`${backend}${path}`, { method, data, headers, timeout: 30000 })
    let body: any = {}
    try { body = await response.json() } catch { /* Assertions below fail closed on a missing business payload. */ }
    return { status: response.status(), body }
  }
  const assertDenied = (response: { status: number; body: any }, operationToken?: string) => {
    expect(response.status).not.toBe(500)
    expect(response.status === 401 || response.status === 403 ||
      (Number.isInteger(response.body?.code) && response.body.code !== 0 && response.body.code !== 500),
    JSON.stringify({ status: response.status, code: response.body?.code, msg: response.body?.msg })).toBe(true)
    expect(response.body?.code).not.toBe(0)
    expect(response.body?.code).not.toBe(500)
    if (operationToken) expect(JSON.stringify(response.body)).not.toContain(operationToken)
  }
  const login = async (account: any, tenantId: string) => {
    const result = await rawApi('POST', '/admin-api/system/auth/login', undefined, tenantId,
      { username: account.username, password: account.password })
    expect(result.status).toBe(200)
    expect(result.body.code).toBe(0)
    expect(result.body.data.accessToken).toBeTruthy()
    return result.body.data.accessToken as string
  }
  const createPending = async (token: string, tenantId: string, label: string) => {
    const name = f.title(label)
    const created = await rawApi('POST', `${prefix}/create`, token, tenantId, {
      name, providerCode: 'OWNED_TEST', deviceCode: key(), model: 'Synthetic',
      connectionType: 'LAN', enabled: true, configuration: {}
    })
    expect(created.status).toBe(200); expect(created.body.code).toBe(0)
    const id = Number(created.body.data)
    expect(Number.isSafeInteger(id) && id > 0).toBe(true)
    const requestId = key()
    const printed = await rawApi('POST', `${prefix}/test-print`, token, tenantId, { id, requestId })
    expect(printed.status).toBe(200); expect(printed.body.code).toBe(0)
    expect(printed.body.data).toMatchObject({ printerId: id, requestId, state: 'UNKNOWN' })
    const pending = await rawApi('GET', pendingPath(id), token, tenantId)
    expect(pending.status).toBe(200); expect(pending.body.code).toBe(0)
    expect(pending.body.data).toMatchObject({ printerId: id, operationType: 'TEST_PRINT', requestId })
    expect(pending.body.data.operationToken).toMatch(/^[0-9a-f-]{36}$/i)
    expect(f.oracle('printer-state', { printerId: id })[0]).toMatchObject({
      tenantId: Number(tenantId), operationType: 'TEST_PRINT', operationRequestId: requestId,
      operationTokenPresent: 1, operationResolutionCount: 0, jobState: 'UNKNOWN'
    })
    return { id, name, requestId, operationToken: pending.body.data.operationToken }
  }
  const before = counters()
  const tenant902Token = await login(context.accounts.hqOtherTenant, '902')
  const tenant901Token = await login(context.accounts.hqCrossTenant, '901')
  expect(f.oracle('printer-auth-tenant', { userId: 910113 })[0]).toEqual({ userTenantId: 901, accessTokenTenantId: 901 })
  expect(f.oracle('printer-auth-tenant', { userId: 910117 })[0]).toEqual({ userTenantId: 902, accessTokenTenantId: 902 })
  let tenant902: any, tenant901: any, afterFixtures: any

  await f.check('tenant-enabled-same-tenant-pending-read', async () => {
    tenant902 = await createPending(tenant902Token, '902', 'recovery-enabled-tenant-902')
    tenant901 = await createPending(tenant901Token, '901', 'recovery-enabled-tenant-901')
    afterFixtures = counters()
    expect(afterFixtures).toEqual({ print: before.print + 2, status: before.status })
    const read902 = await rawApi('GET', pendingPath(tenant902.id), tenant902Token, '902')
    const read901 = await rawApi('GET', pendingPath(tenant901.id), tenant901Token, '901')
    for (const [result, item] of [[read902, tenant902], [read901, tenant901]] as const) {
      expect(result.status).toBe(200); expect(result.body.code).toBe(0)
      expect(result.body.data).toMatchObject({ printerId: item.id, requestId: item.requestId,
        operationToken: item.operationToken, operationType: 'TEST_PRINT' })
    }
    expect(counters()).toEqual(afterFixtures)
  })

  await f.check('tenant-enabled-rejects-missing-and-mismatched-context', async () => {
    assertDenied(await rawApi('GET', pendingPath(tenant901.id)), tenant901.operationToken)
    assertDenied(await rawApi('GET', pendingPath(tenant901.id), undefined, '901'), tenant901.operationToken)
    expect(await rawApi('GET', pendingPath(tenant901.id), tenant901Token)).toMatchObject({
      status: 200, body: { code: 0, data: { operationToken: tenant901.operationToken } }
    })
    assertDenied(await rawApi('GET', pendingPath(tenant901.id), tenant901Token, '902'), tenant901.operationToken)
    expect(await rawApi('GET', pendingPath(tenant902.id), tenant902Token)).toMatchObject({
      status: 200, body: { code: 0, data: { operationToken: tenant902.operationToken } }
    })
    assertDenied(await rawApi('GET', pendingPath(tenant902.id), tenant902Token, '901'), tenant902.operationToken)
    expect(await rawApi('GET', pendingPath(tenant901.id), tenant901Token, '901')).toMatchObject({
      status: 200, body: { code: 0, data: { operationToken: tenant901.operationToken } }
    })
    expect(await rawApi('GET', pendingPath(tenant902.id), tenant902Token, '902')).toMatchObject({
      status: 200, body: { code: 0, data: { operationToken: tenant902.operationToken } }
    })
    expect(counters()).toEqual(afterFixtures)
  })

  await f.check('tenant-enabled-cross-tenant-resource-and-recovery-denied', async () => {
    const before902 = f.oracle('printer-state', { printerId: tenant902.id })[0]
    const before901 = f.oracle('printer-state', { printerId: tenant901.id })[0]
    const crossReads = [
      await rawApi('GET', pendingPath(tenant902.id), tenant901Token, '901'),
      await rawApi('GET', pendingPath(tenant901.id), tenant902Token, '902')
    ]
    assertDenied(crossReads[0], tenant902.operationToken)
    assertDenied(crossReads[1], tenant901.operationToken)
    const command = (item: any) => ({ id: item.id, operationToken: item.operationToken,
      reason: '不同租户不得读取或解除该待处理操作', senderStopped: true, providerWorkSettled: true })
    const crossResolutions = [
      await rawApi('POST', resolvePath, tenant901Token, '901', command(tenant902)),
      await rawApi('POST', resolvePath, tenant902Token, '902', command(tenant901))
    ]
    assertDenied(crossResolutions[0], tenant902.operationToken)
    assertDenied(crossResolutions[1], tenant901.operationToken)
    for (const item of [tenant902, tenant901]) {
      expect(f.oracle('printer-state', { printerId: item.id })[0]).toEqual(item.id === tenant902.id ? before902 : before901)
      expect(f.oracle('printer-operation-resolution', { printerId: item.id,
        operationToken: item.operationToken, requestId: item.requestId })).toHaveLength(0)
      const sameTenant = item.id === tenant902.id
        ? await rawApi('GET', pendingPath(item.id), tenant902Token, '902')
        : await rawApi('GET', pendingPath(item.id), tenant901Token, '901')
      expect(sameTenant.body.data.operationToken).toBe(item.operationToken)
    }
    expect(counters()).toEqual(afterFixtures)
  })
}

const scenarios: Record<string, (flow: Flow) => Promise<void>> = {
  'score-redemption': scoreRedemption, 'score-retry': scoreRetry, 'printer-unknown': printerUnknown,
  'pickup-fulfillment': pickupFulfillment, 'store-statistics': storeStatistics, 'order-print-jobs': orderPrintJobs,
  'pickup-contact': pickupContact, 'auth-session': authSession, 'catalog-order': catalogOrder, 'store-isolation': storeIsolation,
  'order-retry': orderRetry, 'order-cancel': orderCancel,
  'batch-stock-operations': batchStockOperations,
  'coupon-release': flow => couponFlow(flow, false), 'coupon-complete': flow => couponFlow(flow, true),
  'printer-operation-recovery': printerOperationRecovery,
  'printer-operation-recovery-tenant-enabled': printerOperationRecoveryTenantEnabled
}
for (const selected of context.planned) test(selected.id, async ({ flow }) => { await scenarios[selected.id](flow) })
