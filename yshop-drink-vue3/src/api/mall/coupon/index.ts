import request from '@/config/axios'

export interface VO {
  id: number
  shopId: string
  shopName: string
  title: string
  isSwitch: number
  least: number
  value: number
  startTime: Date
  endTime: Date
  weigh: number
  type: number
  exchangeCode: string
  receive: number
  distribute: number
  score: number
  instructions: string
  image: string
  limit: number
  templateVersion: number
  couponKind: string
  claimMode: string
  claimStartTime: Date | number | string
  claimEndTime: Date | number | string
}
// 查询优惠券列表
export const getCouponList = async () => {
  return await request.get({ url: `/coupon/list` })
}
// 查询优惠券列表
export const getCouponPage = async (params: PageParam) => {
  return await request.get({ url: `/coupon/page`, params })
}

// 查询优惠券详情
export const getCoupon = async (id: number) => {
  return await request.get({ url: `/coupon/get?id=` + id })
}

// 新增优惠券
export const createCoupon = async (data: VO) => {
  return await request.post({ url: `/coupon/create`, data })
}

// 修改优惠券
export const updateCoupon = async (data: VO) => {
  return await request.put({ url: `/coupon/update`, data })
}

// 删除优惠券
export const deleteCoupon = async (id: number) => {
  return await request.delete({ url: `/coupon/delete?id=` + id })
}

// 导出优惠券 Excel
export const exportCoupon = async (params) => {
  return await request.download({ url: `/coupon/export-excel`, params })
}

export const getStatistics = (id: number) => request.get({url: `/coupon/statistics`, params: {id}})
export const getOperations = (id: number) => request.get({url: `/coupon/operations`, params: {id}})
export const getLegacyAudit = () => request.get({url: `/coupon/legacy-audit`})
