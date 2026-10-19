import test from 'node:test'
import assert from 'node:assert/strict'
const {couponDiscount,couponContextMatches,claimRequestKey,claimSucceeded}=await import(new URL('../../yshop-drink-uniapp-vue3/utils/coupon-context.js',import.meta.url))
const coupon={id:1,reservationState:'AVAILABLE',least:30,value:5}
test('subtotal including customizations crosses threshold',()=>assert.equal(couponDiscount(coupon,34),5))
test('below threshold never discounts',()=>assert.equal(couponDiscount(coupon,29.99),0))
test('face value capped to product subtotal',()=>assert.equal(couponDiscount({...coupon,least:0,value:50},34),34))
for(const reservationState of ['RESERVED','USED','EXPIRED','NOT_YET_VALID','INVALID','REVIEW_REQUIRED'])test(reservationState+' cannot discount',()=>assert.equal(couponDiscount({...coupon,reservationState},34),0))
test('negative or malformed coupon never discounts',()=>{for(const c of [{value:-1},{least:-1},{value:'oops'},{id:null}])assert.equal(couponDiscount({...coupon,...c},34),0)})
test('shop and consumption type protect delayed response',()=>{assert.equal(couponContextMatches(6,'6',1,1),true);assert.equal(couponContextMatches(7,6,1,1),false);assert.equal(couponContextMatches(6,6,2,1),false)})
test('failed request retains key, success clears only that identity',()=>{const store=new Map();const storage={getStorageSync:k=>store.get(k),setStorageSync:(k,v)=>store.set(k,v),removeStorageSync:k=>store.delete(k)};const a=claimRequestKey(storage,7,'activity:4');assert.equal(claimRequestKey(storage,7,'activity:4'),a);const b=claimRequestKey(storage,7,'activity:5');claimSucceeded(storage,7,'activity:4');assert.equal(claimRequestKey(storage,7,'activity:5'),b);assert.notEqual(claimRequestKey(storage,7,'activity:4'),a)})
test('local request slot stores no public redemption code',()=>{const store=new Map();const storage={getStorageSync:k=>store.get(k),setStorageSync:(k,v)=>store.set(k,v)};claimRequestKey(storage,7,'code:SYNTHETIC_DEMO');assert.ok(!JSON.stringify([...store]).includes('SYNTHETIC_DEMO'))})
