# Business and authentication risk evidence — Phase 6Q-R2

All resources and identities are synthetic and run-owned. Ordinary and MySQL counts overlap; they are not summed into unique scenarios. [Runner](test-runner-audit.md), [mutations](mutation-testing-report.md), [machine receipts](evidence-summary.json).

| Boundary | Actual evidence | Scope limit |
|---|---|---|
| Disabled/deleted/re-enabled member | lifecycle72 + actual two-process HTTP disable/refresh/re-enable/logout | no real accounts/WeChat/SMS |
| Admin disable/re-enable/delete | reproduced before-fix assertion failure; real AdminUserMapper/transaction/service regression; rollback after revoke; member same-ID survives | legacy direct DB state writes are unsupported administrative operations |
| Typed refresh/revoke API | real API delegates/service/mappers, canonical principal and client, old rotated access, stale Redis refusal | existing reusable refresh contract; not one-use rotation |
| Multi-process shared auth | two independent JVM backends;5 rounds x20 workers for refresh/disable/logout;331 HTTP requests | auth boundary measured across processes; not all business races |
| Store product/category/order access | real HQ/staff roles and two stores; database ownership not token shop hint; HTTP B denied/A allowed | staff GUI blocked |
| Store price/SKU/stock | ordering84, catalog96, editing25 real MySQL; A/B oracle, conditional debit and product-edit/order competition | business engine tests use separate connections in one JVM |
| Cancellation/uncertainty/late success | full financial497, including cancellation108; focused guards killed by assertions | synthetic events only; no live reconciliation |
| Coupon issuance/reservation/retry/cancel | coupon285 + payment/coupon68 + Redis66, distinct clients/atomic shared state; own HTTP rights/order retries | complete claim/disable Mini UI not measured |
| Duplicate effects/release | existing compound mutants and DB bill/stock/coupon oracles preserved | three redundant-defense survivors explicitly retained |
| Actual business writes | fresh schema with0 seed INSERTs, own roles/accounts;45 HTTP checks incl two processes, historical amount unchanged after catalog edit, unpaid retry/cancel | CAPTCHA GUI writes not counted PASS |
| Payment freeze | synthetic factory tests, frozen config and request admission counters | not whole-machine packet capture |

## Authentication production review

Current-principal database row → refresh family → access row lock order is used by issuance,refresh,authorization,logout and revocation. READ COMMITTED transactions revalidate principal/family after lock; disabled/deleted accounts and stale caches cannot authorize. Cache writes occur after commit and cache failures only emit a constant warning. Typed endpoints isolate MEMBER/ADMIN and client identity; untyped compatibility delegates preserve canonical token identity.

An actual missing ADMIN revocation was found: old credentials could resume after disable/re-enable. AdminUserServiceImpl now updates principal/revokes families in one transaction, and deletion also revokes. Regression verifies another MEMBER with the same ID is unaffected; injected failure after revocation restores both principal and credentials. Existing member service disable/delete and current-principal checks remain. Ordinary AdminUser36+lifecycle72 and real MySQL lifecycle72 pass.

Reusable refresh remains the documented contract: concurrent refresh may return several access generations but only the final one remains valid. Logout of an old rotated access still finds/revokes its family. Re-enable does not resurrect revoked credentials. Refresh does not record a successful login; logout records logout, not login, and failed issuance/status checks do not update successful login timestamps.

Authorization linearizes at its DB principal/family decision; already admitted requests may finish. Directly changing status in SQL without the service/revocation contract is not supported. GUI/physical-device behaviors beyond the actual measured scenarios remain explicit. No wallet, payment or marketing behavior was changed to improve a test score.

## Actual official simulator supplement

A fresh1f8 compiled mirror verifies380 source hashes and connects only to a newly owned backend. Fifteen checks cover two stores,SKU/topping,quantity,full coupon selection,37.00 unpaid order after deliberately dropped committed response,same-key retry and cancellation. A SQL oracle confirms inventory/coupon restoration and no wallet ledger/payment/attempt records. The paired backend receipt proves cleanup. PaymentRequests=0 is measured from guarded cold boot; globalNetworkMeasured=false. This does not certify old Mini scripts or a later SHA. New synthetic menu fixtures expose product/order navigation to the already-authorized staff role and order navigation to HQ for forthcoming GUI checks, with no production permissions changed.
