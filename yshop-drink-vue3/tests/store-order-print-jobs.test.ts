import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import OrderPrintJobs from '../src/views/mall/store/printer/OrderPrintJobs.vue'

const mocks = vi.hoisted(() => ({
  orderPrintJobs: vi.fn(),
  shops: vi.fn(),
  refreshOrderPrintJob: vi.fn(),
  reprintOrderPrintJob: vi.fn(),
  resolveUnknownOrderPrintJob: vi.fn(),
  error: vi.fn(),
  success: vi.fn()
}))

vi.mock('@/api/mall/store/printer', () => mocks)
vi.mock('@/hooks/web/useMessage', () => ({ useMessage: () => mocks }))

let wrapper: ReturnType<typeof shallowMount> | undefined
const state = () => (wrapper!.vm as any).$.setupState
const row = {
  id: 84,
  eventId: 9,
  orderId: 27,
  shopId: 3,
  printerId: 5,
  printerName: '前台打印机',
  state: 'UNKNOWN',
  attempts: 1,
  payloadSnapshot: JSON.stringify({ orderCode: 'O-27', contactPhone: '13800138000' }),
  createTime: '2031-10-22 09:30:00'
}

beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.orderPrintJobs.mockResolvedValue({ list: [row], total: 1 })
  mocks.shops.mockResolvedValue([{ id: 3, name: '测试门店' }])
  mocks.refreshOrderPrintJob.mockResolvedValue(row)
  mocks.reprintOrderPrintJob.mockResolvedValue(row)
  mocks.resolveUnknownOrderPrintJob.mockResolvedValue({ ...row, state: 'ACCEPTED' })
  ;(globalThis as any).useMessage = () => mocks
  wrapper = shallowMount(OrderPrintJobs, { global: { stubs: {
    ContentWrap: true, Dialog: true, Pagination: true, Icon: true,
    'el-alert': true, 'el-form': true, 'el-form-item': true, 'el-select': true,
    'el-option': true, 'el-button': true, 'el-table': true, 'el-table-column': true,
    'el-tag': true, 'el-input': true, 'el-checkbox': true, 'el-radio-group': true, 'el-radio': true
  }, directives: { loading() {}, hasPermi() {} } } })
})

afterEach(() => wrapper?.unmount())

test('refreshing an unknown job reads state without submitting a print', async () => {
  await flushPromises()

  await state().refresh(row)

  expect(mocks.refreshOrderPrintJob).toHaveBeenCalledWith(row.id)
  expect(mocks.reprintOrderPrintJob).not.toHaveBeenCalled()
  expect(mocks.orderPrintJobs).toHaveBeenCalledTimes(2)
})

test('network failure retries a manual reprint with the same masked request', async () => {
  mocks.reprintOrderPrintJob.mockRejectedValueOnce(new Error('network timeout')).mockResolvedValueOnce(row)
  state().openReprint(row)
  state().reprintReason = '顾客反馈未收到小票，联系电话 13800138000'

  await state().submitReprint()
  const firstRequest = mocks.reprintOrderPrintJob.mock.calls[0][0]
  expect(firstRequest).toMatchObject({ jobId: row.id, reason: '顾客反馈未收到小票，联系电话 138****8000' })
  expect(state().reprintError).toBe(true)
  expect(sessionStorage.getItem(`order-print-reprint:${row.id}`)).toContain(firstRequest.requestKey)

  await state().submitReprint()

  expect(mocks.reprintOrderPrintJob).toHaveBeenCalledTimes(2)
  expect(mocks.reprintOrderPrintJob.mock.calls[1][0]).toEqual(firstRequest)
  expect(sessionStorage.getItem(`order-print-reprint:${row.id}`)).toBeNull()
  expect(state().reprintVisible).toBe(false)
})

test('confirmed printed recovery releases an unknown job with an audited printed disposition', async () => {
  await flushPromises()
  state().openRecovery(row)
  state().recoveryReason = '设备端核实已出纸，旧发送进程已停止'
  state().senderStopped = true
  state().recoveryResolution = 'PRINTED'

  await state().submitRecovery()

  expect(mocks.resolveUnknownOrderPrintJob).toHaveBeenCalledWith(expect.objectContaining({
    jobId: row.id,
    reason: '设备端核实已出纸，旧发送进程已停止',
    senderStopped: true,
    notPrintedConfirmed: false,
    printedConfirmed: true
  }))
  expect(state().recoveryVisible).toBe(false)
})
