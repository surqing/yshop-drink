# Measured coverage — Phase 6Q

Audit baseline: `f96c70a10978939f66392788fa3f0fb863d8aca2`. Measurement date: 2026-10-08, JDK 17, JaCoCo 0.8.12. Source is this QA branch including the disabled-member regression/fix. Test run `68129e3da33e4d7ea871c8f3289ef86c`: **1617 invocations, 75 suites, 1142 distinct Surefire display names**, zero failures/errors/skips. Parameterized/repeated invocations are not different business scenarios. Conditional MySQL-only/TLS/Redis suites have separate actual evidence and are not silently included in this count.

All src/main classes (including test infrastructure) from 55 compiled modules are aggregated once with run-owned execution data from 12 testing modules. The root compiler previously disabled debug information; it now emits line information. Cross-module aggregation matters: coupon/product production classes are executed by order-module tests. Per-module-only reports would undercount that coverage. [Machine result](coverage-summary.json) contains covered/missed counters and unexecuted methods. JaCoCo measures compiled bytecode, including generated methods; these totals are descriptive, not a DTO-based target.

| Scope | Line | Branch | Method |
|---|---:|---:|---:|
| All compiled src/main classes | **30.55%** (7943/25999) | **6.77%** (3036/44864) | **22.39%** (4081/18225) |

| High-risk class | Line | Branch | Method | Entirely unexecuted methods |
|---|---:|---:|---:|---|
| CatalogOptions | 98.51% | 69.57% | 100.0% | none wholly unexecuted; partial branches remain |
| PaymentAttemptService | 96.89% | 77.11% | 95.24% | reconciliationCandidates |
| PaymentCancellationGuard | 97.44% | 73.08% | 100.0% | none wholly unexecuted; partial branches remain |
| StoreAccessService | 82.86% | 83.33% | 80.0% | requireOrder, requireCategory |
| PermissionServiceImpl | 88.68% | 70.83% | 100.0% | none wholly unexecuted; partial branches remain |
| OrderPlacementService | 91.53% | 70.0% | 94.44% | ownsVersion |
| PaymentEffects | 96.3% | 50.0% | 100.0% | none wholly unexecuted; partial branches remain |
| PaymentProcessor | 85.56% | 80.41% | 100.0% | none wholly unexecuted; partial branches remain |
| MemberAuthServiceImpl | 29.41% | 23.08% | 50.0% | login, smsLogin, weixinMiniAppLogin, wechatAuth, login0, sendSmsCode, refreshToken, checkUserIfExists, createLogoutLog, getMobile |
| CouponMarketingService | 98.09% | 69.44% | 100.0% | none wholly unexecuted; partial branches remain |
| CouponLifecycle | 90.2% | 64.58% | 100.0% | none wholly unexecuted; partial branches remain |

High line coverage does not establish complete state coverage. Important remaining branches include cancellation malformed historical evidence, attempt reconciliation candidate selection, permission/token changes during a request, authentication refresh/logout/SMS paths, optional catalog coercions and service-level rollback failures. The authentication class remains only 29.41% line / 23.08% branch covered despite the newly proven disabled-member fix. This is a material gap, not rounded into a green security score.

Reproduce after the attributed Java run (see runner audit):

```sh
python3 tests/quality/coverage.py --run "$OWNED_JAVA_RUN" --cli "$JACOCO_CLI_JAR" --output "$NEW_PRIVATE_COVERAGE_DIR"
```

The CLI JAR is `org.jacoco:org.jacoco.cli:0.8.12:nodeps` from Maven Central. Keep the execution data and matching compiled classes; recompiling different production bytecode invalidates attribution. Coverage command fails on missing/empty owned execution data. Raw XML/HTML are private artifacts, not committed logs.

Node: 47 real test cases with assertions across five suites; Python: 57 tests across four suites. No JS/Python line or branch percentages were measured, so none are claimed. Vue build/type checking and actual Mini Program page assertions are separate checks, not substituted for source coverage.

JaCoCo does not measure concurrency interleavings, correctness of expected values or provider availability. Mutation and MySQL evidence must be read with these numbers. Official behavior: [JaCoCo Maven](https://www.jacoco.org/jacoco/trunk/doc/maven.html), [Surefire test discovery](https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo.html).
