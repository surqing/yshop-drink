import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'

const root = process.env.YSHOP_AUTH_SOURCE || new URL('../yshop-drink-uniapp-vue3/', import.meta.url)
const read = name => fs.readFileSync(typeof root === 'string' ? `${root}/${name}` : new URL(name, root), 'utf8')
const stripImports = source => source.replace(/import\s[\s\S]*?\sfrom\s*['"][^'"]+['"];?/g, '')
function page({ agreed = true, rejectExchange = false } = {}) {
  const requests = [], updates = [], errors = []
  let grants = 0
  const api = { post: async (path, body) => {
    requests.push({ path, body })
    if (rejectExchange) throw new Error('synthetic exchange failure')
    return { userInfo: { id: 101 }, accessToken: 'synthetic-token' }
  }, get: async () => ({}) }
  const auth = vm.createContext({ api })
  vm.runInContext(stripImports(read('api/auth.js')).replace(/export\s+/g, ''), auth)
  const main = { openid: 'untrusted-cached', SET_MEMBER: data => updates.push(['member', data]),
    SET_TOKEN: token => updates.push(['token', token]), SET_OPENID: () => {} }
  const context = vm.createContext({
    ref: value => ({ value }), computed: fn => ({ get value() { return fn() } }),
    onLoad: () => {}, useMainStore: () => main, util: {}, testMobible: () => true,
    userLoginForWechatMini: auth.userLoginForWechatMini, smsSend: auth.smsSend, userLogin: auth.userLogin,
    loginWechatSession: async () => ({ openId: 'synthetic-session' }),
    reportAuthError: error => errors.push(error), reportSmsError: () => {},
    uni: { login: options => { grants++; options.success({ code: 'fresh-login-proof' }) },
      showToast: () => {}, navigateBack: () => {} }, setTimeout: () => 0, console
  })
  const script = read('pages-user/login/login.vue').match(/<script setup>([\s\S]*?)<\/script>/)[1]
  vm.runInContext(stripImports(script) + '\nisChecked.value = ' + agreed + '; uToast.value = { show() {} }; globalThis.handlePhone = loginForWechatMini;', context)
  return { run: detail => context.handlePhone({ detail }), requests, updates, errors, grants: () => grants }
}

test('phone grant exchanges both fresh proofs and stores returned member credentials', async () => {
  const p = page()
  await p.run({ code: 'fresh-phone-proof', encryptedData: 'untrusted-legacy', iv: 'untrusted-legacy' })
  assert.equal(p.grants(), 1)
  assert.equal(p.requests.length, 1)
  assert.equal(p.requests[0].path, '/member/auth/weixin-mini-app-login')
  assert.deepEqual(JSON.parse(JSON.stringify(p.requests[0].body)), { phoneCode: 'fresh-phone-proof', loginCode: 'fresh-login-proof' })
  assert.deepEqual(JSON.parse(JSON.stringify(p.updates)), [['member', { id: 101 }], ['token', 'synthetic-token']])
})
test('failed exchange never automatically replays a one-time phone grant', async () => {
  const p = page({ rejectExchange: true })
  await p.run({ code: 'fresh-phone-proof' })
  assert.equal(p.grants(), 1)
  assert.equal(p.requests.length, 1)
  assert.equal(p.updates.length, 0)
  assert.equal(p.errors.length, 1)
})
test('denied phone grant or missing agreement creates no login request', async () => {
  const denied = page(); await denied.run({ errMsg: 'getPhoneNumber:fail user deny' })
  const unsigned = page({ agreed: false }); await unsigned.run({ code: 'fresh-phone-proof' })
  assert.equal(denied.grants(), 0); assert.equal(denied.requests.length, 0)
  assert.equal(unsigned.grants(), 0); assert.equal(unsigned.requests.length, 0)
})
