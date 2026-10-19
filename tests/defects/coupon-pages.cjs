const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto')
const automator=require(process.env.YSHOP_AUTOMATOR_PATH)
const ctx=JSON.parse(fs.readFileSync(process.env.YSHOP_MINI_CONTEXT,'utf8'))
const folder=path.dirname(process.env.YSHOP_MINI_REPORT)
const report={runId:ctx.runId,sourceSha:ctx.sourceSha,sourceDigest:ctx.sourceDigest,compiledSource:ctx.compiledSource,compiledHashesVerified:false,transport:'owned-loopback',result:'FAIL',cleanup:'NOT_RUN',sourceUnchanged:true,checks:[]}
const save=()=>fs.writeFileSync(process.env.YSHOP_MINI_REPORT,JSON.stringify(report,null,2),{mode:0o600})
const wait=ms=>new Promise(r=>setTimeout(r,ms));let mini
async function until(fn,timeout=20000){for(let deadline=Date.now()+timeout;Date.now()<deadline;){const v=await fn();if(v)return v;await wait(150)}throw Error('PAGE_CONDITION_TIMEOUT')}
async function check(name,fn){report.stage=name;save();const ok=!!await fn();report.checks.push({name,result:ok?'PASS':'FAIL',executed:true});save();if(!ok)throw Error('PAGE_ASSERTION_FAILED')}
async function coupon(page,selector='.coupons-item'){return until(async()=>{for(const e of await page.$$(selector))if((await e.text()).includes('QA Page Lifecycle'))return e;return null})}
;(async()=>{
 save();for(const [name,hash] of Object.entries(ctx.compiledHashes))if(crypto.createHash('sha256').update(fs.readFileSync(path.join(ctx.project,name))).digest('hex')!==hash)throw Error('COMPILED_HASH_MISMATCH');report.compiledHashesVerified=true
 report.stage='official-launch';save();mini=await automator.launch({projectPath:ctx.project,cliPath:ctx.cli,port:ctx.port,timeout:90000});await until(async()=>(await mini.pageStack()).length>0,90000)
 let page=await mini.reLaunch('/pages/index/index')
 await check('coupon lifecycle current app starts',async()=>!!await until(()=>page.$('.index-page')))
 page=await mini.navigateTo('/pages-checkout/shop/shop');const shops=await until(async()=>{const x=await page.$$('.shop-page__item');return x.length?x:null});for(const s of shops)if((await s.text()).includes('QA Shop A')){await(await s.$('.shop-page__body')).tap();break}
 page=await until(async()=>{const p=await mini.currentPage();return await p.$('.property_btn')?p:null})
 await check('coupon lifecycle real store selected',()=>mini.evaluate(()=>Number(wx.getStorageSync('selectedStore').id)===101))
 page=await mini.navigateTo('/pages-user/coupons/coupons');await(await until(()=>page.$$('.coupons-tab')))[1].tap()
 let row=await coupon(page);await(await row.$('.immediate-use')).tap()
 await check('coupon claimed through actual page',()=>until(async()=>(await(await coupon(page)).text()).includes('已达每人领取上限')))
 await(await page.$$('.coupons-tab'))[0].tap()
 await check('claimed coupon available on actual page',()=>until(async()=>!!await(await coupon(page)).$('.immediate-use')))
 await mini.switchTab('/pages/menu/menu');page=await until(async()=>{const p=await mini.currentPage();return await p.$('.property_btn')?p:null});await(await page.$('.property_btn')).tap();await(await until(()=>page.$('.add-to-cart-btn'))).tap()
 page=await mini.navigateTo('/pages-checkout/pay/pay');await until(()=>page.$('.pay-page__footer-amount'))
 // Navigate via the actual coupon row, preserving the checkout page beneath the selector.
 report.stage='open-coupon-selector';save()
 fs.writeFileSync(path.join(folder,'checkout-private.wxml'),await(await page.$('.pay-page')).outerWxml(),{mode:0o600})
 const link=await until(()=>page.getElementByXpath('//view[text()="优惠券"]'))
 await link.tap()
 page=await until(async()=>{const p=await mini.currentPage();return p.path==='pages-checkout/packages/index'?p:null})
 row=await coupon(page,'.packages-item');await(await row.$('.immediate-use')).tap();page=await until(async()=>{const p=await mini.currentPage();return p.path==='pages-checkout/pay/pay'?p:null})
 await check('coupon selection reduces checkout to fourteen',async()=>(await(await page.$('.pay-page__footer-amount')).text()).includes('14.00'))
 await(await page.$('.pay-page__footer-btn')).tap()
 await check('coupon order response loss retains retry identity',()=>until(()=>mini.evaluate(()=>wx.getStorageSync('__qualityCounts').dropped&&!!wx.getStorageSync('checkoutSubmission')&&wx.getStorageSync('cart').length===1)))
 await until(async()=>(await(await page.$('.pay-page__footer-btn')).text()).includes('提交订单'));await(await page.$('.pay-page__footer-btn')).tap()
 await check('coupon same-key retry opens unpaid order',()=>until(async()=>(await mini.currentPage()).path==='pages-order/orders/detail'))
 report.orderId=await mini.evaluate(()=>wx.getStorageSync('__qualityOrderId'));save()
 page=await mini.navigateTo('/pages-user/coupons/coupons')
 await check('reserved coupon is shown as awaiting payment',()=>until(async()=>(await(await coupon(page)).text()).includes('待付款预占')))
 fs.writeFileSync(path.join(folder,'payment-needed.json'),JSON.stringify({orderId:report.orderId}),{mode:0o600})
 await check('trusted synthetic completion and duplicate applied',()=>until(async()=>fs.existsSync(path.join(folder,'payment-done.json')),120000))
 await mini.navigateBack();page=await mini.navigateTo('/pages-user/coupons/coupons')
 await check('redeemed coupon is shown as used',()=>until(async()=>(await(await coupon(page)).text()).includes('已核销')))
 await mini.navigateBack();page=await mini.navigateTo('/pages-user/coupons/coupons')
 await check('page reentry retains redeemed server state',()=>until(async()=>(await(await coupon(page)).text()).includes('已核销')))
 const counts=await mini.evaluate(()=>wx.getStorageSync('__qualityCounts'))
 await check('coupon lifecycle uses no financial provider calls',async()=>counts.paymentRequests===0&&counts.blockedFinancialRequests===0&&counts.orders===2)
 report.paymentRequests=counts.paymentRequests;report.blockedFinancialRequests=counts.blockedFinancialRequests;report.result='PASS'
})().catch(async e=>{report.reasonType=e.name;report.reasonCode=/^(PAGE_|COMPILED_)/.test(e.message)?e.message:'OFFICIAL_AUTOMATION_FAILED';fs.writeFileSync(path.join(folder,'failure-private.log'),e.stack||String(e),{mode:0o600});if(mini)try{await mini.screenshot({path:path.join(folder,'failure.png')})}catch{};process.exitCode=1}).finally(async()=>{try{if(mini){await mini.evaluate(()=>{for(const k of ['accessToken','userinfo','selectedStore','cart','checkoutSubmission','mycoupon','__qualityCounts','__qualityOrderId'])wx.removeStorageSync(k)});await mini.close()}report.cleanup=mini?'PASS':'NOT_RUN'}catch{report.cleanup='FAIL';report.result='FAIL';process.exitCode=1}save();console.log(JSON.stringify({result:report.result,stage:report.stage,checks:report.checks.length,cleanup:report.cleanup}))})
