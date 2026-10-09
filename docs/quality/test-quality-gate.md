# Phase 6Q test quality gate

**PHASE 6Q QUALITY GATE: NOT READY**

This audit provides a working fail-closed test system and actual results. It does not grant unconditional approval to PR #12 or the next business phase. Base `f96c70a10978939f66392788fa3f0fb863d8aca2`, independent branch `audit/phase6q-test-quality-gate`, intended QA PR base `feature/phase6c-coupon-marketing`. PR #12 remains OPEN/unmerged; develop/master are unchanged. [Machine evidence](evidence-summary.json), [asset inventory](test-inventory.md), [risk matrix](business-risk-coverage.md), [coverage](test-coverage-report.md), [mutations](mutation-testing-report.md), [runner audit](test-runner-audit.md).

## Inventory scope

241 assets: 205 ACTIVE,35 UNVERIFIED,1 OBSOLETE;0 DEAD/BROKEN/SKIPPED files in the final evidence classification. There are93 assertion-suite files,88 with actual execution (78 JUnit,5 Node,4 Python,1 device suite);five legacy write-device suites remain UNVERIFIED. Three Java suites are environment-gated in default ordinary Java runs (MySQL editing,Redis,TLS),and were separately executed; default exclusion is not counted as PASS or a skipped test invocation. Repeated/parameterized cases and H2/MySQL runs overlap; totals are not unique business risk counts. The obsolete source rewriting utility is preserved and explicitly marked DO NOT RUN.

## Actual verification

| Check | Actual result / attribution |
|---|---|
| Ordinary Java | **1617 PASS**,75 suites,1142 distinct display names,0 failures/errors/skips; run68129e3da33e4d7ea871c8f3289ef86c |
| Node | **47 PASS**,five suites,0 skip/fail |
| Python | **57 PASS**: runner24,offline secret guard3,existing secret scan4,prepayment tools26; run13738d9eaded414aa4ecc469ac0de84e |
| MySQL8.0.46 business | **487 PASS**,23/23 InnoDB, exact names, cleanup PASS; run02091fc7a7d4469ab2a2e3926935b921 |
| MySQL8.0.46 financial/cancellation/coupon | **497 PASS**,12/12 InnoDB, exact names, cleanup PASS; run358fbec228ed4c44928b1fae7a3dda6c |
| MySQL + synthetic HTTPS ingress | **10 PASS**,12/12 InnoDB, owned proxy/volume and schema/account cleanup PASS; runedc9f0ee82e74d009d172373b24a261f |
| Real Redis | **66 PASS**,two independent main clients, separate actual shutdown-client rejection, random prefix cleanup; runea74e355bd00402594d5376272f22c2b |
| Backend | **55/55 SUCCESS**,final full install/package after auth fix; tests executed separately |
| Vue | Frozen install,build:local and final ts:check PASS; first clean type run lacked generated declarations and was repaired by correct build order |
| UniApp | Actual HBuilderX compile PASS,380 copied source hashes; fresh private project8b8fa8d642ac49c6b7ebc427cb67bf01 |
| Actual Mini Program | **7/7 read-only checks**,five local API successes,0 exceptions,scoped measured paymentRequests=0; no server writes; full write flow BLOCKED |
| Secret | Strict exact private-value/diff/runtime scan PASS + offline new-diff literal guard PASS; injected synthetic Secret log causes actual scan FAIL without echo |
| Historical DesensitizeTest | Reproduced old failure; retained corrected nickname assertion plus boundary/null regressions; web module20 PASS |
| Static sanity | Python AST,Node/Bash syntax,workflow YAML parse,git diff whitespace PASS |

Counts are **invocations in a stated scope**. H2/MySQL executions of the same test are overlapping verification and must not be added as unique business scenarios. No historical Phase6C PASS count is substituted for this audit's run IDs. Private raw logs live under `$YSHOP_TEST_WORKSPACE/.local-dev/quality` and `.local-dev/acceptance`; committed evidence is sanitized summary only.

Initial MySQL attempts encountered network EOF errors; they were failures, not test kills or PASS. Owned failed runs were cleaned and fresh complete serial runs passed. No exact infrastructure root cause is claimed. Quality entrypoints now serialize Maven writes to shared build outputs and bound JDBC connect/socket and process waits. The final complete serial evidence is listed above.

The first independent TLS run found private-workspace and fixed-port assumptions; repaired tests now use explicit workspace and a dedicated synthetic-only port. Its first successful run replaced two old fixed-name private summary files. This side effect is recorded, not erased from history: prior report contents cannot be claimed preserved. Reports now stay in their owned temporary synthetic folder, with a preservation regression and another passing TLS run. No database/credential/operational proxy was changed by that report write.

## Bug findings

- **P0 fixed: disabled existing WeChat member received a token.** An eight-case auth regression first failed because no ServiceException was thrown. The minimal shared token-issuance guard now requires a present,currently enabled member before token/log/update effects. After-fix8 PASS; full member module10 PASS; full Java1617 and backend55/55 PASS. No financial core change.
- **P1 fixed: embedded test Redis silently ignored startup failure and shared a configured port.** A before-fix occupied-port assertion fails; an owned ephemeral loopback fixture with client initialization dependencies and context cleanup passes three regressions. Full Java/backend rerun confirms the repair. No development Redis keys were written by the failing regression.
- **P1 test infrastructure:** stale/minimum-count/missing-suite/skip/cleanup success risks repaired; commented/no-op member assertions replaced with real service contracts; unsafe fixed report output scoped to isolation.
- **P2 historical tests:** Desensitize expectation drift, missed Test2 discovery, renamed filter fixtures/schema drift/material missing resources repaired. Disabled SMTP demo retained as an explicitly retired non-JUnit fail-closed method; its obsolete embedded credential/live call removed, nine mock transport tests retained.
- No other reproducible production P0 defect is asserted. Coverage gaps are not invented bugs, and passing tests do not prove absence of bugs.

## Why the overall gate remains NOT READY

1. Authentication refresh/logout/SMS and permission/token revocation during active requests have insufficient direct controller/cross-instance coverage. The actual auth class is29.41% line/23.08% branch covered. High order/coupon line coverage cannot certify this boundary.
2. Twenty Java dangerous operators were actually attempted:17 killed,3 redundant-defense survivors. The survivors are documented with independently killed composite boundaries. Missing dedicated operators still include repeat release,complete duplicate fulfillment bypass,wrong store price and double coupon reservation. Raw MUTATION conservatively stays nonzero on survivors; no unconditional green score is substituted.
3. Actual device read-only pages are verified. The five legacy business-write smoke scripts are not safe to rerun by default against shared development data; no fresh isolated-backend admin CRUD/full device checkout/network-failure write acceptance is claimed. These remain UNVERIFIED/BLOCKED with explicit entry requirements.
4. The new minimal GitHub Actions job is implemented and locally syntax checked. No remote passing run was observed at documentation freeze. Require a real passing job and branch protection/review policy before relying on it; repository protection was not changed.
5. Heavy integration uses an explicit pre-existing private Docker adapter; a clean machine still needs documented prior local bootstrap. Missing adapter/config/device produces BLOCKED/nonzero. Portable disposable-service bootstrap and stronger host-wide network accounting remain maintenance work.

The test system is materially more trustworthy: fresh exact evidence,no missing/skipped false greens,deliberate runner fault detection,real database concurrency,measured coverage and controlled mutations. Execution integrity is verified for the executed subsets. Assertion effectiveness is supported by kills and cross-table DB oracles. Coverage sufficiency and universal deployment/financial behavior are **not** established.

## Next review decisions

Do not merge PR #12 on the old approved head based on these numbers. Review the QA PR and auth fix, integrate only with explicit approval, then re-review the changed Phase6C head and observe required CI. Do not start Phase6D automatically. Recommended prerequisites are the remaining high-risk auth/mutation coverage and an isolated backend for current-branch write UI acceptance; unrelated features stay out of this QA PR.

Device automation needs macOS,HBuilderX,official WeChat tool CLI and the user's own AppID. Public ingress and any real provider integration need separate merchant credentials/authorization and a manual environment; none are required or permitted for this synthetic gate. No real provider testing was performed. No test suite can guarantee detection of all bugs.

## Financial Safety Statement

No real merchant credentials were used. No real WeChat prepay,query,close or callback; no real wx.requestPayment,refund,recharge or funds transaction. Live and reconciliation switches remain false. Financial tests only use synthetic keys/fake transport and automatically cleaned isolated schemas; development historical paid,balances,points,ledger,payment records and markers were not modified. The measured read-only development financial fingerprint remains unchanged.

`paymentRequests=0` is **MEASURED only during the installed Mini Program guard window**. Database runner zero-provider fields are **DECLARED_NO_PROVIDER_PATH**, not global network telemetry. `realFinancialOperations=0` is declared by the synthetic-only execution design and reinforced by the measured unchanged development fingerprint; it is not misrepresented as host-wide provider capture.
