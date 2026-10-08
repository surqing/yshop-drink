<template>
  <Dialog :title="formType === 'create' ? '创建优惠活动' : '编辑优惠活动'" v-model="visible" width="min(760px,95vw)">
    <el-alert title="编辑仅影响未来领取；停用活动不作废已领取权益。优惠仅抵商品小计，积分兑换暂未开放。" type="info" :closable="false" class="mb-15px" />
    <el-form ref="formRef" :model="data" :rules="rules" label-width="130px" v-loading="loading">
      <el-form-item label="优惠券名称" prop="title"><el-input v-model="data.title" maxlength="50" /></el-form-item>
      <el-form-item label="活动类型"><el-radio-group v-model="data.couponKind"><el-radio label="REGULAR">普通满减券</el-radio><el-radio label="NEW_USER">新注册会员券</el-radio></el-radio-group><div class="text-xs">新人按服务端注册时间判断，全商家只享一次；不代表首次消费。</div></el-form-item>
      <el-form-item label="适用门店" prop="shopId"><el-select v-model="selectedShops" multiple placeholder="明确选择适用门店" style="width:100%"><el-option label="全部门店（仅总部）" value="0" /><el-option v-for="shop in shops" :key="shop.id" :label="shop.name" :value="String(shop.id)" /></el-select></el-form-item>
      <el-form-item label="消费方式"><el-radio-group v-model="data.type"><el-radio :label="0">通用</el-radio><el-radio :label="1">自取</el-radio><el-radio :label="2">外卖</el-radio></el-radio-group></el-form-item>
      <el-form-item label="商品满额" prop="least"><el-input-number v-model="data.least" :min="0" :max="999999.99" :precision="2" /> 元</el-form-item>
      <el-form-item label="优惠金额" prop="value"><el-input-number v-model="data.value" :min="0.01" :max="999999.99" :precision="2" /> 元</el-form-item>
      <el-form-item label="领取开始" prop="claimStartTime"><el-date-picker v-model="data.claimStartTime" type="datetime" value-format="x" /></el-form-item>
      <el-form-item label="领取截止" prop="claimEndTime"><el-date-picker v-model="data.claimEndTime" type="datetime" value-format="x" /></el-form-item>
      <el-form-item label="使用开始" prop="startTime"><el-date-picker v-model="data.startTime" type="datetime" value-format="x" /></el-form-item>
      <el-form-item label="使用截止" prop="endTime"><el-date-picker v-model="data.endTime" type="datetime" value-format="x" /></el-form-item>
      <el-form-item label="发行总量" prop="distribute"><el-input-number v-model="data.distribute" :min="0" :max="1000000" :precision="0" /><span class="ml-10px">已领 {{ data.receive || 0 }} 张</span></el-form-item>
      <el-form-item label="每人限领" prop="limit"><el-input-number v-model="data.limit" :min="1" :max="1000" :precision="0" /> 张，已用/过期券仍计入限额</el-form-item>
      <el-form-item label="领取方式"><el-radio-group v-model="data.claimMode"><el-radio label="PUBLIC">公开领取</el-radio><el-radio label="CODE">公共兑换码</el-radio></el-radio-group></el-form-item>
      <el-form-item v-if="data.claimMode === 'CODE'" label="公共兑换码"><el-input v-model="data.exchangeCode" type="password" autocomplete="off" show-password maxlength="32" :placeholder="data.id ? '留空保留原码；不会回显' : '4–32位字母、数字、下划线或横线'" /><div class="text-xs">公共码不是一次性码；仍受总量及每人限领约束。</div></el-form-item>
      <el-form-item label="图片"><Materials v-model="data.image" :num="1" type="image" /></el-form-item>
      <el-form-item label="使用说明"><el-input v-model="data.instructions" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
      <el-form-item label="活动启用"><el-switch v-model="enabled" /></el-form-item>
    </el-form>
    <template #footer><el-button type="primary" :loading="loading" @click="save">保存</el-button><el-button @click="close">取消</el-button></template>
  </Dialog>
</template>
<script setup lang="ts">
import * as Api from '@/api/mall/coupon'
import * as ShopApi from '@/api/mall/store/shop'
const message = useMessage()
const visible = ref(false), loading = ref(false), formType = ref('create'), formRef = ref()
const data = ref<Partial<Api.VO>>({})
const shops = ref<ShopApi.ShopVO[]>([])
const selectedShops = ref<string[]>([])
const enabled = computed({get: () => data.value.isSwitch === 1, set: value => {data.value.isSwitch = value ? 1 : 0}})
watch(selectedShops, value => { data.value.shopId = value.join(',') }, {deep: true})
const rules = Object.fromEntries(['title','shopId','least','value','claimStartTime','claimEndTime','startTime','endTime','distribute','limit'].map(key => [key,[{required:true,message:'请填写此项',trigger:'change'}]]))
const emit = defineEmits(['success'])
const open = async (type: string, id?: number) => {
  visible.value = true; formType.value = type; loading.value = true
  try {
    shops.value = await ShopApi.getShopList()
    const today = Date.now(), end = today + 30 * 86400000
    data.value = id ? await Api.getCoupon(id) : {title:'',shopId:'',couponKind:'REGULAR',claimMode:'PUBLIC',type:0,least:30,value:5,distribute:100,limit:1,score:0,isSwitch:1,instructions:'',image:'',startTime:today as unknown as Date,endTime:end as unknown as Date,claimStartTime:today,claimEndTime:end}
    data.value.claimStartTime ||= data.value.startTime
    data.value.claimEndTime ||= data.value.endTime
    data.value.couponKind ||= 'REGULAR';data.value.claimMode ||= 'PUBLIC'
    data.value.exchangeCode = ''
    selectedShops.value = data.value.shopId ? String(data.value.shopId).split(',') : []
    formRef.value?.clearValidate()
  } finally {loading.value=false}
}
const close = async () => {try {await message.confirm('放弃本次未保存的活动修改？');visible.value=false}catch {}}
const save = async () => {
  if(!await formRef.value.validate())return
  if(selectedShops.value.includes('0') && selectedShops.value.length!==1){message.error('全店通用不能与具体门店混选');return}
  const begin=Number(data.value.claimStartTime), claimEnd=Number(data.value.claimEndTime), start=Number(data.value.startTime), end=Number(data.value.endTime)
  if(begin>=claimEnd || start>=end || claimEnd>end){message.error('请检查领取/使用时间，领取截止不能晚于使用截止');return}
  if(data.value.claimMode==='CODE' && !data.value.id && !data.value.exchangeCode){message.error('请设置公共兑换码');return}
  loading.value=true
  try {
    const payload = {...data.value,shopId:selectedShops.value.join(','),score:0,startTime:start,endTime:end,claimStartTime:begin,claimEndTime:claimEnd} as unknown as Api.VO
    if(formType.value==='create')await Api.createCoupon(payload);else await Api.updateCoupon(payload)
    message.success('活动已保存，已领取权益保持不变');visible.value=false;emit('success')
  } finally {loading.value=false}
}
defineExpose({open})
</script>
