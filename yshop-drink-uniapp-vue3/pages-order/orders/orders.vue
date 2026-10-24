<template>
	<uv-navbar
	  :fixed="false"
	  :title="title"
	  left-arrow
	  @leftClick="$onClickLeft"
	/>
	<view class="container orders-page">
		<view class="bg-white">
			<uv-tabs :list="tabList" :current="current" @change="change" keyName="name" :scrollable="false"></uv-tabs>
		</view>
		<view class="orders-page__list d-flex flex-column w-100">
			<view class="orders-item" v-for="item in orders" :key="item.orderId" @tap="detail(item.orderId)">
				<list-cell :hover="false">
					<view class="w-100 d-flex align-items-center">
						<view class="flex-fill d-flex flex-column">
							<view class="font-size-lg text-color-base orders-item__shop">
								{{ item.shop.name }}
							</view>
							<view class="font-size-sm text-color-assist">取餐号：{{ item.numberId }}</view>
							<view class="font-size-sm text-color-assist">订单编号：{{ item.orderId }}</view>
						</view>
						<view class="d-flex flex-column align-items-end">
							<view class="font-size-lg text-color-primary">
								{{ item.orderType == 'takein' ? pickupStatusView(item).primary : item.statusDto.title }}
							</view>
							<view v-if="item.orderType == 'takein' && pickupStatusView(item).showFulfillmentSeparately" class="font-size-sm text-color-assist">
								履约状态：{{ pickupStatusView(item).fulfillment }}
							</view>
						</view>
					</view>
				</list-cell>
				<list-cell :hover="false" last>
					<view class="w-100 d-flex flex-column">
						<view class="w-100 text-truncate font-size-lg text-color-base orders-item__goods">
							<view class="flex orders-item__goods-row mb-2" v-for="(good, gIndex) in item.cartInfo" :key="good.id || `${good.title}-${gIndex}`">
								<image :src="good.image" mode="aspectFill" class="orders-item__thumb" lazy-load></image>
								<view class="flex flex-column">
									<view class="font-size-medium mt-1 text-color-base">{{ good.title }}</view>
									<view class="font-size-sm mt-1">{{ good.spec }}</view>
									<view class="font-size-sm mt-2">×{{ good.number }}  ¥{{ good.price }}</view>
								</view>
							</view>
						</view>
						<view class="d-flex justify-content-between align-items-center orders-item__meta">
							<view class="font-size-sm text-color-assist">
								{{formatDateTime(item.createTime) }}
							</view>
							<view class="d-flex font-size-sm text-color-base align-items-center">
								<view class="orders-item__summary">共{{ goodsNum(item.cartInfo) }}件商品，实付</view>
								<view class="font-size-lg">￥{{ item.payPrice }}</view>
							</view>
						</view>
						<view class="d-flex align-items-center justify-content-end orders-item__actions">
							<button
								v-if="canReceive(item) && item.paid > 0 && item.refundStatus == 0"
								class="orders-item__btn"
								:disabled="receivingOrderId == item.id"
								plain
								size="mini"
								@tap.stop="receive(item)"
							>{{ item.orderType == 'takein' ? '确认取餐' : '确认收到餐' }}</button>
							<button class="orders-item__btn" plain size="mini" @tap="detail(item.orderId)">订单详情</button>
						</view>
					</view>
				</list-cell>
			</view>
		</view>
		<uv-empty v-if="orders.length == 0" mode="order"></uv-empty>
	</view>
</template>


<script setup>
import {
  ref,
  computed
} from 'vue'
import { useMainStore } from '@/store/store'
import { storeToRefs } from 'pinia'
import { onLoad,onPullDownRefresh,onReachBottom} from '@dcloudio/uni-app'
import { formatDateTime,kmUnit } from '@/utils/util'
import { pickupStatusView } from '@/utils/pickup-order-status.mjs'
import {
  orderGetOrders,
  orderReceive
} from '@/api/order'
const main = useMainStore()
const { isLogin } = storeToRefs(main)
const title = ref('我的订单')

const page = ref(1)
const pageSize = ref(10)
const orders = ref([])
const tabList = ref([{
			type: -1,
			name: '全部',
		}, {
			type: 0,
			name: '待支付',
		}, {
			type: 1,
			name: '进行中'
		}, {
			type: 4,
			name: '已完成'
		}, {
			type: -3,
			name: '退款单'
		}]
)
const current = ref(0)
const type = ref(-1)
const receivingOrderId = ref(null)
const canReceive = (order) => order.orderType == 'takein'
	? order.fulfillmentStatus == 'READY'
	: order.status < 2

const goodsNum = computed(() => { //计算单个饮品添加到购物车的数量
	return (goods) => {
		let num = 0;
		goods.forEach(good => num += parseInt(good.number))
		return num;
	}
})
onLoad(() => {
	if(!isLogin.value) {
		uni.navigateTo({url: '/pages-user/login/login'})
	}
	getOrders(false)
})
onPullDownRefresh(() => {
	 getOrders(false)
})
onReachBottom(() => {
	getOrders(false)
})

// tab栏切换
const change = (e) => {
	type.value = e.type
	getOrders(true)
}

const getOrders = async(isRefresh = false) => {
	uni.showLoading({
		title: '加载中'
	})
	if(isRefresh) {
		orders.value = []
		page.value = 1
	}
	let ordersData = await orderGetOrders({page:page.value, limit:pageSize.value,type:type.value});

	if(ordersData) {
		orders.value = orders.value.concat(ordersData)
		page.value += 1
	}
	uni.stopPullDownRefresh();
	uni.hideLoading()
}
const detail = (id) => {
	uni.navigateTo({
		url: '/pages-order/orders/detail?id=' + id
	})
}
// 确认收到货
const receive  = async(order) => {
	if (!canReceive(order) || receivingOrderId.value != null) return
	receivingOrderId.value = order.id
	try {
		let data = await orderReceive({
			uni: order.orderId,
			...(order.orderType == 'takein' ? {
				expectedVersion: order.fulfillmentVersion,
				requestKey: `collect-${order.id}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
			} : {})
		});
		if (data) await getOrders(true)
	} catch {
		await getOrders(true)
		uni.showToast({ title: '订单状态已刷新', icon: 'none' })
	} finally {
		receivingOrderId.value = null
	}
}
	

</script>

<style lang="scss" scoped>
$orders-list-padding-x: $spacing-row-base;
$orders-list-padding-bottom: 0;
$orders-item-gap: $spacing-row-lg;
$orders-section-gap: $spacing-row-base;
$orders-btn-gap: $spacing-row-base;
$orders-summary-gap: $spacing-row-base;
$orders-thumb-size: 160rpx;
$orders-thumb-radius: 8rpx;

.orders-page {
	--orders-thumb-size: #{$orders-thumb-size};

	&__list {
		padding: $orders-list-padding-x;
		padding-bottom: $orders-list-padding-bottom;
	}
}

.orders-item {
	margin-bottom: $orders-item-gap;

	&__shop {
		margin-bottom: $orders-section-gap;
	}

	&__goods {
		margin-bottom: $orders-section-gap;
	}

	&__thumb {
		flex-shrink: 0;
		width: var(--orders-thumb-size);
		height: var(--orders-thumb-size);
		margin-right: $spacing-row-lg;
		border-radius: $orders-thumb-radius;
	}

	&__meta {
		margin-bottom: $orders-item-gap;
	}

	&__summary {
		margin-right: $orders-summary-gap;
	}

	&__btn + &__btn {
		margin-left: $orders-btn-gap;
	}
}
</style>
