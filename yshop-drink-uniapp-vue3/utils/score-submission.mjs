const KEY = /^[A-Za-z0-9_-]{16,64}$/
const STATES = new Set(['ready', 'submitting', 'unknown', 'failed'])
const copy = value => value == null ? value : JSON.parse(JSON.stringify(value))
function id(value) {
  const text = String(value ?? '')
  if (!/^[0-9]+$/.test(text) || !/[1-9]/.test(text)) throw Error('SCORE_PARAM_INVALID')
  return text.replace(/^0+/, '')
}
function payloadOf(value) { return {productId:id(value?.productId),addressId:id(value?.addressId),num:1} }
function equal(a,b) { return JSON.stringify(a) === JSON.stringify(b) }
export function scoreFailureIsDefinite(error) {
  // Only known business rejections mean the transaction definitely rolled back.
  return [400,1008018001,1008018002,1008018003,1008018004,1004001000,1004004000,1004004001].includes(error?.data?.code)
}
function newKey() { return `score_${Date.now().toString(36)}_${Math.random().toString(36).slice(2)}_${Math.random().toString(36).slice(2)}` }

export function createScoreSubmission({storage, memberId, send, generateKey=newKey, onChange=()=>{}}) {
  let member
  try { member=id(memberId) } catch { throw Error('SCORE_MEMBER_REQUIRED') }
  const slot=`scoreSubmission:v1:${member}`
  let saved=storage.getStorageSync(slot), intent=null, state='ready', message='', flight=null
  function validate(value) {
    let valid=false
    try {valid=value.version===1 && value.memberId===member && KEY.test(value.idempotencyKey) && STATES.has(value.state) && equal(value.payload,payloadOf(value.payload))} catch {}
    if(!valid)throw Error('SCORE_INTENT_RECOVERY_REQUIRED')
  }
  function adopt(value) {
    validate(value)
    intent={idempotencyKey:value.idempotencyKey,payload:copy(value.payload),display:copy(value.display || {})}
    state=value.state==='submitting'?'unknown':value.state
    message=state==='unknown'?'上次兑换结果待确认，请使用原请求重试或查看兑换记录':value.message || ''
  }
  if(saved)adopt(saved)
  function syncDurable() {
    const value=storage.getStorageSync(slot)
    if(!value)return false
    validate(value)
    const changed=value.idempotencyKey!==intent?.idempotencyKey
    if(changed || (!flight && ['submitting','unknown'].includes(value.state))) {adopt(value);onChange()}
    return changed
  }
  function persist(nextState,nextMessage='',expectedKey=intent?.idempotencyKey) {
    const durable=storage.getStorageSync(slot)
    if(durable && durable.idempotencyKey!==expectedKey)throw Error('SCORE_DURABLE_INTENT_CHANGED')
    storage.setStorageSync(slot,{version:1,memberId:member,...copy(intent),state:nextState,message:nextMessage})
    state=nextState;message=nextMessage;onChange()
  }
  const machine={
    get state(){return state}, get message(){return message}, get intent(){return copy(intent)},
    begin(value,display={}) {
      const payload=payloadOf(value)
      if(!flight)syncDurable()
      if(intent && ['submitting','unknown'].includes(state)) {
        if(!equal(intent.payload,payload))throw Error('UNRESOLVED_SCORE_INTENT')
        return copy(intent)
      }
      if(intent && equal(intent.payload,payload))return copy(intent)
      const key=generateKey();if(!KEY.test(key))throw Error('SCORE_KEY_INVALID')
      const prior=intent;intent={idempotencyKey:key,payload,display:copy(display)}
      try {persist('ready','',prior?.idempotencyKey)} catch(error) {intent=prior;throw error}
      return copy(intent)
    },
    submit() {
      if(flight)return flight
      try {if(syncDurable())return Promise.reject(Error('SCORE_DURABLE_INTENT_CHANGED'))} catch(error) {return Promise.reject(error)}
      if(!intent)return Promise.reject(Error('SCORE_INTENT_REQUIRED'))
      try {persist('submitting')} catch(error) {return Promise.reject(error)}
      const request={...copy(intent.payload),idempotencyKey:intent.idempotencyKey}
      // Reserve the flight before invoking transport (including synchronous errors).
      let resolveFlight,rejectFlight
      flight=new Promise((resolve,reject)=>{resolveFlight=resolve;rejectFlight=reject})
      const current=flight
      let response
      try {response=send(request)} catch(error) {response=Promise.reject(error)}
      Promise.resolve(response).then(result=>{
        if(result!==true)throw Error('SCORE_RESULT_UNKNOWN')
        state='success';message='兑换成功';intent=null
        // If storage removal fails, the persisted original key remains safe to replay.
        try {if(storage.getStorageSync(slot)?.idempotencyKey===request.idempotencyKey)storage.removeStorageSync(slot)} catch {}
        onChange();resolveFlight(true)
      }).catch(error=>{
        const next=scoreFailureIsDefinite(error)?'failed':'unknown'
        const detail=next==='failed'?(error?.data?.msg || '本次兑换未成功，请检查商品、地址和积分后重试'):'兑换结果待确认，请使用原请求重试或查看兑换记录'
        try {persist(next,detail)} catch {state='unknown';message='兑换结果待确认，请使用原请求重试';onChange()}
        rejectFlight(error)
      }).finally(()=>{flight=null})
      return current
    }
  }
  return machine
}
