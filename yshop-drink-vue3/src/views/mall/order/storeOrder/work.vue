<template>
  <ContentWrap>
    <el-form :inline="true" label-width="86px">
      <el-form-item label="统计日期">
        <el-date-picker v-model="summaryDates" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" @change="refreshSummary" />
      </el-form-item>
      <el-form-item label="统计门店">
        <el-select v-model="summaryShopId" clearable placeholder="全部授权门店" class="!w-220px" @change="refreshSummary">
          <el-option v-for="shop in summaryShops" :key="shop.id" :label="shop.name" :value="shop.id" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="summaryLoading" @click="loadSummary">查询</el-button>
      </el-form-item>
    </el-form>
    <div class="mb-12px text-12px text-gray-500">
      订单实付额按支付时间统计，含已退款订单；退款金额单列。履约队列显示当前待处理订单。
    </div>
    <el-row v-loading="summaryLoading" :gutter="12">
      <el-col :xs="12" :sm="6" :lg="4" class="mb-12px">
        <el-card shadow="never"><el-statistic title="已付订单数" :value="summary?.paidOrderCount || 0">
          <template #suffix><el-link type="primary" @click="openSummaryDetails">查看明细</el-link></template>
        </el-statistic></el-card>
      </el-col>
      <el-col :xs="12" :sm="6" :lg="4" class="mb-12px"><el-card shadow="never"><el-statistic title="订单实付额（毛额）" :value="summary?.grossPaidAmount || 0" :precision="2" prefix="¥" /></el-card></el-col>
      <el-col :xs="12" :sm="6" :lg="4" class="mb-12px"><el-card shadow="never"><el-statistic title="销量" :value="summary?.soldQuantity || 0" /></el-card></el-col>
      <el-col :xs="12" :sm="6" :lg="4" class="mb-12px"><el-card shadow="never"><el-statistic title="退款订单" :value="summary?.refundOrderCount || 0">
        <template #suffix><span class="text-12px">处理中 {{ summary?.refundPendingCount || 0 }} / 已完成 {{ summary?.refundCompletedCount || 0 }}</span></template>
      </el-statistic></el-card></el-col>
      <el-col :xs="12" :sm="6" :lg="4" class="mb-12px"><el-card shadow="never"><el-statistic title="退款金额（单列）" :value="summary?.refundAmount || 0" :precision="2" prefix="¥" /></el-card></el-col>
      <el-col :xs="12" :sm="6" :lg="4" class="mb-12px"><el-card shadow="never"><el-statistic title="待接单" :value="summary?.waitAcceptQueueCount || 0" /></el-card></el-col>
      <el-col :xs="12" :sm="6" :lg="4" class="mb-12px"><el-card shadow="never"><el-statistic title="已接单" :value="summary?.acceptedQueueCount || 0" /></el-card></el-col>
      <el-col :xs="12" :sm="6" :lg="4" class="mb-12px"><el-card shadow="never"><el-statistic title="制作中" :value="summary?.preparingQueueCount || 0" /></el-card></el-col>
      <el-col :xs="12" :sm="6" :lg="4" class="mb-12px"><el-card shadow="never"><el-statistic title="待取餐" :value="summary?.readyQueueCount || 0" /></el-card></el-col>
    </el-row>
  </ContentWrap>
  <ContentWrap>
   <el-tabs v-model="activeName"  @tab-click="handleClick">
     <el-tab-pane label="待接单" name="WAIT_ACCEPT"/>
     <el-tab-pane label="已接单" name="ACCEPTED"/>
     <el-tab-pane label="制作中" name="PREPARING"/>
     <el-tab-pane label="待取餐" name="READY"/>
   </el-tabs>
 </ContentWrap>
 <ContentWrap>
   <!-- 搜索工作栏 -->
   <el-form
     class="-mb-15px"
     :model="queryParams"
     ref="queryFormRef"
     :inline="true"
     label-width="68px"
   >
     <el-form-item label="订单号" prop="orderId">
       <el-input
         v-model="queryParams.orderId"
         placeholder="请输入订单号"
         clearable
         @keyup.enter="handleQuery"
         class="!w-240px"
       />
     </el-form-item>
     <el-form-item label="取餐号" prop="numberId">
       <el-input
         v-model="queryParams.numberId"
         placeholder="请输入取餐号"
         clearable
         @keyup.enter="handleQuery"
         class="!w-240px"
       />
     </el-form-item>   
     <el-form-item>
       <el-button @click="handleQuery"><Icon icon="ep:search" class="mr-5px" /> 搜索</el-button>
       <el-button @click="resetQuery"><Icon icon="ep:refresh" class="mr-5px" /> 重置</el-button>
       <el-button
         type="success"
         plain
         @click="handleExport"
         :loading="exportLoading"
         v-hasPermi="['order:store-order:export']"
       >
         <Icon icon="ep:download" class="mr-5px" /> 导出
       </el-button>
     </el-form-item>
   </el-form>
   
 </ContentWrap>

 <!-- 列表 -->
 <ContentWrap>

     <el-row :gutter="24">
       <el-col :span="6" v-for="(order,k) in list" :key="k">
         <div >
           <el-card class="box-card">
             <template #header>
               <div class="card-header">
                 <span>{{ order.shopName }}</span>
               </div>
             </template>
             <div>取餐号:{{ order.numberId }}</div>
             <div>下单时间:{{ formatDate(order.createTime) }}</div>
             <div>取餐时间:{{ formatDate(order.getTime) }}</div>
             <div>类型:{{ order.orderType == 'takeout' ? '外卖' : '自取' }}</div>
             <div>履约状态:{{ fulfillmentLabel(order.fulfillmentStatus) }}</div>
             <div>  
               <el-button
                 type="primary"
                 @click="openForm('orderDetail', order.id)"
                 v-hasPermi="['order:store-order:update']"
               >详情</el-button>
               <el-button
                 v-if="nextFulfillmentAction(order.fulfillmentStatus)"
                 type="success"
                 :loading="transitioningId === order.id"
                 :disabled="transitioningId !== null"
                 @click="transitionOrder(order)"
                 v-hasPermi="['order:store-order:update']"
               >{{ nextFulfillmentLabel(order.fulfillmentStatus) }}</el-button>
             </div>
           </el-card>
        </div>
        </el-col>
        </el-row>
 </ContentWrap>

 <!-- 表单弹窗：添加/修改 -->
 <StoreOrderForm ref="formRef" @success="getList" />
 <OrderSend ref="formRef1" @success="getList" />
 <OrderSendInfo ref="formRef2" @success="getList" />
 <StoreOrderRemark ref="formRef3" @success="getList" />
 <OrderDetail ref="formRef4" />
 <OrderRecord ref="formRef5" />
 <el-dialog v-model="detailsVisible" title="已付订单明细" width="900px">
   <el-table v-loading="detailsLoading" :data="summaryDetails">
     <el-table-column label="订单号" prop="orderId" min-width="180" />
     <el-table-column label="支付时间" prop="payTime" min-width="160">
       <template #default="scope">{{ formatDate(scope.row.payTime) }}</template>
     </el-table-column>
     <el-table-column label="订单实付额" prop="payPrice" width="120" />
     <el-table-column label="退款状态" width="110">
       <template #default="scope">{{ scope.row.refundStatus === 1 ? '处理中' : scope.row.refundStatus === 2 ? '已完成' : '无退款' }}</template>
     </el-table-column>
     <el-table-column label="退款金额" prop="refundPrice" width="110" />
     <el-table-column label="销量" prop="totalNum" width="80" />
     <el-table-column label="履约状态" prop="fulfillmentStatus" min-width="120" />
   </el-table>
   <Pagination v-model:page="detailsPageNo" v-model:limit="detailsPageSize" :total="detailsTotal" @pagination="loadSummaryDetails" />
 </el-dialog>
</template>

<script setup lang="ts" name="StoreOrder">
// import { dateFormatter } from '@/utils/formatTime'
import download from '@/utils/download'
import * as StoreOrderApi from '@/api/mall/order/storeOrder'
import StoreOrderForm from './StoreOrderForm.vue'
import OrderSend from './OrderSend.vue'
import OrderSendInfo from './OrderSendInfo.vue'
import StoreOrderRemark from './StoreOrderRemark.vue'
import OrderDetail from './OrderDetail.vue'
import OrderRecord from './OrderRecord.vue'
import type { TabsPaneContext } from 'element-plus'
import { formatDate } from '@/utils/formatTime'
import { nextIsoDate } from '@/utils/dateRange'

const message = useMessage() // 消息弹窗
const loading = ref(true) // 列表的加载中
const total = ref(0) // 列表的总页数
const list = ref<StoreOrderApi.StoreOrderVO[]>([]) // 列表的数据
const queryParams = reactive({
 pageNo: 1,
 pageSize: 10,
 orderId: "",
 realName: "",
 userPhone: "",
 createTime: [],
 orderStatus: 1,
 fulfillmentStatus: 'WAIT_ACCEPT',
 payStatus: "",
 numberId: undefined

})
const queryFormRef = ref() // 搜索的表单
const exportLoading = ref(false) // 导出的加载中
const transitioningId = ref<number | null>(null)
const listRequestSequence = ref(0)

const activeName = ref('WAIT_ACCEPT')

const localDate = (date: Date) => {
 const year = date.getFullYear()
 const month = String(date.getMonth() + 1).padStart(2, '0')
 const day = String(date.getDate()).padStart(2, '0')
 return `${year}-${month}-${day}`
}
const summaryDates = ref<[string, string]>([localDate(new Date(Date.now() - 6 * 24 * 60 * 60 * 1000)), localDate(new Date())])
const summaryShopId = ref<number>()
const summaryShops = ref<StoreOrderApi.StoreOrderSummaryShop[]>([])
const summary = ref<StoreOrderApi.StoreOrderSummary>()
const summaryLoading = ref(false)
const summaryRequestSequence = ref(0)
const detailsVisible = ref(false)
const detailsLoading = ref(false)
const summaryDetails = ref<StoreOrderApi.StoreOrderSummaryDetail[]>([])
const detailsPageNo = ref(1)
const detailsPageSize = ref(20)
const detailsTotal = ref(0)
const detailsRequestSequence = ref(0)

const summaryQuery = () => {
 if (!summaryDates.value || summaryDates.value.length !== 2) return null
 const [from, inclusiveTo] = summaryDates.value
 return { from, to: nextIsoDate(inclusiveTo), ...(summaryShopId.value == null ? {} : { shopId: summaryShopId.value }) }
}

const loadSummary = async () => {
 const params = summaryQuery()
 const requestSequence = ++summaryRequestSequence.value
 if (!params) {
   summary.value = undefined
   summaryLoading.value = false
   return
 }
 summaryLoading.value = true
 summary.value = undefined
 try {
   const result = await StoreOrderApi.getStoreOrderSummary(params)
   if (requestSequence === summaryRequestSequence.value) summary.value = result
 } catch {
   if (requestSequence === summaryRequestSequence.value) message.error('订单统计加载失败，请重试')
 } finally {
   if (requestSequence === summaryRequestSequence.value) summaryLoading.value = false
 }
}

const refreshSummary = () => {
 detailsPageNo.value = 1
 void loadSummary()
 if (detailsVisible.value) void loadSummaryDetails()
}

const loadSummaryDetails = async () => {
 const params = summaryQuery()
 const requestSequence = ++detailsRequestSequence.value
 if (!params) {
   summaryDetails.value = []
   detailsTotal.value = 0
   detailsLoading.value = false
   return
 }
 detailsLoading.value = true
 try {
   const result = await StoreOrderApi.getStoreOrderSummaryDetails({
     ...params,
     pageNo: detailsPageNo.value,
     pageSize: detailsPageSize.value
   })
   if (requestSequence === detailsRequestSequence.value) {
     summaryDetails.value = result.list
     detailsTotal.value = result.total
   }
 } catch {
   if (requestSequence === detailsRequestSequence.value) message.error('订单明细加载失败，请重试')
 } finally {
   if (requestSequence === detailsRequestSequence.value) detailsLoading.value = false
 }
}

const openSummaryDetails = () => {
 detailsVisible.value = true
 detailsPageNo.value = 1
 void loadSummaryDetails()
}


const handleClick = (tab: TabsPaneContext) => {
 queryParams.fulfillmentStatus = String(tab.paneName)
 queryParams.pageNo = 1
 getList()
}

const fulfillmentLabel = (status?: string) => ({
 WAIT_ACCEPT: '待接单', ACCEPTED: '已接单', PREPARING: '制作中', READY: '待取餐', COLLECTED: '已取餐', LEGACY: '历史订单'
}[status || ''] || '待核对')
const nextFulfillmentAction = (status?: string) => ({
 WAIT_ACCEPT: 'ACCEPT', ACCEPTED: 'START_PREPARING', PREPARING: 'MARK_READY', READY: 'COLLECT'
}[status || ''])
const nextFulfillmentLabel = (status?: string) => ({
 WAIT_ACCEPT: '接单', ACCEPTED: '开始制作', PREPARING: '完成制作', READY: '确认取餐'
}[status || ''] || '')
const transitionOrder = async (order: StoreOrderApi.StoreOrderVO) => {
 const action = nextFulfillmentAction(order.fulfillmentStatus)
 if (!action || order.fulfillmentVersion == null || transitioningId.value !== null) return
 transitioningId.value = order.id
 try {
   await StoreOrderApi.transitionStoreOrderFulfillment({
     id: order.id,
     action: action as StoreOrderApi.StoreOrderFulfillmentTransition['action'],
     expectedVersion: order.fulfillmentVersion,
     requestKey: crypto.randomUUID()
   })
   await getList()
 } catch {
   message.error('订单状态已变化，请刷新后重试')
   await getList()
 } finally {
   transitioningId.value = null
 }
}


/** 查询列表 */
const getList = async () => {
 const requestSequence = ++listRequestSequence.value
 const params = { ...queryParams }
 loading.value = true
 try {
   const data = await StoreOrderApi.getStoreOrderPage(params)
   if (requestSequence === listRequestSequence.value) {
     list.value = data.list
     total.value = data.total
   }
 } finally {
   if (requestSequence === listRequestSequence.value) loading.value = false
 }
}



/** 搜索按钮操作 */
const handleQuery = () => {
 queryParams.pageNo = 1
 getList()
}

/** 重置按钮操作 */
const resetQuery = () => {
 queryFormRef.value.resetFields()
 handleQuery()
}

/** 添加/修改操作 */
const formRef = ref()
const formRef1 = ref()
const formRef2 = ref()
const formRef3 = ref()
const formRef4 = ref()
const formRef5 = ref()
const openForm = (type: string, id?: number) => {
 if (type == 'updateOrder') {
   formRef.value.open(type, id)
 } else if (type == 'orderSend') {
   formRef1.value.open(type, id)
 }else if (type == 'sendInfo') {
   formRef2.value.open(type, id)
 }else if (type == 'remark') {
   formRef3.value.open(type, id)
 }else if (type == 'orderDetail') {
   formRef4.value.open(type, id)
 }else if (type == 'orderRecord') {
   formRef5.value.open(type, id)
 }

 
}

/** 导出按钮操作 */
const handleExport = async () => {
 try {
   // 导出的二次确认
   await message.exportConfirm()
   // 发起导出
   exportLoading.value = true
   const data = await StoreOrderApi.exportStoreOrder(queryParams)
   download.excel(data, '订单.xls')
 } catch {
 } finally {
   exportLoading.value = false
 }
}

/** 初始化 **/
onMounted(async () => {
 getList()
 try {
   summaryShops.value = await StoreOrderApi.getStoreOrderSummaryShops()
 } catch {
   message.error('授权门店列表加载失败')
 } finally {
   void loadSummary()
 }
})
</script>

<style scoped  >
img {
       height: 36px;
       display: block;
   }
.tabBox{
   width: 100%;
   height: 100%;
   display: flex;
   align-items: center
   }
.tabBox_img{
   width: 36px;
   height: 36px;
 }
.tabBox_img img{
 width: 100%;
 height: 100%;
}
.tabBox_tit{
   width :60%;
   font-size: 12px !important;
   margin: 0 2px 0 10px;
   letter-spacing: 1px;
   padding: 5px 0;
   box-sizing: border-box;
   text-align: left;
 }
 .tabBox_pice{
   width :30%;
   font-size: 12px !important;
   margin: 0 2px 0 10px;
   letter-spacing: 1px;
   padding: 5px 0;
   box-sizing: border-box;
   text-align: left;
 }

</style>
