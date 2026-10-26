import { afterEach, expect, test, vi } from 'vitest'
import {
  createBatchContextGuard,
  clearPendingBatchRequests,
  loadPendingBatchRequest,
  pendingBatchStorageKey,
  savePendingBatchRequest,
  isUncertainBatchOutcome
} from '../src/utils/batchStockSession'

afterEach(() => vi.restoreAllMocks())

test('late preview for shop A cannot overwrite the active shop B context', () => {
  const guard = createBatchContextGuard()
  const shopA = guard.capture()
  guard.invalidate()
  const shopB = guard.capture()

  expect(guard.isCurrent(shopA)).toBe(false)
  expect(guard.isCurrent(shopB)).toBe(true)
})

test('pending batch request remains recoverable after thirty minutes and is isolated by account and shop', () => {
  const storage = new Map<string, string>()
  const storageLike = {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key),
    key: (index: number) => [...storage.keys()][index] ?? null,
    get length() { return storage.size }
  }
  const request = {
    userId: 101,
    shopId: 201,
    key: 'batch_0123456789abcdef',
    savedAt: 1_000,
    body: { shopId: 201, lines: [{ productId: 1, skuId: 2, expectedStock: 10, delta: 3 }], reason: '补货' }
  }

  savePendingBatchRequest(storageLike, request)
  expect(loadPendingBatchRequest(storageLike, 101, 201, 1_001)).toEqual(request)
  expect(loadPendingBatchRequest(storageLike, 102, 201, 1_001)).toBeUndefined()
  expect(loadPendingBatchRequest(storageLike, 101, 202, 1_001)).toBeUndefined()
  const aged = loadPendingBatchRequest(storageLike, 101, 201, 1_000 + 30 * 60 * 1000 + 1)
  expect(aged).toEqual(request)
  expect(storage.has(pendingBatchStorageKey(101, 201))).toBe(true)
})

test('pending request survives wall clock rollback without changing key or body', () => {
  const storage = new Map<string, string>()
  const storageLike = {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key),
    key: (index: number) => [...storage.keys()][index] ?? null,
    get length() { return storage.size }
  }
  const request = {
    userId: 101,
    shopId: 201,
    key: 'batch_0123456789abcdef',
    savedAt: 100_000,
    body: { shopId: 201, lines: [{ productId: 1, skuId: 2, expectedStock: 10, delta: 3 }], reason: '补货' }
  }
  const storageKey = pendingBatchStorageKey(request.userId, request.shopId)
  savePendingBatchRequest(storageLike, request)
  const storedBefore = storage.get(storageKey)

  expect(loadPendingBatchRequest(storageLike, request.userId, request.shopId, 99_999)).toEqual(request)
  expect(loadPendingBatchRequest(storageLike, request.userId, request.shopId, 10_000)).toEqual(request)
  expect(storage.get(storageKey)).toBe(storedBefore)
})

test('HTTP business failures are definitive while network loss remains recoverable', () => {
  expect(isUncertainBatchOutcome('error')).toBe(false)
  expect(isUncertainBatchOutcome(Object.assign(new Error('forbidden'), { code: 403 }))).toBe(false)
  expect(isUncertainBatchOutcome(Object.assign(new Error('HTTP 500'), { response: { status: 500 } }))).toBe(true)
  expect(isUncertainBatchOutcome(Object.assign(new Error('Network Error'), { code: 'ERR_NETWORK' }))).toBe(true)
  expect(isUncertainBatchOutcome(Object.assign(new Error(''), { response: { status: 200 } }))).toBe(true)
  expect(isUncertainBatchOutcome(new Error(''))).toBe(true)
  expect(isUncertainBatchOutcome(Object.assign(new Error('Synthetic business rejection'), { businessCode: 500 }))).toBe(false)
  expect(isUncertainBatchOutcome(new Error('unclassified failure'))).toBe(true)
})

test('logout cleanup removes only this feature pending keys', () => {
  const storage = new Map<string, string>([
    ['batch-stock-pending-v1:101:201', '{}'],
    ['batch-stock-pending-v1:102:202', '{}'],
    ['unrelated-session-data', 'keep']
  ])
  const storageLike = {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key),
    key: (index: number) => [...storage.keys()][index] ?? null,
    get length() { return storage.size }
  }

  clearPendingBatchRequests(storageLike)
  expect([...storage.keys()]).toEqual(['unrelated-session-data'])
})
