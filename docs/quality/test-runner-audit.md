# Runner and evidence audit — Phase 6Q-R1

`run.py`, `evidence.py`, inventory,MySQL/Redis/TLS adapters and workflow were audited by actual failures and fresh reruns. Private raw logs are never public artifacts. [Machine scope](evidence-summary.json).

## Fail-closed rules proved

34 evidence/integrity tests cover no tests, missing suite, wrong method/invocation counts, empty/duplicate/foreign reports, skipped cases, hidden assertion failure, subprocess failure/timeout, stale report/runId, changed source SHA/content, standalone-module diagnostics and old inventory certificate/registry mismatch. QUICK discovers registered Python TestCase and Node suites and requires the discovered/planned/registered sets to match exactly; it cannot silently omit a new test file. Exact QUICK manifest is47 Node+79 Python=126.

Fresh Surefire directories contain exact suite and display-method invocation manifests. Every report carries quality.runId; evidence has both commit SHA and full tracked/new nonignored content digest. A dirty-tree run is not relabeled as a later commit. Reports with missing or changed registry cases cannot pass inventory validation. Test totals count invocations, not distinct scenarios or unique business risks.

Subprocess groups are bounded; timeout kills children and cannot PASS. Maven writes within a checkout are serialized to avoid shared target-output corruption. Failure in one module still allows safe diagnostics and remaining independent checks; the whole gate fails. CI compile,guard,QUICK,Java and Vue use independent non-cancelled steps, with no ignored exit codes or excluded failing test classes.

## Actual newly found late-failure bug

The new owned HTTP fixture had already marked22 assertions PASS when its final source-integrity check threw. Its catch initially added reasonType but did not reset result, causing a false PASS. The held GUI receipt containing reasonType is explicitly rejected and preserved privately; it is not GUI acceptance.

Fixed catch always calls failed_report(), resetting FAIL while retaining earlier valid assertion receipts, publishing only exception category/stage and a bounded known source-change code. Three direct failure tests check override, source-change and message privacy. An actual disposable-backend run first executes22 HTTP checks, then receives a nonignored source-canary change: process exit1, resultFAIL, SOURCE_CHANGED_DURING_TEST and cleanupPASS. The owned canary was removed afterward. A fresh normal run then passes22 and cleanup; no source-change exception is treated as benign.

12 owned-resource tests cover ownership mismatch,Docker outage,remove failure,remaining container/volume,unowned named volume,backend owner mismatch and late failures. Actual cleanup verifies random run labels, exact container identity and anonymous64-hex volume names before destruction,then verifies absence. Shared/named volumes are not removed. HTTP writes require the random owner from `/actuator/info`, not merely a healthy port. Credentials/configs are private600 files and deleted on completion.

## Safe diagnostics

Public artifacts provide FQCN, known method name, failure/error/skip counts, exception/root-cause class and bounded source locations. They exclude exception messages, stdout/stderr, request bodies, tokens, SQL values and credentials. XML and raw subprocess output remain private. The original system failure was actually diagnosed through this path; final Linux CI executes all12 Java modules.

## Reproducibility

Standard GitHub Ubuntu runner: JDK17,Maven3.9,Node20,pnpm8,Python3; full55 build; QUICK; exact Java; Vue build before type check to generate declarations. Actual commit9274 CI SUCCESS, with later final-head result in delivery receipt. No Java test exclusion/disabled assertion/exit-ignore was used.

Portable owned dependencies (Docker required; no private service already running): `python3 tests/quality/auth-mysql.py --output <new-private-dir>` after full Maven install; `python3 tests/quality/business-backend.py --output <new-private-dir> --port <free-loopback-port>` after full backend package and installing `tests/quality/requirements.txt`. Both automatically create random MySQL schemas/accounts/container labels; business also owns Redis/backend. Do not reuse output directories.

Full legacy financial/business and TLS acceptance still need an explicit private bootstrap adapter,synthetic local CA and available ports. They passed here but a complete clean-Linux heavy bootstrap is BLOCKED/not proven. Minimal preparation: Docker,JDK17/Maven snapshots; explicit synthetic-only local adapter/config; synthetic CA and dedicated loopback TLS port; then MYSQL/REDIS/TLS runner modes. Never substitute production credentials or silently use shared real accounts. Official Mini automation needs logged-in developer tools and its service port; new project trust must be granted by the user if prompted.
