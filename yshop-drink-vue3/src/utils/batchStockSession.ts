export interface PendingBatchRequest {
  userId: number
  shopId: number
  key: string
  savedAt: number
  body: {
    shopId: number
    lines: { productId: number; skuId: number; expectedStock: number; delta: number }[]
    reason: string
  }
}

export interface SessionStorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
  readonly length: number
  key(index: number): string | null
}

const PREFIX = 'batch-stock-pending-v1:'
const REQUEST_KEY = /^[A-Za-z0-9_-]{16,64}$/

export function createBatchContextGuard() {
  let generation = 0
  return {
    capture: () => generation,
    invalidate: () => ++generation,
    isCurrent: (captured: number) => captured === generation
  }
}

export function pendingBatchStorageKey(userId: number, shopId: number) {
  if (!Number.isSafeInteger(userId) || userId <= 0 || !Number.isSafeInteger(shopId) || shopId <= 0) {
    throw new Error('BATCH_STOCK_CONTEXT_REQUIRED')
  }
  return `${PREFIX}${userId}:${shopId}`
}

export function savePendingBatchRequest(storage: SessionStorageLike, request: PendingBatchRequest) {
  const key = pendingBatchStorageKey(request.userId, request.shopId)
  if (request.body.shopId !== request.shopId || !REQUEST_KEY.test(request.key)) {
    throw new Error('BATCH_STOCK_PENDING_INVALID')
  }
  storage.setItem(key, JSON.stringify(request))
}

export function loadPendingBatchRequest(
  storage: SessionStorageLike,
  userId: number,
  shopId: number,
  now = Date.now()
): PendingBatchRequest | undefined {
  const key = pendingBatchStorageKey(userId, shopId)
  const raw = storage.getItem(key)
  if (!raw) return undefined
  try {
    const value = JSON.parse(raw) as PendingBatchRequest
    const age = now - value.savedAt
    const validLines = Array.isArray(value.body?.lines) && value.body.lines.length > 0 && value.body.lines.length <= 100 &&
      value.body.lines.every(line => line && Number.isSafeInteger(line.productId) && line.productId > 0 &&
        Number.isSafeInteger(line.skuId) && line.skuId > 0 && Number.isSafeInteger(line.expectedStock) && line.expectedStock >= 0 &&
        Number.isSafeInteger(line.delta) && line.delta !== 0 && Math.abs(line.delta) <= 1000000)
    const valid = value.userId === userId && value.shopId === shopId && value.body?.shopId === shopId &&
      REQUEST_KEY.test(value.key) && Number.isFinite(value.savedAt) && age >= 0 &&
      validLines && typeof value.body.reason === 'string' && value.body.reason.trim().length > 0 && value.body.reason.trim().length <= 200
    if (!valid) {
      storage.removeItem(key)
      return undefined
    }
    return value
  } catch {
    storage.removeItem(key)
    return undefined
  }
}

export function clearPendingBatchRequests(storage: SessionStorageLike) {
  const keys: string[] = []
  for (let index = 0; index < storage.length; index++) {
    const key = storage.key(index)
    if (key?.startsWith(PREFIX)) keys.push(key)
  }
  for (const key of keys) storage.removeItem(key)
}

export function isUncertainBatchOutcome(error: unknown) {
  if (error === 'error') return false
  if (error && typeof error === 'object') {
    const candidate = error as { code?: unknown; businessCode?: unknown; response?: { status?: unknown }; message?: unknown }
    const status = Number(candidate.response?.status)
    if (status >= 500) return true
    if ((status >= 400 && status < 500) || candidate.code === 403) return false
    if (candidate.businessCode === 500 || candidate.businessCode === 901) return false
    if (candidate.code === 'ERR_NETWORK' || candidate.code === 'ECONNABORTED' || candidate.code === 'ETIMEDOUT') return true
    if (typeof candidate.message === 'string' && /network error|timeout/i.test(candidate.message)) return true
  }
  // A malformed, empty, or otherwise unclassified failure may happen after the
  // server committed but before the client received a usable response. Keep
  // the idempotency key so the caller can query or retry the original request.
  return true
}
