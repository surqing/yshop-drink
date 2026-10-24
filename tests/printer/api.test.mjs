import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
const require = createRequire(new URL('../../yshop-drink-vue3/package.json', import.meta.url))
const ts = require('typescript')
const source = (await readFile(new URL('../../yshop-drink-vue3/src/api/mall/store/printer/index.ts', import.meta.url), 'utf8')).replace(/^import .*$/gm, '')
const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const calls = [], request = new Proxy({}, { get: (_, method) => options => { calls.push({ method, ...options }); return Promise.resolve({}) } })
const exports = {}
new Function('request', 'exports', code)(request, exports)
test('manual status refresh uses explicit POST and all retrieval APIs remain GET', async () => {
  await exports.status(7)
  assert.deepEqual(calls.pop(), { method: 'post', url: '/store/printer/status/refresh', params: { id: 7 } })
  await exports.get(7); await exports.getJob(7, 'synthetic-request-01'); await exports.jobs(7, { pageNo: 1, pageSize: 10 })
  assert.equal(calls.length, 3); assert.ok(calls.every(call => call.method === 'get'))
})
