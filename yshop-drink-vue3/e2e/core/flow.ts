import { test as base, expect, type Page, type APIRequestContext, type Browser, type Locator } from '@playwright/test'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { execFileSync } from 'node:child_process'
import { createRequire } from 'node:module'
export const context = JSON.parse(readFileSync(process.env.YSHOP_E2E_CONTEXT!, 'utf8'))
const require = createRequire(join(context.repo, 'yshop-drink-vue3/package.json'))

export class Flow {
  public adminToken = ''
  public mini: any = null
  public checks: Array<Record<string, unknown>> = []
  public cleanup = 'NOT_RUN'
  public selected: any
  constructor(public page: Page, public request: APIRequestContext, public browser: Browser, public baseURL: string, public id: string) {
    this.selected = context.planned.find((s: any) => s.id === id)
    if (!this.selected) throw Error('UNREGISTERED_SCENARIO')
  }
  save() {
    writeFileSync(join(context.output, `case-${this.id}.json`), JSON.stringify({ checks: this.checks, driversCleanup: this.cleanup }), { mode: 0o600 })
  }
  async check(id: string, action: () => Promise<unknown> | unknown) {
    if (!this.selected.expectedCheckIds.includes(id) || this.checks.some(c => c.id === id)) throw Error('UNREGISTERED_OR_DUPLICATE_CHECK')
    const receipt: Record<string, unknown> = { id, executed: true, status: 'FAILED' }
    this.checks.push(receipt); this.save()
    try {
      await base.step(id, async () => {
        await action()
        if (process.env.YSHOP_E2E_INJECT_FAILURE === `${this.id}/${id}`) {
          receipt.reasonCode = 'INJECTED_ASSERTION'
          expect(false, 'explicit deterministic negative probe').toBe(true)
        }
      })
      receipt.status = 'PASSED'
    } catch (error) { receipt.reasonCode ||= 'E2E_ASSERTION_FAILED'; throw error }
    finally { this.save() }
  }
  async guard(page: Page) {
    const origin = new URL(this.baseURL).origin
    await page.route('**/*', async route => {
      const url = new URL(route.request().url())
      if (url.protocol === 'data:' || url.protocol === 'blob:') return route.continue()
      if (url.origin !== origin) return route.abort('blockedbyclient')
      if (url.pathname.startsWith('/admin-api/')) {
        if (/\/order\/pay|\/pay\/order|\/prepay|\/refund|\/recharge|\/notify|\/wechat-v3\//.test(url.pathname)) return route.abort('blockedbyclient')
        return route.fulfill({ response: await route.fetch({ url: `${context.backend}${url.pathname}${url.search}`, maxRedirects: 0 }) })
      }
      return route.continue()
    })
  }
  async login(page = this.page, account = context.accounts.admin) {
    await this.guard(page)
    await page.goto('/login')
    await page.getByPlaceholder('请输入用户名', { exact: true }).fill(account.username)
    await page.getByPlaceholder('请输入密码', { exact: true }).fill(account.password)
    const pending = page.waitForResponse(r => r.url().endsWith('/admin-api/system/auth/login') && r.request().method() === 'POST')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    const response = await (await pending).json()
    expect(response.code).toBe(0)
    await page.waitForURL(url => !url.pathname.includes('/login'))
    return response.data.accessToken as string
  }
  async prepare() {
    const info = await this.request.get(`${context.backend}/actuator/info`)
    expect((await info.json()).qualityOwner).toBe(context.owner)
    this.adminToken = await this.login()
    if (context.profile === 'local') {
      const { MiniDriver } = require(join(context.repo, 'tests/mini/e2e-driver.cjs'))
      this.mini = await MiniDriver.launch(context.mini, join(context.output, `mini-${this.id}`))
      if (this.id !== 'auth-session') await this.mini.login(1)
    }
  }
  async finish(success: boolean) {
    try {
      if (this.mini) {
        if (!success) await this.mini.screenshot().catch(() => {})
        await this.mini.close()
      }
      this.cleanup = 'PASSED'
    } catch (error) { this.cleanup = 'FAILED'; throw error }
    finally { this.save() }
    if (success) expect(this.checks.map(c => c.id).sort()).toEqual([...this.selected.expectedCheckIds].sort())
  }
  async api(method: string, path: string, data?: unknown, token = this.adminToken, success = true) {
    if (!path.startsWith('/admin-api/') && !path.startsWith('/app-api/')) throw Error('OWNED_API_REQUIRED')
    if (/\/order\/pay|\/pay\/order|\/prepay|\/refund|\/recharge|\/notify|\/wechat-v3\//.test(path)) throw Error('PROVIDER_PATH_FORBIDDEN')
    const result = await this.request.fetch(`${context.backend}${path}`, { method, data,
      headers: token ? { Authorization: `Bearer ${token}` } : {} })
    const body = await result.json()
    if (success) expect(body.code, path).toBe(0)
    return success ? body.data : body
  }
  oracle(name: string, ids: Record<string, unknown>) {
    return JSON.parse(execFileSync(context.python, ['-m', 'e2e.oracle', '--context', process.env.YSHOP_E2E_CONTEXT!, '--check', name, '--ids', JSON.stringify(ids)],
      { cwd: context.repo, env: process.env, encoding: 'utf8', timeout: 45000 }))
  }
  bridge(action: string, ids: Record<string, unknown>) {
    return JSON.parse(execFileSync(context.python, ['-m', 'e2e.bridge', '--context', process.env.YSHOP_E2E_CONTEXT!, '--action', action, '--ids', JSON.stringify(ids)],
      { cwd: context.repo, env: process.env, encoding: 'utf8', timeout: 65000 }))
  }
  async member(principal = 1) {
    const uuid = () => require('node:crypto').randomBytes(16).toString('hex')
    return (await this.api('POST', '/app-api/member/auth/weixin-mini-app-login', {
      loginCode: `e2e-login-p${principal}-${uuid()}`, phoneCode: `e2e-phone-p${principal}-${uuid()}`
    }, '')).accessToken as string
  }
  title(label = this.id) { return `E2E ${context.runId.slice(0, 8)} ${label}` }
  async productData(id: number) {
    return this.api('GET', `/admin-api/product/catalog/configuration?productId=${id}`)
  }
  async createProduct(shopId = 101, label = this.id, price = 19) {
    const image = 'data:image/svg+xml;base64,PHN2Zy8+'
    const data = { shopId, image, slider_image: [image], store_name: this.title(label), store_info: 'synthetic', keyword: 'synthetic', cate_id: String(shopId),
      description: 'synthetic E2E fixture', unit_name: '杯', is_show: 1, is_integral: 0, spec_type: 0, items: [],
      attrs: [{ price, stock: 10, pic: image, cost: 0, ot_price: price, sku: '默认', detail: {} }] }
    await this.api('POST', '/admin-api/product/store-product/create', data)
    const products = await this.api('GET', `/admin-api/product/store-product/page?pageNo=1&pageSize=100&shopId=${shopId}&storeName=${encodeURIComponent(data.store_name)}`)
    const product = products.list.find((p: any) => p.storeName === data.store_name)
    expect(product).toBeTruthy()
    return { ...product, ...(await this.productData(product.id)), name: data.store_name }
  }
  async createOrder(product: any, token: string, extra: Record<string, unknown> = {}) {
    const body = { shopId: String(product.shopId || 101), mobile: '13800000001', couponId: '0', orderType: 'takein',
      productId: [String(product.id || product.productId)], spec: [product.skus[0].sku], number: ['1'], payType: 'weixin',
      idempotencyKey: require('node:crypto').randomBytes(16).toString('hex'), choices: [{ version: product.version, selections: [] }], ...extra }
    const result = await this.api('POST', '/app-api/order/create', body, token)
    return { orderId: result.orderId, body }
  }
  async viewOrder(orderId: string, expectedText?: string, page = this.page) {
    await page.goto('/mall/orders')
    await page.getByPlaceholder('请输入订单号').fill(orderId)
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    const row = page.getByRole('row').filter({ hasText: orderId })
    await expect(row).toBeVisible()
    if (expectedText) await expect(row).toContainText(expectedText)
    return row
  }
  item(dialog: Locator, label: string) { return dialog.locator('.el-form-item').filter({ has: dialog.page().locator('.el-form-item__label', { hasText: label }) }) }
  async select(container: Locator, value: string) {
    await container.locator('.el-select').click()
    await this.page.getByRole('option', { name: value, exact: true }).filter({ visible: true } as any).click()
  }
  async image(container: Locator) {
    await container.locator('.el-upload--picture-card').click()
    const library = this.page.getByRole('dialog', { name: '图片素材库', exact: true })
    await library.getByRole('checkbox', { name: '选择' }).first().check()
    await library.getByRole('button', { name: '确 定', exact: true }).click()
    await expect(library).toBeHidden()
  }
  async createProductUI() {
    await this.page.goto('/mall/products')
    await this.page.getByRole('button', { name: '新增', exact: true }).click()
    const dialog = this.page.getByRole('dialog').filter({ hasText: '基本信息' })
    await this.select(this.item(dialog, '展示店铺'), 'QA Shop A')
    await dialog.getByPlaceholder('请输入商品名称').fill(this.title())
    await this.select(this.item(dialog, '商品分类'), 'QA Drinks')
    await dialog.getByPlaceholder('请输入单位名').fill('杯')
    await dialog.getByPlaceholder('请输入商品价格').fill('19')
    await dialog.getByPlaceholder('请输入库存', { exact: true }).fill('10')
    await this.image(this.item(dialog, '封面图'))
    await this.image(this.item(dialog, '轮播图'))
    await dialog.getByRole('tab', { name: '商品规格', exact: true }).click()
    const row = dialog.locator('.el-table__body-wrapper .el-table__row').filter({ visible: true } as any).first()
    const inputs = row.getByRole('textbox')
    await inputs.nth(0).fill('19')
    await inputs.nth(3).fill('10')
    await dialog.getByRole('tab', { name: '营销设置', exact: true }).click()
    const pending = this.page.waitForResponse(r => r.url().endsWith('/admin-api/product/store-product/create') && r.request().method() === 'POST')
    await dialog.getByRole('button', { name: '保 存', exact: true }).click()
    expect((await (await pending).json()).code).toBe(0)
    await expect(dialog).toBeHidden()
    const products = await this.api('GET', `/admin-api/product/store-product/page?pageNo=1&pageSize=100&storeName=${encodeURIComponent(this.title())}`)
    const product = products.list.find((p: any) => p.storeName === this.title())
    expect(product).toBeTruthy()
    return { ...product, ...(await this.productData(product.id)), name: this.title() }
  }
  async configureToppingUI(product: any) {
    await this.page.goto('/mall/products')
    await this.page.getByPlaceholder('请输入商品名称').fill(product.name)
    await this.page.getByRole('button', { name: '搜索', exact: true }).click()
    await this.page.getByRole('row').filter({ hasText: product.name }).getByRole('button', { name: '经营设置', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '商品经营设置', exact: true })
    await expect(dialog.locator('.el-loading-mask')).toHaveCount(0)
    await dialog.getByRole('button', { name: '添加分组', exact: true }).click()
    await dialog.getByPlaceholder('例如温度、甜度、冰量、加料').fill('E2E 加料')
    await dialog.getByRole('radio', { name: '加料', exact: true }).check()
    await dialog.getByRole('button', { name: '添加选项', exact: true }).click()
    const row = dialog.locator('.el-table__body-wrapper .el-table__row').first()
    await row.getByRole('textbox').fill('珍珠')
    await row.getByRole('spinbutton').nth(0).fill('2')
    const pending = this.page.waitForResponse(r => r.url().includes('/product/catalog/configuration') && r.request().method() === 'PUT')
    await dialog.getByRole('button', { name: '保存定制与加料', exact: true }).click()
    expect((await (await pending).json()).code).toBe(0)
    await dialog.getByRole('button', { name: 'Close this dialog', exact: true }).click()
    return { ...product, ...(await this.productData(product.id)) }
  }
  async changePriceUI(product: any, price: number) {
    await this.page.goto('/mall/products')
    await this.page.getByPlaceholder('请输入商品名称').fill(product.name)
    await this.page.getByRole('button', { name: '搜索', exact: true }).click()
    await this.page.getByRole('row').filter({ hasText: product.name }).getByRole('button', { name: '经营设置', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '商品经营设置', exact: true })
    await dialog.getByRole('tab', { name: '规格价格与库存', exact: true }).click()
    await dialog.getByRole('button', { name: '修改售价', exact: true }).first().click()
    let prompt = this.page.getByRole('dialog').filter({ hasText: '请输入新的售价' })
    await prompt.getByRole('textbox').fill(String(price)); await prompt.getByRole('button', { name: '确定', exact: true }).click()
    prompt = this.page.getByRole('dialog').filter({ hasText: '填写改价原因' })
    await prompt.getByRole('textbox').fill('E2E snapshot verification')
    const pending = this.page.waitForResponse(r => r.url().includes('/product/catalog/price') && r.request().method() === 'PUT')
    await prompt.getByRole('button', { name: '确定', exact: true }).click()
    expect((await (await pending).json()).code).toBe(0)
    await dialog.getByRole('button', { name: 'Close this dialog', exact: true }).click()
  }
  async createCouponUI(label = this.id) {
    await this.page.goto('/mall/coupons')
    await this.page.getByRole('button', { name: '新增', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '创建优惠活动', exact: true })
    await expect(dialog.locator('.el-loading-mask')).toHaveCount(0)
    await this.item(dialog, '优惠券名称').getByRole('textbox').fill(this.title(label))
    await this.select(this.item(dialog, '适用门店'), 'QA Shop A')
    await dialog.locator('.el-form-item__label').filter({ hasText: '优惠金额' }).click()
    await this.item(dialog, '商品满额').getByRole('spinbutton').fill('0')
    await this.item(dialog, '优惠金额').getByRole('spinbutton').fill('5')
    const pending = this.page.waitForResponse(r => r.url().endsWith('/admin-api/coupon/create') && r.request().method() === 'POST')
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    const response = await (await pending).json(); expect(response.code).toBe(0)
    await expect(dialog).toBeHidden()
    return { id: response.data, title: this.title(label) }
  }
  async disableCouponUI(coupon: any) {
    await this.page.goto('/mall/coupons')
    await this.page.getByPlaceholder('请输入优惠券名称').fill(coupon.title)
    await this.page.getByRole('button', { name: '搜索', exact: true }).click()
    await this.page.getByRole('row').filter({ hasText: coupon.title }).getByRole('button', { name: '编辑', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '编辑优惠活动', exact: true })
    await expect(this.item(dialog, '优惠券名称').getByRole('textbox')).toHaveValue(coupon.title)
    await expect(dialog.locator('.el-loading-mask')).toHaveCount(0)
    await dialog.locator('.el-switch__core').click()
    const pending = this.page.waitForResponse(r => r.url().endsWith('/admin-api/coupon/update') && r.request().method() === 'PUT')
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    expect((await (await pending).json()).code).toBe(0)
    await expect(dialog).toBeHidden()
  }
}

export const test = base.extend<{ flow: Flow }>({
  flow: async ({ page, request, browser, baseURL }, use, testInfo) => {
    const flow = new Flow(page, request, browser, baseURL!, testInfo.title)
    flow.save()
    try { await flow.prepare(); await use(flow) }
    finally { await flow.finish(testInfo.status === 'passed') }
  }
})
export { expect }
