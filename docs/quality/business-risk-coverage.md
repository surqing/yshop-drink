# Business risk evidence — Phase 6Q-R1

Evidence is scoped and independent of file/class existence. [Exact asset inventory](test-inventory.md), [run receipts](evidence-summary.json), [coverage](test-coverage-report.md), [mutations](mutation-testing-report.md).

| Boundary | Actual evidence | Remaining limit |
|---|---|---|
| Disabled/deleted member issuance/refresh/access | OAuth2LifecycleDatabaseTest69, real MySQL69, typed API/filter, own HTTP login+refresh rejection | no real accounts or WeChat/SMS calls |
| Logout/revocation/old rotated access | DB family deletion, DB-backed access check, stale-cache service instance; HTTP logout/refresh denied | request already admitted before disable may finish |
| Refresh/status/logout concurrency |20 rounds each,20 workers, row locks; shared DB, two independent cache/service instances | same JVM; not two separately deployed processes |
| Member/admin identity | typed refresh/logout and actual request filter; admin/member HTTP credentials isolated | full browser authentication still CAPTCHA-blocked |
| Store employee scope | actual HQ/staff roles and two stores; HTTP product/order B denied, A allowed; ordering DB suite | GUI editing with staff not measured |
| Order/attempt cancellation race | existing financial/cancellation tests kept; real MySQL497; order→attempt/event locks; unchanged snapshots | broad mutation operators with runtime errors remain INCONCLUSIVE |
| Server price/stock/SKU | ordering82 including A1.23/B9.87 independent oracle; MySQL488; conditional stock update and concurrency | not production-load performance |
| Coupon claim/preoccupation/cancel | ordinary and MySQL coupon tests; real Redis66; own HTTP claim+retry+reservation+release | complete Mini claim UI beyond measured checkout scope unverified |
| Complete repeated effects/release | new compound mutants detected by valid assertions and balance/stock/coupon/bill DB oracles | retained redundant-defence survivors are not hidden |
| Payment freeze | client factory frozen synthetic key test; compound mutation killed; owned HTTP/Mini hooks measured zero | dispatcher/compile do not measure whole-machine network |
| Actual writes | owned94-table schema,0 seed INSERTs, dedicated accounts/roles, independent Redis/backend; HTTP22; actual official Mini fixture | admin GUI CRUD slider not completed; not claimed PASS |

## Authentication contract

Token creation, refresh and authorization consult current principal and refresh-family/access-row evidence in the database. Disabled/deleted status cannot be bypassed by a stale Redis cache. Principal→family→access lock order and READ COMMITTED transactions serialize refresh with disable/logout; cache changes happen after commit. Disable/delete revokes credentials in the same transaction. Re-enable does not resurrect them. Historical rotated access can still identify its family for logout; revocation is idempotent. Member and admin overloads validate user type,client and canonical identity.

The existing reusable refresh-token contract is preserved: repeated valid refreshes rotate access; only the current access token is valid, while the refresh token remains reusable until expiry/revocation. This is not one-use refresh-token rotation. Failed issuance/disabled refresh do not record successful login; logout logs LOGOUT rather than LOGIN and does not update last-login success.

Conservative limits: authorization is linearized at its current-principal/family decision. An already admitted request is not retroactively aborted. Real WeChat auth, SMS transport, production merchant activity and multi-process scheduling are neither needed nor claimed tested.
