const FULFILLMENT_LABELS = Object.freeze({
	WAIT_ACCEPT: '待接单',
	ACCEPTED: '已接单',
	PREPARING: '制作中',
	READY: '待取餐',
	COLLECTED: '已取餐'
})

function fulfillmentLabel(status) {
	return FULFILLMENT_LABELS[status] || (status === 'LEGACY' ? '历史订单' : '待核对')
}

export function pickupStatusView(order = {}) {
	const paid = Number(order.paid) === 1
	const refundStatus = Number(order.refundStatus || 0)
	const fulfillmentStatus = order.fulfillmentStatus
	const hasCurrentFulfillment = paid && refundStatus < 2
		&& fulfillmentStatus && fulfillmentStatus !== 'LEGACY'
	const fulfillment = hasCurrentFulfillment ? fulfillmentLabel(fulfillmentStatus) : ''
	const paymentOrRefundTitle = !paid
		? (order.statusDto?.title || '未支付')
		: refundStatus === 1
			? (order.statusDto?.title || '申请退款中')
			: refundStatus >= 2
				? (order.statusDto?.title || '已退款')
				: ''
	const primary = paymentOrRefundTitle
		|| (fulfillment ? fulfillment : order.statusDto?.title || fulfillmentLabel(fulfillmentStatus))

	return {
		primary,
		fulfillment,
		showFulfillmentSeparately: Boolean(paymentOrRefundTitle && refundStatus === 1 && fulfillment)
	}
}
