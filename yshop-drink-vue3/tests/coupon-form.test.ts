import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import Form from '../src/views/mall/coupon/Form.vue'
const mocks = vi.hoisted(() => ({
  getCoupon: vi.fn(), getShopList: vi.fn(), createCoupon: vi.fn(), updateCoupon: vi.fn(), createCodeCoupon: vi.fn(),
  success: vi.fn(), error: vi.fn(), confirm: vi.fn(), validate: vi.fn(), clearValidate: vi.fn()
}))
vi.mock('@/api/mall/coupon', () => mocks)
vi.mock('@/api/mall/store/shop', () => mocks)
vi.mock('@/hooks/web/useMessage', () => ({ useMessage: () => mocks }))
const ElForm = defineComponent({ name: 'ElForm', props: ['model', 'rules'], setup(_p, { expose, slots }) {
  expose({ validate: () => mocks.validate(), clearValidate: () => mocks.clearValidate() }); return () => h('form', slots.default?.())
}})
const Button = defineComponent({ name: 'ElButton', props: ['loading', 'disabled'], setup(p, { slots, attrs }) {
  return () => h('button', { ...attrs, disabled: p.disabled }, slots.default?.())
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
  vi.resetAllMocks(); mocks.validate.mockResolvedValue(true); mocks.getShopList.mockResolvedValue([{ id: 1, name: 'Owned shop' }]);
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

const deferred = <T = any>() => {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const saveButton = () => wrapper.findAll('button').find(button => button.text() === '保存')!
const dismissDialog = async () => {
  wrapper.findComponent(Dialog).vm.$emit('update:modelValue', false)
  await flushPromises()
}

test('opening another activity immediately clears old fields, shops and validation and blocks saving', async () => {
  await open(11); await flushPromises()
  const pending = deferred(); mocks.getShopList.mockReturnValueOnce(pending.promise)
  const opening = open(22); await flushPromises()
  expect(state().data.title).toBeUndefined(); expect(state().selectedShops).toEqual([])
  expect(state().shops).toEqual([]); expect(mocks.clearValidate).toHaveBeenCalledTimes(2)
  expect(saveButton().attributes('disabled')).toBeDefined()
  await state().save(); expect(mocks.updateCoupon).not.toHaveBeenCalled()
  pending.resolve([{ id: 1, name: 'Owned shop' }]); await opening
  expect(state().data.id).toBe(22)
})

test('a failed second detail load cannot save the previously viewed activity', async () => {
  await open(11); const failure = new Error('synthetic-detail-failure')
  mocks.getCoupon.mockRejectedValueOnce(failure)
  await expect(open(22)).rejects.toBe(failure); await flushPromises()
  expect(state().data.id).toBeUndefined(); expect(state().selectedShops).toEqual([])
  expect(saveButton().attributes('disabled')).toBeDefined()
  await state().save(); expect(mocks.updateCoupon).not.toHaveBeenCalled()
})

test('shop-list failure blocks detail loading and saving until an explicit reopen succeeds', async () => {
  await open(11); mocks.getCoupon.mockClear()
  const failure = new Error('synthetic-shop-failure'); mocks.getShopList.mockRejectedValueOnce(failure)
  await expect(open(22)).rejects.toBe(failure)
  await state().save(); expect(mocks.getCoupon).not.toHaveBeenCalled(); expect(mocks.updateCoupon).not.toHaveBeenCalled()
  await dismissDialog(); await open(22); await state().save()
  expect(mocks.updateCoupon.mock.calls).toHaveLength(1)
  expect(mocks.updateCoupon.mock.calls[0][0]).toMatchObject({ id: 22, title: 'Campaign 22', shopId: '1' })
})

test('mismatched detail identity never becomes saveable', async () => {
  mocks.getCoupon.mockResolvedValueOnce(fixture(11)); await open(22)
  await state().save(); expect(mocks.updateCoupon).not.toHaveBeenCalled()
  expect(state().data.id).toBeUndefined(); expect(saveButton().attributes('disabled')).toBeDefined()
})

test('edit without a target identity cannot create or update an activity', async () => {
  await (wrapper.vm as any).open('update'); await state().save()
  expect(mocks.updateCoupon).not.toHaveBeenCalled(); expect(mocks.createCoupon).not.toHaveBeenCalled()
})

test.each(['dialog', 'cancel'] as const)('%s close invalidates a pending detail response', async route => {
  const pending = deferred(); mocks.getCoupon.mockReturnValueOnce(pending.promise)
  const opening = open(11); await flushPromises()
  if (route === 'dialog') await dismissDialog(); else await state().close()
  pending.resolve(fixture(11)); await opening
  expect(state().visible).toBe(false); expect(state().loading).toBe(false)
  expect(state().data.id).toBeUndefined()
  await state().save(); expect(mocks.updateCoupon).not.toHaveBeenCalled()
})

test('close then reopen the same identity ignores the earlier response', async () => {
  const old = deferred(); mocks.getCoupon.mockReturnValueOnce(old.promise)
  const opening = open(11); await flushPromises(); await dismissDialog(); await open(11)
  old.resolve({ ...fixture(11), title: 'Stale title' }); await opening
  expect(state().data.title).toBe('Campaign 11')
  await state().save(); expect(mocks.updateCoupon.mock.calls[0][0].title).toBe('Campaign 11')
})

test('pending edit cannot overwrite a newly opened create form or choose its submit API', async () => {
  const pending = deferred(); mocks.getCoupon.mockReturnValueOnce(pending.promise)
  const opening = open(11); await flushPromises(); await open()
  pending.resolve(fixture(11)); await opening
  state().selectedShops = ['1']; await state().save()
  expect(mocks.updateCoupon).not.toHaveBeenCalled(); expect(mocks.createCoupon).toHaveBeenCalledTimes(1)
  expect(mocks.createCoupon.mock.calls[0][0].id).toBeUndefined()
})

test('pending create cannot overwrite a newly opened edit form or choose its submit API', async () => {
  const pending = deferred(); mocks.getShopList.mockReturnValueOnce(pending.promise)
  const opening = open(); await open(22)
  pending.resolve([{ id: 1, name: 'Stale shop' }]); await opening; await state().save()
  expect(mocks.createCoupon).not.toHaveBeenCalled(); expect(mocks.updateCoupon).toHaveBeenCalledTimes(1)
  expect(mocks.updateCoupon.mock.calls[0][0].id).toBe(22)
})

test('duplicate save during validation and submission sends exactly one write', async () => {
  await open(11); const validation = deferred<boolean>(), submission = deferred()
  mocks.validate.mockReturnValueOnce(validation.promise); mocks.updateCoupon.mockReturnValueOnce(submission.promise)
  const first = state().save(); const duplicate = state().save()
  expect(mocks.validate).toHaveBeenCalledTimes(1)
  validation.resolve(true); await flushPromises(); await state().save()
  expect(mocks.updateCoupon).toHaveBeenCalledTimes(1)
  submission.resolve(undefined); await Promise.all([first, duplicate]); expect(wrapper.emitted('success')).toHaveLength(1)
})

test.each(['reopen', 'close'] as const)('validation completing after %s cannot submit the current or old activity', async action => {
  await open(11); const validation = deferred<boolean>(); mocks.validate.mockReturnValueOnce(validation.promise)
  const saving = state().save()
  if (action === 'reopen') await open(22); else await dismissDialog()
  validation.resolve(true); await saving
  expect(mocks.updateCoupon).not.toHaveBeenCalled(); expect(wrapper.emitted('success')).toBeUndefined()
  if (action === 'reopen') {
    await state().save(); expect(mocks.updateCoupon.mock.calls[0][0].id).toBe(22)
  }
})

test('rejected validation releases the save lock for an explicit retry', async () => {
  await open(11); const failure = new Error('synthetic-validation-failure')
  mocks.validate.mockRejectedValueOnce(failure)
  await expect(state().save()).rejects.toBe(failure)
  await state().save(); expect(mocks.updateCoupon).toHaveBeenCalledTimes(1)
})

test('old successful save cannot close a newer loading form or emit a success for it', async () => {
  await open(11); const submission = deferred(), detail = deferred()
  mocks.updateCoupon.mockReturnValueOnce(submission.promise)
  const saving = state().save(); await flushPromises()
  mocks.getCoupon.mockReturnValueOnce(detail.promise); const opening = open(22); await flushPromises()
  submission.resolve(undefined); await saving
  expect(state().visible).toBe(true); expect(state().loading).toBe(true)
  expect(mocks.success).not.toHaveBeenCalled(); expect(wrapper.emitted('success')).toBeUndefined()
  detail.resolve(fixture(22)); await opening; await state().save()
  expect(mocks.updateCoupon.mock.calls.map(call => call[0].id)).toEqual([11, 22])
  expect(wrapper.emitted('success')).toHaveLength(1)
})

test('old failed save cannot clear the loading state of a newer form', async () => {
  await open(11); const submission = deferred(), detail = deferred(), failure = new Error('synthetic-stale-save-failure')
  mocks.updateCoupon.mockReturnValueOnce(submission.promise)
  const saving = state().save(); const rejected = expect(saving).rejects.toBe(failure); await flushPromises()
  mocks.getCoupon.mockReturnValueOnce(detail.promise); const opening = open(22); await flushPromises()
  submission.reject(failure); await rejected
  expect(state().visible).toBe(true); expect(state().loading).toBe(true)
  detail.resolve(fixture(22)); await opening
})

test('old code creation completion cannot expose a code over a newer form', async () => {
  await open(); state().selectedShops = ['1']; state().data.claimMode = 'CODE'
  const submission = deferred(); mocks.createCodeCoupon.mockReturnValueOnce(submission.promise)
  const saving = state().save(); await flushPromises(); await open(22)
  submission.resolve({ id: 9, exchangeCode: 'synthetic-stale-one-time' }); await saving
  expect(state().visible).toBe(true); expect(state().data.id).toBe(22)
  expect(state().codeVisible).toBe(false); expect(state().generatedCode).toBe('')
  expect(wrapper.emitted('success')).toBeUndefined()
})

test('late cancel confirmation cannot close a newer form', async () => {
  await open(11); const confirmation = deferred(); mocks.confirm.mockReturnValueOnce(confirmation.promise)
  const closing = state().close(); await open(22); confirmation.resolve(true); await closing
  expect(state().visible).toBe(true); expect(state().data.id).toBe(22)
})
