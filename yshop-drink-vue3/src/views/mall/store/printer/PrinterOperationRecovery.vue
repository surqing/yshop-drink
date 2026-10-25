<template>
  <Dialog
    :model-value="visible"
    title="待处理设备操作核验"
    width="min(720px, 94vw)"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    @update:model-value="onDialogVisible"
  >
    <section class="printer-operation-recovery">
      <p v-if="phase === 'loading' || phase === 'submitting'" role="status">
        {{ submitting ? '正在提交人工核验结果。' : '正在读取待处理操作。' }}
      </p>
      <p v-if="errorMessage" role="alert" class="text-red-600">{{ errorMessage }}</p>
      <p v-if="notice" role="status">{{ notice }}</p>
      <p v-if="phase === 'empty'">当前没有待处理操作。</p>

      <template v-if="pending && phase !== 'closed' && phase !== 'error'">
        <dl class="grid grid-cols-[140px_1fr] gap-x-12px gap-y-8px">
          <dt>设备</dt>
          <dd>{{ printerName || `打印机 #${printerId}` }}</dd>
          <dt>操作类型</dt>
          <dd>{{ operationLabel }}</dd>
          <dt>开始时间</dt>
          <dd>{{ displayStartedAt(pending.startedAt) }}</dd>
          <dt>安全标识</dt>
          <dd>{{ maskedOperationToken }}</dd>
          <dt>请求编号</dt>
          <dd>{{ pending.requestId || '—' }}</dd>
        </dl>

        <p v-if="!supported" role="alert" class="text-orange-600">
          暂不支持恢复的操作类型；当前仅可查看。
        </p>
        <p v-else-if="!canResolve || permissionLost" role="status">
          {{ permissionLost ? '权限已变化，当前仅可查看。' : '当前账号没有解除权限，仅可查看。' }}
        </p>

        <div class="mt-16px">
          <label for="printer-operation-recovery-reason">核验原因（5–500 字符）</label>
          <el-input
            id="printer-operation-recovery-reason"
            v-model="reason"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            :disabled="formDisabled"
            placeholder="记录人工核验依据"
          />
        </div>
        <div class="mt-12px flex flex-col gap-8px">
          <el-checkbox v-model="senderStopped" :disabled="formDisabled">
            我已确认原发送进程已停止。
          </el-checkbox>
          <el-checkbox v-model="providerWorkSettled" :disabled="formDisabled">
            我已确认厂商侧操作已结束，并已人工核验其结果。
          </el-checkbox>
        </div>
      </template>

      <p v-if="resolution" class="mt-16px" role="status">
        {{ resolutionLabel }}（{{ displayStartedAt(resolution.resolvedAt) }}）
      </p>
    </section>

    <template #footer>
      <el-button :disabled="submitting || phase === 'loading' || phase === 'submitting' || phase === 'closed'" @click="reload">
        重新读取
      </el-button>
      <el-button type="primary" :disabled="!canSubmit" :loading="submitting" @click="submit">
        解除待处理状态
      </el-button>
      <el-button :disabled="submitting" @click="close">关闭</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { PrinterOperationResolution, PrinterTime } from '@/api/mall/store/printer'
import { formatDate } from '@/utils/formatTime'
import { usePrinterOperationRecovery } from './usePrinterOperationRecovery'

defineOptions({ name: 'PrinterOperationRecovery' })

const props = withDefaults(defineProps<{
  visible: boolean
  printerId: number | null
  printerName?: string
  canResolve?: boolean
}>(), { canResolve: false })

const emit = defineEmits<{
  (event: 'update:visible', visible: boolean): void
  (event: 'resolved', result: PrinterOperationResolution): void
}>()

const {
  phase, pending, reason, senderStopped, providerWorkSettled, notice, errorMessage,
  permissionLost, resolution, submitting, supported, operationLabel, canSubmit,
  reload: reloadPending, submit: submitRecovery
} = usePrinterOperationRecovery(props, (result) => emit('resolved', result))

const formDisabled = computed(() => submitting.value || phase.value === 'loading' || phase.value === 'submitting' || !props.canResolve || permissionLost.value)
const maskedOperationToken = computed(() => pending.value ? `••••${pending.value.operationToken.slice(-4)}` : '—')
const resolutionLabel = computed(() => {
  switch (resolution.value?.outcome) {
    case 'AUTHORIZATION_UNKNOWN': return '已记录处置；授权结果仍待核验'
    case 'DEVICE_STATUS_UNKNOWN': return '已记录处置；设备状态仍未知'
    case 'TEST_PRINT_UNKNOWN': return '已记录处置；测试打印结果仍待核验'
    case 'PROVIDER_RESULT_UNKNOWN': return '已记录处置；历史厂商操作结果仍未知'
    default: return '处置结果未知'
  }
})

function displayStartedAt(value: PrinterTime | null) {
  if (value === null || value === undefined) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : formatDate(date)
}

function onDialogVisible(visible: boolean) {
  if (!visible && !submitting.value) emit('update:visible', false)
}

function close() {
  if (!submitting.value) emit('update:visible', false)
}

function reload() {
  void reloadPending()
}

function submit() {
  void submitRecovery()
}
</script>
