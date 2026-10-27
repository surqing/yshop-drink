import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import BatchStockOperations from '../src/views/mall/product/storeProduct/BatchStockOperations.vue'

const mocks = vi.hoisted(() => ({
  configuration: vi.fn(),
  previewStockBatch: vi.fn(),
  applyStockBatch: vi.fn(),
  getStockBatchResult: vi.fn(),
  success: vi.fn(),
  warning: vi.fn()
}))
vi.mock('@/api/mall/product/catalog', () => mocks)
vi.mock('@/store/modules/user', () => ({ useUserStore: () => ({ getUser: { id: 101 } }) }))
vi.mock('@/hooks/web/useMessage', () => ({ useMessage: () => mocks }))

const DialogStub = defineComponent({
  props: ['modelValue'],
  setup(props, { slots }) { return () => props.modelValue ? h('section', [slots.default?.(), slots.footer?.()]) : null }
})
const deferred = <T,>() => {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const config = (productId: number, stock = 10) => ({
  version: 0,
  configuration: { groups: [] },
  skus: [{ id: productId * 10, sku: `SKU-${productId}`, price: 19, stock, is_show: 1 }]
})
const preview = (productId: number, stock = 10, delta = 1) => ({ eligible: true, rows: [{
  productId, skuId: productId * 10, productName: `Product-${productId}`, skuName: `SKU-${productId}`,
  currentStock: stock, expectedStock: stock, delta, afterStock: stock + delta, conflictCode: null
}] })
const result = (batchId: string, productId: number, beforeStock = 10, delta = 1) => ({
  batchId, shopId: productId === 1 ? 101 : 102, state: 'APPLIED',
  lines: [{ productId, skuId: productId * 10, beforeStock, delta, afterStock: beforeStock + delta, reason: 'test restock' }],
  appliedAt: '2031-10-22T09:30:00'
})
let wrapper: ReturnType<typeof mount> | undefined
const state = () => (wrapper!.vm as any).$.setupState

beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.configuration.mockImplementation(async (productId: number) => config(productId))
  mocks.previewStockBatch.mockImplementation(async (body: any) => preview(body.lines[0].productId, body.lines[0].expectedStock, body.lines[0].delta))
  mocks.applyStockBatch.mockResolvedValue(result('0123456789abcdef0123456789abcdef', 1))
  mocks.getStockBatchResult.mockResolvedValue({ status: 'UNCONFIRMED', batch: null })
  wrapper = mount(BatchStockOperations, {
    props: { modelValue: false, shopId: 101, products: [{ id: 1, storeName: 'Product-1', shopId: 101 }] },
    global: { stubs: { Dialog: DialogStub, 'el-table': true, 'el-table-column': true, 'el-alert': true,
      'el-checkbox': true, 'el-input-number': true, 'el-form': true, 'el-form-item': true,
      'el-input': true, 'el-button': true } }
  })
})

afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks(); sessionStorage.clear() })

test('business-code failure never shows success and requires changed input before a new request', async () => {
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  state().rows[0].delta = 2
  state().reason = 'test restock'
  await state().runPreview()
  mocks.applyStockBatch.mockRejectedValueOnce('error')

  await state().submitBatch()

  expect(state().batchResult).toBeUndefined()
  expect(state().requestState).toBe('REJECTED')
  expect(state().pendingRequest).toBeUndefined()
  expect(state().operationMessage).toContain('服务端已拒绝')
  expect(sessionStorage.length).toBe(0)
  expect(state().canConfirm).toBe(false)

  state().rows[0].delta = 3
  await flushPromises()
  await state().runPreview()
  expect(state().canConfirm).toBe(true)
})

test('late preview after the selected shop changes cannot replace the new context', async () => {
  const slowA = deferred<any>()
  const slowB = deferred<any>()
  mocks.previewStockBatch.mockReturnValueOnce(slowA.promise).mockReturnValueOnce(slowB.promise)
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  state().rows[0].delta = 1
  state().reason = 'shop A restock'
  const requestA = state().runPreview()

  await wrapper!.setProps({ shopId: 102, products: [{ id: 2, storeName: 'Product-2', shopId: 102 }] })
  await flushPromises()
  state().rows[0].delta = 1
  state().reason = 'shop B restock'
  const requestB = state().runPreview()
  slowB.resolve(preview(2))
  await requestB
  slowA.resolve(preview(1))
  await requestA

  expect(state().preview.rows[0].productId).toBe(2)
  expect(state().previewSignature).toContain('"shopId":102')
})

test('lost apply response keeps and reuses the original key until a result is confirmed', async () => {
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  state().rows[0].delta = 1
  state().reason = 'test restock'
  await state().runPreview()
  mocks.applyStockBatch.mockRejectedValueOnce(Object.assign(new Error('Network Error'), { code: 'ERR_NETWORK' }))
  await state().submitBatch()
  const originalKey = state().pendingRequest.key

  expect(state().batchResult).toBeUndefined()
  expect(state().pendingRequest.key).toBe(originalKey)
  await state().checkPendingResult()
  expect(state().requestState).toBe('UNCONFIRMED')

  mocks.applyStockBatch.mockResolvedValueOnce(result('abcdef0123456789abcdef0123456789', 1))
  await state().retryOriginalRequest()
  expect(mocks.applyStockBatch).toHaveBeenLastCalledWith(expect.objectContaining({ key: originalKey }))
  expect(state().batchResult.batchId).toBe('abcdef0123456789abcdef0123456789')
  expect(state().pendingRequest).toBeUndefined()
  expect(sessionStorage.length).toBe(0)
})

test('empty HTTP 200 response keeps the original request key for recovery', async () => {
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  state().rows[0].delta = 1
  state().reason = 'test restock'
  await state().runPreview()
  mocks.applyStockBatch.mockRejectedValueOnce(Object.assign(new Error(''), { response: { status: 200, data: '' } }))

  await state().submitBatch()

  expect(state().requestState).toBe('CHECKING')
  expect(state().pendingRequest?.key).toMatch(/^[A-Za-z0-9_-]{16,64}$/)
  expect(sessionStorage.length).toBe(1)
  expect(state().operationMessage).not.toContain('服务端已拒绝')
})

test('reopening with a clock ahead checks the saved original key before loading a new draft', async () => {
  const key = 'batch_0123456789abcdef'
  const saved = {
    userId: 101, shopId: 101, key, savedAt: Date.now() + 60_000,
    body: { shopId: 101, lines: [{ productId: 1, skuId: 10, expectedStock: 10, delta: 1 }], reason: 'test restock' }
  }
  sessionStorage.setItem('batch-stock-pending-v1:101:101', JSON.stringify(saved))
  const resultLookup = deferred<any>()
  mocks.getStockBatchResult.mockReturnValueOnce(resultLookup.promise)

  await wrapper!.setProps({ modelValue: true })
  await flushPromises()

  expect(state().pendingRequest).toEqual(saved)
  expect(mocks.getStockBatchResult).toHaveBeenCalledWith(key)
  expect(mocks.applyStockBatch).not.toHaveBeenCalled()
  expect(mocks.configuration).not.toHaveBeenCalled()

  resultLookup.resolve({ status: 'CONFIRMED', batch: result('fedcba9876543210fedcba9876543210', 1) })
  await flushPromises()
  expect(state().batchResult.batchId).toBe('fedcba9876543210fedcba9876543210')
  expect(sessionStorage.length).toBe(0)
})

test('permission revocation after preview never submits or reports success', async () => {
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  state().rows[0].delta = 1
  state().reason = 'test restock'
  await state().runPreview()
  mocks.applyStockBatch.mockRejectedValueOnce(Object.assign(new Error('denied'), { code: 403 }))

  await state().submitBatch()

  expect(state().requestState).toBe('DENIED')
  expect(state().batchResult).toBeUndefined()
  expect(state().canConfirm).toBe(false)
  expect(mocks.success).not.toHaveBeenCalled()
  expect(sessionStorage.length).toBe(0)
})

test('session storage failure prevents dispatching a request without a recoverable key', async () => {
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  state().rows[0].delta = 1
  state().reason = 'test restock'
  await state().runPreview()
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })

  await state().submitBatch()

  expect(mocks.applyStockBatch).not.toHaveBeenCalled()
  expect(state().batchResult).toBeUndefined()
  expect(state().operationMessage).toContain('本次调整尚未发送')
})

test('late committed response after switching shops leaves the original key for later recovery', async () => {
  const apply = deferred<any>()
  mocks.applyStockBatch.mockReturnValueOnce(apply.promise)
  await wrapper!.setProps({ modelValue: true })
  await flushPromises()
  state().rows[0].delta = 1
  state().reason = 'shop A restock'
  await state().runPreview()
  const submitting = state().submitBatch()
  const requestA = state().pendingRequest

  await wrapper!.setProps({ shopId: 102, products: [{ id: 2, storeName: 'Product-2', shopId: 102 }] })
  await flushPromises()
  apply.resolve(result('abcdef0123456789abcdef0123456789', 1))
  await submitting

  expect(state().batchResult).toBeUndefined()
  expect(state().rows[0].productId).toBe(2)
  expect(state().pendingRequest).toBeUndefined()
  expect(JSON.parse(sessionStorage.getItem('batch-stock-pending-v1:101:101')!).key).toBe(requestA.key)
  expect(mocks.getStockBatchResult).not.toHaveBeenCalled()
})
