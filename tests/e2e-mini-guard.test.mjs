import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'

function fixture() {
  const storage = new Map(), native = []
  const wx = {
    setStorageSync: (key, value) => storage.set(key, value), getStorageSync: key => storage.get(key),
    removeStorageSync: key => storage.delete(key), request() {},
    showModal(options) { native.push(options); return { native: true } }
  }
  const file = process.env.YSHOP_E2E_GUARD_SOURCE || new URL('./mini/e2e-guard.js', import.meta.url)
  vm.runInNewContext(fs.readFileSync(file, 'utf8').replace('__OWNED_BASE__', JSON.stringify('http://127.0.0.1:12345')), { wx })
  return { wx, native, state: () => storage.get('__e2eState') }
}

test('native observation cannot confirm a business dialog without the real platform callback', () => {
  const f = fixture(); let callbacks = 0
  const complete = () => {}
  assert.equal(f.wx.showModal({ title: '取消订单', complete, success: () => { callbacks++ } }).native, true)
  assert.equal(f.state().nativeModalsOpened, 1)
  assert.equal(f.state().nativeModalsConfirmed, 0)
  assert.equal(callbacks, 0)
  assert.equal(f.native[0].complete, complete)
  f.native[0].success({ confirm: true, cancel: false })
  assert.equal(f.state().nativeModalsConfirmed, 1)
  assert.equal(callbacks, 1)
})

test('other native dialogs are delegated unchanged', () => {
  const f = fixture(), options = { title: 'Unrelated synthetic dialog', success() {} }
  f.wx.showModal(options)
  assert.equal(f.native[0], options)
  assert.equal(f.state().nativeModalsOpened, 0)
})
