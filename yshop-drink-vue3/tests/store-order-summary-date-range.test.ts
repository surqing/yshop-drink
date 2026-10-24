import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import Work from '../src/views/mall/order/storeOrder/work.vue'

const mocks = vi.hoisted(() => ({
  getStoreOrderSummary: vi.fn(),
  getStoreOrderSummaryDetails: vi.fn(),
  getStoreOrderSummaryShops: vi.fn(),
  getStoreOrderPage: vi.fn(),
  transitionStoreOrderFulfillment: vi.fn(),
  exportStoreOrder: vi.fn(),
  error: vi.fn(),
  exportConfirm: vi.fn()
}))

vi.mock('@/api/mall/order/storeOrder', () => mocks)
vi.mock('@/hooks/web/useMessage', () => ({ useMessage: () => mocks }))
vi.mock('@/utils/download', () => ({ default: { excel: vi.fn() } }))

let wrapper: ReturnType<typeof shallowMount> | undefined
const state = () => (wrapper!.vm as any).$.setupState
const TableColumn = defineComponent({ setup() { return () => null } })

beforeEach(() => {
  vi.resetAllMocks()
  mocks.getStoreOrderSummary.mockResolvedValue({})
  mocks.getStoreOrderSummaryDetails.mockResolvedValue({ list: [], total: 0 })
  mocks.getStoreOrderSummaryShops.mockResolvedValue([])
  mocks.getStoreOrderPage.mockResolvedValue({ list: [], total: 0 })
  mocks.transitionStoreOrderFulfillment.mockResolvedValue({})
  mocks.exportStoreOrder.mockResolvedValue(new Blob())
  mocks.exportConfirm.mockResolvedValue(true)
  wrapper = shallowMount(Work, { global: { stubs: {
    'el-table': true, 'el-table-column': TableColumn, 'el-date-picker': true,
    'el-dialog': true, 'el-form': true, 'el-form-item': true, 'el-input': true,
    'el-button': true, 'el-select': true, 'el-option': true, 'el-tabs': true,
    'el-tab-pane': true, 'el-row': true, 'el-col': true, 'el-card': true,
    'el-statistic': true, 'el-link': true, ContentWrap: true, Pagination: true,
    Icon: true
  }, directives: { loading() {}, hasPermi() {} } } })
})

afterEach(() => wrapper?.unmount())

test('inclusive date picker end date is sent as the next-day exclusive bound for totals and details', async () => {
  await flushPromises()
  state().summaryDates = ['2031-10-22', '2031-10-22']

  await state().loadSummary()
  expect(mocks.getStoreOrderSummary).toHaveBeenLastCalledWith({ from: '2031-10-22', to: '2031-10-23' })

  state().openSummaryDetails()
  await flushPromises()
  expect(mocks.getStoreOrderSummaryDetails).toHaveBeenLastCalledWith({
    from: '2031-10-22', to: '2031-10-23', pageNo: 1, pageSize: 20
  })

  state().summaryDates = ['2031-12-31', '2031-12-31']
  await state().loadSummary()
  expect(mocks.getStoreOrderSummary).toHaveBeenLastCalledWith({ from: '2031-12-31', to: '2032-01-01' })
})

test('a late fulfillment response cannot replace the currently selected tab orders', async () => {
  await flushPromises()
  vi.clearAllMocks()

  let resolveWaitAccept!: (value: { list: { id: number }[]; total: number }) => void
  let resolveReady!: (value: { list: { id: number }[]; total: number }) => void
  mocks.getStoreOrderPage
    .mockImplementationOnce(() => new Promise((resolve) => { resolveWaitAccept = resolve }))
    .mockImplementationOnce(() => new Promise((resolve) => { resolveReady = resolve }))

  state().queryParams.fulfillmentStatus = 'WAIT_ACCEPT'
  const waitAcceptRequest = state().getList()
  state().queryParams.fulfillmentStatus = 'READY'
  const readyRequest = state().getList()
  expect(mocks.getStoreOrderPage).toHaveBeenNthCalledWith(1, expect.objectContaining({ fulfillmentStatus: 'WAIT_ACCEPT' }))
  expect(mocks.getStoreOrderPage).toHaveBeenNthCalledWith(2, expect.objectContaining({ fulfillmentStatus: 'READY' }))
  resolveReady({ list: [{ id: 22 }], total: 1 })
  await readyRequest
  resolveWaitAccept({ list: [{ id: 11 }], total: 1 })
  await waitAcceptRequest

  expect(state().queryParams.fulfillmentStatus).toBe('READY')
  expect(state().list).toEqual([{ id: 22 }])
  expect(state().total).toBe(1)
})
