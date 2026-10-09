# Risk-based measured coverage — Phase 6Q-R1

JaCoCo uses actual ordinary Java executions, not static call counts. The before run is `68129e3da33e4d7ea871c8f3289ef86c`; after is `c1c1e7016fc948a39837774edac9793a` (1705 invocations,76 suites,1173 distinct display names). All compiled main classes, DTOs and shared test infrastructure remain in the denominator. HTTP/Mini runs are not instrumented and do not inflate these figures. [Full counters and unexecuted methods](coverage-summary.json).

| Scope | Before line / branch / method | After line / branch / method |
|---|---|---|
| All compiled main code |30.55 /6.77 /22.39%|31.10 /6.92 /22.75%|
| MemberAuthServiceImpl |29.41 /23.08 /50.00%|55.88 /53.85 /80.00%|
| OAuth2TokenServiceImpl |91.78 /76.92 /100.00%|94.55 /72.86 /100.00%|
| OAuth2TokenApiImpl |0.00 /n/a /0.00%|38.46 /n/a /50.00%|

Token branch percentage falls because new security branches enlarge the denominator; it is not reported as an improvement. Refresh/revocation paths now have real DB/filter/API evidence and independent assertion kills. No absolute repository threshold or 100% safety claim.

## Measured high-risk classes

| Qualified class | Line | Branch | Method | Unexecuted methods |
|---|---:|---:|---:|---|
| system.api.oauth2.OAuth2TokenApiImpl | 38.46 | n/a | 50.0 | createAccessToken, removeAccessToken, refreshAccessToken, revokeUserTokens |
| product.service.catalog.CatalogOptions | 98.51 | 69.57 | 100.0 | none |
| order.service.payment.attempt.PaymentAttemptService | 96.89 | 77.11 | 95.24 | reconciliationCandidates |
| order.service.payment.attempt.PaymentCancellationGuard | 97.44 | 73.08 | 100.0 | none |
| system.service.auth.AdminAuthServiceImpl | 93.18 | 73.53 | 100.0 | none |
| store.service.storeshop.StoreAccessService | 82.86 | 83.33 | 80.0 | requireOrder, requireCategory |
| member.service.user.UserServiceImpl | 0.0 | 0.0 | 0.0 | <init>, createUser, updateUser, updateMony, deleteUser, validateUserExists, getUser, getUserList, getUserPage, getUserList |
| system.service.oauth2.OAuth2TokenServiceImpl | 94.55 | 72.86 | 100.0 | none |
| system.service.permission.PermissionServiceImpl | 88.68 | 70.83 | 100.0 | none |
| order.service.ordering.OrderPlacementService | 91.53 | 70.0 | 94.44 | ownsVersion |
| order.service.payment.PaymentEffects | 96.3 | 50.0 | 100.0 | none |
| order.service.payment.PaymentProcessor | 85.56 | 80.41 | 100.0 | none |
| member.service.auth.MemberAuthServiceImpl | 55.88 | 53.85 | 80.0 | weixinMiniAppLogin, wechatAuth, sendSmsCode, checkUserIfExists |
| coupon.service.marketing.CouponMarketingService | 98.09 | 69.44 | 100.0 | none |
| coupon.service.marketing.CouponLifecycle | 90.2 | 64.58 | 100.0 | none |

Two differently packaged member user services must not be collapsed into one apparent coverage value. The old `member.service.user.UserServiceImpl` remains 0%; the actual admin disable/delete entry was exercised by the separate HTTP fixture. Remaining unexecuted token API overloads, auth send-SMS/helper/real-WeChat paths and StoreAccess wrappers are gaps, not proof of defects. No real SMS or WeChat login was used.

Frontend Node47 cases test cart/coupon/error/retry contracts. Browser/Mini behavior is reported separately. JavaScript/Python line and branch percentages are NOT MEASURED; successful scripts do not imply coverage.
