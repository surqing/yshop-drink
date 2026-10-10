# PHASE 6Q mutation report

Normal baselines passed before every mutation. Only disposable source trees were altered, with synthetic H2 or stub HTTP; nothing mutated was pushed. Compilation/setup/SQL/timeout errors are INCONCLUSIVE, never KILLED. This run reran all 28 backend operators, then two frontend operators. Source hashes and original identities are in [mutation-results-current.json](mutation-results-current.json).

Java: 28 generated,25 KILLED,3 SURVIVED,0 INCONCLUSIVE; score89.29%. Frontend:2/2 KILLED. Combined:30 generated,27 KILLED,3 SURVIVED,90%. The combined score does not resolve the three backend gaps. QUALITY_GATE_READY=NO.

| Operator | Result | Assertion failures |
|---|---|---:|
|auth-admin-reenable-revocation|KILLED|1|
|inventory-repeat-release|KILLED|3|
|cross-store-price|KILLED|1|
|coupon-double-reservation|KILLED|21|
|duplicate-fulfillment-complete|KILLED|12|
|auth-refresh-disable-bypass|KILLED|2|
|auth-cache-revocation-bypass|KILLED|23|
|payment-freeze-bypass|KILLED|1|
|late-success-terminal|KILLED|4|
|duplicate-event-result|KILLED|10|
|inventory-wrong-debit|KILLED|34|
|cross-store-all-defences|KILLED|4|
|coupon-total-all-defences|KILLED|36|
|coupon-reuse-state|KILLED|3|
|coupon-use-expired|KILLED|22|
|inventory-sku-check|SURVIVED|0|
|inventory-conditional-update|KILLED|1|
|cross-store-product|SURVIVED|0|
|employee-store-scope|KILLED|4|
|cancel-payment-guard|KILLED|3|
|cancel-uncertain-safe|KILLED|1|
|cancel-remote-proof|KILLED|4|
|coupon-member-limit|KILLED|42|
|coupon-total-limit|SURVIVED|0|
|coupon-expiration|KILLED|1|
|coupon-claim-rate|KILLED|1|
|coupon-redis-fail-open|KILLED|1|
|payment-amount|KILLED|2|
|coupon-form-stale-response|KILLED|3|
|cart-null-store-retains-context|KILLED|1|

The three survivors remove SKU availability, product shop, or coupon total prechecks. Remaining SQL/category/conditional-update defenses still prevent the harmful result; compound cross-store and coupon capacity mutations are killed. They are retained as SURVIVED, not renamed EQUIVALENT or hidden. Per the requested policy these high-risk survivors block readiness until defense-level assertions or a rigorous equivalence review resolves them.

Reversing the Vue generation guard fails three assertions; reverting null-store cart clearing fails one. Before-fix production regression: Vue7/8 PASS and1 FAIL (activity11 overwrote22), then10/10 PASS; Node cart21/22 PASS and1 FAIL, then22/22 PASS. Both have independent baseline and mutation proof.

A separate test-framework fault regression injected DataAccessResourceFailureException into the race rejection classifier: before-fix1 assertion FAIL, after-fix ordering85 PASS. This is a runner/test reliability proof, not an extra production mutation kill. Unexpected infrastructure failures now propagate and cannot become expected race losers.
