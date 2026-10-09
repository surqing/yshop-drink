# Controlled mutation testing — Phase 6Q

We selected explicit mutations rather than an unbounded PIT run: database transaction and state invariants cross several Maven modules, and H2/SDK/fixture startup dominates individual test runtime. Every batch compiles a disposable source copy and first requires the original selected suite to pass with fresh exact-name reports. No mutation is applied to this checkout. Temporary copies are removed on completion; private evidence is retained. PIT was evaluated as an alternative but is not installed or claimed as executed.

**20 distinct Java operators: 17 KILLED, 3 SURVIVED (redundant defenses), zero final inapplicable/inconclusive operators.** Four batches contain 22 selection attempts: one initial incorrect textual selector was inapplicable, one cancellation mutation initially used the wrong suite and survived. Both were corrected and rerun; these initial outcomes remain in [raw sanitized history](mutation-results.json). There were 21 applied Java executions, including the repeated cancellation selector; do not count these as 21 unique scenarios.

One additional security mutation injected a synthetic Secret into a disposable log and ran the actual exact-value scanner CLI: nonzero exit, FAIL, no canary echoed. Thus 21 distinct controlled mutations including the log canary, 18 detected and 3 redundant survivors. No merchant credential is used.

| Mutation | Final outcome | Assertion failures | Interpretation |
|---|---|---:|---|
| inventory-sku-check | SURVIVED | 0 | Database conditional stock updates still reject the request; no oversell. Composite wrong/conditional debit operators are killed. |
| inventory-conditional-update | KILLED | 1 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| cross-store-product | SURVIVED | 0 | Category ownership and shop-bound conditional update independently reject. Removing all three defenses is killed. |
| employee-store-scope | KILLED | 3 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| cancel-payment-guard | KILLED | 26 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| cancel-uncertain-safe | KILLED | 12 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| cancel-remote-proof | KILLED | 4 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| coupon-member-limit | KILLED | 46 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| coupon-total-limit | SURVIVED | 0 | Conditional receive<distribute UPDATE is the authoritative cap. Removing both guards is killed. |
| coupon-expiration | KILLED | 1 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| coupon-claim-rate | KILLED | 1 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| coupon-redis-fail-open | KILLED | 1 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| payment-amount | KILLED | 2 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| inventory-wrong-debit | KILLED | 33 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| cross-store-all-defences | KILLED | 4 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| coupon-total-all-defences | KILLED | 40 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| coupon-reuse-state | KILLED | 3 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| coupon-use-expired | KILLED | 22 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| late-success-terminal | KILLED | 4 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |
| duplicate-event-result | KILLED | 10 | Actual JUnit assertion failure in the selected suite; compilation/environment errors excluded. |

The three survivors are not hidden or declared killed. Each removes only an early check while an independently executed database/ownership defense remains. Composite mutations remove the entire effective boundary and are killed. If a future refactor removes those remaining defenses, these classifications must be revisited. The mutation dispatcher conservatively exits nonzero on any survivor, including these three; raw MUTATION is therefore not unconditionally green. A reviewer must accept equivalence evidence, not change the denominator.

`cancel-payment-guard` initially selected OrderingDatabaseTest, whose fixtures did not contain PaymentAttempt evidence. That was a real selector/coverage gap, not equivalence. Selecting PaymentCancellationDatabaseTest kills the same mutation; its concurrency and unchanged cross-table snapshots establish the intended boundary. The original `payment-amount` text did not match current code; zero matches were reported INAPPLICABLE, then the exact current guard was mutated and killed.

The duplicate-event operator detects incorrect idempotent return semantics; it is **not proof of a compound mutation bypassing every database uniqueness/paid guard**. Duplicate fulfillment is separately asserted by payment/wallet database regressions. Release-twice, wrong-store-price, coupon double-reservation, full token revocation, and synthetic live-provider transport escape have not each received a dedicated controlled operator in this audit. These are explicit remaining mutation gaps; do not infer universal mutation coverage from 17 kills.

Reproduce:

```sh
python3 tests/quality/run.py MUTATION --output "$PRIVATE_QUALITY_DIR"
# Or an explicitly selected, attributed subset:
python3 tests/quality/mutate.py --output "$NEW_PRIVATE_MUTATION_DIR" --only cancel-payment-guard,payment-amount
```

Timeouts/compile errors/fixture initialization errors are INCONCLUSIVE, never kills. Only a fresh JUnit assertion failure in the selected suite is a kill. Reactor upstream modules need `failIfNoSpecifiedTests=false`; final exact target-suite/name validation makes a missing target suite fail. This narrow exception is documented rather than removing it blindly and breaking upstream reactors.
