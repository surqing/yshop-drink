import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { PendingPrinterOperation, PrinterOperationResolution } from '@/api/mall/store/printer'
import * as PrinterApi from '@/api/mall/store/printer'

type RecoveryPhase = 'closed' | 'loading' | 'empty' | 'error' | 'ready' | 'submitting' | 'uncertain' | 'resolved'
type ReadMode = 'initial' | 'manual' | 'after-submit'

const OUTCOME_BY_OPERATION: Record<string, string> = {
  AUTHORIZE: 'AUTHORIZATION_UNKNOWN',
  DEAUTHORIZE: 'AUTHORIZATION_UNKNOWN',
  RECONNECT: 'AUTHORIZATION_UNKNOWN',
  STATUS_REFRESH: 'DEVICE_STATUS_UNKNOWN',
  TEST_PRINT: 'TEST_PRINT_UNKNOWN',
  LEGACY_PROVIDER_OPERATION: 'PROVIDER_RESULT_UNKNOWN'
}

const RESOLUTION_LABELS: Record<string, string> = {
  AUTHORIZATION_UNKNOWN: '已记录处置；授权结果仍待核验',
  DEVICE_STATUS_UNKNOWN: '已记录处置；设备状态仍未知',
  TEST_PRINT_UNKNOWN: '已记录处置；测试打印结果仍待核验',
  PROVIDER_RESULT_UNKNOWN: '已记录处置；历史厂商操作结果仍未知'
}

const OPERATION_LABELS: Record<string, string> = {
  AUTHORIZE: '设备授权',
  DEAUTHORIZE: '设备取消授权',
  RECONNECT: '设备重新连接',
  STATUS_REFRESH: '设备状态查询',
  TEST_PRINT: '测试打印',
  LEGACY_PROVIDER_OPERATION: '历史厂商操作'
}

const TOKEN_PATTERN = /^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$/

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function parsePending(value: unknown, printerId: number): PendingPrinterOperation {
  if (!isRecord(value)) throw new Error('PENDING_RESPONSE_INVALID')
  if (value.printerId !== printerId) throw new Error('PENDING_PRINTER_MISMATCH')
  if (typeof value.operationToken !== 'string' || !TOKEN_PATTERN.test(value.operationToken))
    throw new Error('PENDING_TOKEN_INVALID')
  if (value.operationType !== null && value.operationType !== undefined && typeof value.operationType !== 'string')
    throw new Error('PENDING_TYPE_INVALID')
  if (value.requestId !== null && value.requestId !== undefined && typeof value.requestId !== 'string')
    throw new Error('PENDING_REQUEST_ID_INVALID')
  if (value.startedAt !== null && value.startedAt !== undefined && typeof value.startedAt !== 'string' && typeof value.startedAt !== 'number')
    throw new Error('PENDING_TIME_INVALID')
  return {
    printerId,
    operationToken: value.operationToken,
    operationType: typeof value.operationType === 'string' ? value.operationType : '',
    requestId: typeof value.requestId === 'string' ? value.requestId : null,
    startedAt: typeof value.startedAt === 'string' || typeof value.startedAt === 'number' ? value.startedAt : null
  }
}

function isForbidden(error: unknown): boolean {
  if (!isRecord(error)) return String(error).toUpperCase() === 'FORBIDDEN'
  const response = isRecord(error.response) ? error.response : undefined
  return response?.status === 403 || error.status === 403 || error.code === 403 || error.code === 'FORBIDDEN'
}

function validateResolution(value: unknown, id: number, expected: PendingPrinterOperation): PrinterOperationResolution {
  if (!isRecord(value) || value.printerId !== id || value.operationToken !== expected.operationToken ||
      value.operationType !== expected.operationType || typeof value.outcome !== 'string' ||
      value.outcome !== OUTCOME_BY_OPERATION[expected.operationType] ||
      (typeof value.resolvedAt !== 'string' && typeof value.resolvedAt !== 'number'))
    throw new Error('RESOLUTION_RESPONSE_MISMATCH')
  return value as unknown as PrinterOperationResolution
}

export function usePrinterOperationRecovery(
  props: { visible: boolean; printerId: number | null; canResolve: boolean },
  emitResolved: (result: PrinterOperationResolution) => void
) {
  const phase = ref<RecoveryPhase>('closed')
  const pending = ref<PendingPrinterOperation | null>(null)
  const reason = ref('')
  const senderStopped = ref(false)
  const providerWorkSettled = ref(false)
  const notice = ref('')
  const errorMessage = ref('')
  const permissionLost = ref(false)
  const resolution = ref<PrinterOperationResolution | null>(null)
  const pendingVerified = ref(false)
  const submitting = ref(false)
  let sequence = 0
  let submitSequence = 0
  let activeSubmit = 0
  let disposed = false

  const operationLabel = computed(() => {
    const type = pending.value?.operationType || ''
    return OPERATION_LABELS[type] || (type ? `未知操作（${type}）` : '未知操作')
  })
  const supported = computed(() => Boolean(pending.value && OUTCOME_BY_OPERATION[pending.value.operationType]))
  const canSubmit = computed(() => {
    const trimmed = reason.value.trim()
    return props.visible && props.printerId !== null && props.canResolve && !permissionLost.value &&
      !submitting.value && pendingVerified.value && Boolean(pending.value) && supported.value &&
      (phase.value === 'ready' || phase.value === 'uncertain') && trimmed.length >= 5 && trimmed.length <= 500 &&
      senderStopped.value && providerWorkSettled.value
  })

  function clearConfirmations() {
    senderStopped.value = false
    providerWorkSettled.value = false
  }

  function resetSession() {
    sequence++
    activeSubmit = 0
    submitting.value = false
    phase.value = 'closed'
    pending.value = null
    reason.value = ''
    clearConfirmations()
    notice.value = ''
    errorMessage.value = ''
    permissionLost.value = false
    resolution.value = null
    pendingVerified.value = false
  }

  function isCurrent(id: number, requestSequence: number) {
    return !disposed && props.visible && props.printerId === id && sequence === requestSequence
  }

  async function readPending(id: number, mode: ReadMode, expectedToken?: string) {
    const previousToken = pending.value?.operationToken
    const requestSequence = ++sequence
    phase.value = 'loading'
    errorMessage.value = ''
    pendingVerified.value = false
    if (mode === 'initial') notice.value = ''
    if (mode === 'manual') {
      clearConfirmations()
      notice.value = '正在重新读取待处理操作。'
    }
    if (mode === 'after-submit') {
      clearConfirmations()
      notice.value = '提交结果未确认，正在重新读取当前待处理操作。'
    }

    try {
      const raw = await PrinterApi.getPendingPrinterOperation(id)
      if (!isCurrent(id, requestSequence)) return
      if (raw === null) {
        pending.value = null
        pendingVerified.value = false
        if (mode === 'after-submit') {
          phase.value = 'uncertain'
          notice.value = '当前无待处理，上一请求结果未确认。'
        } else {
          phase.value = 'empty'
          notice.value = ''
        }
        return
      }

      const currentPending = parsePending(raw, id)
      pending.value = currentPending
      pendingVerified.value = true
      if (mode === 'after-submit') {
        phase.value = 'uncertain'
        if (currentPending.operationToken === expectedToken) {
          notice.value = '结果未确认：原操作仍在待处理中。请重新核验后，手动提交。'
        } else {
          reason.value = ''
          clearConfirmations()
          notice.value = '发现新的待处理操作；旧原因与核验已作废，请重新核验新操作。'
          phase.value = 'ready'
        }
      } else {
        if (mode === 'manual' && previousToken && previousToken !== currentPending.operationToken) {
          reason.value = ''
          clearConfirmations()
          notice.value = '待处理操作已变化；旧原因与核验已作废，请重新核验。'
        } else if (mode === 'manual') {
          notice.value = '已重新读取待处理操作，请核验后再继续。'
        }
        phase.value = 'ready'
      }
    } catch (error) {
      if (!isCurrent(id, requestSequence)) return
      if (isForbidden(error)) {
        permissionLost.value = true
        pendingVerified.value = false
        phase.value = mode === 'initial' ? 'error' : 'uncertain'
        notice.value = mode === 'initial' ? '' : '权限已变化，服务端拒绝本次操作；结果未确认。'
        errorMessage.value = '当前权限不足，无法读取待处理操作。'
      } else if (mode === 'after-submit') {
        phase.value = 'uncertain'
        notice.value = '结果未确认，重新读取失败；请人工重新读取，系统不会自动重试。'
        errorMessage.value = '待处理操作重新读取失败。'
      } else {
        phase.value = 'error'
        errorMessage.value = '待处理操作读取失败，请重试。'
      }
    }
  }

  async function open() {
    resetSession()
    const id = props.printerId
    if (id === null || !Number.isInteger(id) || id <= 0) {
      phase.value = 'error'
      errorMessage.value = '未选择有效设备。'
      return
    }
    await readPending(id, 'initial')
  }

  function close() {
    resetSession()
  }

  async function reload() {
    const id = props.printerId
    if (id === null || submitting.value || !props.visible) return
    await readPending(id, 'manual')
  }

  async function submit() {
    if (!canSubmit.value || submitting.value || !pending.value || props.printerId === null) return
    const id = props.printerId
    const submitted = pending.value
    const submittedSequence = sequence
    const requestId = ++submitSequence
    activeSubmit = requestId
    const trimmedReason = reason.value.trim()
    submitting.value = true
    phase.value = 'submitting'
    notice.value = '正在提交人工核验结果。'
    errorMessage.value = ''
    try {
      const raw = await PrinterApi.resolvePrinterOperation({
        id,
        operationToken: submitted.operationToken,
        reason: trimmedReason,
        senderStopped: true,
        providerWorkSettled: true
      })
      if (!isCurrent(id, submittedSequence) || pending.value?.operationToken !== submitted.operationToken) return
      const checked = validateResolution(raw, id, submitted)
      resolution.value = checked
      pending.value = null
      pendingVerified.value = false
      reason.value = ''
      clearConfirmations()
      notice.value = RESOLUTION_LABELS[checked.outcome]
      phase.value = 'resolved'
      emitResolved(checked)
    } catch (error) {
      if (!isCurrent(id, submittedSequence) || pending.value?.operationToken !== submitted.operationToken) return
      clearConfirmations()
      if (isForbidden(error)) {
        permissionLost.value = true
        pendingVerified.value = false
        phase.value = 'uncertain'
        notice.value = '权限已变化，服务端拒绝本次操作；请勿继续提交。'
        return
      }
      await readPending(id, 'after-submit', submitted.operationToken)
    } finally {
      if (activeSubmit === requestId) {
        activeSubmit = 0
        submitting.value = false
      }
    }
  }

  watch(
    () => [props.visible, props.printerId] as const,
    ([visible]) => {
      if (!visible) {
        close()
        return
      }
      void open()
    },
    { immediate: true, flush: 'sync' }
  )

  onBeforeUnmount(() => {
    disposed = true
    sequence++
    phase.value = 'closed'
    pending.value = null
    reason.value = ''
    clearConfirmations()
    pendingVerified.value = false
  })

  return {
    phase, pending, reason, senderStopped, providerWorkSettled, notice, errorMessage,
    permissionLost, resolution, submitting, supported, operationLabel, canSubmit,
    reload, submit
  }
}
