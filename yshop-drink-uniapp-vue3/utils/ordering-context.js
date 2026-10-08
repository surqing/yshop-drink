// Store selection is a business context, not an authorization credential.
import { preview } from './catalog-options.js'
export function switchStore(state, storage, next) {
  if (String(state.store?.id || '') !== String(next?.id || '')) {
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

export function submissionKey(storage, data) {
  const payload = JSON.stringify(data)
  const previous = storage.getStorageSync('checkoutSubmission')
  if (previous?.payload === payload && previous.key) return previous.key
  const key = `order_${Date.now()}_${Math.random().toString(36).slice(2)}_${Math.random().toString(36).slice(2)}`
  storage.setStorageSync('checkoutSubmission', { payload, key })
  return key
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
