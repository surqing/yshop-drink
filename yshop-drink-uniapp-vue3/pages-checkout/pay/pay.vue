<template>
	<uv-navbar
	  :fixed="false"
	  :title="title"
	  left-arrow
	  @leftClick="$onClickLeft"
	/>
	<view class="pay-page container position-relative">
		<view class="pay-page__content">
			<view class="pay-page__section">
				<template v-if="store.distance > 0">
					<list-cell class="pay-page__cell pay-page__cell--location">
						<view class="flex-fill d-flex justify-content-between align-items-center">
							<view class="store-name flex-fill">{{ orderType == 'takeout' ? '外卖配送' : '点餐自取' }}</view>
							<uv-switch activeColor="#09b4f1" v-model="active" @change="takout">
							</uv-switch>
						</view>
					</list-cell>
				</template>

				<template v-if="orderType == 'takeout'">
					<list-cell @click="chooseAddress">
						<view v-if="address.realName" class="w-100 d-flex flex-column">
							<view class="d-flex align-items-center justify-content-between mb-10">
								<view class="font-size-lg text-color-base">
									{{ address.address + ' ' + address.detail }}
								</view>
								<image src="/static/images/navigator-1.png" class="pay-page__arrow"></image>
							</view>
							<view class="d-flex text-color-assist font-size-sm align-items-center">
								<view class="mr-10">{{ address.realName }}</view>
								<view>{{ address.phone }}</view>
							</view>
						</view>
						<view v-else class="flex-fill d-flex justify-content-between align-items-center">
							<view class="store-name flex-fill">选择收货地址</view>
							<image src="/static/images/navigator-1.png" class="pay-page__arrow"></image>
						</view>
					</list-cell>
				</template>
			</view>

			<view class="pay-page__section">
				<template>
					<list-cell class="pay-page__cell pay-page__cell--location" @click="goToShop">
						<view class="flex-fill d-flex justify-content-between align-items-center">
							<view class="store-name flex-fill">{{ store.name }}</view>
							<image src="/static/images/navigator-1.png" class="pay-page__arrow"></image>
						</view>
					</list-cell>
				</template>

				<template>
					<list-cell arrow class="pay-page__cell pay-page__cell--meal-time" v-if="orderType == 'takein'">
						<view class="flex-fill d-flex justify-content-between align-items-center"
							@click="takeinTIme = !takeinTIme">
							<view class="title">取餐时间</view>
							<view class="time">
								{{ takeinRange[defaultSelector[0]].name }}
								<u-picker v-model="takeinTIme" :range="takeinRange" range-key="name" mode="selector"
									@cancel="takeinCancelTime" @confirm="takeinConfirmTime"
									:default-selector="defaultSelector"></u-picker>
							</view>
						</view>
					</list-cell>
					<list-cell class="pay-page__cell pay-page__cell--contact" last :hover="false" v-if="orderType == 'takein'">
						<view class="flex-fill d-flex justify-content-between align-items-center">
							<view class="title flex-fill">联系电话</view>
							<view class="time"><input class="text-right" placeholder="请输入手机号码" :value="member.mobile" />
							</view>
							<button class="pay-page__contact-tip font-size-sm">自动填写</button>
						</view>
					</list-cell>
				</template>
				<template v-if="orderType == 'takeout'">
					<list-cell>
						<view class="w-100 d-flex flex-column">
							<view class="d-flex align-items-center font-size-base text-color-base">
								<view class="flex-fill">预计送达时间</view>
								<view class="mr-10">
									{{ defaultTime }}
									<u-picker :default-time="defaultTime" v-model="takeoutTIme" :params="paramsTime"
										mode="time" @cancel="cancelTime" @confirm="choiceTime"></u-picker>
								</view>
							</view>
						</view>
					</list-cell>
				</template>
			</view>
			<!-- 购物车列表 begin -->
			<view class="pay-page__section pay-page__section--cart">
				<view class="pay-page__cart d-flex flex-column">
					<list-cell last v-for="(item, index) in cart" :key="index">
						<view class="w-100 d-flex flex-column">
							<view class="d-flex align-items-center mb-10">
								<view
									class="d-flex flex-fill justify-content-between align-items-center text-color-base font-size-lg">
									<image class="pay-page__cart-thumb" mode="aspectFill" :src="item.image">
									</image>
								</view>
								<view class="pay-page__cart-name overflow-hidden">
									<view class="text-color-base font-size-lg">{{ item.name }}</view>
								</view>
								<view
									class="d-flex flex-fill justify-content-between align-items-center text-color-base font-size-lg">
									<view>x{{ item.number }}</view>
									<view>￥{{ item.price }}</view>
								</view>
							</view>
							<view class="text-truncate font-size-base text-color-assist">{{ item.valueStr }} {{ item.optionLabel || '' }}</view>
						</view>
					</list-cell>
				</view>
				<list-cell arrow @click="goToPackages">
					<view class="flex-fill d-flex justify-content-between align-items-center">
						<view class="text-color-base">优惠券</view>
						<view v-if="coupons == 0" class="text-color-base">暂无可用</view>
						<view v-else-if="coupon.title" class="text-color-danger">
							{{ coupon.title }}(满{{ coupon.least }}减{{ coupon.value }})
						</view>
						<view v-else class="text-color-primary">可用优惠券{{ coupons }}张</view>
					</view>
				</list-cell>
				<list-cell last>
					<view class="flex-fill d-flex justify-content-end align-items-center">
						<view>
							总计￥{{ total }}
							<text v-if="orderType == 'takeout'">,配送费￥{{ store.deliveryPrice }}</text>
							<text v-if="coupon.value">,￥-{{ couponDiscount(main.mycoupon,total).toFixed(2) }}</text>
							,实付
						</view>
						<view class="font-size-extra-lg font-weight-bold">￥{{ amount }}</view>
					</view>
				</list-cell>
			</view>
			<!-- 购物车列表 end -->
			<view class="pay-page__notice d-flex align-items-center justify-content-start font-size-sm text-color-warning">
			</view>
			<!-- 支付方式 begin -->
			<view class="pay-page__payment" v-if="!PAYMENT_FROZEN">
				<list-cell last :hover="false"><text>支付方式</text></list-cell>
				<list-cell>
					<view class="pay-page__payment-row pay-page__payment-row--disabled d-flex align-items-center justify-content-between w-100"
						@click="setPayType('yue')">
						<view class="iconfont iconbalance line-height-100 pay-page__payment-icon"></view>
						<view class="flex-fill">余额支付（余额￥{{ member.nowMoney }}）</view>
						<view class="font-size-sm" v-if="member.nowMoney == 0">余额不足</view>
						<view class="iconfont line-height-100 pay-page__checkbox pay-page__checkbox--checked iconradio-button-on" v-if="payType == 'yue'">
						</view>
						<view class="iconfont line-height-100 pay-page__checkbox iconradio-button-off" v-else></view>
					</view>
				</list-cell>
				<list-cell last>
					<view class="pay-page__payment-row d-flex align-items-center justify-content-between w-100" @click="setPayType('weixin')">
						<view class="iconfont iconwxpay line-height-100 pay-page__payment-icon pay-page__payment-icon--wechat"></view>
						<view class="flex-fill">微信支付</view>
						<view class="iconfont line-height-100 pay-page__checkbox pay-page__checkbox--checked iconradio-button-on" v-if="payType == 'weixin'">
						</view>
						<view class="iconfont line-height-100 pay-page__checkbox iconradio-button-off" v-else></view>
					</view>
				</list-cell>
				<!-- #ifdef H5 -->
				<list-cell>
					<view class="pay-page__payment-row d-flex align-items-center justify-content-between w-100" @click="setPayType('alipay')">
						<view class="iconfont-yshop icon-alipay line-height-100 pay-page__payment-icon pay-page__payment-icon--alipay"></view>
						<view class="flex-fill">支付宝</view>
						<view class="iconfont line-height-100 pay-page__checkbox pay-page__checkbox--checked iconradio-button-on" v-if="payType == 'alipay'" ></view>
						<view class="iconfont line-height-100 pay-page__checkbox iconradio-button-off" v-else ></view>     
					</view>
				</list-cell>
				<!-- #endif -->
			</view>
			<!-- 支付方式 end -->
			<!-- 备注 begin -->
			<list-cell last @click="goToRemark">
				<view class="pay-page__remark d-flex flex-fill align-items-center justify-content-between overflow-hidden">
					<view class="flex-shrink-0 mr-20">备注</view>
					<view class="text-color-primary flex-fill text-truncate text-right">{{ form.remark || '点击填写备注' }}
					</view>
				</view>
			</list-cell>
			<!-- 备注 end -->
		</view>
		<!-- 付款栏 begin -->
		<view class="pay-page__footer w-100 position-fixed fixed-bottom d-flex align-items-center justify-content-between bg-white">
			<view class="pay-page__footer-label font-size-sm">合计：</view>
			<view class="pay-page__footer-amount font-size-lg flex-fill">￥{{ amount }}</view>
			<view class="pay-page__footer-btn bg-primary h-100 d-flex align-items-center just-content-center text-color-white font-size-base"
				@tap="debounce(submit, 500)">{{ submitting ? '提交中' : '提交订单' }}</view>
		</view>
		<!-- 付款栏 end -->
		<modal :show="ensureAddressModalVisible" custom :mask-closable="false" :radius="'0rpx'" width="90%">
			<view class="pay-page__modal">
				<view class="d-flex justify-content-end">
					<image src="/static/images/pay/close.png" class="pay-page__modal-close"
						@tap="ensureAddressModalVisible = false"></image>
				</view>
				<view class="pay-page__modal-title d-flex just-content-center align-items-center">
					<view class="font-size-extra-lg text-color-base">请再次确认下单地址</view>
				</view>
				<view
					class="d-flex font-size-base text-color-base font-weight-bold align-items-center justify-content-between mb-20">
					<view>{{ address.realName }}</view>
					<view>{{ address.phone }}</view>
				</view>
				<view class="d-flex font-size-sm text-color-assist align-items-center justify-content-between mb-40">
					<view class="pay-page__modal-address">{{ address.address + address.detail }}</view>
					<button type="primary" size="mini" plain class="pay-page__modal-change-btn"
						@click="chooseAddress">修改地址</button>
				</view>
				<button type="primary" class="pay-page__modal-submit" @tap="debounce(pay, 500)">确认并提交</button>
			</view>
		</modal>
		<uv-toast ref="uToast"></uv-toast>
	</view>
</template>

<script setup>
import {
  ref,
  computed,
  nextTick
} from 'vue'
import { useMainStore } from '@/store/store'
import { PAYMENT_FROZEN, eligibleCoupons } from '@/utils/ordering-context'
import { cents } from '@/utils/catalog-options'
import { couponDiscount, couponContextMatches } from '@/utils/coupon-context'
import { storeToRefs } from 'pinia'
import { onLoad,onShow ,onPullDownRefresh,onHide} from '@dcloudio/uni-app'
import { formatDateTime,isWeixin } from '@/utils/util'
import  debounce  from '@/uni_modules/uv-ui-tools/libs/function/debounce'

import {
  orderSubmit,
  payUnify,
  getWechatConfig
} from '@/api/order'
import {
  couponMine
} from '@/api/coupon'
// #ifdef H5
import * as jweixin from 'weixin-js-sdk'
// #endif
const main = useMainStore()
const { orderType,address, store,location,isLogin,member,mycoupon } = storeToRefs(main)
const active = ref(false)
const title = ref('确认订单')
const submitting = ref(false)
const jsStr = ref('')
const cart = ref([])
const form = ref({
	remark: ''
})
const  ensureAddressModalVisible = ref(false)
const  takeoutTIme = ref(false) // 外卖取餐时间picker
const paramsTime = ref({
	year: false,
	month: false,
	day: false,
	hour: true,
	minute: true,
	second: false
})
const defaultTime = ref('00:00')
const takeinTIme = ref(false) // 到店自取时间selector
const takeinRange = ref([{
		name: '立即用餐',
		value: 0
	},
	{
		name: '10分钟后',
		value: 10
	},
	{
		name: '20分钟后',
		value: 20
	},
	{
		name: '30分钟后',
		value: 30
	},
	{
		name: '40分钟后',
		value: 40
	},
	{
		name: '50分钟后',
		value: 50
	}
])
const defaultSelector = ref([0])
const payType = ref('weixin') // 付款方式
const coupons = ref(0) // 可用优惠券数量
const coupon = ref(main.mycoupon) // 选中的
const subscribeMss = ref({
	'takein': '',
	'takeout': '',
	'takein_made': '',
	'takeout_made': ''
})// 微信订阅信息
const uToast = ref()

const total = computed(() => cart.value.reduce((sum,item)=>sum + cents(item.price) * item.number,0) / 100)
const amount = computed(() => {
    const subtotal = cents(total.value)
    const delivery = orderType.value === 'takeout' && store.value.distance > 0 ? cents(store.value.deliveryPrice) : 0
    return ((subtotal - cents(couponDiscount(main.mycoupon,total.value)) + delivery) / 100).toFixed(2)
})
let couponGeneration = 0

onShow(() => {
	coupon.value = main.mycoupon
	let date = new Date(new Date().getTime() + 3600000); // 一个小时后
	let hour = date.getHours();
	let minute = date.getMinutes();
	if (hour < 10) {
		hour = '0' + hour;
	}
	if (minute < 10) {
		minute = '0' + minute;
	}
	defaultTime.value = hour + ':' + minute;
	
	console.debug('[checkout] member present:', !!member.value?.id)
	
	if(orderType.value == 'takeout'){
		active.value = true
	}else{
		active.value = false
	}
	
	getCoupons();
	
	let paytype = uni.getStorageSync('paytype');
	payType.value = paytype ? paytype : 'weixin';
	
})
onHide(() => {
	subscribeMss.value = [];
	coupons.value = 0;
})
onLoad((option) => {
	cart.value = uni.getStorageSync('cart') || []
	if(option.remark) {
		form.value.remark = option.remark
	}
})

const getSubscribeMss = async() => {
	 let data = []
	if (data) {
		subscribeMss.value = data;
	}
}
// 更改支付方式
const setPayType = (paytype) => {
	payType.value = 'weixin';
	payType.value= paytype;
	uni.setStorage({
		key: 'paytype',
		data: paytype
	})
}
const getCoupons = async() => {
    const generation=++couponGeneration, shop=store.value.id, type=orderType.value
    try {
        const data=await couponMine({shopId:shop,type:0,page:1,pagesize:100})
        if(generation!==couponGeneration || !couponContextMatches(shop,store.value.id,type,orderType.value))return
        const valid=eligibleCoupons(data,shop,type,total.value)
        coupons.value=valid.length
        if(main.mycoupon.id && !valid.some(c=>c.id===main.mycoupon.id)){main.DEL_COUPON();coupon.value={}}
        else if(main.mycoupon.id){main.SET_COUPON(valid.find(c=>c.id===main.mycoupon.id));coupon.value=main.mycoupon}
    }catch { /* Network failure retains the last selection and checkout retry identity. */ }
}
// 选择时间
const choiceTime = (value) => {
	let hour = value.hour;
	let minute = value.minute;

	let date = new Date(new Date().getTime() + 3600000); // 一个小时后
	let nowhour = date.getHours();
	let nowminute = date.getMinutes();

	if ((hour * 60 * 60 + minute * 60) * 1000 - 3600000 < (nowhour * 60 * 60 + nowminute * 60) * 1000) {
		uToast.value.show({
			message: '请至少选择一个小时之后',
			type: 'error'
		});
		return
	}

	if (hour < 10) {
		hour = '0' + hour;
	}
	if (minute < 10) {
		minute = '0' + minute;
	}
	defaultTime.value = hour + ':' + minute;
	takeoutTIme.value = false;
}
const cancelTime = (value) => {
	takeoutTIme.value = false;
}
// 到店自取-取消选择取餐时间
const takeinCancelTime = (value) => {
	takeinTIme.value = false;
}
// 到店自取-选择取餐时间
const takeinConfirmTime = (value) => {
	defaultSelector.value = value;
}
// 是否外卖开关
const takout = (value) => {
	let type = 'takeout';
	if (value == false) {
		type = 'takein';
	}
	main.SET_ORDER_TYPE(type);

	// 如果存在优惠券看看需不需要清除
	if (coupon.value.hasOwnProperty('type')) {
		//0=通用,1=自取,2=外卖
		if (coupon.value.type != 0) {
			if (coupon.value.type == 1 && orderType.value == 'takeout') {
				coupon.value = {};
                main.DEL_COUPON();
			}
			if (coupon.value.type == 2 && orderType.value == 'takein') {
				coupon.value = {};
                main.DEL_COUPON();
			}
		}
	}
	subscribeMss.value = [];
	coupons.value = 0;
	getCoupons();
}
const goToRemark = () => {
	uni.navigateTo({
		url: '/pages-checkout/remark/remark?remark=' + form.value.remark
	});
}
const chooseAddress = () => {
	uni.navigateTo({
		url: '/pages-user/address/address?is_choose=true&scene=pay'
	});
}
const goToPackages = () => {
	let newamount = total.value;
	let coupon_id = coupon.value.id ? coupon.value.id : 0;
	let type = orderType.value == 'takein' ? 1 : 2;
	let shop_id = store.value.id;
	uni.navigateTo({
		url: '/pages-checkout/packages/index?amount=' + newamount + '&coupon_id=' + coupon_id +
			'&shop_id=' + shop_id + '&type=' + type
	});
}
const goToShop = () => {
	uni.navigateTo({
		url: `/pages-checkout/shop/shop`
	});
}
const submit = () => {
	if (orderType.value == 'takeout') {
		// 外卖类型
		if (typeof address.value.id == 'undefined') {
			uToast.value.show({
				message: '请选择收货地址',
				type: 'error'
			});
			return
		}

		// 起送价钱
		if (store.value.min_price > total.value) {
			uToast.value.show({
				message: '本店外卖起送价为￥' + store.value.min_price,
				type: 'error'
			});
			return
		}

		pay();

	} else {
		pay();
	}
}
const pay = async() => {
    if (submitting.value) return
    if (!cart.value.length || cart.value.some(item => String(item.shopId) !== String(store.value.id))) {
        uni.showToast({ title: '门店已变化，请重新选择商品', icon: 'none' })
        return
    }
    submitting.value = true
    uni.showLoading({ title: '提交中' })
    try {
        const data = {
            orderType: orderType.value,
            addressId: orderType.value === 'takeout' ? address.value.id : 0,
            shopId: store.value.id,
            gettime: takeinRange.value[defaultSelector.value[0]].value,
            payType: 'weixin', remark: form.value.remark,
            productId: cart.value.map(item => item.id),
            spec: cart.value.map(item => item.valueStr.replace(/,/g, '|')),
            number: cart.value.map(item => item.number),
            choices: cart.value.map(item => ({ version: Number(item.catalogVersion || 0), selections: item.selections || [] })),
            couponId: coupon.value.id || 0
        }
        const order = await orderSubmit(data)
        if (!order?.orderId) return
        main.DEL_COUPON()
        main.REMOVE_CART()
        uni.removeStorageSync('cart')
        uni.removeStorageSync('checkoutSubmission')
        await uni.redirectTo({ url: '/pages-order/orders/detail?id=' + order.orderId })
    } catch (error) {
        // Preserve the cart and submission key: a failed response can follow a committed order.
        uni.showToast({ title: String(error?.msg || error?.message || '').includes('CATALOG_CHANGED') ? '商品配置已更新，请返回点餐页刷新并确认' : '提交未确认，请重试；不会重复创建订单', icon: 'none' })
    } finally {
        submitting.value = false
        uni.hideLoading()
    }
}
const balancePay = async(order) => {
    if (PAYMENT_FROZEN) { uni.showToast({ title: '支付暂未开放', icon: 'none' }); return }
	let from = 'routine'
	// #ifdef H5
	from = 'h5'
	// #endif
	let pay = await payUnify({
		uni: order.orderId,
		from: from,
		paytype: 'yue'
	});

	uni.hideLoading();
	if (!pay) {
		return;
	}

	member.value.money -= amount.value
	main.SET_MEMBER(member.value)
	uni.removeStorageSync('cart');
	uni.switchTab({
		url: '/pages/order/order',
		fail(res) {
			console.log(res);
		}
	});
}
const weixinPay = async(order) => {
    if (PAYMENT_FROZEN) { uni.showToast({ title: '支付暂未开放', icon: 'none' }); return }
	let from = 'routine'
	// #ifdef H5
	from = 'h5'
	if(isWeixin()){
		from = 'wechat'
	}
	
	// #endif
	//let that = this;
	let data = await payUnify({
		uni: order.orderId,
		from: from,
		paytype: 'weixin'
	});
	console.debug('[payment] response present:', !!data)
	if (!data) {
		uni.hideLoading();
		return;
	}
	if (data.trade_type == 'MWEB') {
		// #ifdef H5
		// 微信外的H5

		location.href = data.data;
		// #endif

	} else if (data.trade_type == 'JSAPI') {


		// #ifdef MP-WEIXIN
		uni.requestPayment({
			provider: 'wxpay',
			timeStamp: data.data.timeStamp,
			nonceStr: data.data.nonceStr,
			package: data.data.package,
			signType: data.data.signType,
			paySign: data.data.paySign,
			success: function(res) {

				uni.removeStorageSync('cart');
				uni.switchTab({
					url: '/pages/order/order'
				});
			},
			fail: function(err) {
				console.warn('[payment] category=wechat');
			}
		});
		// #endif
	} else if (data.trade_type == 'W-JSAPI'){
		//公众号支付
	
		
	}else if (data.trade_type == 'APP') {

	}
}
const aliPay = async(order) => {
    if (PAYMENT_FROZEN) { uni.showToast({ title: '支付暂未开放', icon: 'none' }); return }

	// #ifdef H5
	//let that = this;
	if(isWeixin()){
		uni.showToast({
			title: '请普通浏览器打开进行支付宝支付~',
			icon: 'none'
		})
		return
	}
	let data = await payUnify({
		uni: order.orderId,
		from: 'h5',
		paytype: 'alipay'
	});

	console.debug('[payment] response present:', !!data?.data)
  // 支付宝支付，这里只要提交表单
	let form = data.data
	const div = document.createElement('formdiv');
	div.innerHTML = form;
	document.body.appendChild(div);      
	//document.forms[0].setAttribute('target', ' self');
	document.forms[0].submit();
	//div.remove();

// #endif


}




</script>

<style lang="scss" scoped>
// 支付页局部 token（与 uni.scss 全局变量配合）
$pay-page-padding: $spacing-row-lg;
$pay-page-section-gap: $spacing-row-lg;
$pay-page-content-offset: 130rpx;
$pay-arrow-size: 50rpx;
$pay-arrow-offset-right: -10rpx;
$pay-cart-name-width: 65%;
$pay-payment-icon-size: 44rpx;
$pay-checkbox-size: 36rpx;
$pay-checkbox-gap: 10rpx;
$pay-payment-gap: 10rpx;
$pay-footer-height: 100rpx;
$pay-footer-shadow: 0 0 20rpx rgba($color: #000, $alpha: 0.1);
$pay-footer-z-index: 1;
$pay-footer-label-margin: $spacing-row-base;
$pay-footer-btn-padding-x: 60rpx;
$pay-remark-margin-bottom: 110rpx;
$pay-notice-padding-y: $spacing-row-base;
$pay-wechat-color: #7eb73a;
$pay-alipay-color: #07b4fd;
$pay-modal-close-size: 40rpx;
$pay-modal-title-margin-bottom: 40px;
$pay-modal-address-max-width: 60%;
$pay-contact-tip-border-width: 2rpx;
$pay-contact-tip-padding-y: 6rpx;
$pay-contact-tip-padding-x: 10rpx;
$pay-contact-tip-margin-left: 10rpx;
$pay-modal-btn-radius: 50rem;
$pay-modal-btn-line-height: 3;
$pay-modal-change-btn-line-height: 2;

.pay-page {
	padding: $pay-page-padding;
	--pay-cart-thumb-size: #{$img-size-lg};

	&__content {
		margin-bottom: $pay-page-content-offset;
	}

	&__section {
		margin-bottom: $pay-page-section-gap;

		&--cart {
			margin-bottom: 0;
		}
	}

	&__arrow {
		position: relative;
		width: $pay-arrow-size;
		height: $pay-arrow-size;
		margin-right: $pay-arrow-offset-right;
	}

	&__cell--location {
		.store-name {
			font-size: $font-size-lg;
		}

		.iconfont {
			font-size: $pay-arrow-size;
			line-height: 100%;
			color: $color-primary;
		}
	}

	&__contact-tip {
		margin-left: $pay-contact-tip-margin-left;
		padding: $pay-contact-tip-padding-y $pay-contact-tip-padding-x;
		border: $pay-contact-tip-border-width solid $color-primary;
		color: $color-primary;
	}

	&__cart-name {
		width: $pay-cart-name-width;
	}

	&__cart-thumb {
		flex-shrink: 0;
		width: var(--pay-cart-thumb-size);
		height: var(--pay-cart-thumb-size);
	}

	&__notice {
		padding: $pay-notice-padding-y 0;
	}

	&__payment {
		margin-bottom: $pay-page-section-gap;
	}

	&__payment-row {
		&--disabled {
			color: $text-color-grey;
		}
	}

	&__payment-icon {
		margin-right: $pay-payment-gap;
		font-size: $pay-payment-icon-size;

		&--wechat {
			color: $pay-wechat-color;
		}

		&--alipay {
			color: $pay-alipay-color;
		}
	}

	&__checkbox {
		margin-left: $pay-checkbox-gap;
		font-size: $pay-checkbox-size;

		&--checked {
			color: $color-primary;
		}
	}

	&__remark {
		margin-bottom: $pay-remark-margin-bottom;
	}

	&__footer {
		z-index: $pay-footer-z-index;
		height: $pay-footer-height;
		box-shadow: $pay-footer-shadow;
	}

	&__footer-label {
		margin-left: $pay-footer-label-margin;
	}

	&__footer-btn {
		padding: 0 $pay-footer-btn-padding-x;
	}

	&__modal-close {
		width: $pay-modal-close-size;
		height: $pay-modal-close-size;
	}

	&__modal-title {
		margin-bottom: $pay-modal-title-margin-bottom;
	}

	&__modal-address {
		max-width: $pay-modal-address-max-width;
	}

	&__modal-change-btn {
		line-height: $pay-modal-change-btn-line-height;
		padding: 0 1em;
		white-space: nowrap;
	}

	&__modal-submit {
		width: 100%;
		line-height: $pay-modal-btn-line-height;
		border-radius: $pay-modal-btn-radius !important;
	}
}
</style>
