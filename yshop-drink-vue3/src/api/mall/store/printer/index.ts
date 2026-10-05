import request from '@/config/axios'

// Server timestamps are milliseconds; preview fixtures may use ISO text.
export type PrinterTime = number | string

export interface ProviderInfo {
  code: string
  name: string
  connectionTypes: string[]
  configFields: { key: string; label: string; secret: boolean; required: boolean }[]
  capabilities: { authorize: boolean; deauthorize: boolean; status: boolean; textPrint: boolean }
  configured: boolean
  requiresAuthorization?: boolean
}
export interface Printer {
  id: number
  name: string
  providerCode: string
  deviceCode: string
  model?: string
  connectionType: string
  enabled: boolean
  deleted: boolean
  authorization: 'UNAUTHORIZED' | 'AUTHORIZED' | 'UNKNOWN'
  deviceState: 'ONLINE' | 'OFFLINE' | 'PAPER_OUT' | 'UNKNOWN'
  stateCheckedAt?: PrinterTime
  shopId?: number
  shopName?: string
  configuredFields: string[]
  lastError?: string
  createTime: PrinterTime
}
export interface SaveRequest {
  id?: number
  name: string
  providerCode: string
  deviceCode: string
  model: string
  connectionType: string
  enabled: boolean
  configuration: Record<string, string>
}
export interface Job {
  requestId: string
  printerId: number
  printerName: string
  state: 'SUBMITTING' | 'ACCEPTED' | 'FAILED' | 'UNKNOWN'
  providerJobId?: string
  errorCode?: string
  createTime: PrinterTime
}
export interface ShopOption {
  id: number
  name: string
}
export const providers = () => request.get<ProviderInfo[]>({ url: '/store/printer/providers' })
export const shops = () => request.get<ShopOption[]>({ url: '/store/printer/shops' })
export const page = (params: PageParam & { name?: string; shopId?: number; deleted?: boolean }) =>
  request.get<{ list: Printer[]; total: number }>({ url: '/store/printer/page', params })
export const get = (id: number) =>
  request.get<Printer>({ url: '/store/printer/get', params: { id } })
export const save = (data: SaveRequest) =>
  data.id
    ? request.put<number>({ url: '/store/printer/update', data })
    : request.post<number>({ url: '/store/printer/create', data })
export const remove = (id: number) =>
  request.delete({ url: '/store/printer/delete', params: { id } })
export const authorize = (id: number, revoke = false) =>
  request.post<{ authorization: Printer['authorization']; errorCode?: string }>({
    url: `/store/printer/${revoke ? 'deauthorize' : 'authorize'}`,
    params: { id }
  })
export const reconnect = (id: number) =>
  request.post<{ authorization: Printer['authorization']; errorCode?: string }>({
    url: '/store/printer/reconnect',
    params: { id }
  })
export const bind = (id: number, shopId: number) =>
  request.put({ url: '/store/printer/binding', data: { id, shopId } })
export const unbind = (id: number) =>
  request.delete({ url: '/store/printer/binding', params: { id } })
export const status = (id: number) =>
  request.get<{ state: Printer['deviceState']; checkedAt: PrinterTime; errorCode?: string }>({
    url: '/store/printer/status',
    params: { id }
  })
export const testPrint = (id: number, requestId: string) =>
  request.post<Job>({ url: '/store/printer/test-print', data: { id, requestId } })
export const getJob = (id: number, requestId: string) =>
  request.get<Job | null>({ url: '/store/printer/test-print/get', params: { id, requestId } })
export const jobs = (id: number, params: PageParam) =>
  request.get<{ list: Job[]; total: number }>({
    url: '/store/printer/test-print/page',
    params: { id, ...params }
  })
