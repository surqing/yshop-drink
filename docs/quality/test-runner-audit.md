# Runner and evidence audit — Phase 6Q-R2

The existing runner is extended, not replaced. QUICK now has **139 exact invocations:47 Node +92 Python**, including43 evidence tests and16 ownership/GUI failure tests. [Machine scopes](evidence-summary.json), [asset classification](test-inventory.json).

## Fail-closed boundaries

Fresh suite/method/invocation manifests, nonempty reports, no skips/errors/failures, unique runId, source SHA and full nonignored content digest remain mandatory. Missing suites, fewer invocations, stale XML, hidden assertion errors, subprocess failure/timeout, later integrity failure and changed source fail the gate. Maven target writes are serialized within a checkout; independent steps still collect diagnostics after another fails.

Controlled GUI/CLI commands need a fresh receipt matching run/source and the exact planned executed assertion names, result and cleanup. Exit0 without a receipt is BLOCKED. `--hold-for-gui` requires all eight actual GUI assertions; missing/partial/stale evidence cannot inherit the successful HTTP result. Device hold is separate. An unrelated private Mini harness cannot certify `mini-readonly.cjs` in inventory. Helper metadata is distinct from a certified test run.

Backend builds parse all55 declared reactor artifacts and require every one SUCCESS plus BUILD SUCCESS; exit0 with a partial/duplicate/skipped reactor fails. MySQL DROP commands must also prove schema/account absence; successful exit with a residual object fails. Run-owned Docker label/name/anonymous volume ownership is verified before destruction and absence afterward. Redis acceptance verifies owned-prefix absence after cleanup. A cleanup error after assertions overrides PASS.

The R1 actual source-canary/late-failure run and cleanup fault injections remain; R2 adds missing GUI receipt, forged Mini attribution, incomplete build and bounded diagnostic failure injections. Ordinary exact test manifests are unchanged except the five explicitly added Java regressions and new runner assertions.

## Safe diagnostic evidence

Java public artifacts contain registered class/method, failure category, exception classes and bounded source frames, never XML messages/system output. Python diagnostics contain only AST-declared test/class names and bounded exception categories; injected payload/unknown names are rejected. Raw logs, request bodies, token values, SQL and secrets remain private. Owned adapter errors publish only numeric MySQL error/SQLSTATE, with raw stderr in a600 file. Both acceptance wrappers save safe structural diagnostics even on Maven failure.

## Portable commands and actual scopes

Prerequisites: Docker, JDK17/Maven3.9, Python3.11; Node20/pnpm8 for standard frontend checks. No private daemon/schema/account/merchant credential is required.

```
python3 tests/quality/run.py QUICK --output /tmp/new-quality
python3 tests/quality/run.py INTEGRATION --output /tmp/new-controlled
# or the exact controlled gate:
python3 tests/quality/heavy.py --output /tmp/new-heavy
python3 -m pip install -r tests/quality/requirements.txt
python3 tests/quality/business-backend.py --output /tmp/new-business --port 48883 --instances 2
```

Build the55 modules first (`Runner.backend()` verifies the reactor). Use a new output directory and free loopback ports. Heavy gate creates owned MySQL8/Redis7.4, random schemas/accounts/ports, synthetic TLS CA and proxy, and destroys them. All SQL is through a labelled own container. TCP readiness prevents using the temporary socket-only bootstrap daemon. The first adapter failure's exact trigger was not reproduced; it is not falsely labeled a confirmed environment flake. Improved diagnostics preserve future numeric causes.

Mac controlled gate actually passes490 business +497 financial +72 auth +66 Redis +10 TLS with cleanup. TLS was repaired to use the owned ingress URL; Docker Desktop uses native host DNS, while Linux uses host networking with localhost-only proxy/backend. No TLS bypass or host trust-store installation. The official Nginx digest is multi-architecture amd64/arm64.

GitHub has separate ordinary Java/Vue and controlled-dependencies jobs; controlled also runs two backend processes with synthetic HTTP writes. Ordinary CI SUCCESS does not certify GUI/Mini or controlled results. Both jobs upload safe report/evidence/diagnostic summaries only. Final-head execution is recorded in delivery/PR checks; old CI is not substituted.

## Linux private Redis bootstrap review fix

The first R2 Linux controlled job failed before assertions with KeyError; both owned containers/volumes were removed. This is retained as FAILURE, not labeled a flake. Redis configurations stay600/read-only. The corrected container runs with the non-root host file owner UID/GID, cap-drop=ALL and no-new-privileges; root test launchers fail closed. Actual run38010582858 passes the dedicated original-permission-failure reproduction step; the full controlled job remains pending at this document snapshot. The separate Linux probe must assert the original UID mismatch permission denial, keep raw logs private and prove cleanup. Safe diagnostics add only a closed set of missing metadata keys and bounded file/function/line frames; arbitrary exception payloads remain private. Mac corrected gates490/497/72/66/10 and two-process45 checks pass with cleanup, with original SHA/digest preserved. Latest-head GitHub conclusions remain an explicit delivery obligation.
