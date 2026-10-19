import api from './api'

export function userLogout() {
  return api.post('/member/auth/logout', {}, { login: true })
}

/**
 * 使用手机 + 验证码登录
 */
export function userLogin(data) {
  return api.post('/member/auth/sms-login', data, { login: false, smsError: 'login' })
}

/**
 * 使用手机 + 验证码登录  member/auth/send-sms-code
 */
export function smsSend(data) {
  return api.post('/member/auth/send-sms-code', data, { login: false, smsError: 'send' })
}

/**
 * 小程序 member/auth/weixin-mini-app-login
 */
export function userLoginForWechatMini(data) {
  return api.post('/member/auth/weixin-mini-app-login', data, { login: false, authError: true })
}

/**
 * userAuthSession
 */
export function userAuthSession(data) {
  return api.post('/member/auth/auth-session', data, { login: false, authError: true })
}

/**
 * wechatAuth
 */
export function wechatAuth(data) {
  return api.get('/member/auth/auth-wechat-login', data, { login: false, authError: true })
}
