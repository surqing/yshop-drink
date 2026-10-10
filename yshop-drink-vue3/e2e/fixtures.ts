import { test as base, expect, type Page, type Route } from '@playwright/test'

export { expect }
export const test = base.extend<{ guard: void }>({
  guard: [async ({ context, baseURL }, use) => {
    const origin = new URL(baseURL!).origin
    await context.route('**/*', async route => {
      const url = new URL(route.request().url())
      // Block external analytics/provider requests, and all financial admissions even locally.
      if (url.origin !== origin || (/^\/(?:admin|app)-api\//.test(url.pathname) && /\/order\/pay|\/pay\/order|\/wechat-v3\/|\/prepay|\/refund|\/recharge/.test(url.pathname))) {
        await route.abort('blockedbyclient'); return
      }
      await route.fallback()
    })
    await use()
  }, { auto: true }]
})

export const menus = [{ id: 3110, parentId: 0, name: '经营管理', path: '/mall', visible: true,
  children: [{ id: 3112, parentId: 3110, name: '优惠券', path: 'coupons',
    component: 'mall/coupon/index', componentName: 'QualityCoupons', visible: true }] }]

export async function session(page: Page, access = 'synthetic-browser-session', refresh = '') {
  await page.addInitScript(({ access, refresh }) => {
    const put = (key: string, value: string) => localStorage.setItem(key, JSON.stringify({ c: Date.now(), e: Date.now() + 3600000, v: JSON.stringify(value) }))
    put('ACCESS_TOKEN', access)
    if (refresh) put('REFRESH_TOKEN', refresh)
  }, { access, refresh })
}

export async function syntheticApi(page: Page, options: { staff?: boolean; rejectSave?: boolean } = {}) {
  const writes: Array<{ path: string; body: any }> = []
  const now = Date.now()
  const activity = { id: 11, title: '页面测试券', shopId: '101', shopName: '测试门店 A', least: 30,
    value: 5, distribute: 100, receive: 0, limit: 1, isSwitch: 1, couponKind: 'REGULAR',
    claimMode: 'PUBLIC', type: 0, startTime: now, endTime: now + 86400000,
    claimStartTime: now, claimEndTime: now + 86400000, image: '', instructions: '' }
  let reject = !!options.rejectSave
  const reply = (route: Route, data: unknown, code = 0, msg = '') => route.fulfill({ json: { code, data, msg } })
  await page.route('**/admin-api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    if (/\/order\/pay|\/pay\/order|\/wechat-v3\/|\/prepay|\/refund|\/recharge/.test(path)) return route.abort('blockedbyclient')
    if (path === '/admin-api/system/auth/get-permission-info') return reply(route, {
      user: { id: 101, nickname: '合成测试身份', avatar: '', deptId: 0 },
      roles: options.staff ? ['quality_staff'] : ['super_admin'],
      permissions: options.staff ? ['coupon::query'] : ['coupon::query', 'coupon::create', 'coupon::update'], menus
    })
    if (path === '/admin-api/system/dict-data/simple-list') return reply(route, [])
    if (path === '/admin-api/coupon/page') return reply(route, { list: [activity], total: 1 })
    if (path === '/admin-api/coupon/get') return reply(route, activity)
    if (path === '/admin-api/store/shop/list') return reply(route, [{ id: 101, name: '测试门店 A' }, { id: 102, name: '测试门店 B' }])
    if (path === '/admin-api/coupon/update' || path === '/admin-api/coupon/create') {
      const body = request.postDataJSON(); writes.push({ path, body })
      if (reject) { reject = false; return reply(route, null, 403, '合成拒绝：无此门店权限') }
      Object.assign(activity, body); return reply(route, true)
    }
    if (path === '/admin-api/system/notify-message/get-unread-count') return reply(route, 0)
    if (path === '/admin-api/system/notify-message/get-unread-list') return reply(route, [])
    // Unknown traffic must fail, never silently simulate a successful business write.
    return reply(route, null, 500, '未配置的合成接口')
  })
  return { writes, activity }
}
