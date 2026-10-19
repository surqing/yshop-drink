import test from 'node:test'
import assert from 'node:assert/strict'

const { describeAuthError, reportAuthError } = await import(new URL('../yshop-drink-uniapp-vue3/utils/auth-errors.js', import.meta.url))

test('distinguishes cancellation, WeChat, exchange and network failures', () => {
  assert.equal(describeAuthError({ errMsg: 'getPhoneNumber:fail user deny' }, 'phone').category, 'cancelled')
  assert.equal(describeAuthError({ errMsg: 'login:fail', errCode: -1 }, 'wechat').category, 'wechat')
  assert.deepEqual(describeAuthError({ authCode: 1004004002, status: 200 }), {
    category: 'backend', code: 1004004002, message: '登录暂时未成功，请稍后重试'
  })
  assert.equal(describeAuthError({ authCategory: 'network' }).category, 'network')
})

test('never logs raw messages, response bodies or string credentials', () => {
  const originalWarn = console.warn
  const logs = []
  let toast
  console.warn = (...args) => logs.push(args)
  globalThis.uni = { showToast: options => { toast = options } }
  try {
    const sensitive = 'private-test-value'
    reportAuthError({ message: sensitive, authCode: sensitive, response: { data: { token: sensitive } } })
    assert.equal(JSON.stringify(logs).includes(sensitive), false)
    assert.equal(toast.title.includes(sensitive), false)
    assert.deepEqual(logs, [['[auth]', { category: 'backend', code: null }]])
  } finally {
    console.warn = originalWarn
    delete globalThis.uni
  }
})
