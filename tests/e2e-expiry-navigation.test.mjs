import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import { setTimeout as sleep } from 'node:timers/promises'

// Execute the actual scenario action, including its waits and business assertions.
const source = fs.readFileSync(new URL('../yshop-drink-vue3/e2e/core/core.spec.ts', import.meta.url), 'utf8')
const start = source.indexOf("  if (local) await f.check('mini-expired-session'")
const end = source.indexOf("  await f.check('logout-revokes-access'", start)
assert.ok(start >= 0 && end > start, 'expired-session scenario action must exist')
const action = source.slice(start, end).replace(/\(r: any\)/g, '(r)')

function expectation(actual) { return { toBe: expected => assert.equal(actual, expected) } }
expectation.poll = read => {
  const check = async (expected, negate) => {
    const deadline = Date.now() + 1000
    do {
      if ((await read() === expected) !== negate) return
      await sleep(2)
    } while (Date.now() < deadline)
    throw Error('condition timeout')
  }
  return { toBe: expected => check(expected, false), not: { toBe: expected => check(expected, true) } }
}

function fixture({ returns = true, redirects = true } = {}) {
  let route = 'pages-user/login/login', returned = false, opened = false, calls = 0, timer
  const mini = {
    loginPage: async () => ({}),
    authorize: async () => {
      if (returns) timer = setTimeout(() => { returned = true; route = 'pages/index/index' }, 20)
      return 'synthetic-token'
    },
    mini: {
      currentPage: async () => ({ path: route }),
      navigateTo: async () => {
        assert.equal(returned, true, 'protected navigation overlaps the pending login return')
        opened = true
        route = redirects ? 'pages-user/login/login' : 'pages-user/address/address'
      }
    },
    state: async () => ({ requests: [{ path: '/app-api/address/list', responseCode: 401 }] })
  }
  const flow = { mini, bridge() {}, check: async (_id, run) => run(),
    api: async () => ++calls === 1 ? { id: 101 } : { code: 401 } }
  const context = vm.createContext({ local: true, expect: expectation })
  vm.runInContext(`globalThis.run = async f => { ${action} }`, context)
  return { run: () => context.run(flow), opened: () => opened, close: () => clearTimeout(timer) }
}

test('expired-session waits for the real login return before opening the protected page', async () => {
  const f = fixture()
  try { await f.run(); assert.equal(f.opened(), true) } finally { f.close() }
})
test('missing login return fails before opening another page', async () => {
  const f = fixture({ returns: false })
  try { await assert.rejects(f.run(), /condition timeout/); assert.equal(f.opened(), false) } finally { f.close() }
})
test('waiting for login return still rejects a missing expired-session redirect', async () => {
  const f = fixture({ redirects: false })
  try { await assert.rejects(f.run(), /condition timeout/); assert.equal(f.opened(), true) } finally { f.close() }
})
