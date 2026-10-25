import axios, { AxiosError, type AxiosAdapter, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { isRelogin, service } from '../src/config/axios/service'

const mocks = vi.hoisted(() => ({
  getAccessToken: vi.fn(),
  getRefreshToken: vi.fn(),
  getTenantId: vi.fn(),
  removeToken: vi.fn(),
  setToken: vi.fn(),
  messageError: vi.fn(),
  confirm: vi.fn(),
  notificationError: vi.fn(),
  resetRouter: vi.fn(),
  deleteUserCache: vi.fn()
}))

vi.mock('element-plus', () => ({
  ElMessage: { error: mocks.messageError },
  ElMessageBox: { confirm: mocks.confirm },
  ElNotification: { error: mocks.notificationError }
}))
vi.mock('@/utils/auth', () => ({
  getAccessToken: mocks.getAccessToken,
  getRefreshToken: mocks.getRefreshToken,
  getTenantId: mocks.getTenantId,
  removeToken: mocks.removeToken,
  setToken: mocks.setToken
}))
vi.mock('@/router', () => ({ resetRouter: mocks.resetRouter }))
vi.mock('@/hooks/web/useCache', () => ({ deleteUserCache: mocks.deleteUserCache }))

const response = (config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse => ({
  data, status, statusText: status === 200 ? 'OK' : 'Forbidden', headers: {}, config, request: {}
})

const businessAdapter = (data: unknown, status = 200): AxiosAdapter => async config => response(config, data, status)

let accessToken = 'old-access-token'
const originalServiceAdapter = service.defaults.adapter
const originalDefaultAdapter = axios.defaults.adapter

beforeEach(() => {
  vi.clearAllMocks()
  accessToken = 'old-access-token'
  mocks.getAccessToken.mockImplementation(() => accessToken)
  mocks.getRefreshToken.mockReturnValue('')
  mocks.getTenantId.mockReturnValue(0)
  mocks.setToken.mockImplementation((tokens: { accessToken?: string }) => {
    if (tokens?.accessToken) accessToken = tokens.accessToken
  })
  mocks.confirm.mockReturnValue(new Promise(() => {}))
  isRelogin.show = false
  ;(globalThis as any).useI18n = () => ({ t: (key: string) => key })
})

afterEach(() => {
  service.defaults.adapter = originalServiceAdapter
  axios.defaults.adapter = originalDefaultAdapter
  isRelogin.show = false
  delete (globalThis as any).useI18n
})

test('HTTP 200 business 403 rejects with a recognizable authorization error', async () => {
  service.defaults.adapter = businessAdapter({ code: 403, msg: 'permission revoked' })

  await expect(service.get('/contract/business-forbidden')).rejects.toMatchObject({
    name: 'Error', message: 'permission revoked', code: 403
  })
  expect(mocks.notificationError).toHaveBeenCalledWith({ title: 'permission revoked' })
})

test('HTTP 403 keeps the original Axios error and response status', async () => {
  service.defaults.adapter = (async config => {
    const received = response(config, { code: 403, msg: 'http forbidden' }, 403)
    throw new AxiosError('Request failed with status code 403', AxiosError.ERR_BAD_REQUEST, config, {}, received)
  }) as AxiosAdapter

  let rejected: unknown
  try { await service.get('/contract/http-forbidden') } catch (error) { rejected = error }
  expect(axios.isAxiosError(rejected)).toBe(true)
  expect((rejected as AxiosError).response?.status).toBe(403)
  expect((rejected as AxiosError).response?.data).toEqual({ code: 403, msg: 'http forbidden' })
  expect(mocks.messageError).toHaveBeenCalledWith('sys.api.apiRequestFailed403')
})

test('network failures keep the original Axios error', async () => {
  service.defaults.adapter = (async config => {
    throw new AxiosError('Network Error', AxiosError.ERR_NETWORK, config, {})
  }) as AxiosAdapter

  let rejected: unknown
  try { await service.get('/contract/network-failure') } catch (error) { rejected = error }
  expect(axios.isAxiosError(rejected)).toBe(true)
  expect((rejected as AxiosError).code).toBe(AxiosError.ERR_NETWORK)
  expect(mocks.messageError).toHaveBeenCalledWith('sys.api.errorMessage')
})

test('401 refreshes the access token and replays the original request once', async () => {
  mocks.getRefreshToken.mockReturnValue('synthetic-refresh-token')
  const requestTokens: string[] = []
  let protectedRequests = 0
  service.defaults.adapter = (async config => {
    requestTokens.push(String(config.headers.Authorization ?? ''))
    protectedRequests++
    return response(config, protectedRequests === 1
      ? { code: 401, msg: 'expired access token' }
      : { code: 200, data: { accepted: true } })
  }) as AxiosAdapter
  axios.defaults.adapter = businessAdapter({ code: 200, data: { accessToken: 'fresh-access-token', refreshToken: 'next-refresh' } })

  await expect(service.get('/contract/refresh')).resolves.toEqual({ code: 200, data: { accepted: true } })
  expect(requestTokens).toEqual(['Bearer old-access-token', 'Bearer fresh-access-token'])
  expect(mocks.setToken).toHaveBeenCalledWith({ accessToken: 'fresh-access-token', refreshToken: 'next-refresh' })
  expect(protectedRequests).toBe(2)
  expect(mocks.confirm).not.toHaveBeenCalled()
})

test('401 without a refresh token preserves the relogin prompt and rejection', async () => {
  service.defaults.adapter = businessAdapter({ code: 401, msg: 'expired access token' })

  await expect(service.get('/contract/relogin')).rejects.toBe('sys.api.timeoutMessage')
  expect(isRelogin.show).toBe(true)
  expect(mocks.confirm).toHaveBeenCalledWith('sys.api.timeoutMessage', 'common.confirmTitle', expect.objectContaining({
    showCancelButton: false, closeOnClickModal: false, showClose: false
  }))
  expect(mocks.resetRouter).not.toHaveBeenCalled()
  expect(mocks.deleteUserCache).not.toHaveBeenCalled()
  expect(mocks.removeToken).not.toHaveBeenCalled()
})

test('business 500 retains its Error rejection', async () => {
  service.defaults.adapter = businessAdapter({ code: 500, msg: 'server error' })
  await expect(service.get('/contract/business-500')).rejects.toMatchObject({ name: 'Error', message: 'server error' })
})

test('other business failures retain their generic rejection', async () => {
  service.defaults.adapter = businessAdapter({ code: 422, msg: 'invalid request' })
  await expect(service.get('/contract/business-other')).rejects.toBe('error')
})
