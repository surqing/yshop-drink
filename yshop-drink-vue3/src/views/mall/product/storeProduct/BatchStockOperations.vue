<template>
  <Dialog v-model="visible" title="批量库存调整" width="min(1100px, 96vw)" :close-on-click-modal="false">
    <el-alert
      title="本操作调整当前门店的可售库存增量，不改变预占、销量、价格或历史订单。预览后仍会由服务端重新检查库存。"
      type="info"
      :closable="false"
      show-icon
      class="mb-12px"
    />
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="mb-12px">
      <el-button link type="primary" @click="loadProducts">重新读取商品库存</el-button>
    </el-alert>

    <template v-if="pendingRequest">
      <el-alert
        :title="pendingMessage"
        type="warning"
        :closable="false"
        show-icon
        class="mb-12px"
      />
      <div class="flex gap-8px mb-12px">
        <el-button :loading="checkingResult" @click="checkPendingResult">查询原请求结果</el-button>
        <el-button
          v-if="requestState === 'UNCONFIRMED'"
          type="warning"
          :loading="submitting"
          @click="retryOriginalRequest"
        >沿用原编号重试</el-button>
      </div>
    </template>

    <template v-else>
      <el-table v-loading="loading" :data="rows" row-key="rowKey" max-height="360">
        <el-table-column label="加入批次" width="90" align="center">
          <template #default="{ row }"><el-checkbox v-model="row.selected" :aria-label="`选择 ${row.productName} ${row.skuName}`" /></template>
        </el-table-column>
        <el-table-column label="商品" min-width="190" prop="productName" />
        <el-table-column label="规格" min-width="150" prop="skuName" />
        <el-table-column label="当前可售" width="110" prop="currentStock" />
        <el-table-column label="增减数量" width="190">
          <template #default="{ row }">
            <el-input-number v-model="row.delta" :min="-1000000" :max="1000000" :precision="0" :step="1" :disabled="row.selected === false" />
          </template>
        </el-table-column>
      </el-table>
      <el-form class="mt-12px" label-width="90px">
        <el-form-item label="调整原因">
          <el-input v-model="reason" maxlength="200" show-word-limit placeholder="填写本批库存调整原因" />
        </el-form-item>
      </el-form>
      <el-alert v-if="operationMessage" :title="operationMessage" type="error" :closable="false" show-icon class="mb-12px" />

      <el-table v-if="preview" :data="preview.rows" row-key="skuId" class="mt-12px">
        <el-table-column label="商品 / 规格" min-width="220">
          <template #default="{ row }">{{ row.productName }} / {{ row.skuName }}</template>
        </el-table-column>
        <el-table-column label="当前 / 期望" width="140">
          <template #default="{ row }">{{ row.currentStock }} / {{ row.expectedStock }}</template>
        </el-table-column>
        <el-table-column label="增减" width="90" prop="delta" />
        <el-table-column label="调整后" width="90" prop="afterStock" />
        <el-table-column label="检查结果" min-width="190">
          <template #default="{ row }">{{ row.conflictCode ? conflictText(row.conflictCode) : '可提交' }}</template>
        </el-table-column>
      </el-table>
      <el-alert
        v-if="batchResult"
        :title="`批次 ${batchResult.batchId} 已提交，共 ${batchResult.lines.length} 个规格`"
        type="success"
        :closable="false"
        show-icon
        class="mt-12px"
      />
    </template>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <template v-if="!pendingRequest && !batchResult">
        <el-button :loading="loading || previewing" :disabled="!validDraft" @click="runPreview">预览调整结果</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!canConfirm" @click="submitBatch">确认批量调整</el-button>
      </template>
      <el-button v-if="batchResult" type="primary" @click="visible = false">完成</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { useMessage } from '@/hooks/web/useMessage'
import * as CatalogApi from '@/api/mall/product/catalog'
import {
  clearPendingBatchRequests,
  createBatchContextGuard,
  isUncertainBatchOutcome,
  loadPendingBatchRequest,
  pendingBatchStorageKey,
  savePendingBatchRequest,
  type PendingBatchRequest
} from '@/utils/batchStockSession'

interface ProductSelection { id: number; storeName?: string; shopId?: number }
interface BatchSkuRow {
  rowKey: string
  productId: number
  skuId: number
  productName: string
  skuName: string
  currentStock: number
  selected: boolean
  delta: number
}

const props = defineProps<{ modelValue: boolean; shopId?: number; products: ProductSelection[] }>()
const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void; (event: 'success'): void }>()
const userStore = useUserStore()
const message = useMessage()
const guard = createBatchContextGuard()
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const rows = ref<BatchSkuRow[]>([])
const preview = ref<CatalogApi.BatchStockPreview>()
const previewSignature = ref('')
const batchResult = ref<CatalogApi.BatchStockResult>()
const pendingRequest = ref<PendingBatchRequest>()
const requestState = ref<'IDLE' | 'CHECKING' | 'UNCONFIRMED' | 'CONFIRMED' | 'REJECTED' | 'DENIED'>('IDLE')
const reason = ref('')
const operationMessage = ref('')
const loadError = ref('')
const pendingMessage = ref('')
const loading = ref(false)
const previewing = ref(false)
const submitting = ref(false)
const checkingResult = ref(false)
const rejectedSignature = ref('')

const selectedRows = computed(() => rows.value.filter(row => row.selected))
const requestLines = computed(() => selectedRows.value.map(row => ({
  productId: row.productId,
  skuId: row.skuId,
  expectedStock: row.currentStock,
  delta: row.delta
})).sort((a, b) => a.productId - b.productId || a.skuId - b.skuId))
const signature = computed(() => JSON.stringify({
  shopId: props.shopId,
  lines: requestLines.value,
  reason: reason.value.trim()
}))
const validDraft = computed(() => Number.isSafeInteger(props.shopId) && Number(props.shopId) > 0 &&
  requestLines.value.length > 0 && requestLines.value.length <= 100 &&
  requestLines.value.every(line => Number.isSafeInteger(line.productId) && line.productId > 0 &&
    Number.isSafeInteger(line.skuId) && line.skuId > 0 && Number.isSafeInteger(line.expectedStock) &&
    line.expectedStock >= 0 && Number.isSafeInteger(line.delta) && line.delta !== 0 && Math.abs(line.delta) <= 1000000) &&
  reason.value.trim().length > 0 && reason.value.trim().length <= 200)
const canConfirm = computed(() => validDraft.value && !!preview.value?.eligible &&
  previewSignature.value === signature.value && signature.value !== rejectedSignature.value &&
  requestState.value !== 'DENIED' && !loading.value && !previewing.value && !submitting.value)
const currentUserId = () => Number(userStore.getUser?.id || 0)

function makeKey() {
  if (!globalThis.crypto?.randomUUID) throw new Error('BATCH_STOCK_RANDOM_KEY_UNAVAILABLE')
  return `batch_${globalThis.crypto.randomUUID().replaceAll('-', '')}`
}

function body() {
  return { shopId: Number(props.shopId), lines: requestLines.value.map(line => ({ ...line })), reason: reason.value.trim() }
}

function clearDraft() {
  rows.value = []
  preview.value = undefined
  previewSignature.value = ''
  batchResult.value = undefined
  pendingRequest.value = undefined
  requestState.value = 'IDLE'
  reason.value = ''
  operationMessage.value = ''
  loadError.value = ''
  pendingMessage.value = ''
  rejectedSignature.value = ''
  loading.value = false
  previewing.value = false
  submitting.value = false
  checkingResult.value = false
}

function invalidatePreview() {
  if (!preview.value) return
  preview.value = undefined
  previewSignature.value = ''
}

watch(rows, invalidatePreview, { deep: true })
watch(reason, invalidatePreview)

function validatePreview(value: CatalogApi.BatchStockPreview, expectedLines: CatalogApi.BatchStockLineInput[]) {
  if (!value || typeof value.eligible !== 'boolean' || !Array.isArray(value.rows) || value.rows.length !== expectedLines.length ||
    value.rows.some((row, index) => !Number.isSafeInteger(row.productId) || !Number.isSafeInteger(row.skuId) ||
      !Number.isSafeInteger(row.currentStock) || !Number.isSafeInteger(row.afterStock) || typeof row.productName !== 'string' ||
      typeof row.skuName !== 'string' || (row.conflictCode !== null && typeof row.conflictCode !== 'string') ||
      row.productId !== expectedLines[index].productId || row.skuId !== expectedLines[index].skuId ||
      row.expectedStock !== expectedLines[index].expectedStock || row.delta !== expectedLines[index].delta ||
      row.afterStock !== row.currentStock + row.delta)) {
    throw Object.assign(new Error('BATCH_STOCK_PREVIEW_RESPONSE_INVALID'), { uncertain: false })
  }
}

function validateResult(value: CatalogApi.BatchStockResult | null | undefined, expected?: CatalogApi.BatchStockBody) {
  if (!value || value.state !== 'APPLIED' || !/^[a-f0-9]{32}$/.test(value.batchId) || !Array.isArray(value.lines) ||
    (expected && (value.shopId !== expected.shopId || value.lines.length !== expected.lines.length)) ||
    value.lines.some((line, index) => !Number.isSafeInteger(line.productId) || !Number.isSafeInteger(line.skuId) ||
      !Number.isSafeInteger(line.beforeStock) || !Number.isSafeInteger(line.delta) || !Number.isSafeInteger(line.afterStock) ||
      line.beforeStock + line.delta !== line.afterStock || (expected &&
        (line.productId !== expected.lines[index].productId || line.skuId !== expected.lines[index].skuId ||
          line.delta !== expected.lines[index].delta || line.reason !== expected.reason)))) {
    throw Object.assign(new Error('BATCH_STOCK_RESULT_RESPONSE_INVALID'), { uncertain: true })
  }
}

function conflictText(code: string) {
  const texts: Record<string, string> = {
    STOCK_CHANGED_REFRESH_REQUIRED: '库存已变化，请重新读取并预览',
    STOCK_ADJUSTMENT_INVALID: '调整后库存超出允许范围',
    PRODUCT_STOCK_SUM_MISMATCH: '商品汇总库存与规格库存不一致',
    STOCK_TOTAL_TOO_LARGE: '商品库存总量超出字段范围'
  }
  return texts[code] || code
}

async function loadProducts() {
  const userId = currentUserId()
  const shopId = Number(props.shopId || 0)
  if (!userId || !shopId) { loadError.value = '请先选择门店并确认当前账号'; return }
  if (!props.products.length) { loadError.value = '请先选择当前门店的商品'; return }
  if (props.products.some(product => product.shopId && product.shopId !== shopId)) {
    loadError.value = '所选商品不属于当前门店，请重新选择'
    return
  }
  const captured = guard.capture()
  loading.value = true
  loadError.value = ''
  try {
    const results = await Promise.all(props.products.map(async product => ({
      product,
      data: await CatalogApi.configuration(product.id)
    })))
    if (!guard.isCurrent(captured)) return
    const nextRows = results.flatMap(({ product, data }) => data.skus.map(sku => ({
      rowKey: `${product.id}:${sku.id}`,
      productId: product.id,
      skuId: Number(sku.id),
      productName: product.storeName || `商品 ${product.id}`,
      skuName: sku.sku,
      currentStock: Number(sku.stock),
      selected: true,
      delta: 0
    })))
    if (nextRows.length > 100) {
      rows.value = []
      loadError.value = '一个批次最多选择 100 个规格，请减少所选商品'
      return
    }
    rows.value = nextRows
    if (requestState.value === 'DENIED') requestState.value = 'IDLE'
  } catch {
    if (guard.isCurrent(captured)) loadError.value = '商品规格读取失败，请检查门店权限后重试'
  } finally {
    if (guard.isCurrent(captured)) loading.value = false
  }
}

async function runPreview() {
  if (!validDraft.value || previewing.value || pendingRequest.value) return
  const captured = guard.capture()
  const sent = body()
  const sentSignature = signature.value
  previewing.value = true
  operationMessage.value = ''
  try {
    const response = await CatalogApi.previewStockBatch(sent)
    validatePreview(response, sent.lines)
    if (!guard.isCurrent(captured)) return
    preview.value = response
    previewSignature.value = sentSignature
    if (!response.eligible) message.warning('预览发现库存冲突，请刷新后重新确认')
  } catch {
    if (guard.isCurrent(captured)) {
      preview.value = undefined
      previewSignature.value = ''
      operationMessage.value = '预览失败，请检查权限、输入和网络后重试'
    }
  } finally {
    if (guard.isCurrent(captured)) previewing.value = false
  }
}

function completeBatch(result: CatalogApi.BatchStockResult) {
  const pending = pendingRequest.value
  validateResult(result, pending?.body)
  if (pending) removePending(pending)
  pendingRequest.value = undefined
  batchResult.value = result
  requestState.value = 'CONFIRMED'
  pendingMessage.value = ''
  operationMessage.value = ''
  message.success(`批量库存调整已提交，批次 ${result.batchId}`)
  emit('success')
}

function savePending(bodyValue: CatalogApi.BatchStockBody, key?: string) {
  const request: PendingBatchRequest = {
    userId: currentUserId(),
    shopId: Number(props.shopId),
    key: key || makeKey(),
    savedAt: Date.now(),
    body: bodyValue
  }
  savePendingBatchRequest(sessionStorage, request)
  pendingRequest.value = request
  return request
}

function removePending(request: PendingBatchRequest) {
  try { sessionStorage.removeItem(pendingBatchStorageKey(request.userId, request.shopId)) } catch {}
}

async function sendPending(request: PendingBatchRequest) {
  const captured = guard.capture()
  submitting.value = true
  operationMessage.value = ''
  requestState.value = 'CHECKING'
  try {
    const result = await CatalogApi.applyStockBatch({ ...request.body, key: request.key })
    validateResult(result, request.body)
    if (!guard.isCurrent(captured)) return
    completeBatch(result)
  } catch (error) {
    if (!guard.isCurrent(captured)) return
    if ((error as { uncertain?: boolean } | null)?.uncertain || isUncertainBatchOutcome(error)) {
      requestState.value = 'CHECKING'
      pendingMessage.value = '提交响应未确认。请先查询原请求结果；若未找到，再使用同一编号重试。'
      operationMessage.value = ''
      return
    }
    removePending(request)
    pendingRequest.value = undefined
    preview.value = undefined
    previewSignature.value = ''
    rejectedSignature.value = JSON.stringify({ shopId: request.body.shopId, lines: request.body.lines, reason: request.body.reason })
    requestState.value = (error as { code?: number } | null)?.code === 403 ? 'DENIED' : 'REJECTED'
    operationMessage.value = requestState.value === 'DENIED'
      ? '当前账号已无此门店的库存调整权限，请重新登录或联系负责人'
      : '服务端已拒绝本次调整。请刷新库存、修改内容并重新预览后再提交。'
    if (requestState.value === 'DENIED') loadError.value = operationMessage.value
  } finally {
    if (guard.isCurrent(captured)) submitting.value = false
  }
}

async function submitBatch() {
  if (!canConfirm.value || pendingRequest.value) return
  let request: PendingBatchRequest
  try {
    request = savePending(body())
  } catch {
    operationMessage.value = '当前浏览器会话无法安全保存请求编号，本次调整尚未发送。请恢复会话存储后重试。'
    return
  }
  await sendPending(request)
}

async function checkPendingResult() {
  const request = pendingRequest.value
  if (!request || checkingResult.value) return
  const captured = guard.capture()
  checkingResult.value = true
  requestState.value = 'CHECKING'
  pendingMessage.value = '正在读取原请求状态；查询不会重新执行库存调整。'
  try {
    const response = await CatalogApi.getStockBatchResult(request.key)
    if (!guard.isCurrent(captured)) return
    if (!response || !['CONFIRMED', 'UNCONFIRMED'].includes(response.status)) {
      throw new Error('BATCH_STOCK_LOOKUP_RESPONSE_INVALID')
    }
    if (response.status === 'CONFIRMED') {
      validateResult(response.batch, request.body)
      completeBatch(response.batch!)
    } else {
      requestState.value = 'UNCONFIRMED'
      pendingMessage.value = '系统尚未确认该编号的结果。未找到不代表已成功或绝不会提交；可查询，或用原编号和原内容重试。'
    }
  } catch {
    if (guard.isCurrent(captured)) {
      requestState.value = 'CHECKING'
      pendingMessage.value = '读取原请求失败。编号和原内容已保留，请重试查询；不要新建请求编号。'
    }
  } finally {
    if (guard.isCurrent(captured)) checkingResult.value = false
  }
}

async function retryOriginalRequest() {
  const request = pendingRequest.value
  if (!request || requestState.value !== 'UNCONFIRMED' || submitting.value) return
  await sendPending(request)
}

async function beginContext() {
  guard.invalidate()
  clearDraft()
  const userId = currentUserId()
  const shopId = Number(props.shopId || 0)
  if (!userId || !shopId) { loadError.value = '请先选择门店并确认当前账号'; return }
  let saved: PendingBatchRequest | undefined
  try { saved = loadPendingBatchRequest(sessionStorage, userId, shopId) }
  catch {
    loadError.value = '无法读取当前账号和门店的未决请求，本次操作已暂停，请检查浏览器会话存储后重新打开。'
    return
  }
  if (saved) {
    pendingRequest.value = saved
    requestState.value = 'CHECKING'
    pendingMessage.value = '发现同一账号和门店的未决请求，正在查询原编号。'
    await checkPendingResult()
    return
  }
  await loadProducts()
}

watch(() => props.modelValue, async open => {
  guard.invalidate()
  if (open) await beginContext()
  else clearDraft()
})
watch(() => props.shopId, async (shopId, previous) => {
  if (shopId === previous) return
  guard.invalidate()
  if (props.modelValue) await beginContext()
})
watch(() => currentUserId(), async (userId, previous) => {
  if (userId === previous) return
  guard.invalidate()
  try { clearPendingBatchRequests(sessionStorage) } catch {}
  if (props.modelValue) await beginContext()
})

defineExpose({ runPreview, submitBatch, checkPendingResult, retryOriginalRequest, loadProducts })
</script>
