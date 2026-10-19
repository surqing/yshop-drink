import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
const root=process.env.YSHOP_AUTH_SOURCE || new URL('../yshop-drink-uniapp-vue3/',import.meta.url)
const read=name=>fs.readFileSync(typeof root==='string'?`${root}/${name}`:new URL(name,root),'utf8')
const strip=source=>source.replace(/import\s[\s\S]*?\sfrom\s*['"][^'"]+['"];?/g,'')
function fixture(reject=false) {
 const requests=[],cleared=[],toasts=[];let resolve
 const pending=new Promise(done=>{resolve=done})
 const api={post:async(...args)=>{requests.push(args);if(reject)throw Error('synthetic transport failure');return pending}}
 const auth=vm.createContext({api});vm.runInContext(strip(read('api/auth.js')).replace(/export\s+/g,''),auth)
 const main={SET_MEMBER:value=>cleared.push(['member',value]),SET_TOKEN:value=>cleared.push(['token',value])}
 const context=vm.createContext({ref:value=>({value}),useMainStore:()=>main,storeToRefs:()=>({}),
  userLogout:auth.userLogout,uni:{removeStorageSync:key=>cleared.push(['storage',key]),redirectTo:()=>{}},setTimeout:()=>0})
 const script=read('pages-user/login/logout.vue').match(/<script setup>([\s\S]*?)<\/script>/)[1]
 vm.runInContext(strip(script)+'\nuToast.value={show: value=>globalThis.captureToast(value)};globalThis.runLogout=logout;',context)
 context.captureToast=value=>toasts.push(value)
 return {run:()=>context.runLogout(),requests,cleared,toasts,resolve}
}
test('logout awaits the authenticated server revocation before clearing local credentials',async()=>{
 const page=fixture();const work=page.run()
 assert.equal(page.requests.length,1)
 assert.equal(page.requests[0][0],'/member/auth/logout')
 assert.equal(page.requests[0][2]?.login,true)
 assert.equal(page.cleared.length,0)
 page.resolve(true);await work
 assert.ok(page.cleared.some(row=>row[0]==='token'&&row[1]===''))
})
test('failed revocation keeps the session available for retry',async()=>{
 const page=fixture(true);await page.run()
 assert.equal(page.requests.length,1);assert.equal(page.cleared.length,0)
 assert.ok(page.toasts.some(value=>value.type==='error'))
})
