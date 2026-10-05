# Live payment rollout: Phase 5G

This PR performs **offline, read-only** preflight and risk reporting only. Both live payment and recovery remain disabled by default. No real merchant credentials or payment APIs are used in development acceptance. A PASS is a local diagnostic result, **not** merchant authorization, proof of public reachability, or permission to send a payment. Real integration requires a separate explicit human authorization.

## Operator diagnostics

Authenticated administrators with `pay:merchant-details:query` can use:

- `GET /admin-api/pay/live-preflight/check?detailsId=<selected merchant details ID>`
- `GET /admin-api/pay/live-preflight/audit`

Results contain fixed check codes, booleans, aggregate counts and clock measurements only. No PEM, encrypted credential envelope, API key, payer identity, transaction payload, callback body or merchant material is returned. Do not expose these endpoints through PermitAll, public ingress or unauthenticated actuator.

`configurationReady` checks the selected nondeleted `wxPay`/V3/nontest merchant, AppID/mchId, certificate serial, platform key ID/public key, authenticated encrypted private/API v3 keys and a local official SDK crypto config build. It also requires the actual schema and an available audit. It does not build a payment client/HTTP transport or contact a provider. API v3 plaintext must decode to 32 UTF-8 bytes. notifyUrl must be HTTPS on 443/default port, an exact raw `/app-api/order/notify/wechat-v3/<detailsId>` path, with no query, userinfo or fragment; loopback hosts are rejected. It cannot prove DNS/TLS, merchant ownership, certificate serial/private-key association, or AppID authorization.

`liveReady` additionally requires all of:

- `yshop.pay.wechat-v3.enabled=true`: this is the existing legacy external live gate. Reconciliation with live disabled is a configuration error.
- `yshop.pay.wechat-v3.reconciliation-enabled=true`.
- No blocking uncertain/stale/paid-order conflict, expired lease, PAYMENT_CONFLICT, RECONCILIATION_REQUIRED or late-success evidence.
- Explicit operator evidence: `yshop.pay.preflight.ingress-verified=true` and `yshop.pay.preflight.history-reviewed=true`. Defaults are false; they attest manual checks, not automatic network probes.
- Recent trusted clock evidence. `yshop.pay.preflight.clock.observed-at` is an ISO UTC timestamp recorded when the host's NTP offset was measured, and `yshop.pay.preflight.clock.ntp-offset-millis` is that signed offset. Age must be 0–300 seconds, absolute NTP offset ≤1000 ms; the database epoch difference must also be ≤5000 ms. Missing/stale evidence fails closed. These diagnostic settings never enable or disable core payment processing.

The server reports current UTC, DB epoch offset and NTP evidence freshness; it makes no NTP/provider network request. Obtain evidence from the deployment's trusted NTP service (for example `chronyc tracking` with a separately recorded UTC observation). Merely enabling “network time”, or comparing application time to the same machine's DB, is not proof of synchronization. Renew the evidence and rerun preflight immediately before a human-authorized first transaction. The existing callback rejects timestamps beyond ±300 seconds; keep clocks much closer than that limit.

## Schema and migration order

Back up and rehearse against an isolated MySQL 8/InnoDB database. MySQL ≥8.0.29 is required by the existing trigger migrations. Stop **all** payment writers and recovery workers; apply DDL in a single operator maintenance session. Do not run opening-balance migration automatically or change wallet balances.

1. Phase 5B: `2026-10-03-payment-finalization.sql`.
2. Phase 5C: `2026-10-03-wallet-ledger.sql` (schema/guards only; independently review any opening-ledger operation).
3. Phase 5D: `2026-10-04-payment-attempt.sql`.
4. Phase 5E: `2026-10-04-wechat-v3.sql`.
5. Phase 5F: `2026-10-04-payment-live-readiness.sql`.
6. Phase 5G adds no schema and performs no financial migration.

Preflight verifies required tables/columns, InnoDB, the generated active slot, unique attempt references/transactions/idempotency, payment/wallet uniqueness, enforced CHECK constraints and immutable triggers. Missing migration/insufficient metadata privileges fails closed; no auto repair is attempted. Replacing triggers is **not** an online deployment. Upgrade every app instance to the same reviewed baseline, retire old SDK/provider entry points, and verify consistent gates on every instance before permitting client traffic.

## Merchant preparation — human only, not performed by this PR

In a separate authorized task, the owner must supply/confirm their direct merchant account, AppID-to-mchId binding, JSAPI/mini-program entitlement, approved test payer and detailsId selection (the current mini/public-account routing selects existing configured IDs). Configure `wxPay`, `V3`, `isTest=0`, AppID, mchId, merchant certificate serial, the matching merchant private key, API v3 key, verified WeChat platform public key and its key ID, and notifyUrl. Submerchant/service-provider mode is not supported by this direct-merchant adapter.

Keep the 32-byte credential master key in server-private secret storage with backups and restricted permissions. Enter private/API keys only through the existing authenticated HTTPS merchant configuration/encryption path or a reviewed server-private provisioning process. Store authenticated `enc:v1:` envelopes bound to detailsId/field; never transplant envelopes, seed real secrets, print credentials, commit them, include them in frontend/UniApp artifacts or capture them in request logs. Keep live/recovery false during provisioning. Rerun offline preflight; fix every merchant/schema failure before considering activation.

## HTTPS callback ingress

Public path: `POST https://<owned-domain>/app-api/order/notify/wechat-v3/<detailsId>` (direct merchant JSAPI adapter). Provision a valid public certificate and TLS 1.2/1.3 on 443, DNS, routing and provider-permitted reachability. This phase supplies documentation/tests only; it does not invent a domain, provision a certificate or test the real WeChat service.

- Preserve exact raw UTF-8 body bytes, JSON whitespace/order and content type. Do not parse/reserialize, transform, decompress/recompress or inject characters through proxy/WAF/Lua middleware. The app verifies the original body through the official SDK and decrypts the notification.
- Forward `Wechatpay-Serial`, `Wechatpay-Timestamp`, `Wechatpay-Nonce`, `Wechatpay-Signature` and optional `Wechatpay-Signature-Type` unchanged. Do not trust `X-Forwarded-*` as signature/merchant evidence.
- Preserve method/path/detailsId. URL detailsId only selects the verification configuration; the persisted Payment Attempt reference/binding remains the financial proof.
- No login/session/cookie, captcha, redirect, CSRF challenge or browser authentication on this exact callback endpoint. Existing `@PermitAll` stays in place; **do not** globally disable security or make admin endpoints public. Internal backend listens privately; only the trusted proxy may reach it.
- The controller limit is 65536 UTF-8 bytes. Keep proxy body limit consistent, prevent retries/rewrites that alter payload, preserve application response status/body, and avoid access/request-body/header-signature logging. SDK verification rejects altered signatures, ciphertext, absent headers and stale timestamps. Valid committed receipts ACK 204; transient processing failure yields retryable failure. Measure end-to-end ACK latency and database contention; satisfy the provider's response-time requirements before enabling live traffic.

Example location snippet inside an already configured/verified TLS server (a **template**, not a deployed ingress):

```nginx
location ~ "^/app-api/order/notify/wechat-v3/[A-Za-z0-9_-]{1,32}$" {
    client_max_body_size 64k;
    proxy_pass http://127.0.0.1:48081; # no URI replacement/trailing path
    proxy_pass_request_body on;
    proxy_pass_request_headers on;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto https;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_intercept_errors off;
    proxy_next_upstream off;
    access_log off;
}
```

Set forwarded headers at the trusted boundary; do not enable arbitrary client forwarded-header trust on the backend. Body buffering is allowed if bytes remain identical. Do not put request-body transforms, `proxy_set_body`, auth gates or custom error rewrites on this route. Test signed **synthetic** notifications through the complete proxy/security chain, verify raw-body/header preservation, idempotent replay and tamper rejection. In-repo tests verify application ingress and the official signature/decryption/finalization chain; they do not prove a public proxy or TLS deployment.

References: [official Java SDK notification/raw-body guidance](https://github.com/wechatpay-apiv3/wechatpay-java), [official WeChat notification processing](https://pay.wechatpay.cn/doc/v3/merchant/4012791836), [Nginx request body/header forwarding](https://nginx.org/en/docs/http/ngx_http_proxy_module.html).

## Historical provider risk and observability

Audit reports:

- Active and uncertain attempts; active older than 30 minutes; expired recovery leases; active order owner/amount/paid/canceled/deleted/refund conflicts.
- PAYMENT_CONFLICT, RECONCILIATION_REQUIRED, transaction-conflict receipts and success evidence after a terminal attempt.
- Pre-attempt WECHAT/ALIPAY receipts (including settled history) and unpaid legacy-external order candidates without an attempt.

Reports are aggregate-only, point-in-time operational evidence, with no row changes, marker clearing, remote close, refund or paid mutation. Normal fresh active records and settled historical receipt counts are informational. Historical candidates require a documented operator investigation before setting `history-reviewed`; a zero count cannot prove absence of old payable URLs. There is **no historical legacy prepay URL registry**, and canceled/deleted or previously unpaid legacy orders may still have unknown provider-side requests. Review provider-side history and old logs/integration records in a separately authorized live task; do not use this phase to query/close real orders. Retain IDs/evidence in restricted incident tooling, not public logs/chat.

Record counts periodically through the authenticated audit endpoint; alert on uncertain/stale attempts, lease expiry, conflicts/late success and unavailable diagnostics. Correlate the existing safe `eventId/status` recovery/finalization logs with restricted database evidence. This PR adds no unattended scheduler, provider query or automatic incident disposition. Old immutable incident rows can remain blockers; resolving them requires a reviewed operator process, never arbitrary SQL to make preflight green.

## Controlled activation and first ≤¥0.10 acceptance

**These are future human actions. This PR does not authorize or perform them.**

1. Confirm all reviewed migrations/instances, backups, observer/on-call and incident/runbook ownership. Verify secrets and merchant authorizations privately.
2. Validate full HTTPS/TLS/proxy ingress using synthetic signatures; document DNS/certificate/raw-body/header/timestamp/ACK evidence; set ingress attestation only after passing.
3. Review all audit counts and historic provider exposure; document known remote order outcomes. Stop if uncertain/conflict/unresolved remote payable requests remain. Set history attestation only after the investigation.
4. Obtain fresh trusted NTP evidence on **each** host. Run preflight on each instance with live off; require configurationReady, expect liveReady=false until activation. A successful offline config check is not authorization.
5. Under a maintenance/traffic block and explicit new human authorization, enable `yshop.pay.wechat-v3.enabled` consistently on every instance. Legacy WeChat remains disabled; legacy Alipay is always refused in live mode and also refused with any active attempt when live is off. Keep ordinary wallet policy unchanged; reject the test if the order has already been wallet/cash paid or carries another funding source.
6. Enable `yshop.pay.wechat-v3.reconciliation-enabled` consistently. Initially keep `reconciliation-close-unpaid=false`; review minimum-age/lease/batch settings and only separately authorize remote close. Recovery activation sends real queries for eligible attempts — it is a live action requiring authorization, not a harmless toggle.
7. Rerun preflight immediately before opening the test: liveReady=true, every check passed, no blocking risk and fresh clock evidence. Reconfirm all instances/gates and allow exactly one owner-approved test payer/order.
8. Price must come from the server and be **1–10 integer cents**, not a frontend override. Record immutable attemptId/reference/merchant/amount binding privately; do not modify a real product's price or order paid status merely to force a test. Explicitly approve the one real prepay and client payment before proceeding; no unattended repeats.
9. Observe the client **and** signed callback **and** a separately authorized official SDK query **and** database result: identical reference, provider transaction, merchant/AppID/CNY/amount. Exactly one attempt PAID, one payment SUCCESS, one order paid and one set of fulfillment effects. A client success display is insufficient. Query/callback replay must remain idempotent; no second fulfillment.
10. Verify normal user/order view and inventory/bill effects, audit counts and ACK latency. Stop after this single ≤¥0.10 transaction. Do not automatically refund, recharge, retry an uncertain prepay or run more payments. Record the actual settlement and obtain separate approval for any later refund.

## Rollback and conflict/late-success handling

First stop new payment admission/client traffic. Keep a known-good attempt-bound callback receiver and durable inbox available for already in-flight payments; turning off live also disables factory verification/recovery, so a blind flag rollback may delay acknowledgements and leave financial uncertainty. Plan recovery/receipt coverage before any switch change. Never reintroduce legacy external entry points or roll back below the active-attempt admission guard while attempts remain active. Do not downgrade schema or remove immutable guards.

If live/recovery must be disabled, do so consistently, preserve all rows/references/markers and document that provider-side orders may still be payable. Alipay admission remains fail-closed for active/uncertain attempts even with live off. Retain platform retries/evidence and resume the reviewed callback/recovery path only under human authorization. Do not retry prepay, create another attempt, clear `prepay_requested_at` or change `paid` to bypass uncertainty.

For PAYMENT_CONFLICT, RECONCILIATION_REQUIRED or terminal late success: halt that order's further admission, preserve original receipts/immutable bindings, compare restricted order/attempt/provider transaction/merchant/amount and settlement evidence. A late provider success after confirmed closure must not automatically fulfill; wallet-first/provider-paid conflicts must not debit again. Escalate to the merchant operator for a documented decision. Any actual query, close, refund or financial correction needs separate approval and a reviewed supported path; never manually edit order paid/ledger/balance/attempt markers or delete conflict evidence.

## Phase 5G acceptance and remaining prerequisites

Synthetic tests cover missing/malformed/wrong-key credentials, notify URL/identity checks, complete offline config, no SDK HTTP/client construction, safe output, gates, fresh/unknown/stale clocks, unavailable schema/audit, read-only risk detection, actual MySQL migration constraints and raw ingress signature/decryption/replay/tamper/header rejection. Existing payment/v3/attempt/wallet/recovery tests remain unchanged.

Still human-required before real acceptance: genuine direct-merchant entitlement and AppID binding; private provisioning/ownership of all key/certificate material; owned public domain/TLS/HTTPS deployment and proxy acceptance; fresh trustworthy NTP evidence; full multi-instance upgrade and historical provider investigation; incident operator/rollback coverage; an explicitly approved server-priced ≤¥0.10 order/payer; new authorization for the particular real API/client-payment actions. **None of these has been claimed completed by synthetic tests.**

Acceptance (2026-10-04): **396 focused PASS**, **294 isolated MySQL 8.0.46/InnoDB PASS** (12/12 InnoDB, 0 failures/errors, auto-cleaned), backend **55/55 SUCCESS**, Vue type/build PASS, UniApp compile PASS, mini-program **14/14 PASS** with zero uncaught exceptions and `paymentRequests=0`, frontend 6 PASS, scanner regression 3 PASS, strict source/runtime/current-identity scan PASS. Runtime admin endpoints work and anonymous access is rejected; local schema PASS, no active/uncertain/conflict/late-success rows, **11 historical unpaid legacy-external candidates**. Local `configurationReady=false` / `liveReady=false` remain expected without genuine merchant provisioning, public-ingress/history attestations and fresh NTP evidence. No real merchant/provider or financial action was performed.

## Phase 5H tooling

See [production-prepayment-readiness.md](production-prepayment-readiness.md) for offline provisioning, actual local TLS ingress, per-instance verification, history exports and the read-only first-payment harness. Active external attempts now reject BALANCE/CASH admission before wallet debit. Synthetic passing checks do not authorize live provider requests.
