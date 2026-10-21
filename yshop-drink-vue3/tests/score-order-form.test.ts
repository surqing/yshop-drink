import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import Form from '../src/views/score/order/OrderForm.vue'

const mocks = vi.hoisted(() => ({
  getOrder: vi.fn(), updateOrder: vi.fn(), getExpressList: vi.fn(), success: vi.fn(), validate: vi.fn()
}))
vi.mock('@/api/score/order', () => mocks)
vi.mock('@/api/express', () => ({ getExpressList: mocks.getExpressList }))
vi.mock('@/hooks/web/useMessage', () => ({ useMessage: () => mocks }))

const ElForm = defineComponent({
  name: 'ElForm',
  props: ['model', 'rules'],
  setup(_props, { expose, slots }) {
    expose({ validate: () => mocks.validate(), resetFields: vi.fn() })
    return () => h('form', slots.default?.())
  }
})
const Button = defineComponent({
  name: 'ElButton',
  props: ['disabled'],
  setup(_props, { slots, attrs }) { return () => h('button', attrs, slots.default?.()) }
})
const Dialog = defineComponent({
  name: 'Dialog',
  props: ['modelValue', 'title'],
  setup(props, { slots }) { return () => props.modelValue ? h('section', [slots.default?.(), slots.footer?.()]) : null }
})

let wrapper: ReturnType<typeof mount> | undefined
const state = () => (wrapper!.vm as any).$.setupState

beforeEach(() => {
  vi.resetAllMocks()
  ;(globalThis as any).useI18n = () => ({ t: (key: string) => key })
  mocks.validate.mockResolvedValue(true)
  mocks.getExpressList.mockResolvedValue([{ code: 'SF', name: 'Synthetic Express' }])
  mocks.getOrder.mockResolvedValue({
    id: 17, uid: 1, orderId: 'synthetic', productId: 99, number: 4, score: 200,
    totalScore: 800, expressSn: 'SF', expressNumber: 'TRACK-17', expressCompany: 'Synthetic Express',
    havePaid: 1, haveDelivered: 0, haveReceived: 0, customerPhone: '13800000000'
  })
  wrapper = mount(Form, { global: { stubs: {
    Dialog, ElForm, ElButton: Button, ElFormItem: true, ElInput: true, ElRadioGroup: true,
    ElRadio: true, ElSelect: true, ElOption: true
  }, directives: { loading() {} } } })
})

afterEach(() => wrapper?.unmount())

test('shipping form submits only the four allowlisted fields', async () => {
  await state().open('update', 17)
  await flushPromises()

  expect(Object.keys(state().formData).sort()).toEqual(['expressCompany', 'expressNumber', 'expressSn', 'id'])
  await state().submitForm()

  expect(mocks.updateOrder).toHaveBeenCalledTimes(1)
  expect(mocks.updateOrder).toHaveBeenCalledWith({
    id: 17,
    expressSn: 'SF',
    expressNumber: 'TRACK-17',
    expressCompany: 'Synthetic Express'
  })
})
