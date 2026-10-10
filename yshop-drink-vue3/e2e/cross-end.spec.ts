import { readFileSync } from 'node:fs'
import { test, expect, session } from './fixtures'

// This suite requires an ownership-verified disposable backend and fresh private identities.
// It is dispatched separately; missing fixtures fail, never skip or fall back to synthetic HTTP.
const fixturePath = process.env.YSHOP_CROSS_FIXTURE
test.beforeEach(async ({ page }) => {
  if (!fixturePath) throw new Error('OWNED_CROSS_FIXTURE_REQUIRED')
  const f = JSON.parse(readFileSync(fixturePath, 'utf8'))
  if (f.owner !== process.env.YSHOP_CROSS_OWNER || f.sourceSha !== process.env.YSHOP_QUALITY_SOURCE_SHA || f.sourceDigest !== process.env.YSHOP_QUALITY_SOURCE_DIGEST) throw new Error('CROSS_FIXTURE_IDENTITY_MISMATCH')
  const backend = new URL(f.backend)
  if (backend.hostname !== '127.0.0.1' || backend.protocol !== 'http:') throw new Error('OWNED_LOOPBACK_REQUIRED')
  const info = await page.request.get(`${f.backend}/actuator/info`)
  expect((await info.json()).qualityOwner).toBe(f.owner)
  await page.route('**/admin-api/**', async route => {
    const url = new URL(route.request().url())
    if (/\/order\/pay|\/pay\/order|\/wechat-v3\/|\/prepay|\/refund|\/recharge/.test(url.pathname)) return route.abort('blockedbyclient')
    const response = await route.fetch({ url: `${f.backend}${url.pathname}${url.search}`, maxRedirects: 0 })
    await route.fulfill({ response })
  })
  await session(page, f.admin)
})

test('owned Vue coupon edit persists to Spring and MySQL', async ({ page }) => {
  const f = JSON.parse(readFileSync(fixturePath!, 'utf8'))
  await page.goto('/mall/coupons')
  const row = page.getByRole('row').filter({ hasText: 'QA Coupon' })
  await row.getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = page.getByRole('dialog').filter({ hasText: '编辑优惠活动' })
  await dialog.locator('.el-form-item').filter({ hasText: '优惠券名称' }).getByRole('textbox').fill(f.couponTitle)
  await expect(dialog.getByRole('switch')).toBeChecked()
  await dialog.locator('.el-switch__core').click()
  await expect(dialog.getByRole('switch')).not.toBeChecked()
  const response = page.waitForResponse(r => r.url().includes('/admin-api/coupon/update') && r.request().method() === 'PUT')
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  expect((await (await response).json()).code).toBe(0)
  await expect(dialog).toBeHidden()
  await page.reload()
  await expect(page.getByRole('row').filter({ hasText: f.couponTitle }).getByText('下架', { exact: true })).toBeVisible()
})

test('owned Vue sees unpaid order and staff cannot see other store order', async ({ page, browser, baseURL }) => {
  const f = JSON.parse(readFileSync(fixturePath!, 'utf8'))
  await page.goto('/mall/orders')
  await page.getByPlaceholder('请输入订单号').fill(f.orderA)
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.getByRole('cell', { name: `自取 ${f.orderA}`, exact: true })).toBeVisible()
  const context = await browser.newContext({ baseURL, serviceWorkers: 'block' })
  try {
    const staff = await context.newPage()
    await context.route('**/*', async route => {
      const url = new URL(route.request().url())
      if (url.origin !== new URL(baseURL!).origin) return route.abort()
      if (url.pathname.startsWith('/admin-api/')) {
        if (/\/order\/pay|\/pay\/order|\/refund|\/recharge|\/wechat-v3\//.test(url.pathname)) return route.abort()
        return route.fulfill({ response: await route.fetch({ url: `${f.backend}${url.pathname}${url.search}`, maxRedirects: 0 }) })
      }
      return route.continue()
    })
    await session(staff, f.staff)
    await staff.goto('/mall/orders')
    await staff.getByPlaceholder('请输入订单号').fill(f.orderB)
    const response = staff.waitForResponse(r => r.url().includes('/admin-api/order/store-order/page') && r.url().includes(f.orderB))
    await staff.getByRole('button', { name: '搜索', exact: true }).click()
    const body = await (await response).json()
    expect(body.code).toBe(0);expect(body.data.list).toEqual([])
    await expect(staff.getByRole('cell', { name: `自取 ${f.orderB}`, exact: true })).toHaveCount(0)
    await staff.getByPlaceholder('请输入订单号').fill(f.orderA)
    await staff.getByRole('button', { name: '搜索', exact: true }).click()
    await expect(staff.getByRole('cell', { name: `自取 ${f.orderA}`, exact: true })).toBeVisible()
  } finally { await context.close() }
})
