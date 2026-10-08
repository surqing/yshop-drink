<template>
  <Dialog v-model="visible" title="商品经营设置" width="min(1050px, 95vw)" :close-on-click-modal="false" :before-close="confirmClose">
    <el-alert title="库存规格在商品编辑中维护；甜度、冰量和加料在这里设置，不拆分库存。加料当前不管理原料库存。" type="info" :closable="false" />
    <el-tabs v-model="tab" v-loading="busy">
      <el-tab-pane label="定制与加料" name="options">
        <el-card v-for="(group, index) in data.configuration.groups" :key="group.id" class="mb-12px">
          <el-form label-width="110px">
            <el-form-item label="分组名称"><el-input v-model="group.name" placeholder="例如温度、甜度、冰量、加料" maxlength="60" /></el-form-item>
            <el-form-item label="类型"><el-radio-group v-model="group.kind"><el-radio label="CUSTOM">定制选项</el-radio><el-radio label="TOPPING">加料</el-radio></el-radio-group><el-switch v-model="group.enabled" active-text="启用" /></el-form-item>
            <el-form-item label="选择规则"><el-switch v-model="group.multiple" active-text="多选" inactive-text="单选" @change="group.max = 1; group.maxPerOption = 1" /> 最少 <el-input-number v-model="group.min" :min="0" :max="group.max" /> 最多 <el-input-number v-model="group.max" :min="1" :max="group.multiple ? 20 : 1" /> 同项最多 <el-input-number v-model="group.maxPerOption" :min="1" :max="group.multiple ? 10 : 1" /></el-form-item>
            <el-form-item label="显示条件"><el-select class="w-200px" :model-value="group.when?.groupId || ''" @change="value => setCondition(group, value)"><el-option label="始终显示" value="" /><el-option v-for="prior in data.configuration.groups.slice(0,index)" :key="prior.id" :label="prior.name" :value="prior.id" /></el-select><el-select class="w-200px" v-if="group.when" v-model="group.when.optionId"><el-option v-for="option in conditionOptions(group)" :key="option.id" :label="option.name" :value="option.id" /></el-select><span class="ml-8px">仅当前置选项被选中时适用</span></el-form-item>
            <el-table :data="group.options">
              <el-table-column label="选项名称"><template #default="{ row }"><el-input v-model="row.name" maxlength="60" /></template></el-table-column>
              <el-table-column label="每份加价（元）"><template #default="{ row }"><el-input-number v-model="row.surcharge" :min="0" :max="999999.99" :precision="2" /></template></el-table-column>
              <el-table-column label="默认数量"><template #default="{ row }"><el-input-number v-model="row.defaultQuantity" :min="0" :max="group.maxPerOption" /></template></el-table-column>
              <el-table-column label="启用"><template #default="{ row }"><el-switch v-model="row.enabled" @change="!row.enabled && (row.defaultQuantity = 0)" /></template></el-table-column>
              <el-table-column label="操作"><template #default="{ $index }"><el-button @click="group.options.splice($index,1)">移除</el-button></template></el-table-column>
            </el-table>
            <el-button @click="addOption(group)">添加选项</el-button><el-button type="danger" @click="data.configuration.groups.splice(index,1)">移除分组</el-button>
          </el-form>
        </el-card>
        <el-button @click="addGroup">添加分组</el-button><el-button type="primary" @click="save" :loading="busy">保存定制与加料</el-button>
      </el-tab-pane>
      <el-tab-pane label="规格价格与库存" name="stock">
        <el-alert title="调整的是当前可售库存，已有订单的预占不变。售罄可将可售库存调整到零；取消订单仍会返还其实际预占数量。商品编辑中的已有规格库存仅作展示。" :closable="false" />
        <el-table :data="data.skus">
          <el-table-column label="规格" prop="sku" /><el-table-column label="可售库存" prop="stock" /><el-table-column label="价格" prop="price" />
          <el-table-column label="销售状态"><template #default="{ row }"><el-button @click="toggle(row)">{{ row.is_show ? '停售此规格' : '恢复销售' }}</el-button></template></el-table-column>
          <el-table-column label="经营操作"><template #default="{ row }"><el-button @click="adjust(row)">调整库存</el-button><el-button @click="changePrice(row)">修改售价</el-button></template></el-table-column>
        </el-table>
        <el-table :data="operations"><el-table-column label="操作人" prop="actor_id" /><el-table-column label="类型"><template #default="{ row }">{{ operationLabel(row.kind) }}</template></el-table-column><el-table-column label="原值" prop="before_value" /><el-table-column label="新值" prop="after_value" /><el-table-column label="原因" prop="reason" /><el-table-column label="时间"><template #default="{ row }">{{ formatDate(row.create_time) }}</template></el-table-column></el-table>
      </el-tab-pane>
      <el-tab-pane label="复制到其他门店" name="copy">
        <el-alert title="复制后为独立商品，默认下架、零库存，不会覆盖目标门店现有商品和价格。" :closable="false" />
        <el-select class="w-200px" v-model="targetShop" placeholder="目标门店" @change="loadCategories"><el-option v-for="shop in shops" :key="shop.id" :label="shop.name" :value="shop.id" /></el-select>
        <el-select class="w-200px" v-model="targetCategory" placeholder="目标分类"><el-option v-for="category in categories" :key="category.id" :label="category.name" :value="category.id || 0" /></el-select>
        <el-button type="primary" @click="copyProduct" :loading="busy">创建独立副本</el-button>
      </el-tab-pane>
    </el-tabs>
  </Dialog>
</template>
<script setup lang="ts">
import * as api from '@/api/mall/product/catalog'
import * as ShopApi from '@/api/mall/store/shop'
import * as CategoryApi from '@/api/mall/product/category'
import { ElMessageBox } from 'element-plus'
import { formatDate } from '@/utils/formatTime'
const operationLabel = (kind: string) => (({ STOCK: '库存调整', PRICE: '售价调整', COPY: '跨店复制' } as Record<string, string>)[kind] || kind)
const message = useMessage()
const visible = ref(false), busy = ref(false), tab = ref('options'), productId = ref(0)
const data = ref<api.CatalogData>({ version: 0, configuration: { groups: [] }, skus: [] })
const operations = ref<Record<string, unknown>[]>([])
const shops = ref<ShopApi.ShopVO[]>([]), categories = ref<CategoryApi.CategoryVO[]>([])
const targetShop = ref<number>(), targetCategory = ref<number>()
const pending = new Map<string, string>()
const savedOptions = ref('')
const optionsDirty = computed(() => savedOptions.value !== JSON.stringify(data.value.configuration))
const confirmClose = async (done: () => void) => { if (!optionsDirty.value) { done(); return } try { await ElMessageBox.confirm('定制与加料尚未保存，确认放弃这些修改？', '未保存的修改'); done() } catch {} }
const canOperate = () => { if (optionsDirty.value) { message.warning('请先保存定制与加料，再执行其他经营操作'); return false } return true }
const operationKey = (payload: unknown) => { const s = JSON.stringify(payload); if (!pending.has(s)) pending.set(s, crypto.randomUUID()); return pending.get(s)! }
const reload = async () => { data.value = await api.configuration(productId.value); savedOptions.value = JSON.stringify(data.value.configuration); operations.value = await api.history(productId.value) }
const open = async (id: number) => { productId.value = id; visible.value = true; busy.value = true; tab.value = 'options'; targetShop.value = undefined; targetCategory.value = undefined; pending.clear(); try { await reload(); shops.value = await ShopApi.getShopList() } finally { busy.value = false } }
const addOption = (group: api.CatalogGroup) => group.options.push({ id: crypto.randomUUID().replaceAll('-', ''), name: '', surcharge: 0, enabled: true, defaultQuantity: 0 })
const addGroup = () => { const group: api.CatalogGroup = { id: crypto.randomUUID().replaceAll('-', ''), name: '', kind: 'CUSTOM', multiple: false, min: 0, max: 1, maxPerOption: 1, enabled: true, when: null, options: [] }; addOption(group); data.value.configuration.groups.push(group) }
const setCondition = (group: api.CatalogGroup, id: string) => { group.when = id ? { groupId: id, optionId: '' } : null }
const conditionOptions = (group: api.CatalogGroup) => data.value.configuration.groups.find(g => g.id === group.when?.groupId)?.options || []
const emit = defineEmits(['success'])
const save = async () => {
  if (data.value.configuration.groups.some(g => !g.name.trim() || !g.options.length || g.options.some(o => !o.name.trim()) || (g.when && !g.when.optionId))) { message.warning('请填写分组、选项和显示条件'); return }
  busy.value = true
  try { await api.configure(productId.value, data.value.version, data.value.configuration); await reload(); message.success('已保存'); emit('success') } finally { busy.value = false }
}
const ask = async (label: string, pattern: RegExp) => (await ElMessageBox.prompt(label, '商品经营操作', { inputPattern: pattern, inputErrorMessage: '请按提示填写有效值', closeOnClickModal: false })).value
const adjust = async (sku: api.CatalogSku) => {
  if (!canOperate()) return
  try { const delta = Number(await ask('请输入库存增减数，例如补货 10、售罄减少 -10', /^-?[1-9]\d{0,6}$/)); const reason = await ask('填写调整原因', /^.{1,200}$/); const payload = { productId: productId.value, skuId: sku.id, expected: sku.stock, delta, reason }; await api.stock({ ...payload, key: operationKey(payload) }); await reload(); emit('success') } catch (e) { if (e !== 'cancel' && e !== 'close') throw e }
}
const changePrice = async (sku: api.CatalogSku) => {
  if (!canOperate()) return
  try { const price = await ask('请输入新的售价（元，最多两位小数）', /^\d{1,6}(\.\d{1,2})?$/); const reason = await ask('填写改价原因', /^.{1,200}$/); const payload = { productId: productId.value, skuId: sku.id, price, version: data.value.version, reason }; await api.price({ ...payload, key: operationKey(payload) }); await reload(); emit('success') } catch (e) { if (e !== 'cancel' && e !== 'close') throw e }
}
const toggle = async (sku: api.CatalogSku) => { if (!canOperate()) return; await api.skuSale({ productId: productId.value, skuId: sku.id, enabled: !sku.is_show, version: data.value.version }); await reload(); emit('success') }
const loadCategories = async () => { targetCategory.value = undefined; categories.value = await CategoryApi.getCategoryList({ shopId: targetShop.value }) }
const copyProduct = async () => {
  if (!canOperate()) return
  if (!targetShop.value || !targetCategory.value) { message.warning('请选择目标门店和分类'); return }
  busy.value = true
  try { const payload = { sourceId: productId.value, targetShopId: targetShop.value, targetCategoryId: targetCategory.value }; const id = await api.copy({ ...payload, key: operationKey(payload) }); message.success(`副本已创建，商品编号 ${id}`); emit('success') } finally { busy.value = false }
}
defineExpose({ open })
</script>
