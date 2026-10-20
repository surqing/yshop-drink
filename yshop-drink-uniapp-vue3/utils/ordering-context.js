// Store selection is a business context, not an authorization credential.
import { preview } from './catalog-options.js'
export function switchStore(state, storage, next) {
  if (!next?.id || String(state.store?.id || '') !== String(next.id)) {
    if (unresolvedSubmission(submissionSnapshot(storage, String(state.member?.id || '')))) {
      throw Error('ORDER_SUBMISSION_PENDING')
    }
    state.cart = []
    state.mycoupon = {}
    storage.removeStorageSync('cart')
    storage.removeStorageSync('checkoutSubmission')
  }
  state.store = next || {}
  storage.setStorageSync('selectedStore', state.store)
}

export function reconcileCart(cart, groups, shopId) {
  const products = new Map((groups || []).flatMap(g => g.goodsList || []).map(p => [String(p.id), p]))
  const remaining = new Map()
  return (Array.isArray(cart) ? cart : []).flatMap(item => {
    const product = products.get(String(item.id))
    const sku = String(item.valueStr || '').replace(/\|/g, ',')
    const value = product?.productValue?.[sku]
    if (!product || String(item.shopId) !== String(shopId) || String(product.shopId) !== String(shopId)
      || !value || value.isShow === 0 || !Number.isInteger(item.number) || item.number <= 0
      || Number(item.catalogVersion || 0) !== Number(product.catalogVersion || 0)) return []
    const available = Math.min(Number(value.stock), Number(product.stock))
    if (!Number.isInteger(available) || available <= 0 || !(Number(value.price) > 0)) return []
    try {
      const quote = preview(product.catalogConfiguration, item.selections || [], value.price)
      const key = String(item.id) + ':' + sku
      const quantity = Math.min(item.number, remaining.has(key) ? remaining.get(key) : available)
      if (quantity <= 0) return []
      remaining.set(key, (remaining.has(key) ? remaining.get(key) : available) - quantity)
      return [{ ...item, shopId, valueStr: sku, number: quantity, optionLabel: quote.label,
        maxQuantity: available, price: quote.price, name: product.storeName, image: product.image }]
    } catch { return [] }
  })
}

export const unresolvedSubmission = value => Boolean(value?.key && (value.state === 'PENDING' || (!value.state && value.payload)))
const ownerSlot = owner => `member:${owner}`
function memberSubmissions(storage) {
  const value = storage.getStorageSync('checkoutSubmissionByMember')
  return value && typeof value === 'object' && !Array.isArray(value) ? { ...value } : {}
}
export function submissionSnapshot(storage, owner = '') {
  const saved = memberSubmissions(storage)[ownerSlot(owner)]
  if (owner && saved?.owner === owner) return saved
  const legacy = storage.getStorageSync('checkoutLegacySubmission')
  if (unresolvedSubmission(legacy) && !legacy.owner) return legacy
  const active = storage.getStorageSync('checkoutSubmission')
  return !owner || !active?.owner || active.owner === owner ? active : undefined
}
function persistSubmission(storage, value) {
  const saved = memberSubmissions(storage)
  const active = storage.getStorageSync('checkoutSubmission')
  if (active?.owner) saved[ownerSlot(active.owner)] = active
  else if (unresolvedSubmission(active)) storage.setStorageSync('checkoutLegacySubmission', active)
  if (value.owner) saved[ownerSlot(value.owner)] = value
  storage.setStorageSync('checkoutSubmissionByMember', saved)
  storage.setStorageSync('checkoutSubmission', value)
}
export function submissionKey(storage, data, pending = false, owner = '') {
  const payload = JSON.stringify(data)
  const previous = submissionSnapshot(storage, owner)
  if (owner && previous?.key && !previous.owner && unresolvedSubmission(previous)) throw Error('ORDER_SUBMISSION_RECOVERY_REQUIRED')
  if (previous?.key && (unresolvedSubmission(previous))) {
    if (previous.payload !== payload) {
      let legacy
      try { legacy = JSON.parse(previous.payload) } catch { throw Error('ORDER_SUBMISSION_PENDING') }
      const withoutContact = { ...data }
      delete withoutContact.mobile
      // Upgrade an unresolved legacy snapshot under its original request key.
      if (previous.state || legacy?.orderType !== 'takein' || Object.prototype.hasOwnProperty.call(legacy, 'mobile')
        || typeof data.mobile !== 'string' || JSON.stringify(withoutContact) !== previous.payload) {
        throw Error('ORDER_SUBMISSION_PENDING')
      }
    }
    persistSubmission(storage, { payload, key: previous.key, state: 'PENDING', owner: owner || previous.owner || '', legacyUnconfirmed: previous.legacyUnconfirmed || !previous.state })
    return previous.key
  }
  if (previous?.payload === payload && previous.key) {
    if (pending) persistSubmission(storage, { payload, key: previous.key, state: 'PENDING', owner: owner || previous.owner || '' })
    return previous.key
  }
  const key = `order_${Date.now()}_${Math.random().toString(36).slice(2)}_${Math.random().toString(36).slice(2)}`
  persistSubmission(storage, { payload, key, state: pending ? 'PENDING' : 'READY', owner })
  return key
}
export function submissionRejected(storage, key, error, owner = '') {
  const previous = submissionSnapshot(storage, owner)
  const response = error?.data
  if (previous?.key === key && previous.state && !previous.legacyUnconfirmed && response?.code === 1008003090 && typeof response.msg === 'string'
    && !/IDEMPOTENCY|RECONFIRM/.test(response.msg)) {
    const saved = memberSubmissions(storage)
    const failed = { ...previous, state: 'FAILED' }
    if (previous.owner) saved[ownerSlot(previous.owner)] = failed
    storage.setStorageSync('checkoutSubmissionByMember', saved)
    if (storage.getStorageSync('checkoutSubmission')?.key === key) storage.setStorageSync('checkoutSubmission', failed)
  }
}
export function matchesSubmittedCart(cart, request) {
  if (!Array.isArray(cart) || !Array.isArray(request?.productId) || cart.length !== request.productId.length) return false
  return cart.every((item, index) => String(item.id) === String(request.productId[index])
    && Number(item.number) === Number(request.number?.[index])
    && String(item.valueStr || '').replace(/,/g, '|') === String(request.spec?.[index] || '')
    && (!request.choices || JSON.stringify({ version: Number(item.catalogVersion || 0), selections: item.selections || [] }) === JSON.stringify(request.choices[index])))
}
export function submissionSucceeded(storage, key, owner = '', confirmedOwnership = false) {
  const saved = memberSubmissions(storage)
  if (saved[ownerSlot(owner)]?.key === key) delete saved[ownerSlot(owner)]
  storage.setStorageSync('checkoutSubmissionByMember', saved)
  const legacy = storage.getStorageSync('checkoutLegacySubmission')
  if (confirmedOwnership && legacy?.key === key && !legacy.owner) storage.removeStorageSync('checkoutLegacySubmission')
  const active = storage.getStorageSync('checkoutSubmission')
  if (active?.key === key && (!owner || active.owner === owner || (confirmedOwnership && !active.owner))) storage.removeStorageSync('checkoutSubmission')
}

// Phase 6A stops at an unpaid order. Enabling payment requires a separately reviewed change.
export const PAYMENT_FROZEN = true

export function eligibleCoupons(coupons, shopId, orderType, amount) {
  const type = orderType === 'takeout' ? 2 : 1
  return (Array.isArray(coupons) ? coupons : []).filter(c => {
    const shops = String(c.shopId || '').split(',')
    return c.reservationState === 'AVAILABLE' && (shops.includes('0') || shops.includes(String(shopId)))
      && (Number(c.type) === 0 || Number(c.type) === type)
      && Number.isFinite(Number(amount)) && Number(c.least) <= Number(amount)
  })
}
