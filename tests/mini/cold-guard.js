// Disposable compiled-app instrumentation, installed before application cold boot.
;(function () {
  const base = __OWNED_BASE__
  const request = wx.request
  const counts = wx.__qualityCounts = { paymentRequests: 0, blockedFinancialRequests: 0, localSuccess: 0, orders: 0, dropped: false }
  const save = () => wx.setStorageSync('__qualityCounts', counts)
  save()
  wx.setStorageSync('accessToken', __MEMBER_TOKEN__)
  wx.setStorageSync('userinfo', { id: 101, nickname: 'synthetic-member', mobile: '13800000001' })
  for (const key of ['selectedStore', 'cart', 'checkoutSubmission', 'mycoupon']) wx.removeStorageSync(key)
  wx.requestPayment = function (o) { counts.paymentRequests++; save(); o.fail && o.fail({ errMsg: 'FINANCIAL_FROZEN' }) }
  wx.login = function (o) { o.fail && o.fail({ errMsg: 'SYNTHETIC_IDENTITY_PREPARED_NO_PROVIDER_LOGIN' }) }
  wx.request = function (o) {
    const url = String(o.url || '')
    if (/\/order\/pay|\/pay\/order|\/prepay|\/refund|\/recharge|\/wechat-v3\//.test(url)) {
      counts.blockedFinancialRequests++; save(); o.fail && o.fail({ errMsg: 'FINANCIAL_FROZEN' }); return { abort() {} }
    }
    if (!url.startsWith(base + '/app-api/') || /\/member\/auth\/(?:weixin|wechat)/.test(url)) {
      o.fail && o.fail({ errMsg: 'OWNED_API_ONLY' }); return { abort() {} }
    }
    const success = o.success
    o.success = function (r) {
      if (r.data && r.data.code === 0) counts.localSuccess++
      if (url.endsWith('/order/create')) {
        counts.orders++
        if (r.data && r.data.code === 0 && r.data.data && r.data.data.orderId) {
          wx.__qualityOrderId = r.data.data.orderId
          wx.setStorageSync('__qualityOrderId', r.data.data.orderId)
          if (!counts.dropped) { counts.dropped = true; save(); o.fail && o.fail({ errMsg: 'SYNTHETIC_COMMITTED_RESPONSE_LOSS' }); return }
        }
      }
      save()
      success && success(r)
    }
    return request.call(wx, o)
  }
})()
