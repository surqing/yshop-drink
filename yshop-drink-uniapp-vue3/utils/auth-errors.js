// Never forward an SDK/request object to a logger or to a user-facing toast.
export function describeAuthError(error, stage = 'exchange') {
  const message = String(error?.errMsg || error?.message || '')
  const status = Number(error?.status || error?.statusCode || 0)
  const rawCode = error?.authCode ?? error?.data?.code ?? error?.response?.data?.code ?? error?.errCode
  const code = typeof rawCode === 'number' && Number.isSafeInteger(rawCode) ? rawCode : null
  let category = 'backend'
  if (/cancel|deny|denied|refuse/i.test(message)) category = 'cancelled'
  else if (error?.authCategory === 'network' || /network|timeout|request:fail/i.test(message) || status < 0) category = 'network'
  else if (stage === 'wechat' || stage === 'phone') category = 'wechat'
  const messages = {
    cancelled: '已取消微信授权',
    wechat: '微信授权暂时不可用，请稍后重试',
    network: '网络连接失败，请检查网络后重试',
    backend: '登录暂时未成功，请稍后重试'
  }
  return { category, code, message: messages[category] }
}

export function reportAuthError(error, stage = 'exchange') {
  const result = describeAuthError(error, stage)
  console.warn('[auth]', { category: result.category, code: result.code })
  uni.showToast({ title: result.message, icon: 'none', duration: 2000 })
  return result
}
