<template>
  <ContentWrap>
    <el-alert v-if="loadError" title="打印机列表加载失败" type="error" :closable="false" show-icon>
      <el-button link type="primary" @click="initialize">重新加载</el-button>
    </el-alert>
    <el-alert
      v-else-if="providerList.length && !providerList.some((p) => p.configured)"
      title="打印服务尚未配置，可先维护设备和门店绑定。连接设备、状态查询和测试打印需配置易联云应用。"
      type="info"
      :closable="false"
      show-icon
      class="mb-16px"
    />
    <el-form :inline="true" @submit.prevent="search">
      <el-form-item label="设备名称"
        ><el-input v-model="query.name" placeholder="搜索设备名称" clearable @keyup.enter="search"
      /></el-form-item>
      <el-form-item label="门店"
        ><el-select v-model="query.shopId" placeholder="全部门店" clearable style="width: 180px">
          <el-option
            v-for="shop in shopList"
            :key="shop.id"
            :label="shop.name"
            :value="shop.id"
          /> </el-select
      ></el-form-item>
      <el-form-item
        ><el-button @click="search">搜索</el-button>
        <el-button
          v-hasPermi="['store:web-print:create']"
          type="primary"
          :disabled="!providerList.length"
          @click="openForm()"
          >新增打印机</el-button
        >
      </el-form-item>
    </el-form>
    <el-table v-loading="loading" :data="list" empty-text="暂无打印机，请先添加设备">
      <el-table-column label="设备名称" prop="name" min-width="130" />
      <el-table-column label="服务商" min-width="95"
        ><template #default="{ row }">{{
          provider(row)?.name || row.providerCode
        }}</template></el-table-column
      >
      <el-table-column label="设备编号" prop="deviceCode" min-width="120" />
      <el-table-column label="型号" prop="model" min-width="90" />
      <el-table-column label="所属门店" min-width="120"
        ><template #default="{ row }">{{ row.shopName || '未绑定' }}</template></el-table-column
      >
      <el-table-column label="启用" width="65"
        ><template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template></el-table-column
      >
      <el-table-column label="授权状态" min-width="120"
        ><template #default="{ row }">
          <el-tag :type="row.authorization === 'AUTHORIZED' ? 'success' : 'info'">{{
            authorizationLabels[row.authorization]
          }}</el-tag>
        </template></el-table-column
      >
      <el-table-column label="设备状态" min-width="170"
        ><template #default="{ row }">
          <div>{{ stateLabels[row.deviceState] }}</div>
          <small v-if="row.stateCheckedAt" class="text-gray-500">{{
            displayTime(row.stateCheckedAt)
          }}</small>
          <div v-if="row.lastError" class="text-orange-600 text-xs">{{
            errorText(row.lastError)
          }}</div>
        </template></el-table-column
      >
      <el-table-column label="操作" min-width="330" fixed="right"
        ><template #default="{ row }">
          <el-button
            v-hasPermi="['store:web-print:update']"
            link
            type="primary"
            :disabled="busy[row.id]"
            @click="openForm(row)"
            >编辑</el-button
          >
          <el-button
            v-if="provider(row)?.capabilities.authorize && row.authorization !== 'AUTHORIZED'"
            v-hasPermi="['store:web-print:authorize']"
            link
            type="primary"
            :disabled="busy[row.id] || !provider(row)?.configured"
            @click="connect(row)"
            >连接设备</el-button
          >
          <el-button
            v-if="provider(row)?.capabilities.deauthorize && row.authorization !== 'UNAUTHORIZED'"
            v-hasPermi="['store:web-print:authorize']"
            link
            type="warning"
            :disabled="busy[row.id] || !!row.shopId || !provider(row)?.configured"
            @click="connect(row, true)"
            >取消授权</el-button
          >
          <el-button
            v-hasPermi="['store:web-print:binding']"
            link
            type="primary"
            :disabled="busy[row.id]"
            @click="openBinding(row)"
            >{{ row.shopId ? '解绑门店' : '绑定门店' }}</el-button
          >
          <el-button
            v-if="provider(row)?.capabilities.status"
            link
            type="primary"
            :disabled="busy[row.id] || !provider(row)?.configured"
            @click="refreshStatus(row)"
            >刷新状态</el-button
          >
          <el-button
            v-if="provider(row)?.capabilities.textPrint"
            v-hasPermi="['store:web-print:test']"
            link
            type="primary"
            :disabled="
              busy[row.id] ||
              !row.enabled ||
              row.authorization !== 'AUTHORIZED' ||
              !provider(row)?.configured
            "
            @click="printTest(row)"
            >测试打印</el-button
          >
          <el-button link type="primary" @click="openJobs(row)">测试记录</el-button>
          <el-button
            v-hasPermi="['store:web-print:delete']"
            link
            type="danger"
            :disabled="busy[row.id] || !!row.shopId || row.authorization !== 'UNAUTHORIZED'"
            @click="remove(row)"
            >删除</el-button
          >
        </template></el-table-column
      >
    </el-table>
    <Pagination
      v-model:page="query.pageNo"
      v-model:limit="query.pageSize"
      :total="total"
      @pagination="load"
    />
    <p class="text-gray-500 text-sm"
      >状态查询以打印服务返回值为准；部分设备离线状态可能延迟更新。删除设备前请先解绑门店并取消授权。</p
    >
  </ContentWrap>

  <Dialog v-model="formVisible" :title="form.id ? '编辑打印机' : '新增打印机'" width="560px">
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="110px"
      @submit.prevent="saveForm"
    >
      <el-form-item label="设备名称" prop="name"
        ><el-input v-model="form.name" maxlength="80"
      /></el-form-item>
      <el-form-item label="打印服务" prop="providerCode"
        ><el-select v-model="form.providerCode" :disabled="!!form.id" @change="changeProvider">
          <el-option
            v-for="p in providerList"
            :key="p.code"
            :label="p.name"
            :value="p.code"
          /> </el-select
      ></el-form-item>
      <el-form-item label="设备编号" prop="deviceCode"
        ><el-input v-model="form.deviceCode" :disabled="!!form.id" maxlength="100"
      /></el-form-item>
      <el-form-item label="型号"
        ><el-input v-model="form.model" maxlength="80" placeholder="可选，填写设备型号"
      /></el-form-item>
      <el-form-item label="连接方式" prop="connectionType"
        ><el-select v-model="form.connectionType" :disabled="!!form.id">
          <el-option
            v-for="type in selectedProvider?.connectionTypes || []"
            :key="type"
            :label="type === 'CLOUD' ? '云端连接' : type"
            :value="type"
          /> </el-select
      ></el-form-item>
      <el-form-item
        v-for="field in selectedProvider?.configFields || []"
        :key="field.key"
        :label="field.label"
        :prop="`configuration.${field.key}`"
        :rules="
          field.required && !configuredFields.includes(field.key)
            ? [{ required: true, message: `请输入${field.label}` }]
            : []
        "
      >
        <el-input
          v-model="form.configuration[field.key]"
          :type="field.secret ? 'password' : 'text'"
          :show-password="field.secret"
          autocomplete="new-password"
          :disabled="!!form.id && editingAuthorization !== 'UNAUTHORIZED'"
          :placeholder="
            configuredFields.includes(field.key)
              ? '已设置，留空保留原值'
              : '可先留空保存，连接设备前需填写'
          "
        />
      </el-form-item>
      <el-form-item label="启用设备"><el-switch v-model="form.enabled" /></el-form-item>
      <el-alert
        title="设备密钥只用于连接打印服务，不会在详情中回显。更换密钥前请先取消设备授权。"
        type="info"
        :closable="false"
      />
    </el-form>
    <template #footer
      ><el-button @click="formVisible = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="saveForm">保存</el-button></template
    >
  </Dialog>

  <Dialog v-model="bindingVisible" title="绑定门店" width="420px">
    <p>{{ bindingPrinter?.name }}</p>
    <el-select v-model="bindingShopId" placeholder="请选择门店" class="w-full">
      <el-option v-for="shop in shopList" :key="shop.id" :label="shop.name" :value="shop.id" />
    </el-select>
    <p class="text-gray-500">每个门店可绑定多台设备；每台设备只能归属一个门店。</p>
    <template #footer
      ><el-button @click="bindingVisible = false">取消</el-button
      ><el-button
        type="primary"
        :disabled="!bindingShopId"
        :loading="bindingSaving"
        @click="saveBinding"
        >绑定</el-button
      ></template
    >
  </Dialog>

  <Dialog
    v-model="jobsVisible"
    :title="`${jobsPrinter?.name || ''} · 测试打印记录`"
    width="min(1000px, 95vw)"
  >
    <el-alert
      title="“已提交”表示打印服务已接受请求，请查看设备确认实际出纸。结果待确认时请先检查设备，避免重复打印。"
      type="info"
      :closable="false"
      class="mb-16px"
    />
    <el-alert v-if="jobsError" title="测试记录加载失败，请重试" type="error" :closable="false" />
    <el-button @click="loadJobs">刷新记录</el-button>
    <el-table v-loading="jobsLoading" :data="jobList" empty-text="暂无测试打印记录">
      <el-table-column label="测试编号" prop="requestId" min-width="230" />
      <el-table-column label="结果" min-width="150"
        ><template #default="{ row }">
          <div>{{ jobLabels[row.state] }}</div>
          <el-button
            v-if="row.state === 'UNKNOWN'"
            v-hasPermi="['store:web-print:test']"
            link
            type="primary"
            @click="allowNewTest(row)"
            >确认设备后允许新测试</el-button
          >
        </template></el-table-column
      >
      <el-table-column label="服务打印编号" prop="providerJobId" min-width="120" />
      <el-table-column label="原因" min-width="150"
        ><template #default="{ row }">{{
          row.errorCode ? errorText(row.errorCode) : '—'
        }}</template></el-table-column
      >
      <el-table-column label="时间" width="180"
        ><template #default="{ row }">{{ displayTime(row.createTime) }}</template></el-table-column
      >
    </el-table>
    <div style="display: flow-root">
      <Pagination
        v-model:page="jobQuery.pageNo"
        v-model:limit="jobQuery.pageSize"
        :total="jobTotal"
        @pagination="loadJobs"
      />
    </div>
  </Dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import * as Api from '@/api/mall/store/printer'
import { CACHE_KEY, useCache } from '@/hooks/web/useCache'
import { formatDate } from '@/utils/formatTime'

defineOptions({ name: 'StorePrinter' })
const message = useMessage()
async function confirm(text: string) {
  try {
    await message.confirm(text)
    return true
  } catch {
    return false
  }
}
const { wsCache } = useCache()
const providerList = ref<Api.ProviderInfo[]>([])
const shopList = ref<Api.ShopOption[]>([])
const list = ref<Api.Printer[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref(false)
const busy = reactive<Record<number, boolean>>({})
const query = reactive({
  pageNo: 1,
  pageSize: 10,
  name: '',
  shopId: undefined as number | undefined
})
const provider = (p: Api.Printer) => providerList.value.find((item) => item.code === p.providerCode)
const stateLabels: Record<Api.Printer['deviceState'], string> = {
  ONLINE: '在线',
  OFFLINE: '离线',
  PAPER_OUT: '缺纸',
  UNKNOWN: '未知'
}
const authorizationLabels: Record<Api.Printer['authorization'], string> = {
  AUTHORIZED: '已连接',
  UNAUTHORIZED: '未连接',
  UNKNOWN: '授权待确认'
}
const jobLabels: Record<Api.Job['state'], string> = {
  SUBMITTING: '提交中',
  ACCEPTED: '已提交',
  FAILED: '提交失败',
  UNKNOWN: '结果待确认'
}
const errors: Record<string, string> = {
  PROVIDER_NOT_CONFIGURED: '打印服务尚未配置',
  ENCRYPTION_NOT_CONFIGURED: '后端加密密钥尚未配置',
  SECRET_UNAVAILABLE: '设备密钥无法读取',
  TERMINAL_KEY_REQUIRED: '请编辑并填写终端密钥',
  PROVIDER_TIMEOUT: '请求超时，请检查设备',
  PROVIDER_UNAVAILABLE: '打印服务暂时不可用',
  PROVIDER_HTTP_ERROR: '打印服务请求异常',
  PROVIDER_INVALID_RESPONSE: '打印服务响应异常',
  RESULT_UNKNOWN: '请检查设备确认出纸',
  PRINTER_DISABLED: '设备已停用',
  PRINTER_NOT_AUTHORIZED: '设备尚未连接'
}
const errorText = (code: string) =>
  errors[code] ||
  (code.startsWith('PROVIDER_REJECTED_')
    ? `打印服务拒绝请求（${code.slice(18)}）`
    : '操作未完成，请检查设备')
const displayTime = (value: Api.PrinterTime) => formatDate(new Date(value))
async function load() {
  loading.value = true
  try {
    const page = await Api.page(query)
    list.value = page.list
    total.value = page.total
    loadError.value = false
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}
async function initialize() {
  loading.value = true
  try {
    ;[providerList.value, shopList.value] = await Promise.all([Api.providers(), Api.shops()])
    await load()
  } catch {
    loadError.value = true
    loading.value = false
  }
}
const search = () => {
  query.pageNo = 1
  return load()
}
async function operation(row: Api.Printer, action: () => Promise<unknown>) {
  if (busy[row.id]) return
  busy[row.id] = true
  try {
    await action()
    await load()
  } finally {
    busy[row.id] = false
  }
}

const blank = (): Api.SaveRequest => ({
  name: '',
  providerCode: '',
  deviceCode: '',
  model: '',
  connectionType: '',
  enabled: true,
  configuration: {}
})
const form = ref<Api.SaveRequest>(blank())
const formVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const configuredFields = ref<string[]>([])
const editingAuthorization = ref<Api.Printer['authorization']>('UNAUTHORIZED')
const selectedProvider = computed(() =>
  providerList.value.find((p) => p.code === form.value.providerCode)
)
const rules: FormRules = {
  name: [{ required: true, whitespace: true, message: '请输入设备名称', trigger: 'blur' }],
  providerCode: [{ required: true, message: '请选择打印服务' }],
  deviceCode: [{ required: true, whitespace: true, message: '请输入设备编号', trigger: 'blur' }],
  connectionType: [{ required: true, message: '请选择连接方式' }]
}
function changeProvider() {
  form.value.configuration = {}
  form.value.connectionType = selectedProvider.value?.connectionTypes[0] || ''
}
async function openForm(row?: Api.Printer) {
  const p = row ? await Api.get(row.id) : undefined
  form.value = p
    ? {
        id: p.id,
        name: p.name,
        providerCode: p.providerCode,
        deviceCode: p.deviceCode,
        model: p.model || '',
        connectionType: p.connectionType,
        enabled: p.enabled,
        configuration: {}
      }
    : blank()
  configuredFields.value = p?.configuredFields || []
  editingAuthorization.value = p?.authorization || 'UNAUTHORIZED'
  if (!p) {
    form.value.providerCode = providerList.value[0]?.code || ''
    changeProvider()
  }
  formVisible.value = true
  formRef.value?.clearValidate()
}
watch(formVisible, (visible) => {
  if (!visible) form.value.configuration = {}
})
async function saveForm() {
  if (saving.value || !(await formRef.value?.validate().catch(() => false))) return
  saving.value = true
  try {
    await Api.save(form.value)
    message.success('设备已保存')
    formVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}
async function connect(row: Api.Printer, revoke = false) {
  if (revoke && !(await confirm(`取消“${row.name}”在打印服务中的授权？`))) return
  await operation(row, async () => {
    const result = await Api.authorize(row.id, revoke)
    if (result.errorCode) message.warning(errorText(result.errorCode))
    else message.success(revoke ? '已取消设备授权' : '设备已连接')
  })
}
async function refreshStatus(row: Api.Printer) {
  await operation(row, async () => {
    const result = await Api.status(row.id)
    if (result.errorCode) message.warning(errorText(result.errorCode))
  })
}
async function remove(row: Api.Printer) {
  if (!(await confirm(`删除“${row.name}”？测试记录将保留。`))) return
  await operation(row, async () => {
    await Api.remove(row.id)
    message.success('设备已删除')
  })
}
const bindingVisible = ref(false)
const bindingSaving = ref(false)
const bindingPrinter = ref<Api.Printer>()
const bindingShopId = ref<number>()
async function openBinding(row: Api.Printer) {
  if (row.shopId) {
    if (!(await confirm(`解除“${row.name}”与“${row.shopName}”的绑定？`))) return
    await operation(row, () => Api.unbind(row.id))
  } else {
    bindingPrinter.value = row
    bindingShopId.value = undefined
    bindingVisible.value = true
  }
}
async function saveBinding() {
  if (!bindingPrinter.value || !bindingShopId.value || bindingSaving.value) return
  bindingSaving.value = true
  try {
    await Api.bind(bindingPrinter.value.id, bindingShopId.value)
    bindingVisible.value = false
    await load()
  } finally {
    bindingSaving.value = false
  }
}
function requestKey(id: number) {
  const user = wsCache.get(CACHE_KEY.USER)?.user?.id
  return `printer-test:${wsCache.get(CACHE_KEY.TenantId)}:${user}:${id}`
}
async function printTest(row: Api.Printer) {
  const key = requestKey(row.id)
  let requestId = sessionStorage.getItem(key)
  if (
    !(await confirm(
      requestId
        ? '上次请求尚未确认。查询同一次测试的结果，不会创建新的打印请求。'
        : `向“${row.name}”发送一份测试小票？`
    ))
  )
    return
  await operation(row, async () => {
    requestId = requestId || crypto.randomUUID()
    sessionStorage.setItem(key, requestId)
    const result = await Api.testPrint(row.id, requestId)
    if (result.state === 'ACCEPTED' || result.state === 'FAILED') sessionStorage.removeItem(key)
    if (result.state === 'ACCEPTED') message.success('测试小票已提交，请查看设备确认出纸')
    else
      message.warning(
        result.state === 'FAILED'
          ? errorText(result.errorCode || '')
          : '结果待确认，请检查设备；再次点击仅查询同一次请求'
      )
  })
}
const jobsVisible = ref(false)
const jobsLoading = ref(false)
const jobsError = ref(false)
const jobsPrinter = ref<Api.Printer>()
const jobList = ref<Api.Job[]>([])
const jobTotal = ref(0)
const jobQuery = reactive({ pageNo: 1, pageSize: 10 })
async function openJobs(row: Api.Printer) {
  jobsPrinter.value = row
  jobList.value = []
  jobTotal.value = 0
  jobsError.value = false
  jobQuery.pageNo = 1
  jobsVisible.value = true
  await loadJobs()
}
async function loadJobs() {
  if (!jobsPrinter.value) return
  jobsLoading.value = true
  try {
    const result = await Api.jobs(jobsPrinter.value.id, jobQuery)
    jobList.value = result.list
    jobTotal.value = result.total
    jobsError.value = false
  } catch {
    jobsError.value = true
  } finally {
    jobsLoading.value = false
  }
}
async function allowNewTest(job: Api.Job) {
  const key = requestKey(job.printerId)
  const pending = sessionStorage.getItem(key)
  if (pending && pending !== job.requestId) {
    message.warning('当前还有另一次待确认测试，请先确认对应记录')
    return
  }
  if (
    !(await confirm('请先查看设备确认是否已出纸。允许新测试后，再次点击测试打印可能多出一份小票。'))
  )
    return
  if (sessionStorage.getItem(key) === job.requestId) sessionStorage.removeItem(key)
  message.success('可以返回设备列表发起新的测试')
}
onMounted(initialize)
</script>
