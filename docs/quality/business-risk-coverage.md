# Business risk coverage — Phase 6Q

This matrix describes actual assertions, not class-name presence. Suite names map to exact cases in `tests/quality/java-manifest.json` and the asset inventory. “Missing” is deliberate; no claim of comprehensive bug detection. H2 and MySQL invocations of one method are overlapping evidence, not new scenarios. P0 marks impact, not proof an unfixed production bug exists.

The audit reproduced one actual authentication bug: an existing disabled member could receive a token through the WeChat route. A failing regression was recorded before the two-line fail-closed status check; the eight-case member service suite then passed. Remaining untested paths below are **coverage gaps**, not invented production vulnerabilities.

## A. Authentication / authorization

| Risk | EXISTING TESTS | COVERED CONDITIONS / TEST QUALITY | MISSING CONDITIONS | SEVERITY | NEXT ACTION |
|---|---|---|---|---|---|
| Login / identity binding | MemberAuthServiceTest: enabled/disabled synthetic WeChat session, exact token identity; password update; PermissionServiceImpl tests | Actual collaborators verified; disabled login failed before fix | SMS/password login HTTP, identity rebind, provider code expiry in real tool | P0 | Add isolated controller/auth-endpoint tests; no real WeChat API |
| Session expiry / token refresh / logout | Expired session denies decrypt/token; missing-token logout idempotence; OAuth2TokenServiceImpl historical tests | Mock service + isolated H2 token fixtures | Cross-instance logout/token revocation during request; refresh races | P0 | Add explicit revocation/refresh race before expanding member functionality |
| Profile access / forged uid | Ordering customerReadUsesOwnedGroupedReferences; coupon user-read permission annotations | Actual DB ownership checks, annotation reflection | All member profile/address HTTP paths and forged JWT context | P1 | Controller tests with independent principals |
| Admin / staff / forged shopId | Ordering staffEndpointsRejectCrossStoreBeforeBusinessCalls, staffCannotReadOrWriteOtherStore, memberCannotUseStaffScope | Independent forbidden-store fixtures, no business mapper calls | Menu/endpoint full route crawl; role changed mid-operation | P0 | Extend controller-level revocation contract; current service refresh test retained |
| Permission changes / multi-store manager | multiShopManagerAndFreshRevocation; explicit headquarters role; unassigned account denied | Reads latest scope, two-shop isolated data | Token caches after real admin revocation, cross-instance enforcement | P1 | Add two-backend permission invalidation test |

## B. Multi-store context

| Risk | EXISTING TESTS | COVERED CONDITIONS / TEST QUALITY | MISSING CONDITIONS | SEVERITY | NEXT ACTION |
|---|---|---|---|---|---|
| Product / SKU / price / inventory / order ownership | Ordering crossStoreProductRejected, crossProductSkuRejected, mixedStoresRejectedAtomically; Catalog twoStoreSameNamedDrinksUseIndependentPrices | Independent prices and cross-table rollback; MySQL mode | All legacy endpoints outside ordering path | P0 | Keep negative service and route coverage together |
| Coupon store scope / staff scope | Coupon invalidUse, adminScopeCannotBeForged; Ordering couponOtherStore/globalAndExplicitMultiStoreCouponAllowed | Independent rights and owned member/store fixtures | Full admin browser forged query/body matrix | P1 | Dedicated isolated admin UI account fixture |
| Store switching / async stale responses | cart-context-test.mjs and coupon-context-test.mjs; read-only Mini actual store/catalog | Real Node assertions on context helpers; actual navigation | Weak-network UI reorder across every page/lifecycle | P1 | Automator delayed-response tests on isolated backend |

## C. Catalog / SKU

| Risk | EXISTING TESTS | COVERED CONDITIONS / TEST QUALITY | MISSING CONDITIONS | SEVERITY | NEXT ACTION |
|---|---|---|---|---|---|
| Single/multi SKU / customization / toppings | CatalogDatabaseTest + catalog-options-test.mjs; real SKU drawer smoke | Fixed explicit catalog prices and combinations, not self-calculated expected output | UI every optional combination and accessibility | P1 | Parameterized UX cases; no invented success |
| Illegal combination / forged price / numeric boundaries | requiredCannotBeOmitted, hotCannotChooseIce, coldNeedsIce, invalidMoney, jsonQuantityCannotBeCoerced, forgedPriceIgnored | Negative cases check unchanged tables and explicit payable values | All large JSON/depth limits and BigDecimal overflow variants | P1 | Targeted property/fuzz bounds |
| Hidden / sold-out / paused store | Ordering unavailableStore, hiddenProduct, skuSoldOut, aggregateSoldOut; Catalog stoppedSkuCannotOrder | Server refuses and inventory/order snapshots unchanged | Live UI stale availability transition during drawer | P1 | Delay/publish race UI fixture |
| Stock adjustments / SKU edits / reservations | inventoryAdjustmentVersusTwentyOrders; CatalogEditingMysqlAcceptance; productEditCannotDestroyUnpaidSkuReservations | MySQL DB transactions and cross-table audit assertions | Multiple concurrent admin editors and long migration locks | P0 | Retain independent connections, add admin HTTP competition |
| Price changes / historic snapshots / copying | priceChangeVersusTwentyOrders, retryDoesNotRepriceCommittedOrder, priceDoesNotChangeInventoryOrOldSnapshot, productCopyIndependentAndIdempotent | Hand-set price expectations, historical snapshots unchanged | Import/export bulk edits and copied optional media | P1 | Future6B maintenance, not scope expansion |

## D. Orders / inventory

| Risk | EXISTING TESTS | COVERED CONDITIONS / TEST QUALITY | MISSING CONDITIONS | SEVERITY | NEXT ACTION |
|---|---|---|---|---|---|
| Create / duplicate / failed-response retry | Ordering duplicateKeyTwentyConcurrent, duplicateKeyConflict, snapshotFailureRollsBackEverything, missingSubmissionKey | DB order/submission/cart/inventory checked; same key returns same committed result | Full device interrupted HTTP response after commit | P0 | Isolated-backend write smoke before UI approval |
| Reservation / last-unit oversell / Redis fault | lastItemTwentyBuyers, databaseLockFailureRollsBackSubmissionAndInventory, redisFailureCannotSkipInventory | 20-worker barrier, DB conditional updates; production ordering does not rely on Redis lock | Host packet loss and long DB failover | P0 | Do not add Redis bypass to satisfy tests |
| Cancel / timeout / retries / permission | cancellationTwentyExactlyOnce, timeoutAndCustomerRaceExactlyOnce, owner check before idempotence; PaymentCancellationDatabaseTest | READ COMMITTED/order→attempt, full unchanged snapshots on block, terminal proof | All legacy partial/corrupt records and HTTP timeout response recovery | P0 | Keep unknown evidence fail closed; extend malformed fixtures |
| Cancel vs attempt/prepay/callback/remote proof | PaymentCancellationDatabaseTest repeated races, lateSuccessAfterProofRacesCancelWithoutFulfillment | Real MySQL, independent transactions; receipts never locked after order | All simultaneous three/four-party schedule permutations | P0 | More controlled barriers; no real provider API |
| Failure atomicity / stock/coupon release once | cancelCouponFailureRollsBackStockAndMarker, releaseAuditFailureRollsBackBusinessState, snapshotFailureRollsBackEverything | Injected post-write failure, independent balance/inventory/rights snapshots | Dedicated release-twice dangerous mutation operator | P0 | Mutation gap explicitly open, not assumed killed |

## E. Coupons

| Risk | EXISTING TESTS | COVERED CONDITIONS / TEST QUALITY | MISSING CONDITIONS | SEVERITY | NEXT ACTION |
|---|---|---|---|---|---|
| Newcomer / registration / cross-campaign eligibility | newUserRequiresRegistrationEvidenceAndOnceAcrossStores, historicalMemberDoesNotBecomeNewByChangingShop, sameNewcomerTwentyConcurrentClaims | Independent registration/claim markers and rights snapshots | Registration transaction racing campaign activation | P1 | Clock-based registration cutoff matrix |
| Minimum spend / store/global / order-type | couponMinimum, couponWrongOrderType, invalidUseLeavesStockAndCouponUntouched | Server-side independent payable/threshold checks | All rounding combinations with stacked future discounts | P1 | Stacking not implemented; no claim it is covered |
| Public code entropy / guessing / old codes | CouponCodeSecurityTest, CouponCodeRedisAcceptanceTest; newCodeCreationIsServerRandomAndDigestOnly, historicalShortDigestCodeAndRequestRetryRemainCompatibleWithoutReset | 20 different codes/keys, independent Redis clients; Redis fail closed; log canary | Distributed hostile IP cohorts, full edge rate-limit behavior | P0 | Application member/IP budgets remain; edge operational control separately |
| Distribution/member limits / concurrent claims | twentyMembersClaimLastCoupon, sameMemberTwentyKeysLimitOne/Three, claimVersusQuotaChange, claimVersusActivityDisable | DB cap/counter/instance assertions, Redis independent clients; dangerous mutations killed | Long-running transport response loss across deployed replicas | P0 | Load-test controlled isolated backend |
| Immutable rights / expire / historical ambiguity | issuedSnapshotDoesNotFollowTemplateEditsOrDisable, expiryAndOrderReservationRace, historicalAmbiguousStatusIsReviewNotUsed | Fixed Clock and explicit history; not guessed used status | Every historical imported schema variant | P1 | Migration review with anonymized samples |
| Reserve / redeem / safe return / reuse | sameCouponTwentyOrdersOnlyOneReservation, receiptWithoutSuccessfulPaymentCannotProveRedemption, CouponPaymentDatabaseTest, pendingAttemptCancellationDoesNotReleaseOrAudit | Synthetic payment DB only; multi-table rollback and no financial dev writes | Dedicated complete double-reservation mutation and full device checkout | P0 | Add mutation plus controlled write UI before unconditional gate |

## F. Synthetic financial safety

| Risk | EXISTING TESTS | COVERED CONDITIONS / TEST QUALITY | MISSING CONDITIONS | SEVERITY | NEXT ACTION |
|---|---|---|---|---|---|
| Attempt idempotency / amount / merchant / reference | PaymentAttemptDatabaseTest; PaymentDatabaseTest; credential/preflight tests | H2 + actual MySQL, server amount immutable SQL/trigger guards | Remote real-provider interoperability deliberately prohibited | P0 | Only authorized later-stage merchant acceptance can cover real transport |
| Callback signature / decryption / body/headers | WechatV3DatabaseTest; CallbackIngressEndToEndTest isolated synthetic TLS fixture | Official SDK verification with generated synthetic keys; ingress separately attributed | Public CDN/WAF/TLS deployment and actual WeChat callbacks | P0 | Dedicated manual deployment authorization, no real callback now |
| Unknown prepay / query/close / late success | PaymentLiveReadinessDatabaseTest + WechatV3DatabaseTest; late-success-terminal mutant killed | Fake transport, DB lease/fencing, callbacks/query/close races | Provider outage semantics outside documented SDK fixture | P0 | Keep both live/reconciliation flags false |
| Transaction replay / conflict / finalization rollback | PaymentDatabaseTest, PaymentAttemptDatabaseTest; duplicate-event-result mutation | DB payment/bill/status/attempt consistency; durable receipt/retry | Compound mutation bypassing all duplicate DB defenses | P0 | Add explicit duplicate-effect composite mutation |
| Wallet / ledger / recharge invariants | WalletDatabaseTest; live readiness financial races | Synthetic isolated balances only, 20-way debit/refund/recharge, reconciliation | Production negative-balance data audit is not run | P0 | Never repair or migrate real wallet data in QA |
| Payment freeze / provider isolation | V3/readiness guard tests; Mini hook window actual 0 calls; existing payment config false | Measured scoped device attempts and unchanged development financial fingerprint | Host-wide egress accounting is not measured | P0 | Do not label declared fake-transport zero as measured global traffic |

## G. Frontend / device

| Risk | EXISTING TESTS | COVERED CONDITIONS / TEST QUALITY | MISSING CONDITIONS | SEVERITY | NEXT ACTION |
|---|---|---|---|---|---|
| Page lifecycle / SKU/cart / price display | Actual read-only Mini7 checks; Node cart/catalog helpers47 test cases in total | Actual official tool pages, local API responses, no mocked page screenshot | Full checkout/coupon write flow on an isolated backend; all devices | P1 | Provision disposable backend/data, retain payment guard |
| Network failure / weak network / double click / stale response | Node cart/coupon context race and retry tests; backend duplicate key contracts | Independent controlled helper promises and explicit DB idempotency | End-to-end interrupted commit response and reconnect | P1 | Device-level controlled HTTP fault injection |
| Form validation / errors / coupon/order state | Node auth/SMS error tests; Coupon policy DB cases; Vue build/types | Real helper assertions + compile/type evidence | All admin validation/component/page interactions | P1 | Component/browser tests, not build-only proof |
| Staff admin CRUD / permission change | Service/controller DB negative tests, existing suite inventory | No fake fresh UI approval based on previous Phase6C report | Actual current-branch staff CRUD and role revoke browser run | P0 | Dedicated controlled accounts/backend; remains UNVERIFIED |

## Independence and concurrency evidence

Money/price/stock/coupon expectations use fixed test values and independent database counts/snapshots. Tests do not use the production pricing result as their only oracle. Forbidden staff operations verify collaborators were not called. Wallet/payment/cancellation cases compare balance, ledger, paid/status, bill, attempt/receipt/conflict and business inventory/rights effects, not just HTTP success counts. SDK signature fixtures intentionally use official crypto generation/verification; this is compatibility testing, not an independent cryptographic proof.

Shared parallel helpers previously released the start signal without waiting for all submitted workers. Ordering, cancellation, wallet, attempt, v3 and payment fixtures now wait for a ready barrier and enforce bounded joins. A new test holds 20 independent transactions simultaneously, checks 20 different MySQL CONNECTION_ID values (H2 SESSION_ID in ordinary mode), and confirms each connection stays transaction-bound. This validates the worker environment; exact start instructions cannot guarantee every CPU instruction overlaps. Resource-level locks intentionally serialize conflicting DB writes, not the entire test before reaching them. Existing orchestrated two-party races retain their explicit held/release barriers. Independent Redis clients and randomized key prefixes exercise cross-client budgets.

Concurrency assertions inspect final DB invariants. Repeated tests (including cancellation/attempt, expiry/attempt, callback/cancel and late-success/cancel 20×20 races) are invocations, not 400 independent business features. MySQL acceptance uses disposable least-privilege schemas/accounts; transport instability is an environment FAIL, never a killed mutant or a pass. See gate report for the successful final run IDs and earlier failures.

Uncovered interleavings, cache invalidation, historical-corruption combinations and full device write flows remain review items. None justify opening payment, forging paid status or editing real balances.
