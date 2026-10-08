/* Page-level local unpaid ordering smoke. No payment, identity output, or source patching. */
const fs = require('node:fs'), path = require('node:path'), {spawnSync} = require('node:child_process');
const workspace = path.resolve(__dirname, '../../..');
const automator = require(path.join(workspace, '.uniapp-dev/automation/node_modules/miniprogram-automator'));
const reportPath = path.join(workspace, '.uniapp-dev/logs/phase6a-smoke.json');
const port = Number(process.env.YSHOP_API_PORT || 48081);
if (![48081,48082].includes(port)) throw new Error('Local API required');
const report = {checks:[], uncaughtExceptions:0, paymentRequests:0, apiPort:port};
const save = () => fs.writeFileSync(reportPath,JSON.stringify(report,null,2),{mode:0o600});
const pause = ms => new Promise(r=>setTimeout(r,ms));
function database(input) {const r=spawnSync('/usr/bin/python3',[path.join(__dirname,'server-check.py')],{input:JSON.stringify(input),encoding:'utf8',timeout:15000});if(r.status)throw new Error('Server check failed');return JSON.parse(r.stdout);}
async function check(name,fn) {report.stage=name;save();const result=await Promise.race([fn(),pause(20000).then(()=>{throw new Error("Automation step timed out")})]);report.checks.push({name,...result});save();if(!result.ok)throw new Error('Business smoke assertion failed');return result;}
async function element(page, selector) {const deadline=Date.now()+20000;while(Date.now()<deadline){const e=await page.$(selector);if(e)return e;await pause(200);}report.missingSelector=selector;throw new Error('Expected element missing');}
async function run() {
  let mini;const deadline=Date.now()+20000;
  while(!mini){try{mini=await automator.connect({wsEndpoint:'ws://127.0.0.1:9420'});}catch{if(Date.now()>deadline)throw new Error('Tool unavailable');await pause(300);}}
  mini.on('exception',()=>{report.uncaughtExceptions++;save();});
  mini.on('console',e=>{if(/\[api\].*POST.*\/order\/pay/.test(JSON.stringify(e)))report.paymentRequests++;});
  const marker=database({action:'marker'}).marker;
  try {
    let initial;const readyDeadline=Date.now()+20000;
    while(!initial){try{initial=await Promise.race([mini.currentPage(),pause(2000).then(()=>null)]);}catch{}if(!initial?.path){initial=null;if(Date.now()>readyDeadline)throw new Error('Mini-program launch not ready');await pause(300);}}
    await pause(800);
    await check('persisted authenticated local member',()=>mini.evaluate(port=>new Promise(resolve=>wx.request({url:`http://127.0.0.1:${port}/app-api/member/user/get-info`,header:{Authorization:'Bearer '+wx.getStorageSync('accessToken')},success:r=>resolve({ok:r.data?.code===0&&!!r.data?.data?.id}),fail:()=>resolve({ok:false})})),port));
    await mini.evaluate(()=>{
      wx.__phase6aSafety={paymentRequests:0,creates:0,failNextCreate:false,lastOrderId:null};
      wx.__phase6aOriginalRequest=wx.request;
      wx.request=function(options){const state=wx.__phase6aSafety;
        if(/\/order\/pay|\/pay\/order\/submit|\/wechat-v3\/prepay/.test(options.url)){state.paymentRequests++;options.fail?.({errMsg:'Payment frozen by smoke safety guard'});return {abort(){}};}
        if(/\/order\/create$/.test(options.url)){state.creates++;if(state.failNextCreate){state.failNextCreate=false;options.fail?.({errMsg:'Synthetic local network failure'});options.complete?.({errMsg:'Synthetic local network failure'});return {abort(){}};}
          const originalSuccess=options.success;options.success=function(r){if(r.data?.data?.orderId)state.lastOrderId=r.data.data.orderId;originalSuccess?.(r);};}
        return wx.__phase6aOriginalRequest.call(wx,options);};
      wx.__phase6aOriginalPayment=wx.requestPayment;wx.requestPayment=function(options){wx.__phase6aSafety.paymentRequests++;options.fail?.({errMsg:'Payment frozen'});};
    });
    let page=initial.path==='pages/index/index'?initial:await mini.reLaunch('/pages/index/index');await element(page,'.index-page');
    await check('homepage renders',async()=>({ok:!!await page.$('.index-page')}));
    async function selectStore(id) {
      page=await mini.navigateTo('/pages-checkout/shop/shop');await element(page,'.shop-page__item');
      const name=await mini.evaluate(({id,port})=>new Promise(resolve=>wx.request({url:`http://127.0.0.1:${port}/app-api/store/list`,data:{lat:0,lng:0,kw:'',shop_id:0},success:r=>resolve(r.data?.data?.find(x=>Number(x.id)===id)?.name||null),fail:()=>resolve(null)})),{id,port});
      let selected;for(const card of await page.$$('.shop-page__item'))if(name&&(await card.text()).includes(name)){selected=card;break;}
      if(!selected)throw new Error('Expected local store unavailable');await (await selected.$('.shop-page__body')).tap();await pause(1000);page=await mini.currentPage();
    }
    async function addItem(){await element(page,'.property_btn');await (await page.$('.property_btn')).tap();await element(page,'.add-to-cart-btn');await (await page.$('.add-to-cart-btn')).tap();await pause(500);}
    await selectStore(2);await addItem();
    await check('store A SKU and cart use selected store',()=>mini.evaluate(()=>({ok:(wx.getStorageSync('cart')||[]).length>0&&(wx.getStorageSync('cart')||[]).every(x=>Number(x.shopId)===2&&x.price>0)})));
    await selectStore(3);
    await check('switch to store B clears old cart and submission',()=>mini.evaluate(()=>({ok:!(wx.getStorageSync('cart')||[]).length&&!wx.getStorageSync('checkoutSubmission')&&Number(wx.getStorageSync('selectedStore')?.id)===3})));
    await selectStore(2);await addItem();page=await mini.navigateTo('/pages-checkout/cart/cart');await element(page,'.cart-item');
    await check('cart page renders selected store item',async()=>({ok:(await page.$$('.cart-item')).length>0}));
    page=await mini.navigateTo('/pages-checkout/pay/pay');await element(page,'.pay-page__footer-btn');await pause(600);
    await check('checkout exposes submit without payment selectors',async()=>({ok:(await (await page.$('.pay-page__footer-btn')).text()).includes('提交订单')&&!await page.$('.pay-page__payment')}));
    await mini.evaluate(()=>{wx.__phase6aSafety.failNextCreate=true;});
    await (await page.$('.pay-page__footer-btn')).tap();await pause(1800);
    await check('failed submission retains cart and idempotency key',()=>mini.evaluate(()=>{const key=wx.getStorageSync('checkoutSubmission');wx.__phase6aSafety.retryKey=key?.key;return {ok:!!key?.key&&(wx.getStorageSync('cart')||[]).length>0&&wx.__phase6aSafety.creates===1};}));
    await (await page.$('.pay-page__footer-btn')).tap();
    let reached=false;for(let i=0;i<60;i++){await pause(200);page=await mini.currentPage();if(page.path==='pages-order/orders/detail'){reached=true;break;}}
    await check('retry enters pending order detail',async()=>({ok:reached&&!!await page.$('.order-detail-page')}));
    await pause(600);
    const created=await check('server generated one unpaid order; no payment',()=>mini.evaluate(port=>new Promise(resolve=>{const s=wx.__phase6aSafety;wx.request({url:`http://127.0.0.1:${port}/app-api/order/detail/${s.lastOrderId}`,header:{Authorization:'Bearer '+wx.getStorageSync('accessToken')},success:r=>resolve({ok:r.data?.code===0&&r.data.data.paid===0&&Number(r.data.data.shopId)===2&&s.paymentRequests===0,orderId:s.lastOrderId,paid:r.data?.data?.paid}),fail:()=>resolve({ok:false})});}),port));
    await mini.mockWxMethod('showModal',{confirm:true,cancel:false});
    const component=await element(page,'.order-cancel-button');
    const cancel=await component.$('button');
    await check('pending order offers cancel',async()=>({ok:!!cancel}));await cancel.tap();await pause(1000);await mini.restoreWxMethod('showModal');
    await check('cancel releases inventory exactly once',async()=>{const state=database({action:'verify',marker,orderId:created.orderId});return {...state,ok:state.unpaidCanceledRows===1&&state.cancelRecords===1&&state.unreleasedInventory===0&&state.submissionRows===1&&state.paymentRequests===0};});
    const safety=await mini.evaluate(()=>({paymentRequests:wx.__phase6aSafety.paymentRequests,creates:wx.__phase6aSafety.creates}));
    report.paymentRequests+=safety.paymentRequests;
    await check('no payment or uncaught exceptions',async()=>({ok:report.paymentRequests===0&&report.uncaughtExceptions===0,paymentRequests:report.paymentRequests,uncaughtExceptions:report.uncaughtExceptions}));
    report.ok=true;report.stage='complete';save();
  } finally {try{await Promise.race([mini.evaluate(()=>{if(wx.__phase6aOriginalRequest)wx.request=wx.__phase6aOriginalRequest;if(wx.__phase6aOriginalPayment)wx.requestPayment=wx.__phase6aOriginalPayment;}),pause(2000)]);}catch{}save();mini.disconnect();}
}
const watchdog=setTimeout(()=>{report.failure='Automation watchdog timeout';report.ok=false;save();process.exit(1);},180000);
run().then(()=>{clearTimeout(watchdog);console.log('PASS: store switch, SKU/cart, network retry, unpaid order, cancel; paymentRequests=0.');}).catch(error=>{clearTimeout(watchdog);report.ok=false;report.failure=String(error.message).slice(0,1200);save();console.error('FAIL: inspect private phase6a smoke report.');process.exitCode=1;});
