# Validated runtime baseline hardening

Baseline tag: `baseline-runtime-validated-2026-10-03` (annotated, immutable).
Baseline commit: `b8800d4071dd601b50f8be75b1b2811ba0558d30`.
Changes live on `chore/baseline-hardening`; the PR targets `develop` and is not merged automatically.

## Authentication and privacy

Both mini-program login callers await a shared in-flight `wx.login` / backend exchange and handle failures. Cancellation, WeChat SDK, backend exchange and network errors have distinct safe messages. Logs contain only category and numeric error code; raw SDK/request objects are not logged. No automatic retry loop is introduced. Cached member tokens remain usable on cold start.

The agreement radio is controlled by the same `isChecked` ref used by both login guards. It starts false and toggles once per user tap. Automation never accepts the agreement.

Login-page loading refreshes the mini session once, including when a cached openid exists. Loading rather than showing avoids replacing the session during a phone-authorization overlay. If phone exchange finds an expired session, it obtains one fresh session and asks the user to authorize again; it never replays old encrypted phone data with a new session key.

Redis mini-session storage now has a positive configurable expiration:

```properties
yshop.member.auth.mini-session-timeout=30m
```

Spring configuration binding also supports `YSHOP_MEMBER_AUTH_MINI_SESSION_TIMEOUT`. Default: 30 minutes. This is a conservative local application cache lifetime, not a claim about WeChat's server-side session lifetime. Existing permanent entries get a TTL on the next successful exchange; no bulk Redis migration is performed. Values must be non-null and greater than zero. The cached value is used to decrypt legacy encrypted phone data, and can be renewed through real `wx.login`.

Frontend token, openid, phone, request payload, full member and payment-response logs were removed. Backend raw request/query logging was replaced by method/path/timing. Access audits still record method, URL, result, user ID and timing; identity endpoints omit payloads and exceptions omit credential-bearing messages. Nested sensitive fields are removed from other audit JSON; invalid JSON fails closed. No authorization or audit service was disabled. Real-identity scanning additionally found MyBatis DEBUG bound-parameter logs. A centralized safe MyBatis logger now emits SQL execution status and numeric row counts, omits SQL text/parameters/results, and reports exception categories without credential-bearing messages. It is registered through the existing MyBatis configuration customizer, independent of the local Logback override. A managed test-only Spring test dependency was added for its two regression tests; no runtime dependency was added.

## Member access-token review

No member access-token TTL was changed. The SQL seed's `system_oauth2_client` default client supplies 18,000,000 seconds (about 208.3 days), shared by admin and member authentication. Its refresh-token lifetime is 43,200 seconds (12 hours). Server refresh-token APIs exist; the mini-program has no integrated refresh cycle. This is inherited seed configuration, with no repository explanation establishing an intentional security policy. Shortening the shared TTL without completing client refresh handling risks breaking both clients. Separate client policies and a complete refresh/re-authentication lifecycle remain follow-up work; this PR does not rewrite authentication or change the database seed.

## Admin TypeScript

The restrictive `typeRoots` prevented resolution of installed package subpath declarations. The `qrcode` types entry named an installation package (`@types/qrcode`) instead of the declared module (`qrcode`). Necessary declarations remain enabled. Existing strictness and library-check settings are unchanged.

Resolving these four TS2688 failures exposed historical project errors. With explicit approval, fixes add concrete page/API/form types, null and empty-range boundaries matching existing runtime defaults, correct component declarations and SDK globals, and retain optional handlers. A few invalid runtime references exposed by checking were corrected: reactive objects were accessed as refs, category loading called a nonexistent tree helper, and draggable/tag-view hooks referenced missing APIs. Normal permission rules, endpoints and business operations remain in place. No dependency major version, Node version or lockfile was changed.

## Repeatable smoke test

The existing sibling `.local-dev` and `.uniapp-dev` facilities remain private and Git ignored. Prerequisites are their validated local setup, personal server credentials, official tool login/service port, and a genuine persisted member login accepted by the user.

From the repository:

```sh
bash tests/smoke/run.sh
```

This starts existing local services without resetting data, checks health, compiles with HBuilderX, temporarily opens WeChat automation on port 9420, verifies real member state, navigates home/store 2/product specification/cart/order pages, creates at most one unpaid order, and checks `paid=0` and zero payment requests against server audit/database records. Credentials stay in memory/stdin. Results go to `.uniapp-dev/logs/baseline-smoke.json`.

The private order checkpoint is reused on later runs. A recorded attempt without a successful checkpoint stops retries for manual read-only reconciliation. To create another test order, first review the previous order and deliberately archive its private checkpoint/attempt records; the script never clears them automatically. Cart additions are real UI actions, so repeated runs increment the existing cart. No store, goods, member or database fixture is fabricated.

Order creation calls the real create API with original page payload semantics after UI cart verification, because the original submit button continues directly into the payment page. This deliberately verifies order creation separately from the payment-linked button. Payment functions are never invoked or mocked. Zero payment requests are checked using persisted audits and the application's request log events; this is not a real-payment test.

The automation port is temporary and the exit trap returns the tool to normal developer mode. Do not expose developer-tool ports on an untrusted network.

Other checks:

```sh
node --test tests/auth-errors.test.mjs
python3 tests/smoke/secret-scan.py
# With the established JDK 17 / Maven / Node / pnpm environment:
cd yshop-drink-boot3
mvn clean install package -Dmaven.test.skip=true
# Focused tests, separately from the repository's skip-tests build:
mvn -pl yshop-module-member/yshop-module-member-biz -Dtest=MiniRedisDAOTest test
mvn -pl yshop-framework/yshop-spring-boot-starter-web -Dtest=ApiAccessLogFilterTest test
mvn -pl yshop-framework/yshop-spring-boot-starter-mybatis -Dtest=SafeSqlLogTest test
cd ../yshop-drink-vue3
pnpm ts:check
pnpm build:local
```

Use the existing private Maven cache setting when reproducing this Mac's build. The secret scanner reads local credential values without printing them, scans changed/new source and the diff, and optionally scans runtime files or identities supplied via stdin. Reports only PASS/FAIL and file paths. It is an exact-value regression check, not a complete secret-discovery product or a claim that historical repository defaults are safe for deployment.

## Known remaining work

Node 20 EOL, Vite CJS, Browserslist, `::v-deep`, wx.getSystemInfoSync, HTTP-image/selector warnings and old dependency deprecations remain as requested. The legacy encryptedData/iv phone API remains. Wallet, recharge, points, desks, payment idempotency and other business features were not redesigned. During developer-tool reload, image requests can be aborted and cause server `ClientAbortException` / `Broken pipe`; distinguish these from steady-state request failures. Local HTTP/domain-check exceptions remain confined to the previously authorized private development project and are unsuitable for release.

Real payment was not invoked. No /order/pay request was made. No wx.requestPayment call was made. No funds were transferred.

## Rollback

Use the immutable baseline tag as the review/rollback reference. Do not reset a checkout with unreviewed local work. No master changes, develop merge, public-history rebase, force push or tag rewrite is part of this task.
