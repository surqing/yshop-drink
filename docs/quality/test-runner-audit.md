# Test runner audit — Phase 6Q

## Discovered false-green and false-negative mechanisms

| Asset | Previous issue | Correction/evidence |
|---|---|---|
| Both MySQL runners | Shared target reports, minimum count, no exact cases; disabled discovery could pass | UUID-owned 0700 report directories, quality.runId property, timestamps, exact suite/case invocation manifest, exit status, zero skip/failure/error checks |
| Both MySQL runners | PASS printed before finally cleanup; partial create/user/grant failure could strand resources | PASS only after scoped cleanup; schema/account ownership flags, private resource receipt, exact random-name cleanup, JDBC file removed even on cleanup failure; actual entrypoint fault injection |
| Concurrent Maven invocations | Shared target/classes and Surefire boot files | Checkout-scoped OS file lock for quality entrypoints, bounded wait; independent business worker concurrency remains unchanged; actual two-process serialization test |
| Business/baseline shell smoke | Implicit start/compile of other checkout, ignored cleanup/open failures, fixed paths | Explicit workspace/API/CLI/automation/project, fresh source compile attribution, failures propagate; legacy write smoke requires explicit authorization and is not run by default |
| Fixed paymentRequests fields | Uninstrumented values looked measured | DECLARED_NO_PROVIDER_PATH / DECLARED_UNINSTRUMENTED_LEGACY_SUITE; actual read-only Mini Program hooks measure a scoped window |
| Surefire naming | Test2.java was undiscovered | Explicit includes restore 23 real tests, exact manifest includes them |
| Root compiler | No debug info prevents meaningful line attribution | Enable debug data, optional JaCoCo profile, cross-module aggregation |
| Vue clean checkout | Type check requires generated auto-import declarations | Frozen install → build → type check; reproduced initial type failure, then both pass |
| DesensitizeTest | yshop fixture expected Chinese nickname prefix | Reproduced alone/full reactor; actual prefix-one mask contract is y****; original assertion retained with correct value plus boundary/null regressions |
| System historical fixtures | yshop fixtures queried obsolete text; tenant domain and token shop_id schema drift; unmocked store mapper dependency | Fix fixture inputs/schema and isolated mock collaborators, retain original row assertions; 435 PASS, no skip |
| Material tests | Missing H2 application/schema and disabled null-placeholder filters | Dedicated isolated resources, concrete filter-positive/negative rows, both filters activated, 7 PASS |
| Member auth historical tests | Missing SDK bean, stale member table, commented production call and unrelated password comparison | Actual supported service contracts with verified collaborators; disabled-WeChat regression proves production bug before minimal fix |
| Manual SMTP testDemo | Disabled, no meaningful assertions, hardcoded obsolete remote credential, nonisolated live behavior | Method retained as deprecated non-JUnit fail-closed stub; no network call/credential; nine mocked MailSendService tests retained |
| Operational payment helpers | Live-capable tools are not ordinary tests | Inventory and 26 pure/mock failure tests; never run live acceptance tools to inflate totals |

The first successful dedicated TLS run revealed a second isolation flaw: the old synthetic helper replaced `payment-acceptance-report.json` and `merchant-provisioning-report.json` in the shared private report directory. These are generated summaries, not merchant configuration/database data. Their previous contents cannot be claimed preserved. The helpers now write reports inside the owned synthetic evidence folder when a strictly validated isolated schema is present; operational defaults are unchanged. A preservation regression and a new real TLS run pass. The existing proxy, credentials and financial data were not changed.

The shared embedded Redis test configuration formerly swallowed startup failures and used a fixed configured port. This could leave a Spring test connected to an unrelated service or passing without its claimed Redis server. A new occupied-port regression fails before the fix. The fixture now binds an owned ephemeral loopback port, updates test connection properties before client creation, propagates startup errors, and stops with its context. Three isolation/lifecycle tests pass; final all-Java rerun has 1617 invocations. This component is test infrastructure, not the deployed Redis configuration.

## Unified entrypoints

All modes: Python 3.9+, explicit checkout; private new UUID output directory; nonzero on FAIL/BLOCKED; reports distinguish scope, source SHA/digest, timeout, exit and measured vs declared counters. Existing environments are not implicitly started. Never supply production URLs or database credentials.

```sh
python3 tests/quality/run.py QUICK --output "$PRIVATE_QUALITY_DIR"
python3 tests/quality/run.py BUSINESS --output "$PRIVATE_QUALITY_DIR"
python3 tests/quality/run.py PAYMENT-SAFETY --output "$PRIVATE_QUALITY_DIR"
python3 tests/quality/run.py INTEGRATION --output "$PRIVATE_QUALITY_DIR"
python3 tests/quality/run.py MINIPROGRAM --output "$PRIVATE_QUALITY_DIR"
python3 tests/quality/run.py FULL --output "$PRIVATE_QUALITY_DIR"
python3 tests/quality/run.py MUTATION --output "$PRIVATE_QUALITY_DIR"
```

| Mode | Actual scope | Dependencies / timeout |
|---|---|---|
| QUICK | 5 Node suites, 4 Python suites, runner fault injection | Node20, Python; 180s per step |
| BUSINESS | Ordering/catalog/coupon/code-guard H2 suites | JDK17, installed reactor dependencies; 1800s/module |
| PAYMENT-SAFETY | Synthetic financial/credential/attempt/v3/preflight/cancellation H2 suites | JDK17; no real provider credentials; 1800s/module |
| INTEGRATION | Random-schema MySQL business + financial, independent-client Redis, optional owned synthetic TLS | Docker/local helper, explicit Redis properties and synthetic TLS dir; MySQL2400s/step, TLS900s |
| MINIPROGRAM | Explicit controlled official developer-tool automation command | macOS, HBuilderX fresh compile, own AppID, official CLI services;600s |
| FULL | QUICK + all ordinary Java + INTEGRATION + backend/Vue + compile + scan + Mini | All above; missing controlled commands/dependencies =>BLOCKED, never PASS |
| MUTATION | Disposable copies, original passing baseline then 20 current operators | JDK17/Maven, H2;7200s outer,1800s/child; survivors exit nonzero |

`YSHOP_TEST_WORKSPACE` resolves private tools outside the worktree. `YSHOP_MAVEN`, `YSHOP_MAVEN_REPOSITORY` configure tool/cache. Device commands use JSON argv in `YSHOP_UNIAPP_ARGV`, `YSHOP_MINIPROGRAM_ARGV`, `YSHOP_SECRET_SCAN_ARGV`; no shell evaluation. For integration use `YSHOP_COUPON_REDIS_CONFIG` (explicit private loopback settings) and `YSHOP_SYNTHETIC_TLS_DIR` (existing authorized synthetic CA/cert only). `tests/quality/ingress.py` creates labeled disposable Nginx resources on localhost48444→isolated test server48881; it leaves the existing localhost48443 ingress untouched.

Actual read-only device command: `node tests/quality/mini-readonly.cjs` with explicit workspace, API48083, automation9421 and new `YSHOP_MINI_REPORT`. It blocks nonlocal APIs, financial paths, provider UI calls and server-side writes during its hook window. It does not claim to observe requests before hooks or outside that process. UniApp compilation uses `python3 tests/quality/uniapp.py`, existing authorized private preparation helper/AppID, fresh private project, source hashes and compiler-success evidence. Mac-only requirements are BLOCKED in generic Linux CI.

## Runner self-tests

24 actual runner self-tests exercise missing/stale/foreign/duplicate/empty/name-substituted/failed/error/skipped/broken XML, actual nonzero child, timeout, failed TCP connection, actual Node assertion, Bash fail, aggregate partial failure, build serialization, and both actual MySQL entrypoints with child, cleanup, partial CREATE USER and GRANT failures. Three additional scanner tests exercise committed/untracked literals and an actual Secret-in-log CLI failure. Existing secret scan four tests and prepayment tools 26 tests also pass. The latter include strict synthetic-port URL admission and preservation of an operator report. No injected fault is committed as application behavior.

JDBC connect/socket timeouts bound local infrastructure faults. Cleanup is exact schema/account only. Failed cleanup leaves a credential-free private receipt for manual investigation; no broad DROP/wildcard recovery. Full reports/logs remain private; CI publishes only machine summaries.

## CI

`.github/workflows/test-quality.yml`: read-only token, PR/workflow_dispatch, no pull_request_target or secrets, Java17/Node20/Python3.11/pnpm8.15.9, cached Maven,45-minute deadline, cancellation of superseded runs. Builds55 modules, exact all-Java checks, QUICK, offline credential diff guard, frozen Vue build/types. Raw logs/artifacts with identities are not uploaded. Integration/device gates remain controlled local checks, not pretend Linux successes. Branch protection is a user decision: require this job and review before merge; no repository policy was changed.

CI config being committed is not evidence GitHub has executed it. See gate report for actual run state. The generic diff credential guard is complementary, not a complete secret detector; local exact private-value/runtime scan remains required.

Heavy integration still requires the explicit existing `.local-dev/database.py` Docker adapter; a fresh machine needs the prior local environment bootstrap. This is a portability gap, not silently supplied production credentials. The generic runner correctly BLOCKS when it is absent.
