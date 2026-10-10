/* Actual WeChat pages, with measured local-only request admission. No order or coupon writes. */
const fs=require('node:fs'),path=require('node:path');
const ws=process.env.YSHOP_TEST_WORKSPACE||path.resolve(__dirname,'../../..');
const automator=require(path.join(ws,'.uniapp-dev/automation/node_modules/miniprogram-automator'));
const port=Number(process.env.YSHOP_API_PORT||48083),auto=Number(process.env.YSHOP_AUTOMATION_PORT||9421);
if(![48081,48082,48083].includes(port)||auto<1024||auto>65535)throw Error('Explicit local ports required');
const out=process.env.YSHOP_MINI_REPORT;
if(!out)throw Error('Private run-specific report path required');
const report={runId:process.env.YSHOP_QUALITY_RUN_ID||require('node:crypto').randomUUID(),checks:[],result:'FAIL',scope:'read-only pages and local cart; order/coupon writes intentionally excluded'};
const save=()=>fs.writeFileSync(out,JSON.stringify(report,null,2),{mode:0o600});
const pause=ms=>new Promise(r=>setTimeout(r,ms));
let mini;
async function element(page,selector){for(let i=0;i<75;i++){let e=await page.$(selector);if(e)return e;await pause(200)}throw Error('PAGE_ELEMENT_MISSING')}
async function check(name,fn){const ok=await fn();report.checks.push({name,ok:!!ok});save();if(!ok)throw Error('PAGE_ASSERTION_FAILED')}
const watchdog=setTimeout(()=>{report.failure='AUTOMATION_TIMEOUT';save();process.exit(1)},180000);
(async()=>{
  report.stage='connect';save();
  for(let i=0;i<40&&!mini;i++){try{mini=await automator.connect({wsEndpoint:`ws://127.0.0.1:${auto}`})}catch{await pause(300)}}
  if(!mini)throw Error('AUTOMATION_UNAVAILABLE');
  let exceptions=0;mini.on('exception',()=>exceptions++);
  report.stage='install-guard';save();
  let ready;for(let i=0;i<50&&!ready;i++){try{ready=await mini.currentPage()}catch{}if(!ready)await pause(200)}
  if(!ready)throw Error('INITIAL_PAGE_NOT_READY');
  await mini.evaluate(port=>{
    wx.__qualityOriginalRequest=wx.request;wx.__qualityOriginalPayment=wx.requestPayment;
    wx.__qualityCounts={paymentRequests:0,blockedWrites:0,requests:0,localApiSuccess:0};
    wx.request=function(o){const c=wx.__qualityCounts;c.requests++;const url=String(o.url||'');
      if(/\/order\/pay|\/pay\/order\/submit|\/wechat-v3\/prepay|\/refund|\/recharge/i.test(url)){c.paymentRequests++;o.fail?.({errMsg:'Quality safety guard'});return {abort(){}}}
      if(!url.startsWith(`http://127.0.0.1:${port}/app-api/`)||(o.method&&o.method.toUpperCase()!=='GET')){c.blockedWrites++;o.fail?.({errMsg:'Read-only quality run'});return {abort(){}}}
      const success=o.success;o.success=function(r){if(r.data?.code===0)c.localApiSuccess++;success?.(r)};
      return wx.__qualityOriginalRequest.call(wx,o);
    };
    wx.requestPayment=function(o){wx.__qualityCounts.paymentRequests++;o.fail?.({errMsg:'Payment frozen'})};
  },port);
  report.stage='homepage';save();
  let page=await mini.reLaunch('/pages/index/index');await element(page,'.index-page');
  await check('homepage renders',async()=>!!await page.$('.index-page'));
  page=await mini.navigateTo('/pages-checkout/shop/shop');await element(page,'.shop-page__item');
  await check('store list loaded from local API',async()=>(await page.$$('.shop-page__item')).length>0);
  const stores=await page.$$('.shop-page__item');await(await stores[0].$('.shop-page__body')).tap();await pause(1200);page=await mini.currentPage();
  await check('selected store catalog renders',async()=>!!await element(page,'.good'));
  await(await element(page,'.property_btn')).tap();await element(page,'.add-to-cart-btn');
  await check('SKU selector renders',async()=>!!await page.$('.add-to-cart-btn'));
  await(await page.$('.add-to-cart-btn')).tap();await pause(400);
  await check('cart belongs to selected store with server price',()=>mini.evaluate(()=>{const cart=wx.getStorageSync('cart')||[];const store=wx.getStorageSync('selectedStore');return cart.length>0&&cart.every(x=>Number(x.shopId)===Number(store?.id)&&Number(x.price)>0)}));
  report.counters=await mini.evaluate(()=>wx.__qualityCounts);
  await check('local API really responded',async()=>report.counters.localApiSuccess>0);
  await check('no payment attempt or runtime exception',async()=>report.counters.paymentRequests===0&&exceptions===0);
  report.exceptions=exceptions;report.paymentRequests={value:report.counters.paymentRequests,evidence:'MEASURED_WX_REQUEST_AND_REQUESTPAYMENT_ADMISSION',scope:'after guard installation through last assertion'};
  report.result='PASS';save();
})().catch(e=>{report.errorType=e.name;report.errorHints=['connect','evaluate','reLaunch','timeout','not defined','read only','not a function','closed'].filter(x=>String(e.message).includes(x));report.stackSites=String(e.stack).match(/[A-Za-z_-]+\.(?:c?js):[0-9]+:[0-9]+/g);report.failure='LOCAL_PAGE_AUTOMATION_FAILED';save();process.exitCode=1}).finally(async()=>{
  clearTimeout(watchdog);
  if(mini){try{await mini.evaluate(()=>{if(wx.__qualityOriginalRequest)wx.request=wx.__qualityOriginalRequest;if(wx.__qualityOriginalPayment)wx.requestPayment=wx.__qualityOriginalPayment})}catch{report.result='FAIL';report.cleanup='FAIL';save();process.exitCode=1}mini.disconnect()}
  console.log(JSON.stringify({result:report.result,checks:report.checks.length,paymentRequests:report.paymentRequests||{value:null,evidence:'INCOMPLETE'}}));
});
