import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import Form from '../src/views/mall/coupon/Form.vue'
const mocks = vi.hoisted(() => ({
  getCoupon: vi.fn(), getShopList: vi.fn(), createCoupon: vi.fn(), updateCoupon: vi.fn(), createCodeCoupon: vi.fn(),
  success: vi.fn(), error: vi.fn(), confirm: vi.fn()
}))
vi.mock('@/api/mall/coupon', () => mocks)
vi.mock('@/api/mall/store/shop', () => mocks)
vi.mock('@/hooks/web/useMessage', () => ({ useMessage: () => mocks }))
const ElForm = defineComponent({ name: 'ElForm', props: ['model', 'rules'], setup(_p, { expose, slots }) {
  expose({ validate: async () => true, clearValidate() {} }); return () => h('form', slots.default?.())
}})
const Button = defineComponent({ name: 'ElButton', props: ['loading'], setup(_p, { slots, attrs }) {
  return () => h('button', attrs, slots.default?.())
}})
const Dialog = defineComponent({ name: 'Dialog', props: ['modelValue', 'title'], setup(p, { slots }) {
  return () => p.modelValue ? h('section', [h('h2', p.title), slots.default?.(), slots.footer?.()]) : null
}})
let wrapper: ReturnType<typeof mount>
const fixture = (id: number) => ({ id, title: `Campaign ${id}`, shopId: '1', least: 30, value: 5, distribute: 100, limit: 1,
  claimMode: 'PUBLIC', isSwitch: 1, claimStartTime: 1000, claimEndTime: 2000, startTime: 1000, endTime: 3000 })
const state = () => (wrapper.vm as any).$.setupState
const open = (id?: number) => (wrapper.vm as any).open(id ? 'update' : 'create', id)
beforeEach(() => {
  vi.resetAllMocks(); mocks.getShopList.mockResolvedValue([{ id: 1, name: 'Owned shop' }]);
  mocks.getCoupon.mockImplementation(async id => fixture(id)); mocks.confirm.mockResolvedValue(true)
  wrapper = mount(Form, { global: { stubs: { Dialog, ElForm, ElButton: Button, ElFormItem: true,
    ElInput: true, ElAlert: true, ElRadioGroup: true, ElRadio: true, ElSelect: true, ElOption: true,
    ElInputNumber: true, ElDatePicker: true, ElSwitch: true, Materials: true }, directives: { loading() {} } } })
})
afterEach(() => wrapper.unmount())
test('renders edit data and blanks historical redemption code', async () => {
  mocks.getCoupon.mockResolvedValue({ ...fixture(7), exchangeCode: 'synthetic-private-code' })
  await open(7); await flushPromises()
  expect(wrapper.text()).toContain('编辑优惠活动'); expect(state().data.id).toBe(7)
  expect(state().data.exchangeCode).toBe(''); expect(wrapper.html()).not.toContain('synthetic-private-code')
})
test('latest open wins when earlier network response arrives last', async () => {
  let resolveOld!: (v: unknown) => void
  mocks.getCoupon.mockImplementation(id => id === 11 ? new Promise(r => { resolveOld = r }) : Promise.resolve(fixture(id)))
  const old = open(11); await flushPromises(); await open(22); resolveOld(fixture(11)); await old
  expect(state().data.id).toBe(22); expect(state().loading).toBe(false)
})
test('mixed all-store and specific-store selection cannot submit', async () => {
  await open(7); state().selectedShops = ['0', '1']; await flushPromises(); await state().save()
  expect(mocks.error).toHaveBeenCalledWith('全店通用不能与具体门店混选'); expect(mocks.updateCoupon).not.toHaveBeenCalled()
})
test('invalid claim/use windows cannot submit', async () => {
  await open(7); state().data.claimEndTime = 4000; await state().save()
  expect(mocks.error).toHaveBeenCalled(); expect(mocks.updateCoupon).not.toHaveBeenCalled()
})
test('permission rejection keeps dialog and edits for retry, without success', async () => {
  await open(7); const denied = new Error('synthetic-403'); mocks.updateCoupon.mockRejectedValueOnce(denied)
  await expect(state().save()).rejects.toBe(denied)
  expect(state().visible).toBe(true); expect(state().loading).toBe(false); expect(state().data.id).toBe(7)
  expect(mocks.success).not.toHaveBeenCalled(); expect(wrapper.emitted('success')).toBeUndefined()
  await state().save(); expect(wrapper.emitted('success')).toHaveLength(1)
})
test('normal save button sends numeric times and closes only after success', async () => {
  await open(7); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === '保存')!.trigger('click'); await flushPromises()
  expect(mocks.updateCoupon).toHaveBeenCalledWith(expect.objectContaining({ id: 7, shopId: '1', score: 0, claimStartTime: 1000, endTime: 3000 }))
  expect(state().visible).toBe(false); expect(wrapper.emitted('success')).toHaveLength(1)
})
test('generated public code shown once and cleared on close', async () => {
  await open(); state().selectedShops = ['1']; state().data.claimMode = 'CODE'
  mocks.createCodeCoupon.mockResolvedValue({ id: 9, exchangeCode: 'synthetic-generated-one-time' })
  await state().save(); await flushPromises()
  expect(state().codeVisible).toBe(true); expect(state().generatedCode).toBe('synthetic-generated-one-time')
  state().codeVisible = false; await flushPromises(); expect(state().generatedCode).toBe('')
  expect(mocks.createCoupon).not.toHaveBeenCalled()
})
test('cancel refusal preserves unsaved activity', async () => {
  await open(7); mocks.confirm.mockRejectedValue(new Error('synthetic-cancel'))
  await state().close(); expect(state().visible).toBe(true); expect(mocks.updateCoupon).not.toHaveBeenCalled()
})
test('stale request completion cannot clear loading for a newer pending request', async () => {
  const resolvers = new Map<number, (v: unknown) => void>()
  mocks.getCoupon.mockImplementation(id => new Promise(r => resolvers.set(id, r)))
  const old = open(11); await flushPromises(); const current = open(22); await flushPromises()
  resolvers.get(11)!(fixture(11)); await old; expect(state().loading).toBe(true)
  resolvers.get(22)!(fixture(22)); await current; expect(state().data.id).toBe(22); expect(state().loading).toBe(false)
})
test('stale shop-list response cannot start a coupon request after a newer open', async () => {
  let resolveOld!: (v: unknown) => void
  mocks.getShopList.mockImplementationOnce(() => new Promise(r => { resolveOld = r }))
  const old = open(11); await open(22); resolveOld([{ id: 2, name: 'Stale shop' }]); await old
  expect(mocks.getCoupon.mock.calls).toEqual([[22]]); expect(state().data.id).toBe(22)
})
