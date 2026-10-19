/* Page-level local unpaid ordering smoke. No payment, identity output, or source patching. */
const fs = require('node:fs'), path = require('node:path'), {spawnSync} = require('node:child_process');
const workspace = path.resolve(process.env.YSHOP_TEST_WORKSPACE || path.resolve(__dirname, '../../..'));
const automator = require(path.join(workspace, '.uniapp-dev/automation/node_modules/miniprogram-automator'));
const reportPath = path.join(workspace, '.uniapp-dev/logs/phase6c-coupon-smoke.json');
const port = Number(process.env.YSHOP_API_PORT || 48083);
const fixture = JSON.parse(fs.readFileSync(process.env.YSHOP_CATALOG_FIXTURE || path.join(workspace,'.local-dev/private/phase6b/catalog-fixture.json'),'utf8'));
const title=process.env.YSHOP_COUPON_TITLE || 'Phase6C 合成A满30减5';
const shopA=fixture.shops[0].id,shopB=fixture.shops[1].id;
const drink=fixture.products.find(p=>p.shopId===shopA&&p.name.startsWith('经典拿铁'));
if(!drink || fixture.shops.length!==2) throw new Error('Synthetic A/B fixture required');
if (![48081,48082,48083].includes(port)) throw new Error('Local API required');
const report = {checks:[], uncaughtExceptions:0, paymentRequests:0, apiPort:port};
const save = () => fs.writeFileSync(reportPath,JSON.stringify(report,null,2),{mode:0o600});
const pause = ms => new Promise(r=>setTimeout(r,ms));
function database(input) {const r=spawnSync('/usr/bin/python3',[path.join(__dirname,'server-check.py')],{input:JSON.stringify(input),encoding:'utf8',timeout:15000});if(r.status)throw new Error('Server check failed');return JSON.parse(r.stdout);}
async function check(name,fn) {report.stage=name;save();const result=await Promise.race([fn(),pause(20000).then(()=>{throw new Error("Automation step timed out")})]);report.checks.push({name,...result});save();if(!result.ok)throw new Error('Business smoke assertion failed');return result;}
async function element(page, selector) {const deadline=Date.now()+20000;while(Date.now()<deadline){const e=await page.$(selector);if(e)return e;await pause(200);}report.missingSelector=selector;throw new Error('Expected element missing');}
async function run() {
  let mini;const deadline=Date.now()+20000;
  while(!mini){try{mini=await automator.connect({wsEndpoint:`ws://127.0.0.1:${process.env.YSHOP_AUTOMATION_PORT||9420}`});}catch{if(Date.now()>deadline)throw new Error('Tool unavailable');await pause(300);}}
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
    await selectStore(shopA);await addItem();
    await check('store A SKU and cart use selected store',()=>mini.evaluate(shopA=>({ok:(wx.getStorageSync('cart')||[]).length>0&&(wx.getStorageSync('cart')||[]).every(x=>Number(x.shopId)===shopA&&x.price>0)}),shopA));
    await selectStore(shopB);
    await check('switch to store B clears old cart and submission',()=>mini.evaluate(shopB=>({ok:!(wx.getStorageSync('cart')||[]).length&&!wx.getStorageSync('checkoutSubmission')&&Number(wx.getStorageSync('selectedStore')?.id)===shopB}),shopB));
    await selectStore(shopA);
    page=await mini.navigateTo('/pages-user/coupons/coupons');await element(page,'.coupons-tab');
    await (await page.$$('.coupons-tab'))[1].tap();await pause(700);
    let activity;for(const card of await page.$$('.coupons-item'))if((await card.text()).includes(title)){activity=card;break;}
    await check('coupon center exposes synthetic store A activity',async()=>({ok:!!activity}));
    await mini.evaluate(title=>{wx.__couponOriginalRequest=wx.request;wx.__couponSafety={calls:0,fail:true,title};wx.request=function(options){if(/\/coupon\/receive$/.test(options.url)){wx.__couponSafety.calls++;if(wx.__couponSafety.fail){wx.__couponSafety.fail=false;options.fail?.({errMsg:'Synthetic claim network failure'});options.complete?.({errMsg:'Synthetic claim network failure'});return {abort(){}};}}return wx.__couponOriginalRequest.call(wx,options);};},title);
    await (await activity.$('.immediate-use')).tap();await pause(500);
    await check('claim network failure retains idempotency slot',()=>mini.evaluate(()=>({ok:wx.__couponSafety.calls===1&&wx.getStorageInfoSync().keys.some(k=>k.startsWith('couponClaim:'))})));
    await (await activity.$('.immediate-use')).tap();await pause(1000);
    await check('same action retry issued one coupon',()=>mini.evaluate(port=>new Promise(resolve=>wx.request({url:`http://127.0.0.1:${port}/app-api/coupon/my`,header:{Authorization:'Bearer '+wx.getStorageSync('accessToken')},data:{shopId:6,type:3,page:1,pagesize:100},success:r=>{const list=r.data?.data?.filter(c=>c.title===wx.__couponSafety.title)||[];wx.__couponSafety.instanceId=list[0]?.id;resolve({ok:r.data?.code===0&&list.length===1&&list[0].reservationState==='AVAILABLE'})},fail:()=>resolve({ok:false})})),port));
    await (await page.$$('.coupons-tab'))[0].tap();await pause(500);
    await check('my coupons render AVAILABLE rule',async()=>({ok:(await (await page.$('.coupons-page')).text()).includes(title)}));
    await (await page.$('.coupons-exchange input')).input('SYNTH6CDEMO');await (await page.$('.coupons-exchange button')).tap();await pause(800);
    await check('public code issued global coupon without leaking code in list',()=>mini.evaluate(port=>new Promise(resolve=>wx.request({url:`http://127.0.0.1:${port}/app-api/coupon/my`,header:{Authorization:'Bearer '+wx.getStorageSync('accessToken')},data:{shopId:6,type:3,page:1,pagesize:100},success:r=>resolve({ok:r.data?.code===0&&r.data.data.some(c=>c.title==='Phase6C 合成公共码通用券'&&String(c.shopId)==='0')&&r.data.data.every(c=>!c.exchangeCode)}),fail:()=>resolve({ok:false})})),port));
    page=await mini.switchTab('/pages/menu/menu');await element(page,'.good');
    let selectedCard;for(const card of await page.$$('.good')) if((await card.text()).includes(drink.name)){selectedCard=card;break;}
    if(!selectedCard)throw new Error('Synthetic drink unavailable');await (await selectedCard.$('.property_btn')).tap();
    await element(page,'.customization-group');
    let medium;for(const value of await page.$$('.properties .value')) if((await value.text()).startsWith('中杯')){medium=value;break;}
    if(!medium)throw new Error('Expected medium cup SKU missing');await medium.tap();await pause(300);
    await check('hot default hides ice',async()=>({ok:!(await page.$('.customization-group[data-group-id="ice"]'))}));
    await (await element(page,'.customization-option[data-option-id="cold"]')).tap();await element(page,'.customization-group[data-group-id="ice"]');
    await check('cold activates required ice group',async()=>({ok:!!await page.$('.customization-group[data-group-id="ice"]')}));
    await (await element(page,'.customization-option[data-option-id="normal"]')).tap();
    await (await element(page,'.customization-option[data-option-id="hot"]')).tap();await pause(400);
    await check('hot switch removes inapplicable ice',async()=>({ok:!(await page.$('.customization-group[data-group-id="ice"]'))}));
    for(let i=0;i<2;i++){await (await element(page,'.customization-option[data-option-id="pearl"]')).tap();await pause(200);}
    const priceText=await (await element(page,'.good-detail-modal .action .price')).text();
    await check('two toppings update preview price',async()=>({ok:Number(priceText.replace(/[^0-9.]/g,''))===20,preview:priceText}));
    await (await element(page,'.good-detail-modal .iconadd-select')).tap();
    await (await element(page,'.add-to-cart-btn')).tap();await pause(500);
    await check('cart keeps customization quantity and precise unit price',()=>mini.evaluate(({shopA,id})=>{const c=wx.getStorageSync('cart')||[];return {ok:c.length===1&&c[0].id===id&&Number(c[0].shopId)===shopA&&c[0].number===2&&c[0].price===20&&c[0].selections.some(s=>s.optionId==='pearl'&&s.quantity===2)&&!c[0].selections.some(s=>s.groupId==='ice')};},{shopA,id:drink.id}));
    page=await mini.navigateTo('/pages-checkout/cart/cart');await element(page,'.cart-item');
    await check('cart page renders selected store item',async()=>({ok:(await page.$$('.cart-item')).length>0}));
    page=await mini.navigateTo('/pages-checkout/pay/pay');await element(page,'.pay-page__footer-btn');await pause(600);
    await check('checkout exposes submit without payment selectors',async()=>({ok:(await (await page.$('.pay-page__footer-btn')).text()).includes('提交订单')&&!await page.$('.pay-page__payment')}));
    page=await mini.navigateTo(`/pages-checkout/packages/index?amount=40&coupon_id=0&type=1&shop_id=${shopA}`);await element(page,'.packages-item');
    let chosen;for(const card of await page.$$('.packages-item'))if((await card.text()).includes(title)){chosen=card;break;}
    await check('checkout selector presents eligible discount',async()=>({ok:!!chosen&&!!await chosen.$('.immediate-use')}));
    await (await chosen.$('.immediate-use')).tap();await pause(600);page=await mini.currentPage();await element(page,'.pay-page__footer-btn');
    await check('checkout displays product subtotal minus five',async()=>({ok:(await (await page.$('.pay-page')).text()).includes('35.00')}));
    await mini.evaluate(()=>{wx.__phase6aSafety.failNextCreate=true;});
    await (await page.$('.pay-page__footer-btn')).tap();await pause(1800);
    await check('failed submission retains cart and idempotency key',()=>mini.evaluate(()=>{const key=wx.getStorageSync('checkoutSubmission');wx.__phase6aSafety.retryKey=key?.key;return {ok:!!key?.key&&(wx.getStorageSync('cart')||[]).length>0&&wx.__phase6aSafety.creates===1};}));
    await (await page.$('.pay-page__footer-btn')).tap();
    let reached=false;for(let i=0;i<60;i++){await pause(200);page=await mini.currentPage();if(page.path==='pages-order/orders/detail'){reached=true;break;}}
    await check('retry enters pending order detail',async()=>({ok:reached&&!!await page.$('.order-detail-page')}));
    await pause(600);
    const created=await check('server generated one unpaid order; no payment',()=>mini.evaluate(({port,shopA})=>new Promise(resolve=>{const s=wx.__phase6aSafety;wx.request({url:`http://127.0.0.1:${port}/app-api/order/detail/${s.lastOrderId}`,header:{Authorization:'Bearer '+wx.getStorageSync('accessToken')},success:r=>resolve({ok:r.data?.code===0&&r.data.data.paid===0&&Number(r.data.data.shopId)===shopA&&s.paymentRequests===0,orderId:s.lastOrderId,paid:r.data?.data?.paid}),fail:()=>resolve({ok:false})});}),{port,shopA}));
    await check('coupon reserved by unpaid order only',async()=>database({action:'coupon',orderId:created.orderId,state:'RESERVED'}));
    await check('server snapshot matches options and quantity',async()=>database({action:'catalog',orderId:created.orderId,productId:drink.id,unitPrice:20,quantity:2}));
    await mini.mockWxMethod('showModal',{confirm:true,cancel:false});
    const component=await element(page,'.order-cancel-button');
    const cancel=await component.$('button');
    await check('pending order offers cancel',async()=>({ok:!!cancel}));await cancel.tap();await pause(1000);await mini.restoreWxMethod('showModal');
    await check('cancel releases inventory exactly once',async()=>{const state=database({action:'verify',marker,orderId:created.orderId});return {...state,ok:state.unpaidCanceledRows===1&&state.cancelRecords===1&&state.unreleasedInventory===0&&state.submissionRows===1&&state.paymentRequests===0};});
    page=await mini.navigateTo('/pages-user/coupons/coupons');await element(page,'.coupons-page');await pause(500);
    await check('safe cancellation makes original coupon available again',async()=>database({action:'coupon',orderId:created.orderId,state:'AVAILABLE'}));
    const safety=await mini.evaluate(()=>({paymentRequests:wx.__phase6aSafety.paymentRequests,creates:wx.__phase6aSafety.creates}));
    report.paymentRequests+=safety.paymentRequests;
    await check('no payment or uncaught exceptions',async()=>({ok:report.paymentRequests===0&&report.uncaughtExceptions===0,paymentRequests:report.paymentRequests,uncaughtExceptions:report.uncaughtExceptions}));
    report.ok=true;report.stage='complete';save();
  } finally {try{await Promise.race([mini.evaluate(()=>{if(wx.__phase6aOriginalRequest)wx.request=wx.__phase6aOriginalRequest;if(wx.__phase6aOriginalPayment)wx.requestPayment=wx.__phase6aOriginalPayment;}),pause(2000)]);}catch{}save();mini.disconnect();}
}
const watchdog=setTimeout(()=>{report.failure='Automation watchdog timeout';report.ok=false;save();process.exit(1);},180000);
run().then(()=>{clearTimeout(watchdog);console.log('PASS: catalog customizations, toppings, quantity, server snapshot, store switch, retry and cancel; paymentRequests=0.');}).catch(error=>{clearTimeout(watchdog);report.ok=false;report.failure=String(error.message).slice(0,1200);save();console.error('FAIL: inspect private phase6c coupon smoke report.');process.exitCode=1;});
