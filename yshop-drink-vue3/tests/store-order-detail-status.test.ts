import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import OrderDetail from '../src/views/mall/order/storeOrder/OrderDetail.vue'

const mocks = vi.hoisted(() => ({ getStoreOrder: vi.fn() }))
vi.mock('@/api/mall/order/storeOrder', () => mocks)

let wrapper: ReturnType<typeof shallowMount> | undefined
const Description = defineComponent({
  setup(_props, { slots }) { return () => h('section', slots.default?.()) }
})
const Drawer = defineComponent({
  props: ['modelValue'],
  setup(props, { slots }) { return () => props.modelValue ? h('section', slots.default?.()) : null }
})
const DescriptionItem = defineComponent({
  props: ['label'],
  setup(props, { slots }) { return () => h('div', [h('span', props.label), slots.default?.()]) }
})

beforeEach(() => {
  vi.resetAllMocks()
  mocks.getStoreOrder.mockResolvedValue({
    id: 17, orderType: 'takein', statusStr: '退款中', fulfillmentStatus: 'WAIT_ACCEPT',
    storeOrderCartInfoDOList: [], userRespVO: { nickname: 'Synthetic member' }
  })
  ;(globalThis as any).useI18n = () => ({ t: (key: string) => key })
  wrapper = shallowMount(OrderDetail, { global: { stubs: {
    'el-descriptions': Description, 'el-descriptions-item': DescriptionItem,
    'el-drawer': Drawer, 'el-image': true, 'el-tag': true,
    'el-timeline': true, 'el-timeline-item': true
  } } })
})

afterEach(() => wrapper?.unmount())

test('order detail keeps refund status and shows pickup fulfillment separately', async () => {
  await (wrapper!.vm as any).open('orderDetail', 17)
  await flushPromises()

  expect(wrapper!.text()).toContain('退款中')
  expect(wrapper!.text()).toContain('订单状态')
  expect(wrapper!.text()).toContain('履约状态')
  expect(wrapper!.text()).toContain('待接单')
  expect(mocks.getStoreOrder).toHaveBeenCalledWith(17)
})
