// Translate only reviewed business codes; never display backend messages or SDK objects.
const messages = {
  1002013000: '手机号不存在，请检查后重试',
  1002013001: '短信发送暂时不可用，请稍后重试',
  1002013002: '短信发送暂时不可用，请稍后重试',
  1002014000: '验证码不存在，请先获取验证码',
  1002014001: '验证码已过期，请重新获取',
  1002014002: '验证码已使用，请重新获取',
  1002014003: '验证码不正确，请重新输入',
  1002014004: '今日验证码发送次数已达上限，请明天再试',
  1002014005: '验证码发送过于频繁，请稍后再试',
  1002014006: '手机号已被使用',
  1002014007: '请先完成验证码验证'
}

export function normalizeSmsError(error) {
  const raw = error?.smsCode ?? error?.data?.code ?? error?.response?.data?.code
  const parsed = raw == null ? NaN : Number(raw)
  const code = Number.isSafeInteger(parsed) ? parsed : null
  const status = Number(error?.status ?? error?.statusCode ?? 0)
  return { smsCode: code, status: Number.isFinite(status) ? status : 0,
    category: error?.category || (code !== null || status > 0 ? 'business' : 'network') }
}

export function describeSmsError(error, action = 'login') {
  const safe = normalizeSmsError(error)
  let message = messages[safe.smsCode]
  if (safe.category === 'network') message = '网络连接失败，请检查网络后重试'
  else if (safe.smsCode === 400) message = action === 'send' ? '手机号格式不正确，请检查后重试' : '手机号或验证码格式不正确'
  else if (safe.smsCode === 401) message = '登录状态已失效，请重新登录'
  if (!message) message = action === 'send' ? '验证码发送失败，请稍后重试' : '短信登录失败，请稍后重试'
  return { category: safe.category === 'network' ? 'network' : 'business', code: safe.smsCode, message }
}

export function reportSmsError(error, action = 'login') {
  const safe = describeSmsError(error, action)
  console.warn('[sms]', { category: safe.category, code: safe.code })
  uni.showToast({ title: safe.message, icon: 'none', duration: 2000 })
  return safe
}
