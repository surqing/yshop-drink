import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const source = await readFile(new URL('../yshop-drink-uniapp-vue3/utils/sms-errors.js', import.meta.url), 'utf8')
const { normalizeSmsError, describeSmsError, reportSmsError } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
const apiSource = await readFile(new URL('../yshop-drink-uniapp-vue3/api/api.js', import.meta.url), 'utf8')

function transport(response, transportError) {
  const toasts = [], calls = []
  class Fly {
    config = {}
    request(url, data, options) {
      calls.push({ url, options })
      return transportError ? Promise.reject(transportError) : Promise.resolve(response)
    }
  }
  const context = { Fly, normalizeSmsError, cookie: { get: () => '' }, logger: { debug() {} },
    uni: { showToast: toast => toasts.push(toast), hideLoading() {} },
    handleLoginFailure() {}, isWeixin: () => false, VUE_APP_API_URL: 'http://localhost', console }
  vm.runInNewContext(apiSource.replace(/^import .*$/gm, '').replace('export default request', 'globalThis.request = request'), context)
  return { request: context.request, toasts, calls }
}

test('SMS business codes give specific safe messages for login and sending', () => {
  const cases = [
    [1002014001, '验证码已过期，请重新获取'],
    [1002014003, '验证码不正确，请重新输入'],
    [1002014004, '今日验证码发送次数已达上限，请明天再试'],
    [1002014005, '验证码发送过于频繁，请稍后再试']
  ]
  for (const [code, expected] of cases) {
    assert.equal(describeSmsError({ smsCode: code, status: 200, msg: 'synthetic-private-value' }).message, expected)
  }
  assert.equal(describeSmsError({ smsCode: 400, status: 200 }, 'send').message, '手机号格式不正确，请检查后重试')
  assert.equal(describeSmsError({ smsCode: 999, status: 200 }, 'send').message, '验证码发送失败，请稍后重试')
  assert.equal(describeSmsError({ smsCode: 999, status: 200 }, 'login').message, '短信登录失败，请稍后重试')
  assert.equal(describeSmsError({ status: 0 }, 'send').category, 'network')
})

test('SMS API normalizes failure once without showing raw backend message or generic auth toast', async () => {
  const { request, toasts, calls } = transport({ status: 200, data: { code: 1002014005,
    msg: 'synthetic-private-value', data: { token: 'synthetic-private-value' } } })
  await assert.rejects(request.post('/member/auth/send-sms-code', {}, { login: false, smsError: 'send' }), error => {
    assert.equal(error.smsCode, 1002014005)
    assert.equal(JSON.stringify(error).includes('synthetic-private-value'), false)
    assert.equal(describeSmsError(error, 'send').message, '验证码发送过于频繁，请稍后再试')
    return true
  })
  assert.equal(toasts.length, 0) // The login page owns the single safe toast.
  assert.equal(calls[0].options.smsError, undefined)
})

test('SMS success and WeChat exchange behavior remain separate', async () => {
  const success = transport({ status: 200, data: { code: 0, data: { sent: true } } })
  assert.equal((await success.request.post('/sms', {}, { smsError: 'send' })).sent, true)
  const exchange = transport({ status: 200, data: { code: 1004004002, msg: 'synthetic-private-value' } })
  await assert.rejects(exchange.request.post('/auth-session', {}, { authError: true }), error => {
    assert.equal(error.authCode, 1004004002)
    assert.equal(error.smsCode, undefined)
    assert.equal(JSON.stringify(error).includes('synthetic-private-value'), false)
    return true
  })
})

test('network/unknown errors cannot leak response, code, phone or backend text', async () => {
  const failure = transport(null, { status: 0, message: 'synthetic-private-value' })
  await assert.rejects(failure.request.post('/sms', {}, { smsError: 'send' }), error => {
    assert.equal(describeSmsError(error, 'send').category, 'network')
    assert.equal(JSON.stringify(error).includes('synthetic-private-value'), false)
    return true
  })
  const oldWarn = console.warn, logs = [], toasts = []
  console.warn = (...args) => logs.push(args)
  globalThis.uni = { showToast: toast => toasts.push(toast) }
  try {
    reportSmsError({ smsCode: 999, status: 200, data: { phone: 'synthetic-private-value' } }, 'send')
    assert.equal(toasts.length, 1)
    assert.equal(toasts[0].title, '验证码发送失败，请稍后重试')
    assert.equal(JSON.stringify({ logs, toasts }).includes('synthetic-private-value'), false)
  } finally {
    console.warn = oldWarn
    delete globalThis.uni
  }
})
