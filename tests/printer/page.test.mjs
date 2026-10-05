import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'

// Execute the real SFC behavior with Vue reactivity and controlled API responses.
const require = createRequire(new URL('../../yshop-drink-vue3/package.json', import.meta.url))
const ts = require('typescript')
const { ref, reactive, computed, watch, nextTick } = require('vue')
const source = await readFile(new URL('../../yshop-drink-vue3/src/views/mall/store/printer/index.vue', import.meta.url), 'utf8')
const script = source.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
const code = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
const factory = new Function('ref', 'reactive', 'computed', 'watch', 'onMounted', 'defineOptions', 'useMessage', 'useCache', 'CACHE_KEY', 'formatDate', 'Api', 'sessionStorage', 'crypto',
  code + ';return {printTest,allowNewTest,testActionLabel,testActionDisabled,openJobs,loadJobs,jobsVisible,jobsPrinter,jobList,jobsError,jobsLoading,jobQuery,load,query,list,providerList}')
const row = (id = 1) => ({ id, name: `设备 ${id}`, providerCode: 'YILIAN_YUN', authorization: 'AUTHORIZED', enabled: true, deleted: false })
const job = (id = 1, state = 'UNKNOWN') => ({ printerId: id, requestId: 'pending-request-0001', state })
function mount(overrides = {}, confirmation = () => Promise.resolve()) {
  const storage = new Map(), confirmations = [], warnings = []
  const Api = { page: async () => ({ list: [], total: 0 }), ...overrides }
  const cache = { get: key => key === 'user' ? { user: { id: 1 } } : 1 }
  const message = { confirm: text => { confirmations.push(text); return confirmation() }, success: () => {}, warning: text => warnings.push(text) }
  const vm = factory(ref, reactive, computed, watch, () => {}, () => {}, () => message, () => ({ wsCache: cache }),
    { USER: 'user', TenantId: 'tenantId' }, () => '', Api,
    { getItem: key => storage.get(key) || null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }, globalThis.crypto)
  vm.providerList.value = [{ code: 'YILIAN_YUN', configured: true, requiresAuthorization: true }]
  return { vm, storage, confirmations, warnings }
}
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

test('a rejected initial request is queried first, and missing intent needs an explicit send with the same id', async () => {
  const sent = [], lookedUp = []
  const { vm, storage, confirmations, warnings } = mount({
    testPrint: async (id, requestId) => { sent.push(requestId); if (sent.length === 1) throw Error('preflight rejection'); return { ...job(id, 'ACCEPTED'), requestId } },
    getJob: async (id, requestId) => { lookedUp.push(requestId); return null }
  })
  await assert.rejects(vm.printTest(row()))
  assert.equal(storage.size, 1)
  await vm.printTest(row())
  assert.equal(sent.length, 1)
  assert.equal(lookedUp[0], sent[0])
  assert.match(warnings.at(-1), /未找到任务，本次未发送打印/)
  assert.equal(vm.testActionLabel(row()), '发送此测试请求')
  await vm.printTest(row())
  assert.equal(sent[1], sent[0])
  assert.match(confirmations[2], /发送一份小票/)
  assert.equal(storage.size, 0)
})
test('unknown results and failed lookups retain the request without resubmitting', async () => {
  let submissions = 0, queries = 0
  const { vm, storage } = mount({
    testPrint: async () => { submissions++; return job() },
    getJob: async () => { queries++; if (queries === 1) throw Error('network'); return job() }
  })
  await vm.printTest(row())
  const requestId = [...storage.values()][0]
  await assert.rejects(vm.printTest(row()))
  await vm.printTest(row())
  assert.equal(submissions, 1)
  assert.equal([...storage.values()][0], requestId)
})
test('result lookup remains available after disabling the device or service', async () => {
  const { vm } = mount({ testPrint: async () => job(), getJob: async () => job() })
  await vm.printTest(row())
  vm.providerList.value[0].configured = false
  assert.equal(vm.testActionDisabled({ ...row(), enabled: false, authorization: 'UNKNOWN' }), false)
})
test('accepted lookup clears a pending request without a second print', async () => {
  let submissions = 0
  const { vm, storage } = mount({ testPrint: async () => { submissions++; return job() }, getJob: async () => job(1, 'ACCEPTED') })
  await vm.printTest(row()); await vm.printTest(row())
  assert.equal(submissions, 1); assert.equal(storage.size, 0)
})
test('canceling explicit send preserves the missing request and never submits', async () => {
  let confirms = 0, submissions = 0
  const { vm, storage } = mount({ getJob: async () => null, testPrint: async () => { submissions++; return job() } },
    () => ++confirms === 2 ? Promise.reject(Error('cancel')) : Promise.resolve())
  storage.set('printer-test:1:1:1', 'pending-request-0001')
  await vm.printTest(row()); await vm.printTest(row())
  assert.equal(storage.size, 1); assert.equal(submissions, 0)
})
test('a slow response for A cannot overwrite B records or loading state', async () => {
  const a = deferred(), b = deferred()
  const { vm } = mount({ jobs: id => id === 1 ? a.promise : b.promise })
  const first = vm.openJobs(row(1)), second = vm.openJobs(row(2))
  a.resolve({ list: [job(1)], total: 1 }); await first
  assert.equal(vm.jobsLoading.value, true)
  assert.equal(vm.jobList.value.length, 0)
  b.resolve({ list: [job(2)], total: 1 }); await second
  assert.equal(vm.jobsPrinter.value.id, 2); assert.equal(vm.jobList.value[0].printerId, 2)
})
test('an old failure cannot mark a newer successful records window as failed', async () => {
  const a = deferred(), b = deferred()
  const { vm } = mount({ jobs: id => id === 1 ? a.promise : b.promise })
  const first = vm.openJobs(row(1)), second = vm.openJobs(row(2))
  b.resolve({ list: [job(2)], total: 1 }); await second
  a.reject(Error('old failure')); await first
  assert.equal(vm.jobsError.value, false); assert.equal(vm.jobList.value[0].printerId, 2)
})
test('closing the records window invalidates an unfinished response', async () => {
  const pending = deferred()
  const { vm } = mount({ jobs: () => pending.promise })
  const request = vm.openJobs(row())
  vm.jobsVisible.value = false; await nextTick()
  pending.resolve({ list: [job()], total: 1 }); await request
  assert.equal(vm.jobList.value.length, 0); assert.equal(vm.jobsLoading.value, false)
})
test('pagination snapshots parameters and ignores an older page response', async () => {
  const first = deferred(), second = deferred(), params = []
  const { vm } = mount({ jobs: (id, query) => { params.push(query); return params.length === 1 ? first.promise : second.promise } })
  const a = vm.openJobs(row())
  vm.jobQuery.pageNo = 2; const b = vm.loadJobs()
  assert.equal(params[0].pageNo, 1); assert.equal(params[1].pageNo, 2)
  second.resolve({ list: [{ ...job(), requestId: 'page-two' }], total: 11 }); await b
  first.resolve({ list: [job()], total: 11 }); await a
  assert.equal(vm.jobList.value[0].requestId, 'page-two')
})
test('history filter ignores a late current-device response', async () => {
  const first = deferred(), second = deferred(); let calls = 0
  const { vm } = mount({ page: () => ++calls === 1 ? first.promise : second.promise })
  const a = vm.load(); vm.query.deleted = true; const b = vm.load()
  second.resolve({ list: [{ ...row(), deleted: true }], total: 1 }); await b
  first.resolve({ list: [row()], total: 1 }); await a
  assert.equal(vm.list.value[0].deleted, true)
})
test('no-authorization devices can send while missing provider metadata defaults to requiring authorization', () => {
  const { vm } = mount()
  const device = { ...row(), authorization: 'UNAUTHORIZED' }
  assert.equal(vm.testActionDisabled(device), true)
  vm.providerList.value[0].requiresAuthorization = false
  assert.equal(vm.testActionDisabled(device), false)
  delete vm.providerList.value[0].requiresAuthorization
  assert.equal(vm.testActionDisabled(device), true)
})
