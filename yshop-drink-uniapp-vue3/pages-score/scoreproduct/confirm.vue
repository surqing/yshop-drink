<template>
	<!-- #ifdef MP-WEIXIN -->
	<uv-navbar
	  :fixed="false"
	  :title="title"
	  left-arrow
	  @leftClick="$onClickLeft"
	/>
	<!-- #endif -->
	<view class="px-2 mt-2 score-product-confirm">
		<view class="bg-white p-2" >
			<uv-form :model="form" ref="uForm" >
				<uv-form-item>
						<view v-if="address.realName">
							<view>{{ address.address }} {{ address.detail }}</view>
							<text>{{ address.realName }} {{ address.phone }}</text>
						</view>
						<view v-else>请选择收货地址</view>
						<template #right>
							<uv-icon name="arrow-right" @click="chooseAddress"></uv-icon>
						</template>
				</uv-form-item>
			</uv-form>
		</view>
		<view class="bg-white p-2 mt-2">
			<view class="mb-5">积分商城</view>
			<view class="flex justify-between">
				<view class="flex">
					<image :src="product.image" mode="aspectFill" class="score-product-confirm__thumb"></image>
					<text class="font-size-medium">{{ product.title }}</text>
				</view>
				<view class="flex flex-column">
					<text class="font-size-medium">{{ product.score }}积分</text>
					<text class="font-size-medium text-color-assist mt-2 text-right">×1</text>
				</view>
			</view>
			<view class="flex justify-between mt-5">
				<text class="font-size-medium">积分抵扣</text>
				<text class="font-size-medium">{{ product.score }}</text>
			</view>
			<view class="text-right font-size-lg mt-5 text-color-assist">本次消耗 <text class="font-size-medium text-color-base ">{{ product.score }} 积分</text></view>
		</view>
	</view>
    <view v-if="feedback" class="px-2 mt-2" role="status">{{ feedback }}</view>
    <view v-if="submissionState === 'unknown'" class="px-2 mt-2" @click="viewOrders">查看兑换记录（确认原兑换结果后再发起新兑换）</view>
	<view class="fixed-bottom flex justify-between align-center bg-white ">
		<view class="mx-2 ont-weight-light">本次消耗 <text class="text-danger">{{ product.score }} 积分</text></view>
		<view><uv-button type="warning" :color="scoreProductAccent" :customStyle="customStyle" size="large" :text="buttonText" :disabled="submissionState === 'submitting' || submissionState === 'success' || recoveryBlocked" :loading="submissionState === 'submitting'" @click="submit"></uv-button></view>
	</view>
	<uv-toast ref="uToast" />
</template>

<script setup>
import { ref, computed, getCurrentInstance } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { scoreShopExchange } from '@/api/score'
import { createScoreSubmission } from '@/utils/score-submission.mjs'
import cookie from '@/utils/cookie'
import { useMainStore } from '@/store/store'
import { storeToRefs } from 'pinia'
const { proxy } = getCurrentInstance()
const main = useMainStore()
const { address: selectedAddress, member, isLogin } = storeToRefs(main)
const title = ref('确认订单')
const selectedProduct = ref({})
const submissionState = ref('ready')
const feedback = ref('')
const recoveryBlocked = ref(false)
const originalIntent = ref(null)
let submission, activeMember
const locked = computed(() => ['submitting','unknown'].includes(submissionState.value))
const product = computed(() => locked.value && originalIntent.value?.display?.product || selectedProduct.value)
const address = computed(() => locked.value && originalIntent.value?.display?.address || selectedAddress.value)
const scoreProductAccent = '#ffcc00'
const customStyle = computed(() => ({paddingLeft:'60rpx',paddingRight:'60rpx'}))
const buttonText = computed(() => ({ready:'立即兑换',submitting:'兑换提交中',unknown:'原请求重试',failed:'重试兑换',success:'兑换成功'})[submissionState.value])
const updateView = () => {
  submissionState.value = submission.state
  feedback.value = submission.message
  originalIntent.value = submission.intent
}
const syncMember = () => {
  if (!isLogin.value || !member.value?.id) {
    submission = null
    activeMember = null
    originalIntent.value = null
    submissionState.value = 'ready'
    recoveryBlocked.value = true
    feedback.value = '请登录后兑换'
    return
  }
  const uid = String(member.value.id)
  if (uid === activeMember && submission) return
  activeMember = uid
  recoveryBlocked.value = false
  try {
    submission = createScoreSubmission({storage:uni,memberId:uid,send:scoreShopExchange,onChange:()=> {
      if (String(member.value?.id) === uid) updateView()
    }})
    updateView()
  } catch {
    submission = null
    submissionState.value = 'unknown'
    originalIntent.value = null
    recoveryBlocked.value = true
    feedback.value = '原兑换记录需要确认，请先查看兑换记录，勿重复兑换'
  }
}
onLoad(() => {
  selectedProduct.value = cookie.get('score_product') || {}
  syncMember()
  if (!isLogin.value) uni.navigateTo({url:'/pages-user/login/login'})
})
onShow(syncMember)
const viewOrders = () => uni.navigateTo({url:'/pages-score/scoreproduct/order'})
const chooseAddress = () => {
  if (locked.value) {
    proxy.$refs.uToast.show({message:'请先确认原兑换结果，当前请求地址已保留',type:'warning'})
    return
  }
  uni.navigateTo({url:'/pages-user/address/address?is_choose=true&scene=scoreShop'})
}
const submit = async () => {
  syncMember()
  if (!submission || recoveryBlocked.value || ['submitting','success'].includes(submissionState.value)) return
  const current = submission
  const uid = activeMember
  try {
    // Unknown requests always replay their persisted snapshot, even if page caches changed.
    if (!locked.value) current.begin({productId:product.value.id,addressId:address.value?.id,num:1},
      {product:product.value,address:address.value})
    const result = await current.submit()
    if (result === true && String(member.value?.id) === uid && current === submission) {
      cookie.remove('score_product')
      uni.showToast({title:'兑换成功',icon:'success'})
      setTimeout(viewOrders,1000)
    }
  } catch (error) {
    if (String(member.value?.id) !== uid || current !== submission) return
    if (error?.message === 'SCORE_PARAM_INVALID') feedback.value = '请选择商品和收货地址'
    else if (error?.message === 'UNRESOLVED_SCORE_INTENT') feedback.value = '请先确认原兑换结果，不能更换本次请求'
    else if (!current.message) feedback.value = '暂时无法保存兑换请求，请稍后重试'
  }
}
</script>

<style lang="scss" scoped>
$score-product-accent: #ffcc00;
$score-product-confirm-thumb-size: 160rpx;
$score-product-confirm-thumb-radius: 8rpx;

.score-product-confirm {
	--score-product-accent: #{$score-product-accent};
	--score-product-confirm-thumb-size: #{$score-product-confirm-thumb-size};

	&__thumb {
		flex-shrink: 0;
		width: var(--score-product-confirm-thumb-size);
		height: var(--score-product-confirm-thumb-size);
		margin-right: $spacing-row-lg;
		border-radius: $score-product-confirm-thumb-radius;
	}
}
</style>