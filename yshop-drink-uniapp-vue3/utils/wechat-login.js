import { userAuthSession } from '@/api/auth'

let pendingSession

// Share an in-flight exchange across App launch/show and the login page.
// A failed attempt is released, but never automatically retried.
export function loginWechatSession() {
  if (!pendingSession) {
    pendingSession = (async () => {
      const result = await uni.login({ provider: 'weixin' }).catch(error => {
        throw { errMsg: error?.errMsg, errCode: error?.errCode, authStage: 'wechat' }
      })
      if (!result?.code) throw { authStage: 'wechat' }
      const session = await userAuthSession({ code: result.code })
      if (!session?.openId) throw { authStage: 'exchange' }
      return session
    })().finally(() => { pendingSession = null })
  }
  return pendingSession
}
