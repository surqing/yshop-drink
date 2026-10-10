import { test, expect, session, syntheticApi } from '../../../yshop-drink-vue3/e2e/fixtures'

test('D009 failed second coupon load must never save the previous coupon', async ({ page }) => {
  await session(page)
  const f=await syntheticApi(page)
  const other={...f.activity,id:12,title:'第二张测试券'}
  await page.route('**/admin-api/coupon/page?**',route=>route.fulfill({json:{code:0,data:{list:[f.activity,other],total:2}}}))
  await page.route('**/admin-api/coupon/get?id=12',route=>route.fulfill({json:{code:500,data:null,msg:'合成详情读取失败'}}))
  await page.goto('/mall/coupons')
  await page.getByRole('row').filter({hasText:'页面测试券'}).getByRole('button',{name:'编辑',exact:true}).click()
  const dialog=page.getByRole('dialog').filter({hasText:'编辑优惠活动'})
  const title=dialog.locator('.el-form-item').filter({hasText:'优惠券名称'}).getByRole('textbox')
  await expect(title).toHaveValue('页面测试券')
  await expect(dialog.locator('.el-loading-mask')).toHaveCount(0)
  await dialog.getByRole('button',{name:'取消',exact:true}).click()
  await page.getByRole('dialog').filter({hasText:'放弃本次未保存的活动修改？'}).getByRole('button',{name:'确定',exact:true}).click()
  await expect(dialog).toBeHidden()
  const failedLoad=page.waitForResponse(r=>r.url().includes('/coupon/get?id=12'))
  await page.getByRole('row').filter({hasText:'第二张测试券'}).getByRole('button',{name:'编辑',exact:true}).click()
  expect((await (await failedLoad).json()).code).toBe(500)
  await expect(dialog.locator('.el-loading-mask')).toHaveCount(0)
  // A failed open may close the dialog, clear the model or disable saving. It must never edit id 11.
  if(await dialog.isVisible() && await dialog.getByRole('button',{name:'保存',exact:true}).isEnabled()) {
    await title.fill('用户以为在改第二张券')
    await dialog.getByRole('button',{name:'保存',exact:true}).click()
    await expect(dialog).toBeHidden()
  }
  expect(f.writes.filter(w=>w.body.id===11),'loading coupon 12 failed; saving must not mutate previously viewed coupon 11').toHaveLength(0)
})
