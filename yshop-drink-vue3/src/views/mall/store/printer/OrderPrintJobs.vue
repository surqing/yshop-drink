<template>
  <ContentWrap>
    <el-alert
      title="已提交仅表示打印服务接受了任务，不代表纸张已出。结果待核对时请先检查设备；刷新只读取系统记录，不会再次发送。"
      type="info"
      :closable="false"
      show-icon
      class="mb-16px"
    />
    <el-alert v-if="loadError" title="订单打印任务加载失败" type="error" :closable="false" show-icon>
      <el-button link type="primary" @click="load">重新加载</el-button>
    </el-alert>
    <el-form :inline="true" @submit.prevent="search">
      <el-form-item label="门店">
        <el-select v-model="query.shopId" placeholder="全部门店" clearable style="width: 200px">
          <el-option v-for="shop in shopList" :key="shop.id" :label="shop.name" :value="shop.id" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">搜索</el-button>
        <el-button @click="load">刷新列表</el-button>
      </el-form-item>
    </el-form>
    <el-table v-loading="loading" :data="list" empty-text="暂无订单打印任务">
      <el-table-column label="门店 / 订单" min-width="190">
        <template #default="{ row }">
          <div>{{ details(row).shopName || `门店 ${row.shopId}` }}</div>
          <small class="text-gray-500">{{ details(row).orderCode || `订单 ${row.orderId}` }}</small>
        </template>
      </el-table-column>
      <el-table-column label="取餐信息" min-width="150">
        <template #default="{ row }">
          <div>取餐号：{{ details(row).pickupNumber || '—' }}</div>
          <small class="text-gray-500">{{ details(row).contactPhone || '—' }}</small>
        </template>
      </el-table-column>
      <el-table-column label="打印机" min-width="130">
        <template #default="{ row }">{{ row.printerName || '等待可用设备' }}</template>
      </el-table-column>
      <el-table-column label="状态" min-width="155">
        <template #default="{ row }">
          <el-tag :type="stateType(row.state)">{{ stateLabels[row.state] || row.state }}</el-tag>
          <div v-if="row.errorCode" class="text-orange-600 text-xs mt-4px">{{ errorText(row.errorCode) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="服务打印编号" prop="providerJobId" min-width="140" />
      <el-table-column label="尝试次数" prop="attempts" width="90" />
      <el-table-column label="重打原因" prop="reprintReason" min-width="160" />
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ displayTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="245" fixed="right">
        <template #default="{ row }">
          <el-button
            v-hasPermi="['store:web-print:query']"
            link
            type="primary"
            :loading="busyId === row.id"
            @click="refresh(row)"
            >刷新状态</el-button
          >
          <el-button
            v-if="row.id > 0 && canReprint(row)"
            v-hasPermi="['store:web-print:test']"
            link
            type="warning"
            :disabled="busyId === row.id"
            @click="openReprint(row)"
            >人工重打</el-button
          >
          <el-button
            v-if="row.id > 0 && row.state === 'UNKNOWN'"
            v-hasPermi="['store:web-print:test']"
            link
            type="danger"
            :disabled="busyId === row.id"
            @click="openRecovery(row)"
            >核验后释放占用</el-button
          >
        </template>
      </el-table-column>
    </el-table>
    <Pagination
      v-model:page="query.pageNo"
      v-model:limit="query.pageSize"
      :total="total"
      @pagination="load"
    />
  </ContentWrap>

  <Dialog v-model="reprintVisible" title="人工重打" width="520px">
    <el-alert
      title="重打会创建关联原任务的新任务，保留原小票快照和打印机。请先检查设备并填写原因。"
      type="warning"
      :closable="false"
      class="mb-16px"
    />
    <el-form label-width="90px" @submit.prevent="submitReprint">
      <el-form-item label="重打原因">
        <el-input
          v-model="reprintReason"
          type="textarea"
          :rows="3"
          maxlength="255"
          show-word-limit
          :disabled="!!reprintRequestKey"
          placeholder="例如：顾客反馈未收到小票"
        />
      </el-form-item>
    </el-form>
    <el-alert v-if="reprintError" title="提交未完成。网络中断后重试会沿用同一请求编号；权限或状态错误请先处理。" type="error" :closable="false" />
    <template #footer>
      <el-button @click="reprintVisible = false">关闭</el-button>
      <el-button type="warning" :loading="reprinting" :disabled="!reprintReason.trim()" @click="submitReprint">
        {{ reprintRequestKey ? '用相同编号重试' : '创建重打任务' }}
      </el-button>
    </template>
  </Dialog>

  <Dialog v-model="recoveryVisible" title="核验后释放打印占用" width="560px">
    <el-alert
      title="此操作只用于进程中断后的 UNKNOWN 任务。必须先确认原发送进程已停止，再根据设备或打印服务核验结果选择已出纸或未出纸。系统会记录操作人和原因，结案后不会自动重发；确认已出纸的任务也不能人工重打。"
      type="error"
      :closable="false"
      show-icon
      class="mb-16px"
    />
    <el-form label-width="120px" @submit.prevent="submitRecovery">
      <el-form-item label="处理说明">
        <el-input
          v-model="recoveryReason"
          type="textarea"
          :rows="3"
          maxlength="255"
          show-word-limit
          :disabled="!!recoveryRequestKey"
          placeholder="记录如何确认旧进程停止且设备没有出纸"
        />
      </el-form-item>
      <el-form-item label="发送进程">
        <el-checkbox v-model="senderStopped" :disabled="!!recoveryRequestKey">
          我已确认原发送进程已停止
        </el-checkbox>
      </el-form-item>
      <el-form-item label="核验结果">
        <el-radio-group v-model="recoveryResolution" :disabled="!!recoveryRequestKey">
          <el-radio label="NOT_PRINTED">打印服务或设备确认未出纸</el-radio>
          <el-radio label="PRINTED">设备或现场确认已出纸</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <el-alert v-if="recoveryError" title="释放未完成。重试会沿用同一请求编号；请勿在未核实结果时释放占用。" type="warning" :closable="false" />
    <template #footer>
      <el-button @click="recoveryVisible = false">关闭</el-button>
      <el-button
        type="danger"
        :loading="recovering"
        :disabled="!recoveryReason.trim() || !senderStopped || !recoveryResolution"
        @click="submitRecovery"
      >确认核验结果并释放占用</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import * as Api from '@/api/mall/store/printer'
import { formatDate } from '@/utils/formatTime'

const message = useMessage()
const list = ref<Api.OrderPrintJob[]>([])
const shopList = ref<Api.ShopOption[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref(false)
const busyId = ref<number>()
const requestSequence = ref(0)
const query = reactive({ pageNo: 1, pageSize: 20, shopId: undefined as number | undefined })
const stateLabels: Record<Api.OrderPrintJob['state'], string> = {
  PENDING: '待发送',
  SENDING: '发送中',
  ACCEPTED: '打印服务已接受',
  FAILED: '发送失败',
  UNKNOWN: '结果待核对',
  WAITING_DEVICE: '等待可用设备'
}
const stateType = (state: Api.OrderPrintJob['state']) =>
  state === 'ACCEPTED' ? 'success' : state === 'FAILED' ? 'danger' : state === 'UNKNOWN' ? 'warning' : 'info'
const errorLabels: Record<string, string> = {
  NO_BOUND_DEVICE: '门店尚未绑定打印机',
  PRINTER_DISABLED: '打印机已停用',
  PRINTER_NOT_AUTHORIZED: '打印机尚未授权',
  DEVICE_OFFLINE: '打印机离线',
  DEVICE_PAPER_OUT: '打印机缺纸',
  DEVICE_UNKNOWN: '设备状态未知',
  WORKER_LEASE_EXPIRED: '发送进程中断，结果待核对',
  PROVIDER_TIMEOUT: '服务响应超时，请检查设备',
  PRINTER_NOT_BOUND: '原打印机已解绑',
  DEVICE_NOT_ONLINE: '打印机当前未在线',
  UNSUPPORTED_OPERATION: '设备不支持订单打印',
  OPERATOR_CONFIRMED_NOT_PRINTED: '已核实未出纸，等待人工重打',
  OPERATOR_CONFIRMED_PRINTED: '人工核验确认已出纸'
}
const errorText = (code: string) => errorLabels[code] || '操作未完成，请检查设备'
const displayTime = (value: Api.PrinterTime) => formatDate(new Date(value))

function details(row: Api.OrderPrintJob): Record<string, unknown> {
  try {
    return JSON.parse(row.payloadSnapshot) as Record<string, unknown>
  } catch {
    return {}
  }
}

async function load() {
  const sequence = ++requestSequence.value
  loading.value = true
  try {
    const [page, shops] = await Promise.all([
      Api.orderPrintJobs({ pageNo: query.pageNo, pageSize: query.pageSize, shopId: query.shopId }),
      shopList.value.length ? Promise.resolve(shopList.value) : Api.shops()
    ])
    if (sequence !== requestSequence.value) return
    list.value = page.list
    total.value = page.total
    shopList.value = shops
    loadError.value = false
  } catch {
    if (sequence === requestSequence.value) loadError.value = true
  } finally {
    if (sequence === requestSequence.value) loading.value = false
  }
}

function search() {
  query.pageNo = 1
  return load()
}

async function refresh(row: Api.OrderPrintJob) {
  if (busyId.value === row.id) return
  busyId.value = row.id
  try {
    await Api.refreshOrderPrintJob(row.id)
    await load()
  } catch {
    message.error('刷新任务状态失败')
  } finally {
    busyId.value = undefined
  }
}

function canReprint(row: Api.OrderPrintJob) {
  if (row.state === 'UNKNOWN' || row.errorCode === 'OPERATOR_CONFIRMED_PRINTED') return false
  return ['ACCEPTED', 'FAILED', 'UNKNOWN'].includes(row.state)
}

const reprintVisible = ref(false)
const reprintTarget = ref<Api.OrderPrintJob>()
const reprintReason = ref('')
const reprintRequestKey = ref('')
const reprinting = ref(false)
const reprintError = ref(false)
const storageKey = ref('')

function openReprint(row: Api.OrderPrintJob) {
  reprintTarget.value = row
  storageKey.value = `order-print-reprint:${row.id}`
  const saved = sessionStorage.getItem(storageKey.value)
  if (saved) {
    try {
      const pending = JSON.parse(saved) as { requestKey: string; reason: string }
      reprintRequestKey.value = pending.requestKey
      reprintReason.value = pending.reason
      reprintError.value = true
    } catch {
      sessionStorage.removeItem(storageKey.value)
      reprintRequestKey.value = ''
      reprintReason.value = ''
    }
  } else {
    reprintRequestKey.value = ''
    reprintReason.value = ''
    reprintError.value = false
  }
  reprintVisible.value = true
}

function newRequestKey() {
  return `reprint_${Date.now()}_${Math.random().toString(36).slice(2, 14)}`
}

function maskPhones(value: string) {
  return value.replace(/(1[3-9][0-9])[0-9]{4}([0-9]{4})/g, '$1****$2')
}

async function submitReprint() {
  const target = reprintTarget.value
  const reason = maskPhones(reprintReason.value.trim())
  if (!target || !reason || reprinting.value) return
  if (!reprintRequestKey.value) {
    reprintRequestKey.value = newRequestKey()
    sessionStorage.setItem(storageKey.value, JSON.stringify({ requestKey: reprintRequestKey.value, reason }))
  }
  reprinting.value = true
  reprintError.value = false
  try {
    await Api.reprintOrderPrintJob({ jobId: target.id, requestKey: reprintRequestKey.value, reason })
    sessionStorage.removeItem(storageKey.value)
    message.success('已创建关联原任务的重打任务')
    reprintVisible.value = false
    await load()
  } catch {
    reprintError.value = true
  } finally {
    reprinting.value = false
  }
}

const recoveryVisible = ref(false)
const recoveryTarget = ref<Api.OrderPrintJob>()
const recoveryReason = ref('')
const senderStopped = ref(false)
const recoveryResolution = ref<'NOT_PRINTED' | 'PRINTED' | ''>('')
const recoveryRequestKey = ref('')
const recovering = ref(false)
const recoveryError = ref(false)
const recoveryStorageKey = ref('')

function openRecovery(row: Api.OrderPrintJob) {
  recoveryTarget.value = row
  recoveryStorageKey.value = `order-print-recovery:${row.id}`
  const saved = sessionStorage.getItem(recoveryStorageKey.value)
  if (saved) {
    try {
      const pending = JSON.parse(saved) as { requestKey: string; reason: string; resolution?: 'NOT_PRINTED' | 'PRINTED' }
      recoveryRequestKey.value = pending.requestKey
      recoveryReason.value = pending.reason
      senderStopped.value = true
      recoveryResolution.value = pending.resolution || 'NOT_PRINTED'
      recoveryError.value = true
    } catch {
      sessionStorage.removeItem(recoveryStorageKey.value)
      recoveryRequestKey.value = ''
      recoveryReason.value = ''
      senderStopped.value = false
      recoveryResolution.value = ''
      recoveryError.value = false
    }
  } else {
    recoveryRequestKey.value = ''
    recoveryReason.value = ''
    senderStopped.value = false
    recoveryResolution.value = ''
    recoveryError.value = false
  }
  recoveryVisible.value = true
}

async function submitRecovery() {
  const target = recoveryTarget.value
  const reason = maskPhones(recoveryReason.value.trim())
  if (!target || !reason || !senderStopped.value || !recoveryResolution.value || recovering.value) return
  if (!recoveryRequestKey.value) {
    recoveryRequestKey.value = `resolve_${Date.now()}_${Math.random().toString(36).slice(2, 14)}`
    sessionStorage.setItem(recoveryStorageKey.value, JSON.stringify({ requestKey: recoveryRequestKey.value, reason, resolution: recoveryResolution.value }))
  }
  recovering.value = true
  recoveryError.value = false
  try {
    await Api.resolveUnknownOrderPrintJob({
      jobId: target.id,
      requestKey: recoveryRequestKey.value,
      reason,
      senderStopped: true,
      notPrintedConfirmed: recoveryResolution.value === 'NOT_PRINTED',
      printedConfirmed: recoveryResolution.value === 'PRINTED'
    })
    sessionStorage.removeItem(recoveryStorageKey.value)
    message.success(recoveryResolution.value === 'PRINTED'
      ? '已记录确认出纸并释放设备占用；原任务不会重发'
      : '已记录确认未出纸并释放设备占用；如需补打，请使用人工重打')
    recoveryVisible.value = false
    await load()
  } catch {
    recoveryError.value = true
  } finally {
    recovering.value = false
  }
}

onMounted(load)
</script>
