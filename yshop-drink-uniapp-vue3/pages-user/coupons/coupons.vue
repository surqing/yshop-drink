<template>
  <uv-navbar :fixed="false" title="优惠券" left-arrow @leftClick="$onClickLeft" />
  <view class="container coupons-page">
    <view class="coupons-exchange"><input v-model="exchange_code" placeholder="公共兑换码" maxlength="32" /><button size="mini" :disabled="!!claiming" @tap="exchange">兑换</button></view>
    <view class="coupons-tabbar"><view class="coupons-tab" v-for="(tab,index) in tabs" :key="index" :class="{'coupons-tab--active':activeTabIndex===index}" @tap="handleTab(index)"><view class="coupons-tab__title">{{tab}}</view></view></view>
    <view class="px-20 text-color-assist font-size-sm">当前门店：{{store.name || '未选择'}}；已领券权益不随活动编辑改变。</view>
    <scroll-view scroll-y class="coupons-list" @scrolltolower="load(false)">
      <uv-empty v-if="!items.length && !loading" mode="coupon" />
      <view class="coupons-list__wrapper">
        <view class="coupons-item" v-for="item in items" :key="item.id" @tap="openDetailModal(item)">
          <view class="coupons-ticket__body">
            <view class="coupons-ticket__left"><image v-if="item.image" class="coupons-ticket__picture" :src="item.image" mode="aspectFill" /><view class="coupons-ticket__intro"><view class="coupons-ticket__value">￥<text class="coupons-ticket__amount">{{item.value}}</text><view>商品满{{item.least}}减{{item.value}}</view></view><view class="coupons-ticket__type">{{item.title}}</view><view class="coupons-ticket__date">{{formatDateTime(item.startTime,'yyyy-MM-dd')}}—{{formatDateTime(item.endTime,'yyyy-MM-dd')}}</view><view>{{item.shopName || '范围待审核'}}</view><view>{{typeInfo(item.type)}}</view></view></view>
            <view class="coupons-ticket__right" @tap.stop="">
              <view v-if="activeTabIndex===1 && item.claimReason==='AVAILABLE'" class="coupons-ticket__btn coupons-ticket__btn--use immediate-use" @tap="receive(item)">{{claiming===String(item.id)?'领取中':'立即领取'}}</view>
              <view v-else-if="activeTabIndex===0 && item.reservationState==='AVAILABLE'" class="coupons-ticket__btn coupons-ticket__btn--use immediate-use" @tap="useCoupon">立即使用</view>
              <view v-else class="coupons-ticket__btn coupons-ticket__btn--used">{{label(item)}}</view>
            </view>
          </view>
        </view>
      </view>
    </scroll-view>
    <modal custom :show="detailModalVisible" @cancel="detailModalVisible=false" width="90%" title="优惠券规则">
      <view class="modal-content"><view>{{coupon.title}}</view><view>商品满{{coupon.least}}减{{coupon.value}}；不抵配送费</view><view>使用：{{formatDateTime(coupon.startTime)}}—{{formatDateTime(coupon.endTime)}}</view><view v-if="activeTabIndex===1">领取：{{formatDateTime(coupon.claimStartTime || coupon.startTime)}}—{{formatDateTime(coupon.claimEndTime || coupon.endTime)}}</view><view>适用：{{coupon.shopName}}，{{typeInfo(coupon.type)}}</view><view v-if="activeTabIndex===1">每人限领{{coupon.limit}}张，已领{{coupon.claimedCount || 0}}张</view><view>{{coupon.couponKind==='NEW_USER'?'服务端注册资格，全商家一次新人权益；不是首次消费。':''}}</view><view>{{coupon.instructions}}</view><view>{{label(coupon)}}</view></view>
    </modal>
  </view>
</template>
<script setup>
import {ref,computed,watch} from 'vue'
import {onShow,onPullDownRefresh} from '@dcloudio/uni-app'
import {storeToRefs} from 'pinia'
import {useMainStore} from '@/store/store'
import {formatDateTime} from '@/utils/util'
import {couponReceive,couponMine,couponIndexApi} from '@/api/coupon'
import {couponLabels,claimRequestKey,claimSucceeded} from '@/utils/coupon-context'
const main=useMainStore()
const {store,isLogin,member}=storeToRefs(main)
const tabs=['我的优惠券','可领取活动']
const activeTabIndex=ref(0), myCoupons=ref([]), notCoupons=ref([]), loading=ref(false), claiming=ref('')
const items=computed(()=>activeTabIndex.value===0?myCoupons.value:notCoupons.value)
const exchange_code=ref(''), coupon=ref({}), detailModalVisible=ref(false)
let generation=0,page=1,finished=false
const label=item=>couponLabels[activeTabIndex.value===0?item.reservationState:item.claimReason] || '需审核'
const typeInfo=type=>Number(type)===1?'自取':Number(type)===2?'外卖':'自取及外卖'
const load=async(reset=true)=>{
  if(!isLogin.value)return
  if(reset){generation++;page=1;finished=false;myCoupons.value=[];notCoupons.value=[]}
  else if(loading.value || finished)return
  const current=generation, shop=store.value.id, tab=activeTabIndex.value, requestedPage=page
  loading.value=true
  try {
    const data=tab===0?await couponMine({shopId:shop,type:3,page:requestedPage,pagesize:20}):await couponIndexApi({id:shop,page:requestedPage,pagesize:20})
    if(current!==generation || String(shop)!==String(store.value.id) || tab!==activeTabIndex.value)return
    const target=tab===0?myCoupons:notCoupons
    target.value=[...target.value,...data.filter(c=>!target.value.some(old=>old.id===c.id))]
    page=requestedPage+1;finished=data.length<20
  }catch{uni.showToast({title:'网络失败，可下拉重试',icon:'none'})}
  finally{if(current===generation)loading.value=false;uni.stopPullDownRefresh()}
}
const handleTab=index=>{if(activeTabIndex.value!==index){activeTabIndex.value=index;load(true)}}
onShow(()=>load(true));onPullDownRefresh(()=>load(true));watch(()=>store.value.id,()=>load(true))
const openDetailModal=item=>{coupon.value=item;detailModalVisible.value=true}
const useCoupon=()=>uni.switchTab({url:'/pages/menu/menu'})
const issue=async(id,code)=>{
  if(claiming.value)return
  const identity=id?`activity:${id}`:`code:${code}`,uid=member.value.id
  if(!uid){uni.showToast({title:'请先登录',icon:'none'});return}
  const key=claimRequestKey(uni,uid,identity)
  claiming.value=String(id || 'code')
  try {
    await couponReceive({...(id?{id}:{code}),requestKey:key})
    claimSucceeded(uni,uid,identity);detailModalVisible.value=false
    uni.showToast({title:id?'领取成功':'兑换成功',icon:'success'});await load(true)
  }catch{ /* Preserve request key on network/business failure. Backend supplies explicit reason. */ }
  finally{claiming.value=''}
}
const receive=item=>issue(item.id,null)
const exchange=()=>{const code=exchange_code.value.trim();if(!code){uni.showToast({title:'请输入公共兑换码',icon:'none'});return}return issue(null,code)}
</script>
<style lang="scss" scoped>
// 优惠券页局部 token（与 uni.scss 全局变量配合）
$coupons-exchange-height: 100rpx;
$coupons-exchange-input-width: 70%;
$coupons-tabbar-height: 80rpx;
$coupons-tab-indicator-height: 5rpx;
$coupons-tab-title-padding-y: 15rpx;
$coupons-list-offset-nav: 120rpx;
$coupons-list-offset-header: 200rpx;
$coupons-list-padding-x: $spacing-row-base;
$coupons-item-gap: $spacing-row-lg;
$coupons-item-radius: $border-radius-base;
$coupons-notch-size: 30rpx;
$coupons-notch-offset: 65rpx;
$coupons-ticket-radius: 20rpx;
$coupons-ticket-divider: $border-color-light;
$coupons-ticket-left-width: 70%;
$coupons-ticket-right-width: 30%;
$coupons-ticket-padding: $spacing-row-base;
$coupons-ticket-right-padding-y: 40rpx;
$coupons-picture-size: 190rpx;
$coupons-amount-font-size: 60rpx;
$coupons-date-font-size: 20rpx;
$coupons-intro-gap: 10rpx;
$coupons-btn-radius: 40rpx;
$coupons-btn-padding-x: 20rpx;
$coupons-btn-line-height: 40rpx;
$coupons-btn-margin-left: 20rpx;

/* #ifdef H5 */
page {
	height: 100%;
}
/* #endif */

.coupons-page {
	--coupons-picture-size: #{$coupons-picture-size};
	--coupons-list-offset-nav: #{$coupons-list-offset-nav};
	--coupons-list-offset-header: #{$coupons-list-offset-header};

	display: flex;
	flex-direction: column;
}

.coupons-exchange {
	flex-shrink: 0;
	display: flex;
	align-items: center;
	justify-content: center;
	height: $coupons-exchange-height;
	background-color: $text-color-white;

	&__input {
		width: $coupons-exchange-input-width;
	}
}

.coupons-tabbar {
	flex-shrink: 0;
	display: flex;
	align-items: center;
	justify-content: center;
	width: 100%;
	height: $coupons-tabbar-height;
}

.coupons-tab {
	flex: 1;
	display: flex;
	flex-direction: column;
	align-items: center;
	justify-content: center;
	height: 100%;
	font-size: $font-size-base;
	color: $text-color-base;
	position: relative;

	&__title {
		padding: $coupons-tab-title-padding-y 0;
	}

	&--active {
		color: $color-primary;

		.coupons-tab__title {
			border-bottom: $coupons-tab-indicator-height solid $color-primary;
		}
	}
}

.coupons-list {
	height: calc(100vh - var(--coupons-list-offset-nav) - var(--coupons-list-offset-header));

	/* #ifdef H5 */
	height: calc(100vh - var(--coupons-list-offset-nav) - var(--coupons-list-offset-header) - 44px);
	/* #endif */

	&__wrapper {
		display: flex;
		flex-direction: column;
		padding: 0 $coupons-list-padding-x;
	}
}

.coupons-item {
	display: flex;
	flex-direction: column;
	position: relative;
	margin-bottom: $coupons-item-gap;
	background-color: $text-color-white;
	border-radius: $coupons-item-radius;
	box-shadow: $box-shadow;

	&::before,
	&::after {
		content: '';
		position: absolute;
		bottom: $coupons-notch-offset;
		width: $coupons-notch-size;
		height: $coupons-notch-size;
		background-color: $bg-color;
		border-radius: $border-radius-circle;
	}

	&::before {
		left: calc(-1 * #{$coupons-notch-size} / 2);
	}

	&::after {
		right: calc(-1 * #{$coupons-notch-size} / 2);
	}
}

.coupons-ticket {
	background-color: $text-color-white;

	&__body {
		display: flex;
	}

	&__left {
		display: flex;
		width: $coupons-ticket-left-width;
		padding: $coupons-ticket-padding;
		background-color: $text-color-white;
		border-radius: $coupons-ticket-radius;
		border-right: dashed 2rpx $coupons-ticket-divider;
	}

	&__picture {
		flex-shrink: 0;
		width: var(--coupons-picture-size);
		height: var(--coupons-picture-size);
		border-radius: $coupons-ticket-radius;
	}

	&__intro {
		margin-left: $coupons-intro-gap;
		min-width: 0;
	}

	&__value {
		font-size: $font-size-base;
		color: $uv-warning;

		.coupons-ticket__amount {
			margin-right: $coupons-intro-gap;
			font-size: $coupons-amount-font-size;
			font-weight: bold;
		}
	}

	&__type {
		font-size: $font-size-base;
		color: $uv-info-dark;
	}

	&__date {
		margin-top: $coupons-intro-gap;
		font-size: $coupons-date-font-size;
		color: $uv-info-dark;
	}

	&__right {
		display: flex;
		align-items: center;
		width: $coupons-ticket-right-width;
		padding: $coupons-ticket-right-padding-y $coupons-ticket-padding;
		background-color: $text-color-white;
		border-radius: $coupons-ticket-radius;
	}

	&__btn {
		height: auto;
		margin-left: $coupons-btn-margin-left;
		padding: 0 $coupons-btn-padding-x;
		font-size: $font-size-sm;
		line-height: $coupons-btn-line-height;
		border-radius: $coupons-btn-radius;
		color: $text-color-white !important;

		&--use {
			background-color: $uv-warning !important;
		}

		&--used {
			background-color: $uv-info-dark !important;
		}
	}
}
</style>
