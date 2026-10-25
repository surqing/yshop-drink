import { beforeEach, expect, test, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import * as PrinterApi from '../src/api/mall/store/printer'

const request = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const pageMocks = vi.hoisted(() => ({ permissions: ['store:web-print:query'] as string[] }))
vi.mock('@/config/axios', () => ({ default: request }))
vi.mock('@/hooks/web/useCache', () => ({
  CACHE_KEY: { USER: 'USER', TenantId: 'TENANT' },
  useCache: () => ({ wsCache: { get: () => ({ permissions: pageMocks.permissions, user: { id: 101, shopId: 0 } }) } })
}))
vi.mock('@/hooks/web/useMessage', () => ({
  useMessage: () => ({ confirm: vi.fn().mockResolvedValue(true), success: vi.fn(), warning: vi.fn(), error: vi.fn() })
}))

const tokenA = 'b91cc79d-a46f-4ce3-b903-0e26a8e8d80c'
const tokenB = 'e0d79e1e-dd99-40e5-889f-9bf0f5a73d9f'
const pending = (printerId = 7, operationToken = tokenA, operationType = 'STATUS_REFRESH') => ({
  printerId,
  operationToken,
  operationType,
  requestId: null,
  startedAt: '2031-10-22 09:30:00'
})
const resolution = (overrides: Record<string, unknown> = {}) => ({
  printerId: 7,
  operationToken: tokenA,
  operationType: 'STATUS_REFRESH',
  outcome: 'DEVICE_STATUS_UNKNOWN',
  resolvedAt: '2031-10-22 09:30:05',
  ...overrides
})

const DialogStub = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  setup(props, { slots, emit }) {
    return () => props.modelValue
      ? h('section', { 'data-testid': 'dialog' }, [
          h('button', { type: 'button', onClick: () => emit('update:modelValue', false) }, 'dialog close'),
          slots.default?.(),
          slots.footer?.()
        ])
      : null
  }
})
const InputStub = defineComponent({
  props: ['modelValue', 'disabled', 'type', 'maxlength', 'placeholder'],
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h(props.type === 'textarea' ? 'textarea' : 'input', {
      value: props.modelValue,
      disabled: props.disabled,
      maxlength: props.maxlength,
      placeholder: props.placeholder,
      onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value)
    })
  }
})
const CheckboxStub = defineComponent({
  props: ['modelValue', 'label', 'disabled'],
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h('label', [
      h('input', {
        type: 'checkbox', checked: props.modelValue, disabled: props.disabled,
        onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).checked)
      }),
      props.label
    ])
  }
})
const ButtonStub = defineComponent({
  props: ['disabled', 'loading'],
  setup(props, { slots, attrs }) {
    return () => h('button', { ...attrs, disabled: props.disabled || props.loading }, slots.default?.())
  }
})
const deferred = <T,>() => {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const recoveryComponents = import.meta.glob('../src/views/mall/store/printer/PrinterOperationRecovery.vue')
const printerPages = import.meta.glob('../src/views/mall/store/printer/index.vue')
const loadComponent = async () => {
  const load = recoveryComponents['../src/views/mall/store/printer/PrinterOperationRecovery.vue']
  if (!load) throw new Error('RECOVERY_COMPONENT_MISSING')
  return (await load() as { default: unknown }).default
}
const loadPrinterPage = async () => {
  const load = printerPages['../src/views/mall/store/printer/index.vue']
  if (!load) throw new Error('PRINTER_PAGE_MISSING')
  return (await load() as { default: unknown }).default
}
const mountRecovery = async (printerId: number | null = 7) => {
  const component = await loadComponent()
  const wrapper = mount(component, {
    props: { visible: true, printerId, printerName: `设备 ${printerId}`, canResolve: true },
    global: { stubs: { Dialog: DialogStub, ElInput: InputStub, ElCheckbox: CheckboxStub, ElButton: ButtonStub, ElAlert: true } }
  })
  await flushPromises()
  return wrapper
}
const button = (wrapper: ReturnType<typeof mount>, label: string) =>
  wrapper.findAll('button').find((item) => item.text() === label)!

beforeEach(() => {
  vi.resetAllMocks()
  request.get.mockResolvedValue(pending())
  request.post.mockResolvedValue(resolution())
})

test('pending operation API reads the exact printer operation endpoint', async () => {
  const pending = {
    printerId: 7,
    operationToken: 'b91cc79d-a46f-4ce3-b903-0e26a8e8d80c',
    operationType: 'STATUS_REFRESH',
    requestId: null,
    startedAt: '2031-10-22 09:30:00'
  }
  request.get.mockResolvedValue(pending)

  const actual = await (PrinterApi as any).getPendingPrinterOperation(7)

  expect(request.get).toHaveBeenCalledWith({
    url: '/store/printer/operation/pending',
    params: { id: 7 }
  })
  expect(actual).toEqual(pending)
})

test('resolve operation API posts the exact conditional recovery command', async () => {
  const command = {
    id: 7,
    operationToken: 'b91cc79d-a46f-4ce3-b903-0e26a8e8d80c',
    reason: '厂商后台已核实旧请求结束',
    senderStopped: true,
    providerWorkSettled: true
  }
  const result = {
    printerId: 7,
    operationToken: command.operationToken,
    operationType: 'STATUS_REFRESH',
    outcome: 'DEVICE_STATUS_UNKNOWN',
    resolvedAt: '2031-10-22 09:30:05'
  }
  request.post.mockResolvedValue(result)

  const actual = await (PrinterApi as any).resolvePrinterOperation(command)

  expect(request.post).toHaveBeenCalledWith({
    url: '/store/printer/operation/resolve',
    data: command
  })
  expect(actual).toEqual(result)
})

test('empty pending state is distinct from a read failure and sends no command', async () => {
  request.get.mockResolvedValueOnce(null)
  const empty = await mountRecovery()
  expect(empty.text()).toContain('当前没有待处理操作')
  expect(request.post).not.toHaveBeenCalled()
  empty.unmount()

  request.get.mockRejectedValueOnce(new Error('offline'))
  const failed = await mountRecovery()
  expect(failed.text()).toContain('读取失败')
  expect(failed.text()).not.toContain('当前没有待处理操作')
  expect(request.post).not.toHaveBeenCalled()
  failed.unmount()
})

test('mismatched pending identity fails closed and unknown operation type is read only', async () => {
  request.get.mockResolvedValueOnce(pending(8))
  const mismatched = await mountRecovery(7)
  expect(mismatched.text()).toContain('读取失败')
  mismatched.unmount()

  request.get.mockResolvedValueOnce(pending(7, tokenA, 'FUTURE_OPERATION'))
  const unknown = await mountRecovery(7)
  expect(unknown.text()).toContain('暂不支持恢复的操作类型')
  expect(button(unknown, '解除待处理状态').element.disabled).toBe(true)
  expect(request.post).not.toHaveBeenCalled()
  unknown.unmount()
})

test('reason must be 5 to 500 trimmed characters and both confirmations are explicit', async () => {
  const wrapper = await mountRecovery()
  const submit = button(wrapper, '解除待处理状态')
  const input = wrapper.get('textarea')
  const confirmations = wrapper.findAll('input[type="checkbox"]')

  await input.setValue('     ')
  await confirmations[0].setValue(true)
  await confirmations[1].setValue(true)
  expect(submit.element.disabled).toBe(true)

  await input.setValue(`  ${'x'.repeat(4)}  `)
  expect(submit.element.disabled).toBe(true)
  await input.setValue(`  ${'x'.repeat(5)}  `)
  expect(submit.element.disabled).toBe(false)
  expect(request.post).not.toHaveBeenCalled()

  await input.setValue('x'.repeat(501))
  expect(submit.element.disabled).toBe(true)
  await input.setValue('x'.repeat(500))
  expect(submit.element.disabled).toBe(false)
  await confirmations[1].setValue(false)
  expect(submit.element.disabled).toBe(true)
  wrapper.unmount()
})

test('submission trims reason, allows one in-flight command, and emits only a matching audited result', async () => {
  const post = deferred<unknown>()
  request.post.mockReturnValueOnce(post.promise)
  const wrapper = await mountRecovery()
  await wrapper.get('textarea').setValue('  厂商后台已核实旧请求结束  ')
  const confirmations = wrapper.findAll('input[type="checkbox"]')
  await confirmations[0].setValue(true)
  await confirmations[1].setValue(true)
  await button(wrapper, '解除待处理状态').trigger('click')
  await button(wrapper, '解除待处理状态').trigger('click')

  expect(request.post).toHaveBeenCalledTimes(1)
  expect(request.post).toHaveBeenCalledWith(expect.objectContaining({
    url: '/store/printer/operation/resolve',
    data: { id: 7, operationToken: tokenA, reason: '厂商后台已核实旧请求结束', senderStopped: true, providerWorkSettled: true }
  }))
  expect(button(wrapper, '解除待处理状态').element.disabled).toBe(true)
  expect(button(wrapper, '关闭').element.disabled).toBe(true)

  post.resolve(resolution())
  await flushPromises()
  expect(wrapper.text()).toContain('设备状态仍未知')
  expect(wrapper.emitted('resolved')).toHaveLength(1)
  expect(wrapper.emitted('resolved')?.[0]).toEqual([resolution()])
  expect(request.get).toHaveBeenCalledTimes(1)
  wrapper.unmount()
})

test('a mismatched resolve response never appears successful and only rereads pending', async () => {
  request.post.mockResolvedValueOnce(resolution({ printerId: 8 }))
  const wrapper = await mountRecovery()
  await wrapper.get('textarea').setValue('已核验旧请求已结束')
  const confirmations = wrapper.findAll('input[type="checkbox"]')
  await confirmations[0].setValue(true)
  await confirmations[1].setValue(true)
  await button(wrapper, '解除待处理状态').trigger('click')
  await flushPromises()

  expect(wrapper.text()).toContain('结果未确认')
  expect(wrapper.text()).not.toContain('已记录处置')
  expect(wrapper.emitted('resolved')).toBeUndefined()
  expect(request.post).toHaveBeenCalledTimes(1)
  expect(request.get).toHaveBeenCalledTimes(2)
  wrapper.unmount()
})

test('lost response with original token clears confirmations and never auto-posts again', async () => {
  request.post.mockRejectedValueOnce(new Error('network lost'))
  request.get.mockResolvedValueOnce(pending()).mockResolvedValueOnce(pending())
  const wrapper = await mountRecovery()
  await wrapper.get('textarea').setValue('人工核实原厂商操作结束')
  const confirmations = wrapper.findAll('input[type="checkbox"]')
  await confirmations[0].setValue(true)
  await confirmations[1].setValue(true)
  await button(wrapper, '解除待处理状态').trigger('click')
  await flushPromises()

  expect(wrapper.text()).toContain('结果未确认')
  expect(wrapper.findAll('input[type="checkbox"]').every((item) => !(item.element as HTMLInputElement).checked)).toBe(true)
  expect(button(wrapper, '解除待处理状态').element.disabled).toBe(true)
  expect(request.post).toHaveBeenCalledTimes(1)
  expect(wrapper.emitted('resolved')).toBeUndefined()
  wrapper.unmount()
})

test('lost response with no pending operation is not reported as success', async () => {
  request.post.mockRejectedValueOnce(new Error('response lost'))
  request.get.mockResolvedValueOnce(pending()).mockResolvedValueOnce(null)
  const wrapper = await mountRecovery()
  await wrapper.get('textarea').setValue('人工核实原厂商操作结束')
  const confirmations = wrapper.findAll('input[type="checkbox"]')
  await confirmations[0].setValue(true)
  await confirmations[1].setValue(true)
  await button(wrapper, '解除待处理状态').trigger('click')
  await flushPromises()

  expect(wrapper.text()).toContain('当前无待处理，上一请求结果未确认')
  expect(wrapper.emitted('resolved')).toBeUndefined()
  expect(request.post).toHaveBeenCalledTimes(1)
  wrapper.unmount()
})

test('new token after a lost response replaces the operation and resets operator input', async () => {
  request.post.mockRejectedValueOnce(new Error('response lost'))
  request.get.mockResolvedValueOnce(pending()).mockResolvedValueOnce(pending(7, tokenB, 'TEST_PRINT'))
  const wrapper = await mountRecovery()
  await wrapper.get('textarea').setValue('人工核实原厂商操作结束')
  const confirmations = wrapper.findAll('input[type="checkbox"]')
  await confirmations[0].setValue(true)
  await confirmations[1].setValue(true)
  await button(wrapper, '解除待处理状态').trigger('click')
  await flushPromises()

  expect(wrapper.text()).toContain('新的待处理操作')
  expect(wrapper.text()).toContain('测试打印')
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('')
  expect(wrapper.findAll('input[type="checkbox"]').every((item) => !(item.element as HTMLInputElement).checked)).toBe(true)
  expect(request.post).toHaveBeenCalledTimes(1)
  expect(wrapper.emitted('resolved')).toBeUndefined()
  wrapper.unmount()
})

test('a 403 after opening disables recovery for the same operator session', async () => {
  request.post.mockRejectedValueOnce(Object.assign(new Error('forbidden'), { response: { status: 403 } }))
  const wrapper = await mountRecovery()
  await wrapper.get('textarea').setValue('人工核实原厂商操作结束')
  const confirmations = wrapper.findAll('input[type="checkbox"]')
  await confirmations[0].setValue(true)
  await confirmations[1].setValue(true)
  await button(wrapper, '解除待处理状态').trigger('click')
  await flushPromises()

  expect(wrapper.text()).toContain('权限已变化')
  expect(button(wrapper, '解除待处理状态').element.disabled).toBe(true)
  expect(request.get).toHaveBeenCalledTimes(1)
  expect(wrapper.emitted('resolved')).toBeUndefined()
  wrapper.unmount()
})

test('a late response from device A cannot replace device B details or loading state', async () => {
  const first = deferred<unknown>()
  request.get.mockReturnValueOnce(first.promise).mockResolvedValueOnce(pending(8, tokenB, 'TEST_PRINT'))
  const component = await loadComponent()
  const wrapper = mount(component, {
    props: { visible: true, printerId: 7, printerName: '设备 A', canResolve: true },
    global: { stubs: { Dialog: DialogStub, ElInput: InputStub, ElCheckbox: CheckboxStub, ElButton: ButtonStub, ElAlert: true } }
  })
  await wrapper.setProps({ printerId: 8, printerName: '设备 B' })
  await flushPromises()
  first.resolve(pending(7))
  await flushPromises()

  expect(wrapper.text()).toContain('设备 B')
  expect(wrapper.text()).toContain('测试打印')
  expect(wrapper.text()).not.toContain('设备 A')
  expect(wrapper.text()).not.toContain(tokenA)
  wrapper.unmount()
})

test('closing and reopening the same device ignores the earlier read response', async () => {
  const earlier = deferred<unknown>()
  const later = deferred<unknown>()
  request.get.mockReturnValueOnce(earlier.promise).mockReturnValueOnce(later.promise)
  const component = await loadComponent()
  const wrapper = mount(component, {
    props: { visible: true, printerId: 7, printerName: '设备 A', canResolve: true },
    global: { stubs: { Dialog: DialogStub, ElInput: InputStub, ElCheckbox: CheckboxStub, ElButton: ButtonStub, ElAlert: true } }
  })
  await wrapper.setProps({ visible: false })
  await wrapper.setProps({ visible: true })
  earlier.resolve(pending(7, tokenA))
  await flushPromises()
  expect(wrapper.text()).toContain('正在读取')
  later.resolve(pending(7, tokenB, 'TEST_PRINT'))
  await flushPromises()
  expect(wrapper.text()).toContain('测试打印')
  expect(wrapper.text()).not.toContain('••••e8d80c')
  wrapper.unmount()
})

test('late submit completion after unmount does not emit a recovery result', async () => {
  const post = deferred<unknown>()
  request.post.mockReturnValueOnce(post.promise)
  const wrapper = await mountRecovery()
  await wrapper.get('textarea').setValue('人工核实原厂商操作结束')
  const confirmations = wrapper.findAll('input[type="checkbox"]')
  await confirmations[0].setValue(true)
  await confirmations[1].setValue(true)
  await button(wrapper, '解除待处理状态').trigger('click')
  wrapper.unmount()
  post.resolve(resolution())
  await flushPromises()
  expect(request.get).toHaveBeenCalledTimes(1)
})

test('A to B switch ignores a late read failure without replacing B state', async () => {
  const oldRead = deferred<unknown>()
  request.get.mockResolvedValueOnce(pending())
  request.get.mockReturnValueOnce(oldRead.promise)
  request.get.mockResolvedValueOnce(pending(8, tokenB))
  const wrapper = await mountRecovery()

  await button(wrapper, '重新读取').trigger('click')
  await wrapper.setProps({ printerId: 8, printerName: '设备 8' })
  await flushPromises()
  expect(wrapper.text()).toContain('设备 8')
  expect(wrapper.text()).toContain(tokenB.slice(-4))

  oldRead.reject(new Error('late A read failure'))
  await flushPromises()
  expect(wrapper.text()).toContain('设备 8')
  expect(wrapper.text()).not.toContain('读取失败')
  expect(wrapper.text()).toContain(tokenB.slice(-4))
  wrapper.unmount()
})

test('late A submit finally cannot unlock B submit after switching devices', async () => {
  const oldSubmit = deferred<unknown>()
  const newSubmit = deferred<unknown>()
  request.get.mockResolvedValueOnce(pending())
  request.get.mockResolvedValueOnce(pending(8, tokenB))
  request.post.mockReturnValueOnce(oldSubmit.promise).mockReturnValueOnce(newSubmit.promise)
  const wrapper = await mountRecovery()

  await wrapper.get('textarea').setValue('核实 A 设备原操作结束')
  let confirmations = wrapper.findAll('input[type="checkbox"]')
  await confirmations[0].setValue(true)
  await confirmations[1].setValue(true)
  await button(wrapper, '解除待处理状态').trigger('click')
  expect(request.post).toHaveBeenCalledTimes(1)

  await wrapper.setProps({ printerId: 8, printerName: '设备 8' })
  await flushPromises()
  expect(wrapper.text()).toContain('设备 8')
  expect(button(wrapper, '解除待处理状态').element.disabled).toBe(true)
  await wrapper.get('textarea').setValue('核实 B 设备原操作结束')
  confirmations = wrapper.findAll('input[type="checkbox"]')
  await confirmations[0].setValue(true)
  await confirmations[1].setValue(true)
  await button(wrapper, '解除待处理状态').trigger('click')
  expect(request.post).toHaveBeenCalledTimes(2)

  oldSubmit.resolve(resolution())
  await flushPromises()
  expect(button(wrapper, '解除待处理状态').element.disabled).toBe(true)
  expect(button(wrapper, '关闭').element.disabled).toBe(true)
  expect(wrapper.emitted('resolved')).toBeUndefined()

  newSubmit.resolve(resolution({ printerId: 8, operationToken: tokenB }))
  await flushPromises()
  expect(wrapper.emitted('resolved')).toHaveLength(1)
  expect(wrapper.emitted('resolved')?.[0]?.[0]).toMatchObject({ printerId: 8, operationToken: tokenB })
  wrapper.unmount()
})

test('late A submit failure after a device switch cannot clear B submit state or trigger a reread', async () => {
  const oldSubmit = deferred<unknown>()
  const newSubmit = deferred<unknown>()
  request.get.mockResolvedValueOnce(pending()).mockResolvedValueOnce(pending(8, tokenB))
  request.post.mockReturnValueOnce(oldSubmit.promise).mockReturnValueOnce(newSubmit.promise)
  const wrapper = await mountRecovery()

  await wrapper.get('textarea').setValue('核实 A 设备原操作结束')
  let confirmations = wrapper.findAll('input[type="checkbox"]')
  await confirmations[0].setValue(true)
  await confirmations[1].setValue(true)
  await button(wrapper, '解除待处理状态').trigger('click')
  await wrapper.setProps({ printerId: 8, printerName: '设备 8' })
  await flushPromises()

  await wrapper.get('textarea').setValue('核实 B 设备原操作结束')
  confirmations = wrapper.findAll('input[type="checkbox"]')
  await confirmations[0].setValue(true)
  await confirmations[1].setValue(true)
  await button(wrapper, '解除待处理状态').trigger('click')
  expect(request.post).toHaveBeenCalledTimes(2)

  oldSubmit.reject(new Error('late A submit failure'))
  await flushPromises()
  expect(request.get).toHaveBeenCalledTimes(2)
  expect(button(wrapper, '解除待处理状态').element.disabled).toBe(true)
  expect(button(wrapper, '关闭').element.disabled).toBe(true)
  expect(wrapper.text()).not.toContain('读取失败')

  newSubmit.resolve(resolution({ printerId: 8, operationToken: tokenB }))
  await flushPromises()
  expect(wrapper.emitted('resolved')).toHaveLength(1)
  wrapper.unmount()
})

test('external close and reopen invalidates an old submit before a new session', async () => {
  const oldSubmit = deferred<unknown>()
  request.post.mockReturnValueOnce(oldSubmit.promise).mockResolvedValueOnce(resolution({ operationToken: tokenB }))
  request.get.mockResolvedValueOnce(pending()).mockResolvedValueOnce(pending(7, tokenB))
  const wrapper = await mountRecovery()
  await wrapper.get('textarea').setValue('核实旧设备操作结束')
  let confirmations = wrapper.findAll('input[type="checkbox"]')
  await confirmations[0].setValue(true)
  await confirmations[1].setValue(true)
  await button(wrapper, '解除待处理状态').trigger('click')

  await wrapper.setProps({ visible: false })
  await wrapper.setProps({ visible: true })
  await flushPromises()
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('')
  expect(wrapper.text()).toContain(tokenB.slice(-4))
  expect(wrapper.emitted('resolved')).toBeUndefined()

  oldSubmit.resolve(resolution())
  await flushPromises()
  expect(wrapper.emitted('resolved')).toBeUndefined()
  expect(button(wrapper, '解除待处理状态').element.disabled).toBe(true)
  wrapper.unmount()
})

test('printer list opens the separate recovery component with read-only permission state', async () => {
  pageMocks.permissions = ['store:web-print:query']
  const row = {
    id: 7, name: '设备 7', providerCode: 'E2E', deviceCode: 'DEVICE-7', connectionType: 'CLOUD',
    enabled: true, deleted: false, authorization: 'UNKNOWN', deviceState: 'UNKNOWN', configuredFields: [],
    createTime: '2031-10-22 09:00:00'
  }
  request.get.mockImplementation(({ url }: { url: string }) => {
    if (url === '/store/printer/providers') return Promise.resolve([])
    if (url === '/store/printer/shops') return Promise.resolve([])
    if (url === '/store/printer/page') return Promise.resolve({ list: [row], total: 1 })
    if (url === '/store/printer/operation/pending') return Promise.resolve(pending())
    return Promise.reject(new Error(`UNEXPECTED_GET:${url}`))
  })
  ;(globalThis as any).useI18n = () => ({ t: (key: string) => key })
  const page = await loadPrinterPage()
  const wrapper = mount(page as any, {
    global: {
      stubs: {
        ContentWrap: true, Pagination: true, OrderPrintJobs: true, Dialog: DialogStub,
        ElTabs: true, ElTabPane: true, ElAlert: true, ElForm: true, ElFormItem: true,
        ElSelect: true, ElOption: true, ElInput: InputStub, ElButton: ButtonStub,
        ElTable: true, ElTableColumn: true, ElTag: true, ElSwitch: true, ElCheckbox: CheckboxStub
      },
      directives: { hasPermi() {}, loading() {} }
    }
  })
  await flushPromises()

  const state = (wrapper.vm as any).$.setupState
  expect(state.openRecovery).toBeTypeOf('function')
  state.openRecovery(row)
  await flushPromises()
  expect(request.get).toHaveBeenCalledWith({ url: '/store/printer/operation/pending', params: { id: 7 } })
  expect(wrapper.text()).toContain('当前账号没有解除权限')
  expect(button(wrapper as any, '解除待处理状态').element.disabled).toBe(true)
  wrapper.unmount()
})
