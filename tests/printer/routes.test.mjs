import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'

const require = createRequire(new URL('../../yshop-drink-vue3/package.json', import.meta.url))
const ts = require('typescript')
const source = await readFile(new URL('../../yshop-drink-vue3/src/utils/printerRoute.ts', import.meta.url), 'utf8')
const { outputText: code } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
const { canManagePrinters, filterPrinterMenus, hasPrinterMenu } = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`)
const menu = [{ path: '/mall', children: [
  { path: 'shops', component: 'mall/store/shop/index' },
  { path: 'web-print', component: 'mall/store/printer/index' }
] }]

test('only headquarters with query permission can register the printer page', () => {
  assert.equal(canManagePrinters(0, ['store:web-print:query']), true)
  assert.equal(canManagePrinters(0, ['*:*:*']), true)
  assert.equal(canManagePrinters(1, ['*:*:*']), false)
  assert.equal(canManagePrinters(0, ['store:web-print:test']), false)
  assert.equal(canManagePrinters(undefined, ['*:*:*']), false)
})
test('shop accounts lose printer routes while other menus and the cached source survive', () => {
  const original = structuredClone(menu)
  const filtered = filterPrinterMenus(menu, false)
  assert.deepEqual(filtered[0].children.map(r => r.path), ['shops'])
  assert.deepEqual(menu, original)
  assert.equal(hasPrinterMenu(filtered), false)
})
test('an authorized account still needs the restored menu before enabling the legacy link', () => {
  assert.equal(hasPrinterMenu(filterPrinterMenus(menu, true)), true)
  assert.equal(hasPrinterMenu([{ path: '/mall', children: [] }]), false)
  const old = [{ path: '/shop', children: [{ path: 'web-print', component: '/store/webPrint/index' }] }]
  assert.equal(hasPrinterMenu(old), false)
  assert.equal(filterPrinterMenus(old, false)[0].children.length, 0)
})
