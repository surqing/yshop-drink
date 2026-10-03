# Payment finalization baseline (Phase 5B)

This baseline uses synthetic payment events only. It does not establish production payment readiness.

## Current callback chain and SDK evidence

Before this change:
`AppOrderController.payBack -> MerchantPayServiceManager.payBack -> BasePayService.payBack -> Wx/AliPayMessageHandler -> PayNoticeProducer -> Redis Stream -> PayNoticeConsumer -> paySuccess(orderId, payType)`.

The actual resolved dependency jars were inspected with `javap -c -p`:

| Artifact | Version | SHA-256 |
| --- | --- | --- |
| pay-spring-boot | 1.0.5 | b825b1d3a43eaab5047ef2644819b77d04efb3bb5283711730e779259ed014c9 |
| pay-java-common | 2.14.9 | 608c723ff2aae14fe8636d7198d8bd6d7344781f014b32720d13f365f8f533db |
| pay-java-wx | 2.14.9 | 202fa06cb9497a048ba488441172696d5e3017a0f75747d2a4b4873b3f45d124 |
| pay-java-ali | 2.14.9 | dc34b7187ba16c7f0cc5e7353c2227ee888de9de5d0500140b0cee4b967d47cb |

`MerchantPayServiceManager.payBack(String, NoticeRequest)` loads the server merchant and delegates to its `PayService.payBack`. `BasePayService.payBack(NoticeRequest)` parses the notice, logs the entire parameter map at DEBUG, calls `verify`, and only then invokes interceptors/handler. `WxPayService.verify(NoticeParams)` has these actual bytecode branches:

- offsets 5–17: a map containing `req_info` immediately returns true;
- offsets 18–110: missing sign or non-SUCCESS return/result code logs the whole map at ERROR and returns false;
- offsets 111–127: ordinary success calls private `signVerify(Map, String)`;
- `signVerify` uses configured `SignUtils`, server private key and input charset; it does not compare callback appid/mch_id with the server merchant.

`AliPayService.verify` checks the signature with SDK `signVerify`; its unsigned branch can log the raw map at DEBUG. The legacy application handlers checked only SUCCESS/trade status and had no amount or transaction binding. `StoreOrderDO.outTradeNo` has no writer in this source tree; its semantics are not sufficiently defined to reuse.

The new `VerifiedPaymentCallback` deliberately avoids `BasePayService.payBack` and its raw-body logs. It binds the URL details ID to server-decrypted SDK configuration, parses bounded XML with DTD/external entities disabled, and invokes guarded handlers. Ordinary signature verification remains the actual SDK `verify` implementation, not a custom MD5/HMAC/RSA implementation. Before invoking it, the WeChat entry rejects `req_info`, missing/invalid sign, non-success codes, wrong appid/mch_id, foreign currency and incompatible sign type. AliPay requires successful trade status, sign, matching app ID and configured seller ID. SDK sandbox mode is rejected: its key lookup may perform an HTTP request and is outside this baseline. Direct calls to the legacy handler method fail closed.

Tests use ephemeral synthetic MD5/HMAC and RSA keys, including the actual SDK refund bypass, valid signatures, tampering, missing signatures and wrong merchant/application. Full local bytecode evidence is retained outside Git under `.uniapp-dev/logs/phase5b-sdk-audit.txt`; no callback payloads are recorded there.

[WeChat v2 notification documentation](https://pay.wechatpay.cn/doc/v2/merchant/4011937152) requires verification and amount checks. Provider retries are not a substitute for a durable local payment event.

## Event model and database constraints

Apply `yshop-drink-boot3/sql/migrations/2026-10-03-payment-finalization.sql` before deploying this branch, after importing the existing seed for a fresh database. The additive migration does not modify old orders, bills, credentials or seed data.

`PaymentSuccessEvent` carries provider, server merchant-details ID, merchant order number, provider transaction ID, positive exact amount in cents, appid, mch/seller ID, SUCCESS code and server receive time. Raw XML, signature, openid, personal information and merchant keys do not enter the event, database or payment wake-up message. MQ carries only the ID of a previously committed event.

`yshop_order_payment` stores this minimal event, processing status/reason, first/last receive timestamps, duplicate count, processed/create/update times and nullable `success_order_id`. Case-sensitive ASCII identifiers prevent collation from merging distinct provider identifiers. Constraints:

- `UNIQUE(provider, provider_transaction_id)` prevents transaction reuse;
- `UNIQUE(success_order_id)` allows exactly one final success for each canonical local order;
- a CHECK requires positive cents and a SUCCESS row with a non-null `success_order_id` equal to its order ID; non-success rows must leave it null.

Reusing a transaction with a different order or immutable merchant/amount data preserves the original event and inserts a minimal `yshop_order_payment_conflict` record. It never overwrites the successful original. There is no raw-payload storage.

States: RECEIVED, SUCCESS, PAYMENT_AMOUNT_MISMATCH, UNKNOWN_ORDER, TRANSACTION_ORDER_CONFLICT (conflict audit reason), PAYMENT_CONFLICT, RECONCILIATION_REQUIRED, UNSUPPORTED_RECHARGE, FAILED_RETRYABLE. Result values distinguish FIRST_SUCCESS, IDEMPOTENT_DUPLICATE, RECONCILIATION_REQUIRED, REJECTED, UNKNOWN_ORDER and RETRY. PROCESSING is a database row lock inside the transaction, rather than a crash-prone separately committed flag.

## Atomic transition and transaction boundaries

The independently committed inbox receipt precedes Redis notification and finalization. `PaymentProcessor.process` locks the event and canonical order with SELECT FOR UPDATE. Deleted/system-deleted/refund-in-progress/refunded/canceled/invalid states require reconciliation. A previously paid order without this baseline's successful event is a conflict, not a guessed duplicate. No canceled order is resurrected, and no automatic refund occurs.

`StoreOrderMapper.markPaid` updates by the locked primary key only when `paid=0 AND status=0 AND refund_status=0 AND is_system_del=0 AND deleted=0`. Only one affected row permits database business effects. Zero rows causes reread/classification and never runs effects. Exact payable cents are checked before the update using BigDecimal `movePointRight(2).setScale(0, UNNECESSARY).longValueExact()`.

The paid update, real member pay-count increment, success status, BigDecimal purchase bill and successful event/unique constraint share one REQUIRED transaction. Exceptions at any stage roll everything back; the separate receipt remains retryable. No double/float is used for the payment amount. Status-service payment statistics have moved to after commit.

After commit, statistics and the existing WeChat notice producer are invoked once for the first successful transition. Transport failure is logged by safe category/event ID and cannot roll payment back. Reprocessing a success does not re-enqueue. Notification delivery is best effort: a process crash after commit can lose a notice; there is no durable notification outbox, and delivery is not claimed to be exactly-once.

`cancelOrder` obtains the same order row lock and conditionally marks logical deletion BEFORE stock/coupon restoration. Deletion, stock and coupon changes remain one transaction. If cancellation wins, the later payment is durably RECONCILIATION_REQUIRED without fulfillment effects. If payment wins, cancellation cannot restore inventory/coupon. Restoration failure rolls back cancellation and its preceding inventory change. Both manual and the active delayed timeout path call this method. System deletion can cause reconciliation but does not perform inventory rollback.

## Redis delivery and provider acknowledgement

The real `AbstractRedisStreamMessageListener` does business processing before XACK. An exception propagates and does not ACK. The current group uses last-consumed offsets, manual acknowledgement and does not cancel on error. `RedisPendingMessageResendJob` checks idle pending entries after 300 seconds; it XADDs a copied record and only then acknowledges the old entry. This is replacement delivery, not XCLAIM, and its object/map serialization is not sufficient evidence of reliable original event reconstruction.

Therefore Redis is a wake-up optimization only. The database receipt is committed first; synchronous finalization tries immediately. A scheduled 30-second database recovery processes up to 100 external RECEIVED/FAILED_RETRYABLE events independently of Redis. Consumer failures propagate so the listener leaves the message pending; retries/redelivery still meet the database gate. Old messages containing only orderId/payType are safely quarantined (category log and ACK), never converted into trusted payment events. They require manual reconciliation from provider records if historical unpaid processing was outstanding; this baseline does not fabricate their missing verification/amount data.

Provider ACK policy:

| Outcome | Response | Durable basis |
| --- | --- | --- |
| FIRST_SUCCESS / matching duplicate | SUCCESS | committed successful payment and effects |
| Canceled/deleted/paid transaction conflict / amount mismatch / unsupported recharge | SUCCESS | committed review/rejection record; ACK means recorded, not fulfillment success |
| Same transaction claiming another order/payload | SUCCESS | separately committed conflict audit; original untouched |
| Unknown order | FAIL | UNKNOWN_ORDER persisted; provider retry can re-evaluate it |
| Database receipt or processing failure | FAIL | no assumed success; committed receipt is recovered if present |
| Invalid signature/merchant/result/payload | FAIL | never reaches finalization |
| Redis failure after receipt | outcome-dependent | durable DB receipt survives; Redis success is not required |

UNKNOWN_ORDER is retried only upon provider notification/manual processing; permanent review states are not automatically made payable. Repeated transient failures remain visible in the inbox and safe category logs; this is a minimal recovery loop, not a reconciliation UI or general event platform.

## Internal balance/cash and recharge boundary

`paySuccess(orderId,payType)` now accepts only internal cash/balance, never WECHAT/ALIPAY. Internal references use an explicit CASH/BALANCE provider and `internal:<canonical-order-id>`; they are not fabricated provider transaction IDs. Internal receipts can survive a rolled-back caller but are excluded from external MQ/database recovery so a wallet debit cannot be bypassed. Cash/balance must retry through their own server-side adapter.

`yuePay` uses an explicit transaction template because `pay()` invokes it on the same instance and bypassed its transaction annotation. Debit and finalization commit/rollback together; a duplicate finalization after debit throws so that debit rolls back. This is minimum compatibility, not a solution for concurrent wallet spending across different orders. Wallet atomic debit remains Phase 5C. Recharge references are recorded as UNSUPPORTED_RECHARGE, leaving bill status and balance untouched; recharge has not been enabled or declared safe.

## Local access-audit diagnosis

The upstream application-local profile sets `yshop.access-log.enable=false`; the auto-configuration/filter itself works. The Git-ignored `.local-dev/application-local.properties` now overrides it to true. The conditional bean registration and real filter behavior are tested. Callback routes are treated as sensitive routes so generic access/error logging excludes body, query and response values even if response auditing is explicitly enabled. Payment inbox records remain the business audit source of truth.

## Verification and remaining work

`PaymentDatabaseTest` runs real production MyBatis mappers, database transactions, status/bill services and payment effects against isolated H2 in MySQL mode; unrelated transport APIs are mocked. Member calls delegate to the actual member mapper's SQL. It covers a single success, 100 sequential duplicates, 20 simultaneous callers, low/high amount, unknown/canceled/deleted/refund states, transaction/order conflicts, rollback at five stages, Redis failure/redelivery/ACK behavior, recovery, internal payment/recharge boundaries, notification failure and twenty cancellation/payment races, independently visible after-commit notification and wallet-debit rollback at the existing adapter. Inventory/coupon transport stubs execute transaction-bound SQL, and restoration failure is injected via a database CHECK.

An additional unchanged historical `DesensitizeTest` fails because its nickname input is `yshop` but the expected masked value starts with a different Chinese character. It is recorded separately and is not part of the focused payment regression suite.

Focused command from the backend root:

```sh
mvn -pl yshop-module-pay/yshop-module-pay-biz,yshop-module-mall/yshop-module-order-biz,yshop-framework/yshop-spring-boot-starter-web test \
  -Dtest=PaymentCredentialCryptoServiceTest,PaymentCredentialDatabaseTest,MerchantDetailsControllerSecurityTest,PaymentDatabaseTest,CallbackVerificationTest,ApiAccessLogFilterTest,SensitiveDataSanitizerTest,GlobalExceptionHandlerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Use JDK 17 and the established local Maven cache option where applicable. Delivery verification: full backend 55/55 SUCCESS; focused Java regression 118 PASS (76 Phase 5B + 27 credential + 15 access/error/SQL log); Node regression 6 PASS; scanner regression 3 PASS; Vue type check/build PASS; UniApp compile PASS; mini-program smoke 14 checks PASS with zero uncaught errors and paymentRequests=0.

The initial smoke used an unrelated stale cart SKU that is no longer in the current product catalog. An audited rejection and zero created orders were confirmed before archiving the failed attempt. The smoke now creates one unpaid order from the item actually added through the UI in that run, preserving existing cart contents and inventory; no product/order source or database inventory is rewritten.

The unchanged stock scanner also flags the already public default admin value inside unchanged generated Vue login assets. The repository/private regression scan stays strict; a separate local runtime classification recognizes only that exact known public baseline value, and still scans genuine private credentials/current simulator identity/session. The stock scanner and its sensitivity are unchanged. Production defaults and CI classification remain future work. The existing public baseline administrator password is a known baseline value, not an exemption for passwords; production initialization/forced rotation/CI classification remain future work. This branch does not change the scanner's sensitivity.

Remaining: wallet concurrency, refunds, recharge, real merchant integration, API v3 migration decision, CI, production default administrator credentials, master-key rotation, operational reconciliation and reliable notification outbox/delivery. No claim of real-payment validation is made.

## Payment safety statement

Real merchant credentials were not used.
Real payment was not invoked.
No production /order/pay request was made.
No wx.requestPayment call was made.
No WeChat prepay request was made.
No real payment callback was processed.
No refund was invoked.
No funds were transferred.
