# Dangerous behavior mutations — Phase 6Q-R1

**27 operators:21 KILLED /3 SURVIVED /3 INCONCLUSIVE.** No unconditional green score. Raw MUTATION exits nonzero when any operator survives or is inconclusive. [Full hashes and actual receipts](mutation-results.json).

Each operator changes real production source only in a disposable copy; the original selected suite must pass immediately before mutation. Exact suites/methods/invocation counts and fresh runId are required. A compile,SQL,transport,initialization or cleanup error is NOT a kill, even when other cases assert. Only valid assertion failures with zero errors/skips qualify.

| Operator | Actual classification | Assertion failures | Suite |
|---|---|---:|---|
| inventory-repeat-release | KILLED | 3 | OrderingDatabaseTest |
| cross-store-price | KILLED | 1 | OrderingDatabaseTest |
| coupon-double-reservation | KILLED | 21 | CouponDatabaseTest |
| duplicate-fulfillment-complete | KILLED | 12 | PaymentDatabaseTest |
| auth-refresh-disable-bypass | KILLED | 2 | OAuth2LifecycleDatabaseTest |
| auth-cache-revocation-bypass | KILLED | 22 | OAuth2LifecycleDatabaseTest |
| payment-freeze-bypass | KILLED | 1 | PaymentCredentialDatabaseTest |
| late-success-terminal | KILLED | 4 | PaymentAttemptDatabaseTest |
| duplicate-event-result | KILLED | 10 | PaymentDatabaseTest |
| inventory-wrong-debit | KILLED | 33 | OrderingDatabaseTest |
| cross-store-all-defences | KILLED | 4 | OrderingDatabaseTest |
| coupon-total-all-defences | KILLED | 39 | CouponDatabaseTest |
| coupon-reuse-state | KILLED | 3 | CouponDatabaseTest |
| coupon-use-expired | KILLED | 22 | CouponDatabaseTest |
| inventory-sku-check | SURVIVED | 0 | OrderingDatabaseTest |
| inventory-conditional-update | INCONCLUSIVE | 1 | OrderingDatabaseTest |
| cross-store-product | SURVIVED | 0 | OrderingDatabaseTest |
| employee-store-scope | KILLED | 3 | OrderingDatabaseTest |
| cancel-payment-guard | INCONCLUSIVE | 20 | PaymentCancellationDatabaseTest |
| cancel-uncertain-safe | INCONCLUSIVE | 18 | PaymentCancellationDatabaseTest |
| cancel-remote-proof | KILLED | 4 | PaymentCancellationDatabaseTest |
| coupon-member-limit | KILLED | 42 | CouponDatabaseTest |
| coupon-total-limit | SURVIVED | 0 | CouponDatabaseTest |
| coupon-expiration | KILLED | 1 | CouponDatabaseTest |
| coupon-claim-rate | KILLED | 1 | CouponCodeSecurityTest |
| coupon-redis-fail-open | KILLED | 1 | CouponCodeSecurityTest |
| payment-amount | KILLED | 2 | PaymentDatabaseTest |

## Review corrections and new boundaries

The seven new composite boundaries are inventory-repeat-release,cross-store-price,coupon-double-reservation,duplicate-fulfillment-complete,auth-refresh-disable-bypass,auth-cache-revocation-bypass,payment-freeze-bypass. Each was actually killed by effective assertions, not compilation. Their failure counts are3,1,21,12,2,22,1 respectively.

The first price operator used an invalid SKU column and produced mixed errors; it was excluded, not called KILLED. The corrected valid operator initially SURVIVED because both test-store prices were1.23. A new independent oracle explicitly sets B9.87 while A stays1.23 and requires the A order amount1.23, B stock/price unchanged and paid0. Both original and mutant execute82 cases; original PASS, mutant one assertion failure,zero errors/skips. Fixture weakness was repaired without production price changes.

Three earlier broad operators previously overclaimed as kills were rerun and correctly reclassified INCONCLUSIVE: inventory-conditional-update,cancel-payment-guard,cancel-uncertain-safe. Their selected full-suite mutations cause runtime errors as well as assertions; valid originals pass but this does not establish a kill. This uncertainty remains visible.

Three SURVIVED operators have independent remaining defenses: inventory-sku-check is rejected by DB conditional update; cross-store-product by category/conditional-shop admission; coupon-total-limit by SQL capacity update. Composite removal of those boundaries is independently detected. Defenses were retained rather than removed to force a score.

Secret canary tests are separate from Java mutation percentages. A synthetic secret deliberately placed in a private log causes a real scan failure without echoing it. No real provider transport,merchant key or funds were involved.
