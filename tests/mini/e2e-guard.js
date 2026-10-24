// Instrument only a disposable current-source compilation, before App.onLaunch.
;(function () {
  const base = __OWNED_BASE__
  const request = wx.request
  const state = { requests: [], orderIds: [], authResponses: 0, dropNextOrderResponse: false,
    dropped: false, scoreDropped: false, financialRequests: 0, blockedExternalRequests: 0, unpreparedLogins: 0,
    nativeModalsOpened: 0, nativeModalsConfirmed: 0 }
  const save = () => wx.setStorageSync('__e2eState', state)
  wx.__e2e = state
  wx.removeStorageSync('__e2eFault')
  save()
  for (const key of ['accessToken', 'userinfo', 'selectedStore', 'cart', 'checkoutSubmission', 'checkoutSubmissionByMember', 'checkoutLegacySubmission', 'mycoupon', 'score_product']) wx.removeStorageSync(key)
  if (wx.getStorageInfoSync) for (const key of wx.getStorageInfoSync().keys) if (key.startsWith('scoreSubmission:v1:')) wx.removeStorageSync(key)
  wx.login = function (options) { state.unpreparedLogins++; save(); options.fail && options.fail({ errMsg: 'E2E_PLATFORM_PROOF_NOT_PREPARED' }) }
  wx.requestPayment = function (options) { state.financialRequests++; save(); options.fail && options.fail({ errMsg: 'E2E_FINANCIAL_FROZEN' }) }
  const showModal = wx.showModal
  wx.showModal = function (options) {
    if (options.title !== '取消订单') return showModal.call(wx, options)
    state.nativeModalsOpened++; save()
    const success = options.success
    return showModal.call(wx, { ...options, success(result) {
      if (result.confirm) state.nativeModalsConfirmed++
      save(); success && success(result)
    } })
  }
  wx.request = function (options) {
    const url = String(options.url || '')
    if (!url.startsWith(base + '/app-api/') || /\/order\/pay|\/pay\/order|\/prepay|\/refund|\/recharge|\/notify|\/wechat-v3\//.test(url)) {
      state.blockedExternalRequests++; save(); options.fail && options.fail({ errMsg: 'E2E_OWNED_API_ONLY' }); return { abort() {} }
    }
    const record = { path: url.slice(base.length).split('?')[0], method: options.method || 'GET' }
    if (url.endsWith('/order/create') || url.endsWith('/score-order/submit')) record.data = typeof options.data === 'string' ? JSON.parse(options.data) : JSON.parse(JSON.stringify(options.data || {}))
    if (url.endsWith('/order/take')) {
      const body = typeof options.data === 'string' ? JSON.parse(options.data) : options.data || {}
      record.data = { uni: body.uni, expectedVersion: body.expectedVersion, requestKey: body.requestKey }
    }
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
      if (url.endsWith('/score-order/submit') && response.data && response.data.code === 0 && (wx.getStorageSync('__e2eFault') || {}).dropNextScoreResponse) {
        state.scoreDropped = true; wx.removeStorageSync('__e2eFault'); save()
        options.fail && options.fail({ errMsg: 'E2E_COMMITTED_SCORE_RESPONSE_LOSS' }); return
      }
      save(); success && success(response)
    }
    return request.call(wx, options)
  }
})()
