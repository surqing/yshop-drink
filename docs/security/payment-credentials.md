# Payment credential boundary (Phase 5A)

This change protects configuration material. It does not implement or exercise payment, refunds, callback idempotency, wallet concurrency or a payment state machine.

## Audit performed before implementation

The original flow was `MerchantDetailsController -> Create/Update/Base VO -> MerchantDetailsConvert -> MerchantDetailsServiceImpl -> MerchantDetailsMapper -> merchant_details`. `RespVO` inherited the credential-bearing Base VO. Get/list/page could return private material; `MerchantDetailsExcelVO` could export it; Lombok DO/write-model strings could dump it. Page/export request models also accepted credential filters. API auditing already used the shared `SensitiveDataSanitizer`, and SQL auditing already used `SafeSqlLog`, from PR #1.

The resolved dependencies and their actual compiled JDBC mapper/platform implementations were inspected: egzosn `pay-spring-boot-starter` / `pay-spring-boot` **1.0.5**, `pay-java-wx` and `pay-java-ali` **2.14.9**. The configured `wxPay` platform builds `WxPayService`/`WxPayConfigStorage`: **WeChat API v2**, where `keyPrivate` supplies the API signing key. No API v3 configuration was inferred from unused library classes.

`MerchantPayServiceConfigurer` previously installed the SDK JDBC manager with `cache(false)`. That manager selected the table directly, independently of our MyBatis DO/convert layer, then called `CommonPaymentPlatformMerchantDetails.initService()` and installed the existing callback handlers. Encrypting only the admin write path would therefore break the SDK. The replacement builder retains the SDK and handler wiring, reads fresh ciphertext each time and decrypts immediately before SDK construction. Logically deleted merchant configurations are unavailable to the runtime; migration still covers their stored secrets.

Runtime consumers remain unchanged: `AppStoreOrderServiceImpl` calls `MerchantPayServiceManager.toPay`, `AppOrderController` calls `payBack` on its existing notification route, and `StoreOrderServiceImpl` uses the manager for refunds. The manager's verify/query/refund/order operations all resolve merchant configuration through the same SDK service. No direct project query-order invocation was found. `MerchantDetailsService.getMerchantDetails` is also used to read the H5 return URL; it retains ciphertext internally and does not decrypt. No plaintext Redis/cache write was added.

## Credential classification

| Field | Classification | SDK use |
| --- | --- | --- |
| `keyPrivate` | Secret | WeChat v2 API signing key or provider private key / keystore reference |
| `keyCertPwd` | Secret | `setKeystorePwd` / `getKeyPrivateCertPwd`, client keystore password |
| `keyCert` | Secret | Additional client certificate/keystore; can contain private P12 material, not safely public |
| `keyPublic` | Public certificate/key | SDK public verification material; excluded from DO strings and audit content |
| appid, mchId, subAppId, subMchId, notifyUrl, returnUrl, signType | Metadata | Kept in read/export models |

Aliases such as privateKey, apiKey, apiV3Key, mchKey, payKey, AppSecret and clientSecret remain covered by the shared sanitizer. keyCert, keystore/password aliases and master-key names are now covered as well. No second logging sanitizer was introduced in tracked code.

## API and UI contract

| Endpoint | Credential behavior |
| --- | --- |
| POST `/pay/merchant-details/create` | Accepts plaintext only in write-only fields; encrypts before persistence; returns record ID |
| PUT `/pay/merchant-details/update` | Missing, null, empty or whitespace input preserves each secret column; nonblank input explicitly replaces it with fresh ciphertext |
| GET `/pay/merchant-details/get` | Independent read DTO; secret properties do not exist |
| GET `/pay/merchant-details/list` | Same read DTO for every element |
| GET `/pay/merchant-details/page` | Same read DTO; secret query filters removed |
| GET `/pay/merchant-details/export-excel` | Public metadata and status booleans only; no secret or ciphertext columns/values |

Responses expose `privateKeyConfigured`, `certificatePasswordConfigured`, `keyCertificateConfigured`, with no masked secret suffixes or reveal endpoint. These statuses indicate presence, not successful authentication with a payment provider. Permission annotations remain unchanged.

Admin API TypeScript read/write contracts are separate. Editing loads metadata/status only; a replacement control explicitly reveals an empty password input. Cancelled replacement and blank input are omitted from the request. Record ID is immutable in edit mode because it binds AAD. Create supports the already-existing backend custom ID contract, including isolated synthetic configurations. No secret-clear operation was added.

## Storage and master key

`PaymentCredentialCryptoService` uses JDK 17 `AES/GCM/NoPadding`, a random 12-byte nonce per encryption and a 128-bit authentication tag. AAD is UTF-8 `merchant_details:<detailsId>:<fieldName>`; IDs are stable `[A-Za-z0-9_-]{1,32}` and field names are allowlisted. Format: `enc:v1:` followed by Base64 of `nonce || ciphertext || authentication tag`. Base64 is envelope encoding, not the encryption.

Supply a Base64 random **32-byte** key through **YSHOP_PAYMENT_CREDENTIAL_MASTER_KEY**, or the server-private Spring property **yshop.pay.credentials.master-key**. Do not put the value in arguments, YAML committed to Git, the frontend, database, Redis, examples, PRs or logs. Local setup uses a repo-external `.uniapp-dev/server/application-payment.properties`, mode 600, loaded by the existing private backend launcher. It is independent of the WeChat login AppSecret and database password. Keep the same key across restarts; losing it makes existing envelopes unreadable. Secure backup/access control and planned key rotation are deployment responsibilities; v1 does not implement multi-key rotation or KMS.

Missing key permits metadata reads but credential encryption/decryption and migration fail closed. Invalid key configuration, malformed/unknown envelopes, authentication failures and client-supplied `enc:` material produce static errors without values/causes. Nonempty legacy plaintext is **never** accepted as a runtime decrypt fallback. Plaintext byte buffers are cleared where practicable; Java/SDK strings cannot promise memory zeroization.

Write DTO secret fields are Jackson WRITE_ONLY and Lombok string/equality excluded. DO secret columns have JsonIgnore, ToString/EqualsAndHashCode exclusions and explicit NOT_NULL update strategy. MapStruct writes ignore raw secret inputs and the service encrypts them separately. SDK merchant object serialization and strings omit material, including inherited aliases. Certificate stream loading errors are replaced with static failures before SDK logging can expose private paths. The existing global access/error/SQL protections remain in place.

## Explicit upgrade / legacy migration

1. Stop credential writers/payment activity and preserve an authorized database backup and the private encryption key. Do not test with upstream merchant values.
2. Apply **sql/migrations/payment-credential-columns.sql** against the intended database. It expands all three secret columns to MEDIUMTEXT without reading, replacing or deleting values. Repeating the DDL is safe. The SQL seed retains its original schema and five records, but all `key_private`, `key_cert` and `key_cert_pwd` values are empty. Apply the column migration after a fresh seed import too; no imported provider credentials should be used.
3. Configure the server-private master key.
4. Explicitly start the backend once with **yshop.pay.credentials.migrate-once=true** in a private config or the non-secret command option `--yshop.pay.credentials.migrate-once=true`. Ordinary startup leaves migration disabled.
5. Require the count-only `Payment credential migration verified` result; then remove/disable the flag before the next ordinary restart. No migration HTTP endpoint is exposed.

`PaymentCredentialMigrationService` runs one transaction, locks all rows including soft-deleted rows, recognizes `enc:` envelopes and verifies them rather than re-encrypting, encrypts nonblank legacy fields, writes with bound JDBC parameters and reads back/decrypts to verify. Failure on any record rolls back the transaction. Unknown/malformed versions stop rather than falling back. Empty fields and records are preserved. Re-running reports zero migrated fields and verifies existing envelopes. DDL and data migration are separate because MySQL DDL auto-commits.

Before the review follow-up, the SQL seed contained five credential-bearing rows (nonblank private-key fields: 5; password: 1; additional certificate: 1; encrypted: 0). These values were not used and have now been removed from the current seed. All five records and every non-credential field are preserved; the three protected fields are empty. A fresh payment-table import in an isolated MySQL database verified five records and zero nonblank values in each protected column. Existing local database inventory before this phase: five rows, zero nonblank values in all three protected columns; prior setup had already disabled/cleared imported external credentials. The data migration mechanism is still supplied and tested against synthetic legacy data.

## Validation and limits

Focused tests use randomly generated test master keys and synthetic credential strings only: authenticated roundtrip, nonce uniqueness, wrong key, tampering, field/record AAD, blank inputs, version/malformed parsing, missing/invalid key and no secret-bearing errors; actual MyBatis/H2 preserve/replace semantics; migration idempotence/deleted records/transaction rollback; construction of the existing Wx SDK with plaintext from ciphertext and no payment method invocation; actual Controller/MapStruct/crypto/access/error/Excel flows through MockMVC.

MockMVC isolates the mapper and authentication; production permission enforcement is unchanged, not proven by that standalone test. Database tests use real MyBatis and Spring-managed transactions. Local authenticated Admin UI/API and the existing real WeChat login/order smoke provide runtime checks. Provider certificate/merchant integration and real network payment are deliberately untested. Stored references do not encrypt external certificate files: operator-managed files still require private filesystem permissions.

The local runtime did not persist new payment-configuration access-audit rows during the authenticated checks. Access/error payload sanitization is proven by the actual endpoint/filter MockMVC tests; end-to-end audit persistence remains a follow-up. No audit/security setting was disabled to obtain a passing result.

Local validation on 2026-10-03: backend **55/55 SUCCESS**; **27 new Java tests + 24 existing focused regressions PASS** (42 Java, 6 Node, 3 Python); Vue `ts:check` and `build:local` PASS; UniApp compile and real WeChat login/member/store/products/cart/unpaid-order/list/detail smoke PASS, **paymentRequests = 0**. The authenticated Admin UI created an isolated synthetic merchant, displayed only configured status, preserved all three stored ciphertexts on metadata edits, replaced only the selected credential, and preserved the previous value after cancellation. Actual authenticated GET/list/page and downloaded Excel contained no credential fields/material; absent/blank/null API updates preserved storage. Backend stdout contained neither synthetic plaintext nor its stored ciphertext.

The original five local rows were retained. The additional synthetic UI record stored three encrypted fields. Explicit migration verification reported **records=6, migratedFields=0, verifiedEncryptedFields=3**; the migration flag was then disabled for ordinary startup. Schema expansion was applied separately. No real merchant material was introduced. Existing compiler/MapStruct warnings, Vite's deprecated CJS API/outdated Browserslist notice and the WeChat CLI's `punycode` deprecation warning remain outside this credential change.

JDK crypto reference: [Oracle JDK 17 Cipher](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/crypto/Cipher.html).

## Remaining work

Payment callback idempotency; wallet concurrency; real WeChat merchant integration; real payment callback verification; refund integration; broader Admin targeted smoke; CI; secure production master-key backup/rotation/access controls; per-provider real certificate compatibility.

## Payment safety statement

Real merchant credentials were not used.
Real payment was not invoked.
No /order/pay request was made.
No wx.requestPayment call was made.
No WeChat prepay request was made.
No funds were transferred.
