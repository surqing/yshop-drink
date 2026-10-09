# Phase 6Q-R1 quality gate

**PHASE 6Q QUALITY GATE: NOT READY**

The P0 CI/authentication/evidence repairs have actual passing evidence. Remaining GUI and portability/measurement gaps are explicit; this is not permission to merge PR12/13 or begin Phase6D. Existing PR13 remains on `audit/phase6q-test-quality-gate`, base `feature/phase6c-coupon-marketing`. develop/master unchanged. [Evidence](evidence-summary.json),[inventory](test-inventory.md),[risk](business-risk-coverage.md),[coverage](test-coverage-report.md),[mutations](mutation-testing-report.md),[runner](test-runner-audit.md).

## Original CI failure and root cause

Run37902827072 actually failed after11 Java modules. Safe diagnostic run37910149176 identified exact timestamp assertions in OAuth2ApproveServiceImplTest.testGetApproveList; OAuth2CodeServiceImplTest.testConsumeAuthorizationCode_success/testCreateAuthorizationCode; OAuth2TokenServiceImplTest.testCheckAccessToken_success/testRefreshAccessToken_success/testGetAccessTokenPage/testGetAccessToken/testCreateAccessToken. AssertionFailedError,not compilation failure. Clean official Linux amd64 Maven3.9.9/Temurin17 at approved d8 baseline independently reproduced9 timestamp assertions (the additional removeAccessToken case is precision-sensitive).

Linux Clock nanoseconds disagree with database microsecond rounding and MySQL DATETIME(0); Mac timing had masked it. Canonicalized persisted token/code expiry to whole seconds and exact time fixtures to the same contract. A fixed nanosecond Clock regression proves it. No tolerance relaxation,test disable,skip or ignored exit was used. Default macOS sandbox Mockito attach errors were separately retained and never confused with the real CI defect.

Actual implementation run [37922370065](https://github.com/surqing/yshop-drink/actions/runs/37922370065) at head9274a5b8f2bf31a501bc735b0a8d3d449c5aebfd is SUCCESS:55 build,guard,QUICK123,Java1705,Vue build/type. GitHub tests merge checkout66c72de5de12e0ee6709614374ba5304d2b09297; its content digest matches the source snapshot. The subsequent late-failure fix has QUICK126 and actual source-fault proof; final pushed HEAD must have its own observed successful CI,recorded outside this self-referential documentation in final delivery.

## Actual results

| Scope | Result |
|---|---|
| Ordinary Java |1705 PASS;76 suites;1173 distinct display names;12 modules;0 failures/errors/skips|
| QUICK |126 PASS:47 Node +79 Python; exact manifests; includes34 evidence and12 ownership/failure checks|
| MySQL8.0.46 business |488 PASS;23 InnoDB tables; cleanup PASS|
| MySQL8.0.46 financial/cancellation/coupon |497 PASS;12 InnoDB tables; cleanup PASS|
| Owned MySQL authentication |69 PASS;4 InnoDB tables; owned container+volume removed|
| Actual Redis |66 PASS;independent clients; shutdown fail-closed; owned-prefix cleanup|
| Synthetic HTTPS ingress |10 PASS; dedicated synthetic proxy/schema; cleanup PASS|
| Backend / Vue |55/55 SUCCESS;build/type check PASS|
| UniApp |actual HBuilderX compile PASS;380 source hashes; compiled local48083 binding verified|
| Owned business HTTP |22 checks/30 requests PASS; product/coupon/unpaid retry/cancel/disable-refresh/logout; automatic owned cleanup|
| Actual official Mini simulator |see machine receipt: owned-store/SKU/topping/cart/unpaid submit/cancel plus database oracle; no historical7/7 result substituted|
| Admin GUI |BLOCKED at human slider; GUI CRUD not claimed PASS|
| Mutations |21 KILLED /3 SURVIVED /3 INCONCLUSIVE; seven new dangerous compound boundaries killed|
| Strict scan |private-value/diff/JAR/fresh Mini scan PASS; final receipt at delivery|

Ordinary and real-DB invocations overlap; do not sum them into unique scenarios. Each measurement retains original SHA/digest/runId; earlier uncommitted code runs are not rewritten to a later commit. Source-change failure receipts, fixture failures and mutation-survivor history are preserved. Raw logs/SQL/token content are not public artifacts.

## Production fixes and contracts

Current principal and DB revocation evidence now protect issuance,refresh and old access tokens. Typed member/admin refresh/logout prevent identity confusion. Disable/delete and family revocation are transactional; cache side effects follow commit and a stale cache cannot authorize. Old rotated access identifies its family for logout; re-enable cannot resurrect it. Existing reusable refresh contract remains;20 concurrent refreshes return access generations but only the final one is valid. Current-principal/family lock serializes logout/disable/refresh. Two independent instances share a real DB in the same test JVM; a multi-process deployment is not claimed.

Member password/SMS/logout/refresh tests check success/failure logs and last-login changes. Actual isolated HTTP disable→refresh/login rejection and logout→refresh rejection are measured. No real WeChat authorization,SMS,merchant or user account is used. The DB decision does not retroactively interrupt an already admitted request.

## Remaining UNVERIFIED / human action pack

1. Isolated admin GUI create/edit product and coupon requires the user to complete the frontend slider. No programmatic CAPTCHA bypass. The earlier5185 fixture expired and was cleaned; reply to arrange a fresh owned fixture rather than using original5175 or shared data.
2. Full clean-Linux legacy MySQL business/financial/TLS bootstrap is not implemented end-to-end; own Docker auth/HTTP bootstrap works here and standard CI is proven, but legacy heavy modes still require explicit synthetic private adapter/CA. Minimal setup and nonzero/BLOCKED behavior are in runner audit.
3. Three broad old mutants remain INCONCLUSIVE and three redundant defenses SURVIVED; neither is hidden. Complete Mini claim UI,staff GUI and physical-device permission scenarios beyond the exact measured run remain unverified.
4. Whole-machine network capture,real-provider behavior and multi-process production requests are not measured. HTTP/Mini paymentRequests=0 applies only to their installed admission hooks. Compile/dispatcher do not manufacture a runtime zero.

No routine approval is needed for further tests. Only the real GUI CAPTCHA/user-specific official tool permission,if prompted,requires human action. No merge,Phase6D or production rollout is authorized by this gate.

## Financial Safety Statement

No real merchant credentials; no real WeChat prepay/query/close,real callback,wx.requestPayment,refund,recharge or funds transaction. Live and reconciliation remain false. Paid-success tests only use trusted synthetic events in isolated DBs. Owned business/Mini orders remain unpaid; four financial tables empty. Shared development financial fingerprint unchanged in read-only verification. No shared balances,points,ledger,payment records or markers changed. These are declared procedural limits reinforced by scoped measured evidence,not whole-machine network proof.
