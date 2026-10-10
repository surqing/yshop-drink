# PHASE 6Q quality gate — original request

QUALITY_GATE_READY = NO

PHASE_6D_ALLOWED = NO

This conclusion applies to the original comprehensive request and overrides the earlier R2 scoped READY statement below. No PR is merged. PR12 stays at f96c70a; existing stacked PR13 is reused to preserve9 prior commits and its independent worktree. develop/master are untouched.

Measured results and defects: [inventory](test-inventory.md), [coverage](coverage-report.md), [mutations](mutation-report.md), [gaps](test-gaps.md), [execution](test-execution.md), [machine receipts](request-evidence.json).

Blocking criteria: high-risk survivors3;85/75 critical coverage not all achieved; current real GUI/Mini and broader components not fully verified. Passing ordinary tests or Linux CI do not override these criteria. No unrun test is called PASS. The known empty-plan and broad race exception false-pass paths are repaired with before/after evidence. Latest-head CI still must actually finish before delivery; its exact result is recorded outside Git to avoid a self-referential commit hash.

CI remains pull_request-triggered,contents:read, no pull_request_target or merchant secrets, bounded timeout and sanitized artifact upload. It now runs a real Vue component test and both frontend mutations. Database gates use owned resources. A workflow configuration does not itself make branch protection required; repository rules were not changed.

Financial safety: zero real payment/provider/funds operations, synthetic-only fixtures, both flags false. This is declared scoped execution evidence, not whole-machine packet monitoring. No Phase6D.

## Historical scoped R2 record (does not certify this revision)

# Phase 6Q-R2 quality gate

**PHASE 6Q QUALITY GATE: READY for the completed `808294c` code snapshot. Final publication requires actual latest-head CI SUCCESS.**

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
| Actual official Mini |15 fresh runtime+DB checks at808; source380 hashes verified; measured guarded paymentRequests=0; paired owned backend cleanup PASS|
| Admin GUI |8/8 actual GUI checks at808; manual HQ/staff slider, independent SQL oracles, normal logout; backend/frontend/owned containers and volumes destroyed|
| Linux controlled |38011484288 at808 SUCCESS in both jobs; original Redis UID/0600 fault reproduced;490 business+497 financial+72 auth+66 Redis+10 TLS+45 HTTP checks, cleanup PASS|

Final-head CI, renewed coverage, strict scan and official device evidence are recorded in delivery and the PR's actual checks. This source document does not predict a pending run's conclusion. Failures/blocked scopes are not suppressed.

## Original CI regression retained

37902827072 failed system-biz timestamp assertions after11 modules. Safe diagnostics and clean Linux reproduction identified nanosecond Clock versus SQL precision, including approve/code/token expiry assertions. R1 persisted whole-second expiry and canonical fixtures plus fixed-nanosecond regression, with no tolerance weakening/skip. Actual R1 final run37945545110 at8eb succeeded. That old run does not certify this R2 head.

## Remaining scoped limitations

The old device/shell/operator scripts retain documented manual/private environment requirements and are CONDITIONAL, not deleted as dead code. Production deployment shell is BLOCKED_EXTERNAL; ProjectReactor rewriting utility is preserved OBSOLETE pending any external/manual ownership. Actual caller/commands/hash/reason are recorded per asset. Helpers/fixtures are not standalone tests.

The six requested mutation uncertainties are resolved without removing defenses. Python coverage measures only selected drivers; Node coverage is NOT MEASURED. Business/payment DB races are in one JVM with independent DB connections; actual two-process auth is separately proven. Whole-machine networking,real provider behavior and physical-device scenarios are not measured.

## Human action pack

The manual HQ and staff slider actions were completed for the renewed owned5185 environment; all eight GUI checks passed. No human blocker remains for this scope. Only manual slider authentication can unlock a future renewed GUI run. Use a fresh owned5185 fixture, never the expired page or shared5175 session. The actual new URL/expiry/prefilled synthetic identity and confirmation text are supplied once at handoff. Missing GUI receipt stays BLOCKED, and owned resources auto-expire/destroy. No CAPTCHA bypass.

## Financial Safety Statement

No real merchant credentials, real WeChat prepay/query/close/callback, wx.requestPayment, refund,recharge or funds operation. Both payment/reconciliation flags are false. Financial events use only isolated synthetic databases. Shared balances/points/ledger/payment history/markers are not modified. Measured HTTP/Mini admission counts apply only to their stated hook/interval; zero global network traffic is not claimed.

## Final publication attribution

The completed808 CI checked the pull-request merge checkout fc77e92d; its content digest matches local808. Local ordinary Java1710, fresh UniApp compile, official Mini15, actual GUI8, HTTP45 and coverage carry their original808 identities. The final publication adds only this evidence and owned synthetic coupon action/menu/media fixtures; it does not modify application Java/Vue/UniApp behavior. Production source equivalence is verified separately, and latest-head CI must actually succeed before handoff. An earlier SUCCESS never substitutes for that requirement.

GUI measured storeA product23→24 and stock7+3=10, created/stopped coupon30−5, and queried two unpaid storeA/B orders. StoreA staff searching the known storeB order saw no rows; its own order remained visible. Original product19→22 left the existing order19 and historical coupon order31 unchanged; all orders remained unpaid, issued coupon right remained available, financial tables stayed empty. Three coupon action menu entries were missing from the synthetic HQ browser cache; only the owned fixture was corrected, followed by normal logout and manual re-login. The source seed now includes those entries and one generated local PNG; no real role, media or upload provider is used.
