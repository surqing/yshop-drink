# Payment live readiness (Phase 5F)

This phase supplies cross-provider admission control and WeChat API v3 uncertainty reconciliation. It does **not** enable live payment. Validation uses synthetic identities, generated keys, fake SDK transport and terminal fake HTTP interceptors only. WeChat payment and reconciliation remain disabled by default.

## Cross-provider boundary

`yshop.pay.wechat-v3.enabled=true` is also the live external-provider policy switch. In that mode:

- `/order/pay` rejects legacy Alipay before accessing the order/bill or provider SDK. New WeChat JSAPI payments must use the Phase 5E attempt-bound adapter; there is no legacy WeChat fallback.
- The global legacy merchant SDK builder fails closed before database/credential reads. This protects other legacy SDK callers as well; it intentionally disables legacy external runtime operations, including legacy refunds, in live mode. No new Alipay, UnionPay or other external legacy provider path is admitted.
- Creating an Alipay attempt is refused, and finalization rejects both legacy external events and bound Alipay events. Existing completed receipts remain idempotent; rejected events remain audited.
- Independently of that switch, an active attempt prevents a legacy external event from completing the order. Phase 5E's stronger WeChat history guard remains in place. Both checks use current locking reads under the order lock, with no Redis correctness dependency.

Independently of the live switch, `/order/pay` rejects legacy Alipay creation when **any active attempt** exists, including uncertain WeChat requests left after disabling live mode. Admission performs current `FOR UPDATE` reads in **order → attempt** order inside a short transaction. It completes before legacy SDK/provider I/O; no row lock is held during that I/O. With live mode disabled and no active attempt, legacy Alipay compatibility is preserved.

The generated unique active-order slot continues to permit only one active attempt per order. Changing provider/merchant cannot silently replace it. Wallet and cash logic are unchanged. If wallet/cash completes while an external attempt remains active, recovery still queries that attempt: confirmed external nonpayment can be closed, while external SUCCESS enters conflict/reconciliation rather than a second fulfillment. This is not an automatic refund facility.

Before turning on live mode, independently reconcile/close any historical external prepay URLs or provider orders created before attempts existed. A local database cannot prove that such a historical external request is no longer payable. Historical receipts are not fabricated into attempts, and switching providers cannot solve that deployment prerequisite.

Deploy the policy consistently on every instance and stop pre-5F writers before enabling live mode; a mixed deployment must not leave an older provider entry running.

## Official SDK audit

The pinned official SDK remains `com.github.wechatpay-apiv3:wechatpay-java:0.2.17`. Audited exact methods/DTOs:

- `JsapiService.queryOrderByOutTradeNo(QueryOrderByOutTradeNoRequest)`: GET by attempt `providerOrderReference`, with its bound merchant's mchId.
- `JsapiService.closeOrder(CloseOrderRequest)`: POST close with the same reference and mchId; successful SDK completion is required before storing close evidence.

The official configured HTTP client signs requests and validates provider query responses; connection retries stay disabled and timeouts bounded. No custom RSA/AES implementation, raw endpoint request, or new SDK dependency was added. Errors/timeouts/unknown states do not become proof of closure. The query result must match JSAPI, reference, AppID, mchId, CNY and amount snapshot before any action.

Reference: [official 0.2.17 JsapiService source](https://github.com/wechatpay-apiv3/wechatpay-java/blob/v0.2.17/service/src/main/java/com/wechat/pay/java/service/payments/jsapi/JsapiService.java).

## Recovery configuration

| Property | Default | Effect |
| --- | --- | --- |
| `yshop.pay.wechat-v3.enabled` | false | Payment SDK gate and live external-provider policy |
| `yshop.pay.wechat-v3.reconciliation-enabled` | false | Recovery service/scheduler gate; live gate must also be true |
| `yshop.pay.wechat-v3.reconciliation-close-unpaid` | false | Scheduler may close a verified NOTPAY order only if explicitly enabled |
| `yshop.pay.wechat-v3.reconciliation-minimum-age-seconds` | 60 | Database-time grace since prepay claim/creation |
| `yshop.pay.wechat-v3.reconciliation-lease-seconds` | 60 | Database lease; accepted range 30–600 seconds |
| `yshop.pay.wechat-v3.reconciliation-delay-ms` | 60000 | Fixed delay between scheduler batches |
| `yshop.pay.wechat-v3.reconciliation-batch` | 25 | Bounded candidate batch, capped at 100 |

`WechatV3ReconciliationService.reconcile(attemptId, closeUnpaid)` is a trusted internal service entry, not a new HTTP/client endpoint. There is no synthetic production endpoint. The conditional scheduler selects eligible database attempts. Multiple instances compete for a token/lease; correctness uses database time, order → attempt locks, conditional UPDATEs and the existing unique slot/transaction constraints. No Redis lock or synchronized block is used.

The service rejects an outer transaction so SDK I/O never spans order/attempt locks. A short claim transaction commits before query/close. After querying NOTPAY, a second short lock/lease check prevents closing an already-finalized attempt. Provider close itself atomically refuses an actually paid provider order. A callback can still arrive during external I/O; the post-close conditional transition cannot downgrade PAID. It is impossible to make local locks atomic with a remote provider, so both provider close semantics and the final local state check are required.

An expired worker cannot commit a terminal transition or clear a replacement worker's lease. If a worker loses its lease after remote close, local state stays active; a later verified CLOSED query completes the termination. Repeated queries may occur after failures/lease expiry, but prepay is never resent.

## State machine and proof

No additional Payment Intent/balance table or status enum was introduced. Add four attempt metadata fields: `reconciliation_token`, `reconciliation_lease_until`, `remote_terminal_state`, `remote_confirmed_at`. The last two are immutable terminal evidence, not secrets or raw provider payloads.

| Verified query result | Behavior |
| --- | --- |
| SUCCESS | Construct the trusted success event and call the shared `verifiedSuccess()` / `acceptAttemptVerified()` path; durable receipt and exactly-once finalization/recovery remain unchanged |
| NOTPAY, close disabled | Keep active; report NOTPAY |
| NOTPAY, close requested | SDK close; only successful close plus current lease and active attempt may commit CANCELED with CLOSED evidence |
| CLOSED / REVOKED | Commit CANCELED with that verified evidence |
| PAYERROR | Commit FAILED with verified PAYERROR evidence |
| USERPAYING / ACCEPT / REFUND / unknown | Remain active/uncertain; do not close or fulfill |
| Transport error, invalid signature, malformed response, identity/amount/reference mismatch | Remain active/uncertain; no financial state change |

`prepay_requested_at` is never cleared. Remote-confirmed termination retains original prepay/reference and request marker, releases only the generated active slot, and prevents reactivation. A retry creates a **new server-selected generation key and provider reference**, only after the previous attempt is terminal. Concurrent retries reuse the same new active record. A still-active uncertain attempt prevents a retry. Late SUCCESS for a terminated attempt becomes durable reconciliation and never automatically fulfills.

`2026-10-04-payment-live-readiness.sql` extends the existing terminal CHECK and immutable triggers to permit only evidence-backed terminal transitions for issued/prepay-created attempts. It preserves immutable amounts, identities, prepay references, transaction/event IDs and the no-delete guard. It is rerunnable, changes no historical financial data and must run in **one maintenance session with workers/financial writers stopped**, after Phase 5D/5E migrations and before deploying the new mapper. Trigger replacement is not an online application migration.

## Verification

The new database suite exercises production services/mappers under H2 and real isolated MySQL 8/InnoDB. Coverage includes cross-provider/legacy rejection, unchanged balance completion, recovery gates, timeout without prepay resend, confirmed versus failed close, explicit terminal/unknown states, query identity/amount/reference conflicts, 20 concurrent recoveries, 20 mixed callback/query SUCCESS calls, separate-thread callback/close winners, late success, rollback/database recovery, expired-owner fencing, immutable remote proof, generation retry and migration rerun. Official SDK query signature verification and close status handling are additionally exercised through terminal fake HTTP interceptors with no socket/DNS/network.

Run the existing focused payment/attempt/wallet/credential/v3/callback/log suites including `PaymentLiveReadinessDatabaseTest`; run `python3 tests/payment/mysql-acceptance.py --readiness` for the isolated real database acceptance. The runner drops its random schema/account and private JDBC file afterward; it never uses the development wallet. Also run backend full build, Vue `ts:check` / `build:prod`, UniApp compile, real mini-program smoke (`paymentRequests=0`) and the unchanged strict secret scanner plus current-session/runtime artifact checks.

Phase 5F results: **359 focused PASS** (311 retained Phase 5E + 47 readiness + 1 legacy SDK gate test); **288 MySQL PASS** (241 retained + 47 readiness), zero failures/errors, MySQL 8.0.46 with 12/12 InnoDB tables. Backend **55/55 SUCCESS**, Vue type/build and UniApp compile PASS, mini-program smoke **14/14 PASS**, zero uncaught exceptions and `paymentRequests=0`. Frontend baseline 6 PASS, scanner regression 3 PASS and strict repository/runtime/current-session scans PASS. Local maintenance migration preserved balance/ledger/recharge/payment fingerprints and left all API v3 secret fields empty. Restarted backend health UP with zero ERROR lines, Vue HTTP 200. Existing nonfatal compiler/Swagger, Vue/Browserslist/UnoCSS and developer-tool deprecation warnings remain. Packaging skips tests; the focused suites and real database acceptance are separate runs.

## Remaining live rollout requirements

Separately authorize and provision the merchant's own bound AppID/mchId, JSAPI permission, encrypted merchant private key/API v3 key, certificate serial and official WeChat platform public key/ID. Establish trusted HTTPS ingress retaining raw notification bodies/headers, synchronized server time, master-key protection/rotation, reconciliation monitoring and a runbook for payment conflicts/late success. Reconcile pre-attempt legacy provider orders before enabling the switch. H5, v3 refund and real recharge remain out of scope; no automatic refund or operator HTTP console is provided by this phase. Real merchant/provider integration and callback verification over public ingress remain unperformed by design.

Financial safety: no real merchant credentials, prepay, order query, remote close, payment callback, wx.requestPayment, refund, recharge or funds movement were used.

Review-fix rerun: backend **55/55 SUCCESS**, focused **359 PASS**, isolated MySQL **288 PASS**, zero failures/errors. The three added cases verify disabled-live admission rejection for active and uncertain WeChat attempts (legacy provider calls = 0), and preserved disabled-live compatibility without an active attempt (provider call = 1, no transaction held during I/O). Query/close, leases, finalization and wallet logic are unchanged. Frontend/UniApp/smoke results above are the existing Phase 5F baseline, not reruns for this backend-only fix.
