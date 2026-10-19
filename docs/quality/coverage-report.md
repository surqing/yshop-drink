# PHASE 6Q coverage report

These are measured results, not estimates. Execution SHA/digest/run IDs are retained in [request-evidence.json](request-evidence.json). Local Java ran at bd76 + dirty content c37ea441; final-head CI regenerates its own report. No historic GUI result is relabeled.

| Scope | Lines | Branches | Meaning |
|---|---:|---:|---|
| All compiled Java src/main |31.16% (8117/26052)|6.93% (3112/44914)|DTOs and test infrastructure remain in denominator|
| UniApp first-party utils/*.js |30.29% (206/680)|87.24% (171/196)|12 modules, including 7 unexecuted modules; no pages or API transport coverage claim|
| All Vue src TS/SFC except declarations |0.17% (79/46267)|8.76% (53/605)|unexecuted files remain; V8-generated branch denominator differs from JaCoCo|
| Coupon Form.vue |100% (79/79)|91.66% (33/36)|10 actual component tests; HTTP and vendor form components stubbed|

Node tests import unchanged production modules directly, with Node20 module mode. Native V8 ranges are merged with pinned @bcoe/v8-coverage, v8-to-istanbul and Istanbul. Unexecuted utils are explicitly zeroed. Vue uses pinned Vitest/V8 and the real Vue compiler/SFC. Line coverage includes the tool's SFC mapping and is not proof of every template interaction. Branch figures across tools/scopes must not be added. Python's earlier 36.36/34.10% helper result is HISTORICAL; this extension did not remeasure Python.

## Critical risk thresholds

Initial target: 85% line /75% branch for core business boundaries. Every class remains included; missing targets are gaps, not excluded to improve the score. These targets are not all achieved. Whole-repository percentages are not the acceptance threshold.

| Class | Line % | Branch % | Uncovered methods | Target |
|---|---:|---:|---|---|
|co/yixiang/yshop/module/product/service/catalog/CatalogOptions|98.51|69.57|none (branch gaps still exist)|GAP|
|co/yixiang/yshop/module/order/service/payment/attempt/PaymentCancellationGuard|97.44|73.08|none (branch gaps still exist)|GAP|
|co/yixiang/yshop/module/store/service/storeshop/StoreAccessService|94.29|87.5|none (branch gaps still exist)|MET|
|co/yixiang/yshop/module/system/service/oauth2/OAuth2TokenServiceImpl|94.55|72.86|none (branch gaps still exist)|GAP|
|co/yixiang/yshop/module/order/service/ordering/OrderPlacementService|91.53|70.0|ownsVersion|GAP|
|co/yixiang/yshop/module/order/service/payment/PaymentEffects|96.3|50.0|none (branch gaps still exist)|GAP|
|co/yixiang/yshop/module/order/service/payment/PaymentProcessor|85.56|80.41|none (branch gaps still exist)|MET|
|co/yixiang/yshop/module/member/service/auth/MemberAuthServiceImpl|55.88|53.85|weixinMiniAppLogin, wechatAuth, sendSmsCode, checkUserIfExists|GAP|
|co/yixiang/yshop/module/coupon/service/marketing/CouponCodeGuard|86.96|67.86|admit|GAP|
|co/yixiang/yshop/module/coupon/service/marketing/CouponMarketingService|98.09|69.44|none (branch gaps still exist)|GAP|
|co/yixiang/yshop/module/coupon/service/marketing/CouponLifecycle|90.2|64.58|none (branch gaps still exist)|GAP|

Raw Java XML/HTML: `java-coverage/jacoco.xml`, `java-coverage/html/` under the Git-external evidence root. The XML lists each unexecuted method and source line. Node per-file ranges: frontend-publication/<runId>/node-coverage/istanbul.json. Vue per-file source ranges: frontend-publication/<runId>/vue-coverage/coverage-final.json.

P1 branch gaps remain in PaymentEffects, cancellation guard, ordering, catalog and coupon lifecycle/marketing. MemberAuth real WeChat/SMS and legacy member financial administration are not covered by making real calls. Add synthetic transport and transaction tests; do not relax the threshold. V8 tools use range-derived branches, including fallback entries for unexecuted files; the broad branch percentage does not certify unexecuted modules.

- `yshop-drink-uniapp-vue3/utils/auth-errors.js`: lines 100%, branches 91.3%, executed=True.
- `yshop-drink-uniapp-vue3/utils/catalog-options.js`: lines 100%, branches 93.44%, executed=True.
- `yshop-drink-uniapp-vue3/utils/cookie.js`: lines 0%, branches 0%, executed=False.
- `yshop-drink-uniapp-vue3/utils/coupon-context.js`: lines 100%, branches 94.44%, executed=True.
- `yshop-drink-uniapp-vue3/utils/index.js`: lines 0%, branches 0%, executed=False.
- `yshop-drink-uniapp-vue3/utils/logger.js`: lines 0%, branches 0%, executed=False.
- `yshop-drink-uniapp-vue3/utils/ordering-context.js`: lines 100%, branches 91.22%, executed=True.
- `yshop-drink-uniapp-vue3/utils/querystring.js`: lines 0%, branches 0%, executed=False.
- `yshop-drink-uniapp-vue3/utils/router.js`: lines 0%, branches 0%, executed=False.
- `yshop-drink-uniapp-vue3/utils/sms-errors.js`: lines 100%, branches 80%, executed=True.
- `yshop-drink-uniapp-vue3/utils/util.js`: lines 0%, branches 0%, executed=False.
- `yshop-drink-uniapp-vue3/utils/wechat-login.js`: lines 0%, branches 0%, executed=False.
