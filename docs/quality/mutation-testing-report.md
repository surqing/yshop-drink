# Dangerous mutation evidence — Phase 6Q-R2

The joined historical/focused matrix is **25 KILLED / 3 SURVIVED / 0 INCONCLUSIVE**. This is explicitly a union of attributed snapshots, **not 28 fresh final-head executions**. The original R1 report remains in Git history; the six requested operators were re-run against R2 source with a passing original before every mutation. See [machine results](mutation-results.json) for each SHA/content digest and source hashes.

| Operator | Classification | Assertion failures | Exact selection |
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
| inventory-conditional-update | KILLED | 1 | OrderingDatabaseTest#availableStockConditionalDebitMustSucceed |
| cross-store-product | SURVIVED | 0 | OrderingDatabaseTest |
| employee-store-scope | KILLED | 3 | OrderingDatabaseTest |
| cancel-payment-guard | KILLED | 3 | PaymentCancellationDatabaseTest#activeOrUncertainBlocksCustomerAndExpiry |
| cancel-uncertain-safe | KILLED | 1 | PaymentCancellationDatabaseTest#activeOrUncertainBlocksCustomerAndExpiry |
| cancel-remote-proof | KILLED | 4 | PaymentCancellationDatabaseTest |
| coupon-member-limit | KILLED | 42 | CouponDatabaseTest |
| coupon-total-limit | SURVIVED | 0 | CouponDatabaseTest |
| coupon-expiration | KILLED | 1 | CouponDatabaseTest |
| coupon-claim-rate | KILLED | 1 | CouponCodeSecurityTest |
| coupon-redis-fail-open | KILLED | 1 | CouponCodeSecurityTest |
| payment-amount | KILLED | 2 | PaymentDatabaseTest |
| auth-admin-reenable-revocation | KILLED | 1 | OAuth2LifecycleDatabaseTest#adminDisableReenableCannotResurrectOldCredentials |

## Resolution of the six requested cases

`inventory-conditional-update` now targets the independently asserted valid-stock debit path: one unpaid order, A stock9, B stock10 and exactly one reservation. Original PASS; mutant one assertion failure, zero errors/skips. The ordinary full suite is still run separately.

`cancel-payment-guard` and `cancel-uncertain-safe` target `activeOrUncertainBlocksCustomerAndExpiry`: three and one assertion failures respectively, zero runtime errors. The complete payment/cancellation regression remains enabled; narrowing the mutation selector does not exclude ordinary tests.

The three survivors are retained production defenses: removing the SKU admission check is still rejected by the conditional debit SQL matching product/stock; removing the product shop check is still rejected by category ownership and shop-bound SQL; removing the Java coupon capacity check is still rejected by `receive<distribute`. Independent composite mutants (`cross-store-all-defences`, `coupon-total-all-defences`, wrong debit) are killed. These are SURVIVED redundant defenses, not invented kills or blanket EQUIVALENT claims.

The new `auth-admin-reenable-revocation` removes both real ADMIN family revocations. The fresh original passes72 invocations; the exact selected regression kills the mutant with one assertion failure. Its first replacement left a dangling if body and failed compilation: that attempt is retained as INCONCLUSIVE, never included in kills. Corrected replacement is an explicit empty statement.

Broad R1 mutations that had initialization/SQL errors remain in historical evidence; the new focused reruns supersede only those requested uncertainty cases. A compile, SQL, timeout, initialization or cleanup failure is not a kill. Mutation residue is removed with the disposable source copy. No real transport or funds are used.

## Reproduction ownership

Redis bootstrap and menu-fixture repairs do not change the production sites under mutation. The28 operator records retain their original SHA,digest,baseline and assertion evidence;25 KILLED/3 SURVIVED is an attributed multi-snapshot matrix, not a claim that all operators were rerun against an arbitrary later documentation commit. Compile/init/timeout failures remain separately preserved and are never promoted to KILLED.
