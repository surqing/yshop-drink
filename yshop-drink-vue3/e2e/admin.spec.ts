import { test, expect, session, syntheticApi } from './fixtures'

test('anonymous protected page redirects to actual login', async ({ page }) => {
  await page.goto('/mall/coupons')
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await expect(page.getByPlaceholder('请输入用户名')).toBeVisible()
})

test('coupon edit survives reload with actual Element Plus form', async ({ page }) => {
  await session(page); const api = await syntheticApi(page)
  await page.goto('/mall/coupons')
  await page.getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = page.getByRole('dialog').filter({ hasText: '编辑优惠活动' })
  await dialog.locator('.el-form-item').filter({ hasText: '优惠券名称' }).getByRole('textbox').fill('页面修改后的券')
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByText('活动已保存，已领取权益保持不变')).toBeVisible()
  expect(api.writes).toHaveLength(1)
  expect(api.writes[0].body).toMatchObject({ title: '页面修改后的券', shopId: '101', score: 0 })
  await page.reload()
  await expect(page.getByRole('cell', { name: '页面修改后的券', exact: true })).toBeVisible()
})

test('required fields prevent coupon creation request', async ({ page }) => {
  await session(page); const api = await syntheticApi(page)
  await page.goto('/mall/coupons')
  await page.getByRole('button', { name: '新增', exact: true }).click()
  const dialog = page.getByRole('dialog').filter({ hasText: '创建优惠活动' })
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog.getByText('请填写此项').first()).toBeVisible()
  expect(api.writes).toHaveLength(0)
})

test('403 retains coupon edit and retry sends unchanged business fields', async ({ page }) => {
  await session(page); const api = await syntheticApi(page, { rejectSave: true })
  await page.goto('/mall/coupons')
  await page.getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = page.getByRole('dialog').filter({ hasText: '编辑优惠活动' })
  await dialog.locator('.el-form-item').filter({ hasText: '优惠券名称' }).getByRole('textbox').fill('保留未保存修改')
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByText('合成拒绝：无此门店权限')).toBeVisible()
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog).toBeHidden()
  expect(api.writes).toHaveLength(2)
  expect(api.writes[0].body).toEqual(api.writes[1].body)
})

test('staff page omits unauthorized coupon write controls', async ({ page }) => {
  await session(page); await syntheticApi(page, { staff: true })
  await page.goto('/mall/coupons')
  await expect(page.getByRole('cell', { name: '页面测试券', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '新增', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '编辑', exact: true })).toHaveCount(0)
})
