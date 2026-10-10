# PHASE 6Q execution and reproducibility

Use JDK17, Maven3.9, Node20, pnpm8 and Python3.11 (local Python3.9 also executed the recorded gates). Install Vue dependencies with `pnpm --dir yshop-drink-vue3 install --frozen-lockfile`. Optional owned HTTP acceptance needs `pip install -r tests/quality/requirements.txt`. Docker is needed for MySQL8/Redis7.4/TLS; no existing developer database or merchant credential is required.

| Mode | Command | Discovery and safety |
|---|---|---|
| quick | `python3 tests/quality/run.py quick` |5 Node +5 Python suites,147 exact calls, no skips|
| java | `python3 tests/quality/run.py java` |76 ordinary suites;1711 current planned calls;3 conditional suites dispatched separately|
| business | `python3 tests/quality/run.py business` |ordering/catalog/coupon/security manifest; no empty selection|
| finance | `python3 tests/quality/run.py finance` |payment/credential/preflight synthetic suites, frozen real payment|
| mysql | `python3 tests/quality/run.py mysql` |owned Docker; random schemas/accounts; genuine InnoDB; exact reports + cleanup|
| frontend | `python3 tests/quality/run.py frontend` |quick checks +10 real Vue component tests + both V8 reports|
| security | `python3 tests/quality/run.py security` |quick +desensitization/coupon/security/permission/auth + offline secret diff guard|
| mutation | `python3 tests/quality/run.py mutation` |28 backend +2 frontend operators; any survivor returns failure|
| full | `python3 tests/quality/run.py full` |ordinary/quick/frontend/owned DB/build + explicit controlled compile/secret/Mini receipts; unavailable device receipts remain BLOCKED|
| report | `python3 tests/quality/run.py report --run-report <current-run/report.json>` |explicit same-source reports only; zero executed tests, stale/duplicate/failed/skipped reports rejected|

Provide `--output` outside Git. Each dispatcher run uses a unique directory, SHA, full tracked/unignored content digest, start/end, private command record, public command digest, exit code, timeout and fresh exact suite/method/invocation checks. Evidence and heavy gates now also record timestamps; MySQL receipt carries actual engine/version. Build success is recorded separately from test success. Report mode does not turn a build-only receipt into passing tests.

Current local ordinary1710 + tightened ordering85 overlap84 calls:1711 registered ordinary invocations were observed across these explicit snapshots, not1795 unique tests. Controlled before-tightening: business490,financial497,auth72,Redis66,TLS10 PASS with2 owned containers and2 volumes removed. The strengthened ordering requires the new491 business run in final-head CI; the old490 is not relabeled. H2/MySQL counts overlap and are not added as unique scenarios. Final-head CI outcome is independently verified in PHASE6Q-DELIVERY.md.

All20x20 database groups use separate transactions/connections and final stock/order/coupon/ledger assertions. Existing examples: lastItemTwentyBuyers, twentyMembersClaimLastCoupon, sameMemberTwentyKeysLimitOne, duplicateKeyTwentyConcurrent, priceChangeVersusTwentyOrders, skuEditAndReservationRace, cancellationAndAttemptCreationRace and callbackAndCancelRaceDoesNotDeadlock. No deadlock/SQL fault may be caught as an ordinary race loser in the hardened ordering cases.

Financial transport is fake/synthetic; real payment and reconciliation flags remain false. No provider, SMS, refund, recharge, merchant credentials, shared balance/points/marker mutation. Local HBuilderX compile uses a new private sanitized mirror and preserves source hashes; compile is not runtime UI verification. Official GUI/CAPTCHA and legacy seed-port scripts remain explicit local conditions, never silently skipped.

Raw/private evidence: `/Users/sur/Documents/Codex/iPeony/.local-dev/quality/phase6q-request`. Local full Java wall duration includes waiting on the owned MySQL Maven lock. Owned gate observed folder-create→receipt duration1720.08s; this is filesystem timing for the legacy receipt, not retrofitted start/end fields. Future heavy receipts contain native timestamps. Old sandbox JVM initialization failure and an aborted waiting run remain NOT_READY, not test passes. The initial frontend mutation path normalization error and missing converter dependency were INCONCLUSIVE/FAIL and retained before repaired runs.

Strict exact-value scan uses local protected values, not a public canary in place of real credentials: `YSHOP_TEST_WORKSPACE=<workspace> python3 tests/smoke/secret-scan.py --base f96c70a10978939f66392788fa3f0fb863d8aca2 --runtime <safe-artifact>`. The portable CI diff guard is complementary. Earlier broad-baseline overlap with public development defaults remains separately FAIL; this PR/changed artifact PASS is not a claim that all public defaults were removed.
