# Phase 5H: production pre-payment readiness

This phase performs no live provider calls. Both `yshop.pay.wechat-v3.enabled` and
`yshop.pay.wechat-v3.reconciliation-enabled` remain false. A passing synthetic test
is not permission to enable either gate or spend money.

## Safety changes

Internal BALANCE/CASH admission now locks **order → active attempt**, before any
user/wallet lock or debit. An active external attempt (including a timed-out prepay)
rejects internal funding. Creating an external attempt races against this same order
lock: exactly one funding route is admitted. Already-paid retries remain idempotent.
Historical wallet-paid/external-attempt conflicts remain recoverable and covered by
explicit isolated fixtures; they are not reproduced through a newly unsafe admission.

New purchase bills store the order identifier in the existing `extend_field` column.
This is fulfillment metadata, not a second balance. Existing bills are not backfilled.
Balance, ledger, order, payment and new bill effects retain their existing transaction.

## Reproducible local tools

Run from the repository root with Python 3 and the project-local toolchain. No tool
accepts a secret as a command-line argument. Private inputs/reports live in the
Git-ignored sibling `.local-dev/private/`, directories 700 and files 600.

- `scripts/payment/local-ingress.py start`: real pinned ARM64 Nginx, loopback HTTPS
  `https://localhost:48443`, upstream `127.0.0.1:48081`. Synthetic CA is never installed
  in macOS trust. Access/error body logging is off, raw body and Wechatpay headers
  preserved, TLS 1.2/1.3, 64 KiB limit, provider callback response preserved, no upstream
  retry. Private container volume avoids broad Docker Documents sharing. The local
  configuration includes protected administration routes; do not publish it unchanged.
- `scripts/payment/verify-callback-ingress.py`: points the local proxy at the isolated
  application test listener, invokes real MySQL acceptance and restores the ordinary
  backend upstream in `finally`. Signature verification uses the official SDK and
  generated synthetic keys. Transport never contacts WeChat. Positive notification,
  replay, altered body, missing headers, expired timestamp, wrong merchant, size limit,
  anonymous admin rejection, encrypted provisioning and read-only acceptance are tested.
- `scripts/payment/clock-evidence.py`: Apple SNTP peers resolved over verified HTTPS,
  at least two actual samples, measured uncertainty/offset and database clock difference.
  Invalid renewal first removes the previous properties. Evidence expires after 300
  seconds and must be renewed on every instance immediately before activation. Set server-private
  `yshop.pay.preflight.clock.evidence-file` to the protected generated properties path;
  diagnostics reload this file without restarting. Missing/public/stale files fail closed. This is
  operational SNTP evidence, not an NTS claim or proof of an OS synchronization setting.
- `scripts/payment/review-local-history.py`: one read-only transaction, per-order local
  evidence and scoped dev-smoke provenance. Local paid=0 and a smoke artifact do not
  prove the absence of a remotely payable link. No financial records or markers changed.
- `scripts/payment/match-history-export.py --export PRIVATE_CSV`: matches one official
  operator-provided export per provider/merchant to the entire candidate list. Duplicate
  references fail closed. Missing/settlement-only records cannot resolve risk. It never
  makes a provider request, changes payment state, or claims export authenticity.
- `scripts/payment/test-order-plan.py [--order-id PUBLIC_ORDER_ID]`: finds existing
  1–10 cent SKU candidates and optionally verifies a newly created normal order's final
  server total, unpaid state, absence of attempts and wallet debit. No price edits,
  cart/order creation or payment calls occur.
- `scripts/payment/acceptance-harness.py --evidence-dir PRIVATE_DIRECTORY`: observes
  the database read-only and compares independently verified client/callback/query JSON.
  It never obtains a code, calls query, executes payment or mutates finances.
- `scripts/payment/verify-deployment.py --inventory PRIVATE_JSON --expected-sha SHA`:
  authenticated GET through independently authorized local TLS forwards; checks embedded
  build SHA, schema/MySQL, gates, callback route, master-key availability, merchant public
  metadata fingerprint and risk counts. Production inventory completeness requires operator
  attestation. Working-tree HEAD is never substituted for a missing running build SHA.

## Merchant provisioning

Place **only through a private editor/filesystem**, not chat, these files in
`.local-dev/private/live-merchant/`: `merchant-private.pem`, `merchant-cert.pem`,
`api-v3-key.txt`, `platform-public.pem`, `metadata.json`. The metadata fields are
`detailsId`, `appid`, `mchId`, `merchantCertificateSerial`, `platformPublicKeyId`,
`notifyUrl`. For this mini program the actual routing key is `wx_miniapp`, not a
numeric guessed detailsId. The callback URL is exactly
`https://YOUR_AUTHORIZED_DOMAIN/app-api/order/notify/wechat-v3/wx_miniapp`.

`provision-merchant.py` validates RSA 2048, certificate/private-key agreement,
certificate serial/validity, 32 UTF-8-byte API key, platform public key, identity
format, permissions and callback URL completely offline. With `--apply` it uses a
private `--connection-file` containing `adminOrigin`, `caFile`, `tokenFile`, through
verified localhost HTTPS on port 48443, with redirects/proxy environment disabled.
The existing authenticated controller encrypts secrets with server-side master key;
no encrypted envelope is exported. Both gates must be off and audit available without
active/uncertain/conflicting attempts. Configured credentials are never rotated by
this helper. Offline `configurationReady` is checked after the write; `liveReady`
remains false. A failed final diagnostic is reported, never auto-activated.

An explicitly selected `--restore-empty-placeholder` may provision only a deleted
`wx_miniapp/wx_wechat/wx_h5` row with `wxPay`, sanitized `local-unconfigured*` AppID,
blank mchId and all four secret columns blank. Restoration and encrypted write are a
single conditional UPDATE; a configured merchant cannot be overwritten. No rows are
undeleted merely to make preflight green. All actual local merchant placeholders remain
unchanged in this phase. Ordinary create/update behavior remains compatible.

The helper creates no temporary plaintext file. Original operator material is not
silently destroyed: after successful encrypted write, private backup/master-key recovery
validation and strict scan, remove the staging copies with the operator's retention
policy; retain only authorized secure backups. An absent merchant file yields the
fixed `REAL_MERCHANT_PROVISIONING` blocker, not invented credentials.

## First 1–10 cent authorization plan

Local seed has existing 1-cent items at store 2 (product IDs 16 and 17); this is not
proof of production inventory or permission to expose those items publicly. Use a
**dedicated acceptance deployment**, separate from ordinary customer traffic and access
controlled to the specifically authorized tester by the deployment/network operator.
Do not modify normal production SKU prices or existing order amounts. Use the ordinary
store → product → cart → order path without coupons/points or wallet funding. Read final
order amount from the server and confirm 1–10 cents; fees may change the total, in which
case stop. Bind a normal PaymentAttempt to that verified total. No client override.
An isolated staging host/schema/access policy must be reviewed before merchant activation;
there is no new production fixture endpoint or hidden price override in this PR.

Only after a separate explicit real-payment authorization may the tester invoke exactly
one `wx.requestPayment`. Capture minimized client result; retain verified callback evidence
and the separately authorized official query result privately; use the harness to compare
order/attempt/reference/transaction/AppID/mchId/amount/CNY, PAID states, one successful
receipt, one order-bound bill/status effect, no wallet debit and no unresolved conflict.
A client success alone never proves server fulfillment. The harness cannot authenticate
operator-supplied files. Receipt count is a database count, **not** proof of zero wire
replays; record actual callback delivery count separately. Missing observations fail closed.

## Deployment and human boundary

Follow [live-payment-rollout.md](live-payment-rollout.md) migration order and rollback.
Only MySQL 8.x >=8.0.29 is supported. Verify every instance, not only this Mac. Build an
otherwise clean checkout using `scripts/payment/build-verified-backend.py` (which checks
clean status and unchanged HEAD before/after building and embeds the actual SHA); missing provenance is
`UNVERIFIED`. Default builds do not pretend to be a verified commit.

Production public callback exposes only the exact callback location. Preserve original
body and Wechatpay-* headers, disable body/signature logging and proxy retry, preserve
status codes, apply 64 KiB/TLS settings. Admin routes stay behind authenticated private
access; callback does not require login/session. Do not inject synthetic notifications
into a real merchant/public callback. Public DNS/TLS/ingress and NTP must be separately
verified after deployment and before live gate activation. Current tests prove localhost
HTTPS only.

The one consolidated human action pack consists of merchant platform binding/JSAPI
permission, private material placement, domain/DNS/cloud authentication, complete official
history exports (including unpaid/closed requests), full production instance/access
inventory and dedicated tester deployment. **Real payment authorization is a later,
separate boundary**; completing these inputs does not authorize any live provider calls.

Never clear `prepay_requested_at`, delete an attempt, edit `paid`, fake a receipt, refund
or recharge to resolve a marker. Follow the established reconciliation/conflict runbook.
Both actual payment activation and scheduled real query/close remain disabled throughout
this phase. No live provider calls.
