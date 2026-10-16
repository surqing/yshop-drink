// Instrument only a disposable current-source compilation, before App.onLaunch.
;(function () {
  const base = __OWNED_BASE__
  const request = wx.request
  const state = { requests: [], orderIds: [], authResponses: 0, dropNextOrderResponse: false,
    dropped: false, financialRequests: 0, blockedExternalRequests: 0 }
  const save = () => wx.setStorageSync('__e2eState', state)
  wx.__e2e = state
  wx.removeStorageSync('__e2eFault')
  save()
  for (const key of ['accessToken', 'userinfo', 'selectedStore', 'cart', 'checkoutSubmission', 'mycoupon']) wx.removeStorageSync(key)
  wx.login = function (options) { options.fail && options.fail({ errMsg: 'E2E_PLATFORM_PROOF_NOT_PREPARED' }) }
  wx.requestPayment = function (options) { state.financialRequests++; save(); options.fail && options.fail({ errMsg: 'E2E_FINANCIAL_FROZEN' }) }
  wx.request = function (options) {
    const url = String(options.url || '')
    if (!url.startsWith(base + '/app-api/') || /\/order\/pay|\/pay\/order|\/prepay|\/refund|\/recharge|\/notify|\/wechat-v3\//.test(url)) {
      state.blockedExternalRequests++; save(); options.fail && options.fail({ errMsg: 'E2E_OWNED_API_ONLY' }); return { abort() {} }
    }
    const record = { path: url.slice(base.length).split('?')[0], method: options.method || 'GET' }
    if (url.endsWith('/order/create')) record.data = typeof options.data === 'string' ? JSON.parse(options.data) : JSON.parse(JSON.stringify(options.data || {}))
    state.requests.push(record); save()
    const success = options.success
    options.success = function (response) {
      record.responseCode = response.data && response.data.code
      if (url.endsWith('/member/auth/auth-session')) state.authResponses++
      if (url.endsWith('/order/create') && response.data && response.data.code === 0) {
        state.orderIds.push(response.data.data.orderId)
        if ((wx.getStorageSync('__e2eFault') || {}).dropNextOrderResponse) {
          state.dropNextOrderResponse = false; state.dropped = true; wx.removeStorageSync('__e2eFault'); save()
          options.fail && options.fail({ errMsg: 'E2E_COMMITTED_RESPONSE_LOSS' }); return
        }
      }
      save(); success && success(response)
    }
    return request.call(wx, options)
  }
})()
