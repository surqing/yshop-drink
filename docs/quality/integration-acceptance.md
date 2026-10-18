# Accumulated-work integration acceptance

This candidate combines PR12–18 into one reviewed change targeting develop. The
frozen starting develop is `fa39ef2b854eb21655390866090f61b0c4d52a11`; the cumulative
starting head is `29b4faf0ed72379633170afa702c601b3f01b0d2` (52 commits). Production
changes include coupons, member identities, token revocation, address ownership,
order lifecycle and the test framework. This is not a test-only PR.

## Scope gate

D009, introduced by the coupon form rewrite, must pass its original two-context
browser regression. Component tests additionally exercise failed detail/shop
loads, close/reopen, response order, target identity, pending validation, duplicate
saves and stale write completion. The public API remains unchanged.

D001/D004 and their positive controls must pass on the final candidate. The six
known develop defects D002/D003/D005/D006/D007/D008 remain FAILED, with their
original assertions and nonzero exit. Their exact business observations must
match two repetitions against the unchanged, separately built develop archive.
Any new ID, missing case, changed observation/assertion, inconclusive result,
source drift or incomplete owned cleanup blocks the separate regression-scope
certificate. An accepted known failure never contributes to passing test counts.
The historical discovery documents retain their original source and statuses.

```sh
python3 tests/quality/legacy_baseline.py --output /private/output/baseline
python3 tests/defects/http_red.py --output /private/output/candidate --repeat 2
# Both commands above deliberately retain raw FAILED and exit 1.
python3 tests/quality/merge_scope.py --baseline /private/output/baseline/report.json \
  --candidate /private/output/candidate/report.json \
  --base fa39ef2b854eb21655390866090f61b0c4d52a11 \
  --output /private/output/regression-scope.json
```

The baseline runner checks identical ASTs for shared business assertions, archives
and hashes baseline tracked files, builds all 55 baseline modules and launches
that unchanged JAR with external owned fixtures/configuration. It never patches
baseline production classes or applies candidate-only migrations. A changed
develop base requires a new baseline decision and cannot reuse this exception.

## Migration contract

The three new migrations are coupon-marketing, coupon-permissions and
member-identity. Run `python3 tests/quality/migration_acceptance.py --output
/private/output/migrations` against a new owned MySQL8 container. It checks
historical rows/grants, full schema and idempotent second application. The separate
registered MemberIdentityMysqlAcceptanceTest receipt remains mandatory, including
identity conflict rejection and snapshot restoration; EXTERNAL_REQUIRED is not PASS.

Coupon marketing accepts a legacy schema or the full expected schema. Partial or
incorrect columns/indexes/evidence tables require operator investigation and are
rejected before table/data DDL. Coupon permission migration requires one active
coupon::query descriptor and its active menu ancestor. It registers descriptors
without role grants. The schema-only test environment creates its synthetic menus
before applying that permission migration. Production installations must provide
their actual valid menus; no fixture is copied into production.

Identity migration still requires all identity writers stopped and a verified
snapshot. DDL implicit commits require snapshot restoration after a late ALTER
failure; a transaction alone is not a rollback plan. Actual historical conflicts
and platform configuration remain deployment prerequisites.

## Final evidence and merge

Freeze the candidate before final BUILD/QUICK/Java/frontend/controlled-dependency,
browser, cross-end and registered mutation checks. Obtain two independent full
local E2E runs plus one actual Linux CI run, using the existing strict acceptance
validator. Bind reports to source SHA/content digest, JAR/support hashes, exact
case manifests, unique run/owner IDs and verified cleanup. Scan the entire diff
with `secret_guard.py --base <frozen-develop-sha>`; SECURITY's historical base alone
does not cover the full cumulative PR.

CI regression-scope tests the exact PR head; other jobs separately exercise the
GitHub merge checkout. Record their actual trees and source identities. Merge
with a merge commit only after independent review and all scope gates pass.
Check actual merge parents/tree and that all frozen prior heads are ancestors.
Close remaining old PRs only after checking their current heads are included.
Preserve branches and evidence.

QUALITY_GATE_READY and PHASE_6D_ALLOWED remain NO. Known legacy product defects,
broader coverage, physical devices, production operations and printer management
remain separate follow-up work. No real provider/funds operation is authorized by
this acceptance.
