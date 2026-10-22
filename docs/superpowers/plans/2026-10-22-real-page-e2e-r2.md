# Real Page E2 R2 Implementation Plan

> **For agentic workers:** This plan is executed by the assigned subagent in the isolated worktree.

**Goal:** Register and implement auditable real-page E2 scenarios for pickup fulfillment, store statistics, and order-print job history.

**Architecture:** Extend the existing exact scenario registry and Playwright executor. Drive existing Vue pages and owned local APIs, then compare API/UI results with a SELECT-only SQL oracle. Keep Mini-specific acceptance checks limited to the local profile and clearly report environment-blocked portions.

**Tech Stack:** Python unittest/runner, TypeScript Playwright, Vue 3 pages, Java test-only owned E2 support, MySQL read-only SQL oracle, Mini Program automator.

**Spec:** `开发报告/2026-10-22-负责人审查意见与验收决定.md` (R2 coverage requested by task delegation).

## Global Constraints

- Use real pages and real owned local APIs; component mocks cannot substitute.
- Never call real payment or printer provider operations; use only the owned synthetic completion and fake printer controls.
- Keep oracle queries named, parameter-validated, and SELECT-only.
- Every registered check must be executed for a scenario to pass; unexecuted or unsupported boundaries stay NOT_RUN/BLOCKED.
- Do not change production printer service Java code.

## Review Focus

- Pickup transitions racing across two staff sessions: verify one version-checked transition and refresh the losing page.
- Mini pickup retry: replay the identical request key and verify one collect event and one points award.
- Statistics date bounds and store authorization: reconcile page results, details, and SQL under HQ and branch accounts.
- Store switching with out-of-order summary responses: ensure stale response does not replace the active store summary.
- Print task uncertainty and reprint: readonly refresh must not send; manual reprint must preserve reason/parent audit; foreign-store reads must be denied.

---

### Task 1: Registry and SQL oracle contracts

**Files:** `tests/quality/e2e/registry.py`, `tests/quality/e2e/oracle.py`, `tests/quality/test_e2e_acceptance.py`, `tests/quality/test_e2e_cli.py`, new oracle contract tests.

- [x] Register exact scenario IDs, profile variants, capabilities, and checks.
- [x] Test the precise manifest and SQL query allowlists/validation before implementing query generation.
- [x] Add narrow aggregate, detail, fulfillment, and print-job SELECT observations.

### Task 2: Real page and Mini executor flows

**Files:** `yshop-drink-vue3/e2e/core/core.spec.ts`, `yshop-drink-vue3/e2e/core/flow.ts`, `tests/mini/e2e-driver.cjs`, `tests/mini/e2e-guard.js`, `tests/quality/e2e/bridge.py`, `tests/quality/e2e/runner.py`, test-only `OwnedE2eApplication.java`, `tests/quality/business_http.py`.

- [x] Implement real page fulfillment and concurrent staff conflict; validate Mini states, collection replay, and reward SQL once where Mini is available.
- [x] Implement headquarters/branch statistics, date-window, SQL detail reconciliation, stale response, and branch-scope checks.
- [x] Implement print history, no-device/offline waits, UNKNOWN readonly refresh, reasoned manual reprint, and cross-store denial.

### Task 3: Documentation, execution, and commit

**Files:** `tests/quality/e2e/README.md` and changed exact manifest/tests.

- [x] Update the documented scenario count and coverage boundary.
- [x] Run focused Python contract tests and each new scenario under the CI E2 profile; preserve Mini as NOT_RUN where the CI profile excludes it.
- [ ] Review diff and commit the isolated branch, then rerun on the merged candidate source before reporting final acceptance.

Initial owned CI receipts on base source `dd3bd89c76c9b9c4379949cb6241fd7fba7d51c8`:

| Scenario | Run ID | Result | Scope note |
|---|---|---|---|
| `pickup-fulfillment` | `680e9bac94b4477392f7b93d35d4f051` | 10/10 checks PASS; cleanup PASS | Mini is NOT_RUN in CI. |
| `store-statistics` | `9876c4e026cf4a4a8d53fd66527617e6` | 10/10 checks PASS; cleanup PASS | Date assertion verifies UI inclusive day to API exclusive next-day bound and SQL predicate; no exact-midnight order fixture is inserted. |
| `order-print-jobs` | `a5bc7226daf64f77b85e05a1b4d13266` | 7/7 checks PASS; cleanup PASS | This receipt predates final candidate merge; recovery-resolution checks still need to run on the candidate source. |

Focused Python contracts: 109 tests passed. These initial receipts establish only the base-source page paths; final candidate acceptance must use the merge SHA and fresh run IDs.
