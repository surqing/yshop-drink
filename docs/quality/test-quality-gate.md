# Phase 6Q-R2 quality gate

**PHASE 6Q QUALITY GATE: NOT READY**

Only existing PR13 is updated, based on feature/phase6c-coupon-marketing. PR12/13 are not merged; develop/master and Phase6D are untouched. Read [criteria and machine evidence](evidence-summary.json), [assets](test-inventory.md), [runner](test-runner-audit.md), [risk](business-risk-coverage.md), [coverage](test-coverage-report.md), [mutations](mutation-testing-report.md).

READY requires: exact ordinary/quick suites with no skips; both final-head GitHub jobs successful; owned MySQL/Redis/TLS and cleanup; actual mutation classifications; production authentication review; isolated writes/two-process auth; fresh attributed risk coverage; eight real GUI assertions after manual CAPTCHA; strict scan; clean pushed worktree. No test count or overall coverage percentage replaces these criteria.

## Measured R2 results (original identities retained)

| Gate | Evidence |
|---|---|
| Ordinary Java |1710 PASS;76 suites/12 modules;zero failures/errors/skips|
| QUICK |139 planned exact invocations:47 Node+92 Python; fresh final-head run required|
| Owned MySQL8.0.46 business |490 PASS;23 InnoDB tables; schema/account absence and owned cleanup|
| Owned financial |497 PASS;12 InnoDB tables; no developer DB writes|
| Owned auth |72 PASS;4 InnoDB tables; container/volume destroyed|
| Owned Redis |66 PASS;prefix cleanup and fault fail-closed|
| Synthetic TLS |10 PASS;original body/headers, signatures/replay/admin protections; no TLS bypass|
| Actual isolated HTTP |45 checks;two independent JVMs,5x20 auth competitions,331 auth requests; all orders unpaid|
| Backend/Vue |55/55 reactor;build/type PASS at R2 source; rechecked in final-head CI|
| UniApp |fresh HBuilderX mirror compile PASS;380 source files; local-only binding; compile is not a runtime payment measurement|
| Mutations |joined25 KILLED/3 SURVIVED/0 INCONCLUSIVE; explicitly different attributed snapshots|
| Asset taxonomy |253 assets:89 ACTIVE_TEST,145 ACTIVE_HELPER,17 CONDITIONAL,1 BLOCKED_EXTERNAL,1 OBSOLETE;no files deleted|
| Admin GUI |BLOCKED: manual slider was not completed; expired old fixture destroyed; zero GUI assertions claimed|
| Linux controlled |final pushed HEAD must be inspected in controlled-dependencies job; local Mac PASS is not a Linux result|

Final-head CI, renewed coverage, strict scan and official device evidence are recorded in delivery and the PR's actual checks. This source document does not predict a pending run's conclusion. Failures/blocked scopes are not suppressed.

## Original CI regression retained

37902827072 failed system-biz timestamp assertions after11 modules. Safe diagnostics and clean Linux reproduction identified nanosecond Clock versus SQL precision, including approve/code/token expiry assertions. R1 persisted whole-second expiry and canonical fixtures plus fixed-nanosecond regression, with no tolerance weakening/skip. Actual R1 final run37945545110 at8eb succeeded. That old run does not certify this R2 head.

## Remaining scoped limitations

The old device/shell/operator scripts retain documented manual/private environment requirements and are CONDITIONAL, not deleted as dead code. Production deployment shell is BLOCKED_EXTERNAL; ProjectReactor rewriting utility is preserved OBSOLETE pending any external/manual ownership. Actual caller/commands/hash/reason are recorded per asset. Helpers/fixtures are not standalone tests.

The six requested mutation uncertainties are resolved without removing defenses. Python coverage measures only selected drivers; Node coverage is NOT MEASURED. Business/payment DB races are in one JVM with independent DB connections; actual two-process auth is separately proven. Whole-machine networking,real provider behavior and physical-device scenarios are not measured.

## Human action pack

Only manual slider authentication can unlock remaining admin GUI writes/stock/coupon/permission/order/history checks. Use a fresh owned5185 fixture, never the expired page or shared5175 session. The actual new URL/expiry/prefilled synthetic identity and confirmation text are supplied once at handoff. Missing GUI receipt stays BLOCKED, and owned resources auto-expire/destroy. No CAPTCHA bypass.

## Financial Safety Statement

No real merchant credentials, real WeChat prepay/query/close/callback, wx.requestPayment, refund,recharge or funds operation. Both payment/reconciliation flags are false. Financial events use only isolated synthetic databases. Shared balances/points/ledger/payment history/markers are not modified. Measured HTTP/Mini admission counts apply only to their stated hook/interval; zero global network traffic is not claimed.
