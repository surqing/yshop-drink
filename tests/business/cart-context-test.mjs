import test from 'node:test'
import assert from 'node:assert/strict'
const file = new URL('../../yshop-drink-uniapp-vue3/utils/ordering-context.js', import.meta.url)
const { switchStore, reconcileCart, submissionKey, PAYMENT_FROZEN, eligibleCoupons } = await import(file)
function storage() { const data = new Map(); return { getStorageSync:k=>data.get(k), setStorageSync:(k,v)=>data.set(k,v), removeStorageSync:k=>data.delete(k) } }
const groups = [{goodsList:[{id:11,shopId:1,stock:2,storeName:'Fresh server title',image:'local.png',productValue:{'cold,normal':{stock:1,price:'1.23'}}}]}]
const line = {id:11,shopId:1,number:1,valueStr:'cold,normal',price:999}
test('switching stores removes cart, coupon and ambiguous submission context',()=>{const s=storage(), state={store:{id:1},cart:[line],mycoupon:{id:1}};s.setStorageSync('cart',[line]);s.setStorageSync('checkoutSubmission',{key:'old'});switchStore(state,s,{id:2});assert.deepEqual(state.cart,[]);assert.deepEqual(state.mycoupon,{});assert.equal(s.getStorageSync('cart'),undefined);assert.equal(s.getStorageSync('checkoutSubmission'),undefined);assert.equal(s.getStorageSync('selectedStore').id,2)})
test('refreshing same store preserves valid context',()=>{const s=storage(),state={store:{id:1},cart:[line],mycoupon:{id:1}};switchStore(state,s,{id:'1'});assert.equal(state.cart.length,1);assert.equal(state.mycoupon.id,1)})
test('no catalog cannot retain stale cart',()=>assert.deepEqual(reconcileCart([line],[],1),[]))
test('other store cart cannot be reused',()=>assert.deepEqual(reconcileCart([line],groups,2),[]))
test('legacy missing store association is discarded, never guessed',()=>assert.deepEqual(reconcileCart([{...line,shopId:undefined}],groups,1),[]))
test('catalog from another store is rejected',()=>assert.deepEqual(reconcileCart([line],[{goodsList:[{...groups[0].goodsList[0],shopId:2}]}],1),[]))
test('SKU substitution is rejected',()=>assert.deepEqual(reconcileCart([{...line,valueStr:'other'}],groups,1),[]))
test('changed server price and name replace stale display data',()=>{const [r]=reconcileCart([line],groups,1);assert.equal(r.price,1.23);assert.equal(r.name,'Fresh server title')})
test('cart caps at minimum aggregate and SKU stock',()=>assert.equal(reconcileCart([{...line,number:10}],groups,1)[0].number,1))
test('sold out SKU is removed',()=>assert.deepEqual(reconcileCart([line],[{goodsList:[{...groups[0].goodsList[0],productValue:{'cold,normal':{stock:0,price:1.23}}}]}],1),[]))
test('invalid quantity is removed',()=>{for(const number of [-1,0,1.5,'1'])assert.deepEqual(reconcileCart([{...line,number}],groups,1),[])})
test('network retry retains same submission key',()=>{const s=storage(),d={shopId:1,number:['1']};const key=submissionKey(s,d);assert.match(key,/^[A-Za-z0-9_-]{16,64}$/);assert.equal(submissionKey(s,d),key);assert.notEqual(submissionKey(s,{...d,shopId:2}),key)})
test('payment is frozen at the API boundary',()=>assert.equal(PAYMENT_FROZEN,true))

const coupon = {id:1,shopId:'1',type:0,least:10,reservationState:'AVAILABLE'}
test('coupon in another store is hidden',()=>assert.deepEqual(eligibleCoupons([coupon],2,'takein',10),[]))
test('global and explicitly shared coupon are eligible',()=>{for(const shopId of ['0','1,2'])assert.equal(eligibleCoupons([{...coupon,shopId}],2,'takein',10).length,1)})
test('minimum spend and enjoyment type are enforced',()=>{assert.deepEqual(eligibleCoupons([coupon],1,'takein',9.99),[]);assert.deepEqual(eligibleCoupons([{...coupon,type:2}],1,'takein',10),[])})
test('reserved, used and expired coupons cannot be selected',()=>{for(const reservationState of ['RESERVED','USED','EXPIRED',undefined])assert.deepEqual(eligibleCoupons([{...coupon,reservationState}],1,'takein',10),[])})
test('missing store and malformed containers discard stale context safely',()=>{const s=storage(),state={cart:[line],mycoupon:{id:1}};switchStore(state,s,null);assert.deepEqual(state.store,{});assert.deepEqual(state.cart,[]);assert.deepEqual(reconcileCart(null,null,1),[]);assert.deepEqual(eligibleCoupons(null,1,'takein',10),[])})
test('multiple cart entries cannot each spend the same last SKU stock',()=>{const result=reconcileCart([line,{...line,selections:[]}],groups,1);assert.equal(result.length,1);assert.equal(result[0].number,1)})
test('hidden SKU, changed catalog version, invalid stock or invalid price remove stale lines',()=>{for(const patch of [{catalogVersion:2},{productValue:{'cold,normal':{stock:1,price:1,isShow:0}}},{productValue:{'cold,normal':{stock:0.5,price:1}}},{productValue:{'cold,normal':{stock:1,price:0}}}])assert.deepEqual(reconcileCart([line],[{goodsList:[{...groups[0].goodsList[0],...patch}]}],1),[])})
test('invalid option quote is removed before checkout',()=>{const product={...groups[0].goodsList[0],catalogConfiguration:{groups:[{id:'required',name:'Required',enabled:true,min:1,max:1,multiple:false,options:[{id:'x',enabled:true,surcharge:0}]}]}};assert.deepEqual(reconcileCart([line],[{goodsList:[product]}],1),[])})
test('takeout eligibility uses server state and rejects nonfinite subtotal',()=>{assert.equal(eligibleCoupons([{...coupon,type:2}],1,'takeout',10).length,1);for(const amount of [NaN,Infinity,'invalid'])assert.deepEqual(eligibleCoupons([coupon],1,'takein',amount),[])})
