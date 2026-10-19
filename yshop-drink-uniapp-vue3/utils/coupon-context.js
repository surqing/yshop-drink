import { cents } from './catalog-options.js'

export const couponLabels = {
  AVAILABLE: '可用', RESERVED: '待付款预占', USED: '已核销', EXPIRED: '已过期',
  NOT_YET_VALID: '尚未生效', INVALID: '已作废', REVIEW_REQUIRED: '需审核',
  COUPON_DISABLED: '活动已停用', COUPON_CLAIM_NOT_STARTED: '领取尚未开始',
  COUPON_CLAIM_EXPIRED: '领取已结束', COUPON_POINTS_NOT_SUPPORTED: '积分兑换暂未开放',
  COUPON_ISSUANCE_NEEDS_REVIEW: '历史发行数量需审核', COUPON_SOLD_OUT: '已领完',
  COUPON_USER_LIMIT: '已达每人领取上限', COUPON_NEW_USER_NOT_ELIGIBLE: '仅符合注册资格的新会员可领',
  COUPON_REVIEW_REQUIRED: '活动需审核'
}

// Local request-slot fingerprint only, never an authorization or redemption-code verifier.
function slot(uid, identity) {
  let value = 2166136261
  for (const char of String(identity)) value = Math.imul(value ^ char.charCodeAt(0), 16777619)
  return `couponClaim:${uid}:${value >>> 0}`
}
export function claimRequestKey(storage, uid, identity) {
  if (!uid || !identity) throw new Error('领取身份缺失')
  const name = slot(uid, identity)
  let key = storage.getStorageSync(name)
  if (!key) {
    key = `coupon_${Date.now().toString(36)}_${Math.random().toString(36).slice(2)}_${Math.random().toString(36).slice(2)}`
    storage.setStorageSync(name, key)
  }
  return key
}
export function claimSucceeded(storage, uid, identity) { storage.removeStorageSync(slot(uid, identity)) }
export function couponDiscount(coupon, subtotal) {
  if (!coupon?.id || coupon.reservationState !== 'AVAILABLE') return 0
  const total = cents(subtotal), threshold = cents(coupon.least), value = cents(coupon.value)
  if (![total,threshold,value].every(Number.isSafeInteger) || value <= 0 || threshold < 0 || total < threshold) return 0
  return Math.min(value, total) / 100
}
export function couponContextMatches(selectedShop, responseShop, selectedType, responseType) {
  return String(selectedShop) === String(responseShop) && selectedType === responseType
}
