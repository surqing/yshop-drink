> Historical R2 record. Current original-request audit: [coverage-report.md](coverage-report.md).

# Coverage measurement — Phase 6Q-R2

Coverage is risk evidence, not a whole-repository100% target. Every receipt retains its actual SHA/content digest; a dirty8eb measurement is not relabeled as a later commit. Final-head CI regenerates attributed ordinary Java coverage; delivery must also record the actual final-head measurements. [Machine scope](evidence-summary.json).

| Scope | Line % | Branch % | Method % |
|---|---:|---:|---:|
| R1 ordinary Java |31.10|6.92|22.75|
| R2 ordinary Java1710 |31.16|6.93|22.80|
| R2 ordinary + owned HTTP backend agent |39.04|7.41|30.67|
| Python62 selected driver invocations |36.55|34.10|NOT MEASURED|
| Node/Vue |NOT MEASURED|NOT MEASURED|NOT MEASURED|

The Java denominator includes all compiled src/main classes, DTOs and test infrastructure. The HTTP increment includes actual application startup: it is not attributed exclusively to new business assertions. The report requires matching artifact hash and current source, fresh exec certificates, and rejects JaCoCo class mismatch. Java final-head CI uses ordinary suites; separate local HTTP coverage must not be silently added to it.

Python uses coverage.py branch instrumentation over quality helpers from three actual unittest drivers (43 evidence,16 ownership and3 credential tests at the recorded precommit snapshot).493/1349 statements and148/434 branches executed. Child subprocesses are not instrumented and no function/method coverage is claimed. Node source-transform/data-URL suites pass real assertions but V8 source-file coverage was not collected; they are NOT MEASURED, not assumed100%.

| High-risk class | Line % | Branch % | Method % | Unexecuted methods |
|---|---:|---:|---:|---|
| co/yixiang/yshop/module/system/api/oauth2/OAuth2TokenApiImpl | 100.0 | None | 100.0 |  |
| co/yixiang/yshop/module/product/service/catalog/CatalogOptions | 98.51 | 69.57 | 100.0 |  |
| co/yixiang/yshop/module/order/service/payment/attempt/PaymentAttemptService | 96.89 | 77.11 | 95.24 | reconciliationCandidates |
| co/yixiang/yshop/module/order/service/payment/attempt/PaymentCancellationGuard | 97.44 | 73.08 | 100.0 |  |
| co/yixiang/yshop/module/system/service/auth/AdminAuthServiceImpl | 93.18 | 76.47 | 100.0 |  |
| co/yixiang/yshop/module/store/service/storeshop/StoreAccessService | 94.29 | 87.5 | 100.0 |  |
| co/yixiang/yshop/module/member/service/user/UserServiceImpl | 17.31 | 18.18 | 30.0 | createUser, updateMony, deleteUser, getUser, getUserList, getUserPage, getUserList |
| co/yixiang/yshop/module/system/service/oauth2/OAuth2TokenServiceImpl | 95.45 | 74.29 | 100.0 |  |
| co/yixiang/yshop/module/system/service/permission/PermissionServiceImpl | 90.57 | 75.0 | 100.0 |  |
| co/yixiang/yshop/module/order/service/ordering/OrderPlacementService | 91.86 | 73.5 | 100.0 |  |
| co/yixiang/yshop/module/order/service/payment/PaymentEffects | 96.3 | 50.0 | 100.0 |  |
| co/yixiang/yshop/module/order/service/payment/PaymentProcessor | 85.56 | 80.41 | 100.0 |  |
| co/yixiang/yshop/module/member/service/auth/MemberAuthServiceImpl | 55.88 | 53.85 | 80.0 | weixinMiniAppLogin, wechatAuth, sendSmsCode, checkUserIfExists |
| co/yixiang/yshop/module/coupon/service/marketing/CouponMarketingService | 98.09 | 70.83 | 100.0 |  |
| co/yixiang/yshop/module/coupon/service/marketing/CouponLifecycle | 90.2 | 64.58 | 100.0 |  |

OAuth2TokenApiImpl increased from38.46% line/50% methods to100% through real service/mappers, typed/untyped refresh and revoke. StoreAccessService increased from82.86/83.33/80 to94.29/87.5/100 with database-owned category/order authorization. Legacy member UserServiceImpl now has17.31/18.18/30 through actual HTTP status updates; remaining financial/admin paths are not pretended covered. MemberAuth55.88% lines still leaves real WeChat/SMS/profile branches outside this synthetic scope. No real provider call is made to inflate coverage.

Independent assertions require current-token identity, disabled/deleted/reenabled rejection, rollback, cross-store refusal, owned stock, unpaid order/coupon snapshots and logs without issued tokens. Mutation results prove selected boundaries can detect wrong behavior. Coverage does not replace concurrency/GUI evidence.

## Completed808 evidence supplement

Renewed808 ordinary Java: line31.16%, branch6.93%, method22.80% locally. Ordinary plus owned HTTP: line38.99%, branch7.40%, method30.65%. Python62 driver assertions:493/1349 lines36.55%,148/434 branches34.10%, methods NOT MEASURED. Node coverage remains NOT MEASURED. The HTTP increment includes real application startup, not only extra tests. Linux Java method rounding/class scope is retained in its own coverage receipt. Final-publication coverage must carry its own SHA; these808 numbers are not relabeled.
