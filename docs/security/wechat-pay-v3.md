# WeChat Pay API v3 adapter (Phase 5E)

The `/app-api/order/pay` WeChat JSAPI branch now uses a stored payment attempt and the official `com.github.wechatpay-apiv3:wechatpay-java:0.2.17` SDK. This adapter is **disabled by default** (`yshop.pay.wechat-v3.enabled=false`). This change was accepted with synthetic identities, generated keys and fake transports only. It is not a live merchant rollout.

## Audit and compatibility boundary

- `AppStoreOrderServiceImpl.pay()` formerly supplied the business order reference and an order/user-bill amount to `MerchantPayOrder` / `PayServiceManager`, backed by `pay-java-wx:2.14.9` and `pay-spring-boot:1.0.5`. Its WeChat branch now routes only mini-program and official-account JSAPI clients to the v3 adapter. H5 WeChat payment is explicitly rejected; there is no v2 fallback. The existing response envelope (`data`, `trade_type`) remains compatible.
- Merchant selection remains server-side (`PayIdEnum.WX_MINIAPP` / `WX_WECHAT`), and the payer openid comes from the authenticated member record. Frontend values cannot select a merchant, supply an openid, or set the payment amount.
- `WxPayMessageHandler` / legacy callbacks and `PayServiceManager` remain for historical compatibility. The old SDK builder refuses V3 merchant records. `PaymentProcessor` rejects legacy WeChat completion for **any WeChat attempt history**, including terminal attempts. It locks the order then performs a current, locking attempt read, so an outer InnoDB repeatable-read snapshot cannot hide a concurrently created attempt. A raw attempt reference cannot be guessed into a business order.
- `PaymentFinalizationService.acceptAttemptVerified()` retains the Phase 5D durable receipt, conflict auditing, event → order → attempt locks, atomic fulfillment and rollback/recovery. Alipay, balance and cash completion remain unchanged; no refund or recharge adapter is added.

## Merchant data and deployment migration

Apply `yshop-drink-boot3/sql/migrations/2026-10-04-wechat-v3.sql` **in a single MySQL 8 session**, after the existing credential, wallet and attempt migrations. It is additive and rerunnable and does not change balances, payment records or historical credentials.

| Field | Role |
| --- | --- |
| `wechat_api_version` | Explicit `V3` selection; historical records remain NULL |
| `merchant_certificate_serial` | Serial of the merchant signing certificate |
| `platform_public_key_id` | Official WeChat Pay public-key ID |
| `api_v3_key` | Encrypted, write-only 32-byte API v3 secret |
| existing `key_private` | Encrypted merchant RSA private key (PEM) |
| existing `key_public` | Official WeChat Pay verification public key (PEM), not a private key |
| existing `appid` / `mch_id` | Identity snapshotted into the attempt |

`api_v3_key` uses the existing AES-GCM credential envelope with merchant ID and field name as AAD. Admin create/update explicitly encrypts replacements; blank/absent input preserves existing material. Read, page and Excel models expose only `apiV3KeyConfigured`; historical secrets are never sent to the frontend. Audit sanitation already includes this field; the entire v3 callback body is omitted from access/error request logs. Errors crossing the adapter boundary contain static categories, not SDK response bodies or credential-bearing causes.

`EncryptedWechatV3ClientFactory` decrypts only while building a runtime SDK client, and does not cache plaintext material or clients. It requires a live-mode, nondeleted V3 WeChat merchant and an HTTPS callback URL exactly matching `/app-api/order/notify/wechat-v3/{merchantDetailsId}` (no query, fragment or userinfo). The SDK uses `RSAPublicKeyConfig`; no automatic certificate downloader, network initialization, or homemade RSA/AES implementation is involved. SDK HTTP connection retries are disabled; connect/read/write timeouts are bounded.

## Prepay binding and uncertainty

1. Validate the server member openid and enabled merchant configuration before creating an attempt.
2. Create/read the WECHAT attempt using the stable per-order key `wechat-v3-jsapi`. Existing single-active-attempt, ownership, payable order and immutable snapshot rules apply.
3. Check runtime AppID / mchId against the attempt snapshot. Build the official SDK request from `provider_order_reference`, `amount_cents`, CNY and those snapshots; do not use the business order reference or a frontend amount.
4. Atomically claim `prepay_requested_at` under order → attempt locks, committing **before** provider I/O. Only the claimant sends the request; neither an outer caller transaction nor a database lock is held during HTTP.
5. Persist `prepay_id` through `recordPrepay()` before signing/returning parameters. The frontend receives exactly `timeStamp`, `nonceStr`, `package`, `signType=RSA`, `paySign`. The SDK Signer generates RSA using the official JSAPI message layout. Repeated requests with a committed prepay reference return fresh signed parameters without sending another prepay request.

A timeout, invalid response, or reference persistence failure retains CREATED and the immutable request marker. It cannot be locally terminated, retried by transport, or replaced with a new attempt. A database trigger rejects marker clearing and terminal transitions after a claimed request. PREPAY_CREATED also retains its active slot. Operator reconciliation / provider query and trustworthy remote-close evidence are future work, required before permitting replacement. If a verified success wins before prepay persistence, finalization may commit PAID; the pay request fails safely without returning unusable parameters or resending.

## Notification proof and completion

The independent POST `/app-api/order/notify/wechat-v3/{detailsId}` adapter requires the signed Wechatpay headers, checks a ±300 second timestamp window and caps accepted body size at 64 KiB. `NotificationParser` verifies the official platform signature and decrypts AES-GCM with the SDK. SUCCESS / JSAPI / CNY and positive amount are required. AppID/mchId must match the runtime verifier and immutable attempt identity. `out_trade_no` resolves **only** through the attempt reference; unknown attempts are not fulfilled. URL `detailsId` chooses verification material and must also match the stored merchant binding; it alone is never binding evidence.

The adapter submits the verified event to `acceptAttemptVerified()`. The durable receipt preserves canonical order ID, raw reference and attempt ID. Finalization rechecks provider, merchant, identity, snapshot and current order amount under locks. Transaction/reference uniqueness, replay idempotency, single fulfillment and rollback/recovery remain enforced. Late success for terminated attempts enters reconciliation instead of fulfillment. Accepted/duplicate events return HTTP 204; unknown/unacknowledged results return 503; invalid notifications return 400. No raw body, headers or SDK material is returned or logged.

## Reproducible verification

Use the existing local JDK 17/Maven environment and private Maven cache. Run the payment/wallet/attempt/credential/callback/log focused suites, then `python3 tests/payment/mysql-acceptance.py --v3`. The latter creates a random isolated MySQL 8/InnoDB schema/account, runs the production mappers, migrations and transaction services, and removes both afterwards. No development wallet is used.

V3 tests use generated merchant/platform RSA pairs, a generated API v3 key, the official SDK signer/verifier/encryptor/parser, fake SDK transport and a terminal fake OkHttp interceptor (no socket/DNS/provider call). Coverage includes signed request/response verification, five-field parameters, concurrent prepay/callback ×20, uncertain responses, identity/amount/reference/transaction conflicts, callback HTTP semantics, legacy closure, early notification, fault rollback/recovery, migration rerun and the actual MySQL repeatable-read current-read guard.

Also run the full backend build, Vue type/build checks, UniApp compilation, the existing mini-program smoke (`paymentRequests=0`) and strict repository/runtime secret scans. The smoke uses ordinary mini-program login and unpaid-order navigation and never invokes `/order/pay` or `wx.requestPayment`.

Phase 5E results: focused regression **311 PASS** (270 retained baseline cases + 37 adapter cases + 4 credential cases); isolated MySQL **241 PASS**, zero failures/errors, MySQL 8.0.46 and 12/12 InnoDB tables. Backend build **55/55 SUCCESS** with tests skipped during packaging and focused suites run separately; Vue type/build and UniApp compilation passed. Real mini-program smoke **14/14 PASS**, zero uncaught exceptions, `paymentRequests=0`. The MySQL old-snapshot guard runs at REPEATABLE READ; H2 uses READ COMMITTED for the corresponding concurrent guard because its locking snapshot semantics differ from InnoDB.

Strict repository/runtime scans passed, including the current simulator login/session values compared in memory against code, compiled artifacts and logs. The stock scanner was unchanged; its 3 regression tests and the 6 frontend baseline tests passed. Local additive migration preserved existing balance/ledger/recharge/payment fingerprints and left all API v3 secret fields empty. The restarted backend was healthy with zero ERROR lines; Vue served HTTP 200. Existing nonfatal compiler/Swagger, deprecated Vue selector/Browserslist/UnoCSS and tool Node deprecation warnings remain.

## Before a separately authorized live rollout

Obtain the merchant's authorized AppID/mchId binding, merchant private key/certificate serial, API v3 key and official platform public key/ID. Provision secret replacements through the encrypted server-side path, with the credential master key stored privately. Establish trusted public HTTPS ingress preserving raw body/signature headers and accurate server time; validate callback and merchant permissions. Provide uncertainty reconciliation/remote-close and operational monitoring before enabling live payment. H5, v3 refund and live recharge are outside this PR. No real merchant material or financial transaction was used for this delivery.

Official references: [Java SDK](https://github.com/wechatpay-apiv3/wechatpay-java), [JSAPI/mini-program prepay](https://pay.wechatpay.cn/doc/v3/merchant/4012791897).
