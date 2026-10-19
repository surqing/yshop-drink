# PHASE 6Q gaps and critical business matrix

Presence of a named test is not full coverage. Actual certificates are linked in request-evidence.json; current-head CI is separate. PASS below means the cited behavior has execution evidence in its recorded snapshot. GUI rows have an explicit current-revision gap.

| Boundary | Evidence | Remaining risk / follow-up |
|---|---|---|
| HQ/store/illegal shop/revoked staff | Ordering: headquartersMustHaveExplicitRole, multiShopManagerAndFreshRevocation, staffCannotReadOrWriteOtherStore | current real GUI renewed CAPTCHA/fixture needed |
| Batch authorization/store stock-price isolation | Catalog: batchAllOrNothingOnCrossStore,twoStoreSameNamedDrinksUseIndependentPrices | whole deployment multi-process competition not measured |
| Product sale/SKU preservation/options/toppings | Catalog +25 MySQL editing calls; stoppedSkuCannotOrder,editPreservesSkuIdStockSalesAndPendingOrderSnapshot | CatalogOptions branch69.57% |
| Server price/snapshot/old order compatibility | forgedPriceIgnored,correctStoreServerPriceAndSynchronousSnapshot,migrationRerunPreservesUnpaidSnapshot | all historical production data only read-only; not repaired |
| Last stock/edit-stock/price concurrency |20x20 lastItemTwentyBuyers,twentyBuyersLastUnit,priceChangeVersusTwentyOrders,skuEditAndReservationRace |3 redundant-defense survivors remain P1 |
| Inventory cancel/repeated release | cancellationTwentyExactlyOnce,timeoutAndCustomerRaceExactlyOnce,inventory-repeat-release mutant | no genuine provider cancel |
| Order invalid inputs/idempotency/conflict | malformedLines,fractionalQuantity,duplicateKeySameRequest,duplicateKeyConflict,duplicateKeyTwentyConcurrent | client response loss runtime Mini proof is historic |
| DB failure/rollback/recovery | snapshotFailureRollsBackEverything,databaseLockFailureRollsBackSubmissionAndInventory,databaseRecoveryWithoutRedis | true process-crash/chaos recovery matrix not exhaustive |
| Unexpected race exceptions | new unexpectedDatabaseFailuresCannotBecomeNormalRaceLosers +85 calls | targeted current MySQL must be confirmed by final CI |
| Cancellation/attempt/timeout-success | financial497; cancellationAndAttemptCreationRace,expiryAndAttemptCreationRace,callbackAndCancelRaceDoesNotDeadlock | guard branch73.08% below target |
| Coupon issuance/per-member/idempotent retries |20x20 twentyMembersClaimLastCoupon,sameMemberTwentyKeysLimitOne,sameClaimRequestTwentyRetries | Marketing branch69.44%; issuance precheck survivor |
| Code retry/rate/newcomer/scoped operation | Coupon,CodeSecurity,Redis66; publicCodeTwentySameRetries,newUserRequiresRegistrationEvidenceAndOnceAcrossStores | new UI claim/disable runtime not rerun |
| Rights snapshots/use/reserve/release/expiry | issuedSnapshotDoesNotFollowTemplateEditsOrDisable,expiredReservationReleaseDoesNotRenewEligibility,sameCouponTwentyOrdersOnlyOneReservation | Lifecycle branch64.58% |
| Synthetic redemption/duplicate success/ambiguous old evidence | CouponPayment68,receiptWithoutSuccessfulPaymentCannotProveRedemption,historicalAmbiguousStatusIsReviewNotUsed | PaymentEffects branch50%; financial fingerprints remain synthetic |
| Member identity/login/status/session revoke | lifecycle72; prior actual45 HTTP checks/two JVMs; final CI renews HTTP | MemberAuth55.88/53.85%, real WeChat/SMS excluded; use synthetic IO to fill gaps |
| Wallet/points/PaymentAttempt/inbox/conflict/cancel gate | Payment/Wallet/Attempt/Wechat/readiness suites in owned financial497 | legacy member financial methods, reconciliationCandidates unexecuted; no Phase6D |
| Frontend store/cart/SKU/coupon/late response/retry |52 Node +10 Vue; two real frontend bugs fixed and mutation-killed | no claim of complete component/real page coverage |
| Frontend form/permission/network error | real Form domain validations,403 retain-and-retry,deterministic delayed promises | vendor required-field validation is stubbed, offline/reconnect whole app P2 gap |
| Real official Mini/admin GUI | historical R2 Mini15 and GUI8, current HBuilderX compile PASS | revised cart/Form runtime NOT_RUN; expired fixtures/captcha require new owned session |
| Secret/permissions/desensitization | new exact-value+diff scan PASS; Desensitize Chinese/Latin/null serialized cases | baseline public-default overlap remains disclosed; branch protection not changed |

## Priority gaps

P1: three core survivors; branch thresholds in PaymentEffects,OrderPlacementService,PaymentCancellationGuard,CatalogOptions,CouponLifecycle,CouponMarketingService and OAuth2 token service and CouponCodeGuard not all met. Responsibility: order/payment/catalog/coupon/auth modules. Add independent guard-path tests and rigorous equivalence evaluation; do not remove defenses, weaken assertions, or rename survivors.

P2: synthetic coverage of remaining MemberAuth/provider-error/profile and legacy member financial management; true process crash/retry; whole-business multi-process contention; broader Vue components/UniApp pages/vendor validation and network reconnect. Responsibility: member/backend/frontend. Requires owned resources and fake transport, not real calls.

ENV_BLOCKED:17 conditional assets and1 deployment entry retain their documented conditions (not automatically certified). Six legacy device scripts use private compile/session/ports; shell wrappers keep explicit authorization and fixed seed assumptions. Current real GUI/Mini fixtures have expired; prior execution is not current verification. No production deployment, true provider/SMS, phone or paid API test is run to fill these gaps.

The historical desensitization assertion expected 芋*** for literal yshop. ChineseNameDesensitize retains one leading character with suffix0; slider handler masks the remainder, so yshop→y****. Production serialization was correct; the sample expectation was stale. Existing fixed test retains all other field assertions and adds 张三→张*,李小明→李**,A→*,AB→A*,Latin and null cases. This latest ordinary run includes the repaired suite; no disabled/excluded test workaround.
