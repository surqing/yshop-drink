# Payment Attempt foundation (Phase 5D)

An external payment success needs to identify a previously created server-side attempt, not merely an order ID supplied in a callback. This change adds that binding and an independently durable receipt followed by atomic finalization. There are **no new HTTP endpoints, SDK prepayment calls, live callbacks or client payment calls**. Synthetic events/references exist only in tests; the production service accepts a success event only from trusted server code that has already authenticated the provider notification.

## Audit and compatibility boundary

- `AppStoreOrderServiceImpl.pay()` reads the order amount from the server, chooses fixed merchant IDs according to provider/client type, builds `MerchantPayOrder`, and calls `PayServiceManager.getOrderInfo()` (WeChat MWEB/JSAPI) or `toPay()` (Alipay WAP). It still uses the business order reference. The old bill-based recharge placeholder is not activated.
- `MerchantPayOrder` in the existing third-party SDK holds merchant selection and provider order data, but does not establish a persisted attempt binding. `PayServiceManager` builds/delegates to provider services; its SDK call is outside this new foundation.
- Existing WeChat/Alipay handlers verify their notifications and pass `PaymentSuccessEvent` to the old `PaymentFinalizationService.accept()`. The callback URL's merchant ID selects a verification configuration; it does **not** prove an attempt binding.
- The old `yshop_order_payment` inbox deduplicates provider transactions and enforces one successful finalization per order. This PR adds an optional attempt association and a separate bound entry; it does not replace that inbox.
- **Legacy external pay/callback paths remain unbound in this PR. Real payment rollout must switch both request creation and verified notification handling to the attempt-bound path.** This foundation alone does not make the old paths ready for live payment.
- BALANCE/CASH compatibility, wallet ledger, refund flow, balances and existing idempotency checks remain unchanged. This PR does not enable recharge, refunds or provider APIs.

The existing WeChat SDK is `pay-java-wx:2.14.9` behind `pay-spring-boot:1.0.5`, using the API v2/XML unified-order path. No v3 dependency, bean or eager provider-network initialization is introduced.

## Schema and migration

Run `yshop-drink-boot3/sql/migrations/2026-10-04-payment-attempt.sql` with an operator DDL account, using the MySQL client in **one connection** (session variables, prepared ALTER statements and DELIMITER are used). Deploy DDL before the new payment mapper. MySQL 8.0.29+ supports the rerunnable `CREATE TRIGGER IF NOT EXISTS`; acceptance uses MySQL 8.0.46/InnoDB. Rerunning preserves existing rows, references, triggers and payment data. The runtime account need not create triggers.

`yshop_order_payment_attempt`:

| Fields | Meaning |
| --- | --- |
| `attempt_id`, `provider_order_reference` | 32 lowercase UUID hexadecimal characters; provider reference equals attempt ID, distinct from business order ID |
| `order_id`, `uid`, `idempotency_key` | immutable order, owner and request identity |
| `provider`, `merchant_details_id` | immutable WECHAT/ALIPAY selection from trusted server code |
| `amount_cents`, `currency` | immutable positive integer cents from `Money.cents(order.payPrice)`, CNY; create API has no amount argument |
| `appid`, `merchant_identity` | immutable AppID and WeChat mchId / Alipay seller snapshots; no credential material |
| `prepay_reference`, `provider_transaction_id` | nullable provider references, bind once, never overwrite |
| `status`, `payment_event_id`, `paid_at`, `create_time`, `update_time` | lifecycle, successful receipt association and timestamps |
| generated `active_order_id` | order ID only for CREATED/PREPAY_CREATED, otherwise NULL |

Unique constraints cover `(order_id,idempotency_key)`, `active_order_id`, provider order reference, `(provider,prepay_reference)`, `(provider,provider_transaction_id)` and payment event ID. CHECKs enforce positive amount/CNY/provider/state/reference shape and paid/prepay consistency. MySQL UPDATE/DELETE triggers prevent changing immutable identity, amount, merchant snapshots, already bound references or timestamps, illegal state transitions and deletion. The service uses restricted transition UPDATEs and has no deletion endpoint. These safeguards do not claim protection against a database administrator who can disable/drop triggers.

`yshop_order_payment.attempt_id` is nullable and indexed. Old rows remain NULL; no attempts are fabricated or backfilled. The association is revalidated under row locks rather than inferred from callback URL parameters. No second Payment Intent table or wallet balance exists: the business order supplies the intention identity; `yshop_user.now_money` remains the wallet's balance truth.

The metadata mapper explicitly selects only details ID, provider type, AppID, mchId/seller for a nondeleted merchant. It does not select, copy or decrypt any merchant Secret. Creation requires an existing merchant matching the requested provider and populated public identity fields. Server callers must decide the authenticated user and allowed merchant/provider; these are not a public client-controlled API.

## Locking, idempotency and lifecycle

`PaymentAttemptService.createOrGet(uid, orderId, provider, merchantDetailsId, key)` locks **order → attempt**. It checks owner, paid=0, status=0, refundStatus=0, no deletion and a valid positive monetary amount before creating or replaying.

- Same order/key and unchanged provider, merchant, identity and amount returns the original record, including a terminal record, without reactivation.
- Changed binding or amount with the same key is rejected. A different key/provider/merchant while an active attempt exists is rejected, never silently substituted.
- Already paid, canceled/deleted or otherwise nonpayable orders reject create even for an old key. `read(uid, attemptId)` provides owned history separately.
- CREATED may become PREPAY_CREATED (bind reference once), PAID, FAILED, EXPIRED or CANCELED. Only CREATED can be explicitly terminated in this PR; retry then needs a **new key**.
- PREPAY_CREATED may only become PAID. Local timeout/cancellation does not release its slot. A future trusted remote close result is needed before introducing cancellation/expiration of prepay attempts; no timer is added here.
- A verified early success may complete CREATED before prepay response persistence. A later local prepay write cannot reactivate or modify that PAID attempt.
- Terminal late success is durably marked RECONCILIATION_REQUIRED with no automatic fulfillment; a replacement attempt is not overwritten.

Read/transition methods check owner. Transitions re-lock the latest order and attempt, and prepay persistence also compares the order's current amount with the immutable snapshot. Generated uniqueness is the database backstop for the single active slot, not a Redis lock or synchronized block.

## Bound finalization and recovery

`PaymentFinalizationService.acceptAttemptVerified(event)` is the new **server-internal** entry and requires no active caller transaction. Do not call it while holding order/attempt locks. The verified event's provider reference resolves a persisted attempt; an unknown reference is not treated as a business order ID and returns UNKNOWN_ORDER without a fabricated receipt.

`PaymentInbox.captureAttempt()` commits in REQUIRES_NEW with canonical order ID, raw provider order reference and attempt ID, using the existing transaction uniqueness/conflict audit. `PaymentProcessor` then locks **payment event → order → attempt**, checks persisted provider/merchant/AppID/merchant identity/reference/owner, snapshot amount, current order amount, lifecycle and order payability. Wrong bindings are rejected with a durable review record; URL merchant selection is never substituted for these checks.

Attempt transaction binding + PAID/event/paidAt, order paid/payType, existing bill/status/member pay-count effects and payment SUCCESS all commit in the **same database transaction**. Failure rolls them all back; the independent receipt remains FAILED_RETRYABLE and the existing recovery loop can retry it. After-commit notifications retain the existing behavior. Same verified event replay returns the idempotent result; a transaction reused across attempts records a conflict without altering the original. A second transaction for an already completed attempt enters reconciliation and cannot fulfill again.

There is no synthetic-completion HTTP endpoint or production test-mode switch. Tests construct authenticated-shape synthetic events internally and use synthetic merchant metadata. No provider SDK is invoked by these services/tests.

## Reproducible validation

From the backend root, with JDK 17 and the established Maven cache:

```sh
mvn clean install package -Dmaven.test.skip=true
mvn -pl yshop-module-pay/yshop-module-pay-biz,yshop-module-mall/yshop-module-order-biz,yshop-framework/yshop-spring-boot-starter-web,yshop-framework/yshop-spring-boot-starter-mybatis test \
  -Dtest=PaymentCredentialCryptoServiceTest,PaymentCredentialDatabaseTest,MerchantDetailsControllerSecurityTest,PaymentDatabaseTest,WalletDatabaseTest,PaymentAttemptDatabaseTest,CallbackVerificationTest,ApiAccessLogFilterTest,SensitiveDataSanitizerTest,GlobalExceptionHandlerTest,SafeSqlLogTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

From repository root, `python3 tests/payment/mysql-acceptance.py --attempt` runs all payment/wallet/attempt integration cases on an automatically cleaned random MySQL 8/InnoDB schema/account. The guard rejects the development database. Private JDBC credentials and logs stay outside Git; configuration is deleted during cleanup. MySQL cases additionally prove direct SQL snapshot/reference mutation and deletion fail and migration reruns preserve attempts and historical NULL associations. H2 exercises the services/constraints but does not claim MySQL trigger coverage. Existing 200 focused and 134 MySQL baseline cases are retained; the delivery report records actual expanded totals.

Additional validation: Vue type check/local build, HBuilderX UniApp compilation, authenticated mini-program smoke with `paymentRequests=0`, and unchanged strict repository/private-value scanning. Mini smoke only reads existing data and can create an unpaid ordinary order; it never calls `/order/pay` or `wx.requestPayment`.

Delivery validation on 2026-10-04: backend **55/55 SUCCESS**; focused Java **270 PASS** (existing 200 + 70 attempt); real MySQL 8.0.46/InnoDB **204 PASS** (existing 134 + 70 attempt), all with zero failures/errors/skips. Node **6 PASS**, scanner **3 PASS**, Vue type check/local build PASS, UniApp compile PASS, and mini-program **14/14 PASS** with zero uncaught errors and **paymentRequests=0**. Strict source/log/private identity scans PASS. Additive local DDL preserved the wallet/financial-row fingerprint and left historical inbox associations NULL; the development database contains zero attempts. Test schemas/accounts are removed after acceptance.

## Next provider integration

Recommend **API v3 with the official Java SDK** for the next real WeChat integration, keeping this attempt/inbox/atomic finalization boundary. Official docs provide the signing/verification and notification-decryption model. Before rollout, define trusted merchant authorization, request/prepay retry and uncertainty behavior, remote close evidence, verified callback adapter, key/certificate lifecycle and reconciliation operations. This PR implements none of those live provider functions and makes no claim that v2 is disabled/deprecated.

Sources: [WeChat mini-program order/reference rules](https://pay.wechatpay.cn/doc/v3/merchant/4012791897), [API v3 overview](https://pay.wechatpay.cn/doc/v3/merchant/4012081606), [official Java SDK](https://github.com/wechatpay-apiv3/wechatpay-java).
