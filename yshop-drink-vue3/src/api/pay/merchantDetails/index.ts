import request from '@/config/axios'

export interface MerchantDetailsMetadata {
  detailsId: string
  payType: string
  appid: string
  mchId: string
  certStoreType: string
  keyPublic: string
  notifyUrl: string
  returnUrl: string
  signType: string
  seller: string
  subAppId: string
  subMchId: string
  inputCharset: string
  isTest: number
}

export interface MerchantDetailsVO extends MerchantDetailsMetadata {
  privateKeyConfigured: boolean
  certificatePasswordConfigured: boolean
  keyCertificateConfigured: boolean
}

export interface MerchantDetailsWriteVO extends Partial<MerchantDetailsMetadata> {
  keyPrivate?: string
  keyCert?: string
  keyCertPwd?: string
}

// 查询支付服务商配置列表
export const getMerchantDetailsPage = async (params: PageParam) => {
  return await request.get({ url: `/pay/merchant-details/page`, params })
}

// 查询支付服务商配置详情
export const getMerchantDetails = async (id: string) => {
  return await request.get({ url: `/pay/merchant-details/get?id=` + id })
}

// 新增支付服务商配置
export const createMerchantDetails = async (data: MerchantDetailsWriteVO) => {
  return await request.post({ url: `/pay/merchant-details/create`, data })
}

// 修改支付服务商配置
export const updateMerchantDetails = async (data: MerchantDetailsWriteVO) => {
  return await request.put({ url: `/pay/merchant-details/update`, data })
}

// 删除支付服务商配置
export const deleteMerchantDetails = async (id: string) => {
  return await request.delete({ url: `/pay/merchant-details/delete?id=` + id })
}

// 导出支付服务商配置 Excel
export const exportMerchantDetails = async (params) => {
  return await request.download({ url: `/pay/merchant-details/export-excel`, params })
}
