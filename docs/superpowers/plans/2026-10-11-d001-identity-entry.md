# Approved D001 / D003 / D002 staged implementation

> Use superpowers:executing-plans, test-driven-development, systematic-debugging and requesting-code-review. Only Stage 1 is authorized in this execution; stop for human confirmation before D003.

Goal: preserve cdae16c / 31cd487 while closing proven remaining identity risks, then separately address score stock and quantity.
Architecture: existing yshop_user identity columns, provider verification, dedicated writes and MySQL uniqueness; no new identity table or unified identity redesign.
Spec: user-approved plan in this conversation (2026-10-11), including permission to change obsolete interfaces and necessary schema. No legacy users is a hypothesis; observed conflicts or assets must never be deleted automatically.

## Global constraints
- Independent worktree fix/d001-identity-entry from 31cd487ce73eb7c8d86731a1cfb61f446a55696f; PR12-16 refs/status untouched.
- Preserve original D001-D009 business assertions; report existing failures without weakening/skipping.
- MySQL/Redis are disposable owned fixtures; no real provider, financial, deployment, merge, quality-gate enablement or Phase6D operation.
- Every unproven race requires controlled RED before repairing it. Existing safe operations need positive controls.
- Source SHA/digest/runId and exact cases bind fresh evidence. Compile/setup failures are INCONCLUSIVE, never RED or mutant kills.
- One reviewed final commit and draft stacked PR for Stage1; user confirmation before Stage2.

## Review focus
- Token principal must follow verified phone/provider identity, not request openid.
- Unique loser retries only after full rollback and rechecks both identity owners.
- Generic entity writes cannot restore mobile/openid; wallet protocol remains intact.
- SMS one-time consumption, identity mutation, token SQL/cache obey transaction commit boundaries.
- Confirm-receipt order/status/reward/bill must roll back together if RED establishes partial commit or duplication.

### Task 1: Baseline and controlled RED fixtures
- [ ] Verify exact baseline, clean worktree, PR16 final CI (four SUCCESS).
- [ ] Run unchanged QUICK/JAVA. Preserve sandbox failures separately; rerun with local socket/Mockito permissions without changing tests.
- [ ] Add server test-scoped JUnit/MySQL dependency/profile and MemberIdentityMysqlAcceptanceTest plus SecurityMysqlTestSupport.
- [ ] Reuse real production services, MyBatis, Spring transaction proxies, canonical DDL only (no seed INSERT), owned MySQL/Redis and existing report receipts.
- [ ] Prove SMS A+openid B wrong principal, old-phone mismatch, new-phone occupied, stale full write, concurrent registrations/bindings, real SMS dual consumption, generic bind transfer and confirm-receipt partial/duplicate rewards.
- [ ] Use test-only barriers around real reads/writes. No production hooks. Allow Green row-lock serialization without harness deadlock.
- [ ] Record expected assertion failures and positive fixtures; runtime/config failures are not RED.

### Task 2: Identity GREEN and migration
- [ ] Normalize validated +86/0086 phone to 11 digits throughout member send/use/lookup; canonical empty identity is NULL.
- [ ] Add three named full unique indexes on mobile/openid/routine_openid, retaining deleted-member occupancy. Openid legal ASCII, case-sensitive storage; reject whitespace/invalid/overlong provider values.
- [ ] Precheck final normalized collisions, malformed nonempty values, tenant/AppID deviations and generic MEMBER social remnants; abort before mutation; no user/asset merging or deletion.
- [ ] Generic identity fields updateStrategy NEVER; dedicated mapper CAS and exactly-one checks; internal random unique username.
- [ ] SMS/password login cannot bind or select by client openid. Modern mini uses fresh phoneCode+loginCode from same SDK namespace; auth-session fresh SDK only; remove encrypted-data route and sessionKey cache write.
- [ ] Existing phone/openid owners must agree. Proven single-sided provider member may gain unused phone at same UID; two existing UIDs conflict, never merge.
- [ ] Replace MEMBER generic social bind/unbind with token UID+current-phone new SMS (scene5/6)+fresh provider proof. Same-binding idempotent; no foreign transfers; unbind verifies exact current openid and explicit NULL CAS. ADMIN social remains unchanged.
- [ ] Bind/unbind sending requires MEMBER auth and server-selected current phone. No cross-scene consumption. Configuring real SMS templates/providers stays outside execution; test templates/providers are synthetic.
- [ ] Remove unused saveWechatMember API; remove username=openid overwrite.
- [ ] Provider calls outside single TransactionTemplate REQUIRES_NEW RC attempt; remove outer login @Transactional. Identity+SMS+token+success log/login metadata in that attempt; cache only after commit. Target unique-conflict rollback then max one new transaction retry with retained trusted proof, not reusing committed SMS or calling one-time provider code again.
- [ ] Change phone validates stored old phone, both proofs, new ownership under row lock; bind/unbind/phone-change revoke member token families in same transaction. Preserve principal->family lock order.
- [ ] Adapt UniApp to existing modern endpoint, e.detail.code + fresh uni.login code. Do not automatically replay failed phone grants.

### Task 3: Confirmed business-writer GREEN
- [ ] Background ordinary update ignores identity and uses adjustment endpoint for assets; creation uses canonical identity and uniqueness.
- [ ] Background money/points adjustment loads member after row lock, only writes points field; retains wallet idempotency/transaction rules.
- [ ] Proven receipt race: lock owned current store order then member, revalidate eligibility, status CAS exactly1 before reward. Outer transaction includes order/status log/points/bill. No unowned order access.
- [ ] Reward points sparse/atomic; exact income with actual after balance. Exact bill insert must be1 or rollback; run wallet/payment regressions for stronger shared contract.
- [ ] No edits to dead regressionIntegral unless RED establishes a caller. No D003/D002 production repairs in this phase.

### Task 4: Refactor, verification, review and draft publication
- [ ] Refactor only proven duplication, obsolete routes and imports; preserve original RED business assertions.
- [ ] Register exact new suites in manifest/conditional dispatch, owned integration runner and CI; no environment-based skip.
- [ ] Run targeted MySQL/Redis; 20x20 identity concurrency; two-instance permission/asset invariants; real SQL faults and zero-return contract faults.
- [ ] Run BUILD JAVA QUICK FRONTEND INTEGRATION CROSS-END BROWSER MUTATION PERFORMANCE and affected UniApp build/page regressions. HTTP RED repeat2 and D009 browser preserve aggregate failures.
- [ ] Current D001/D004 and new identity targets PASS; D002/D003/D005-D009 remain actual status. Provider end-to-end NOT_RUN clearly stated.
- [ ] Performance: two JVMs, normal validation/refresh/revoke, zero unexpected errors/deadlocks; existing P99<15s smoke budget plus measured throughput/P95/P99/lock waits (not production capacity).
- [ ] Fresh independent whole-branch review. Resolve Critical/Important with RED-GREEN; then freeze source, validate certificate provenance, commit Stage1 only, push new branch, create/attach draft PR against fix/d001-d004-minimal, check final CI.
- [ ] Deliver evidence, review, schema/rollback boundaries and remaining failures. STOP for human Stage2 authorization.

## Later stages (not authorized now)
Stage2 D003: submit transaction member->score product; stock/sales conditional update exactly1, conditional points debit exactly1, order save/bill insertion checked; delete fail-open global Redis lock. Owned address before side effects. Positive integer quantity mandatory. Snapshot rollback of order/points/stock/sales/bill, last-item two-JVM races/faults. D002 remains explicit known undercharge; do not encode it as desired test expectation. D006/D007 may become green as necessary adjacent fixes, reported truthfully. Independent fix/d003-score-stock from human-accepted Stage1 SHA, draft against Stage1, review/evidence/CI and human gate.
Stage3 D002: one checked quantity parse and checked totalCost=unitScore*quantity; positive price, integer quantity and actual DECIMAL(8,2) capacity (integer cost<=999999). BigDecimal, no double/intValue balance truncation. Order unit/qty/total, debit and exact bill all aligned. Test 2*100 from1000=>800 with total200 stock8 sales+2 bill200/balance800; invalid quantities, overflow, exact/insufficient/fractional funds, same-member races and price snapshot, all Stage2 rollback tests. Independent fix/d002-score-quantity from accepted Stage2 SHA and draft against Stage2; stop after acceptance, gate/Phase6D remain closed.

## Rollback
No destructive/shared/production migration is executed. Test DDL forward/reverse and snapshot restoration separately (DDL implicit commits). Never restore unsafe identity entrypoints to active service. Stage2/3 reversal retains prior fixes but disables affected exchange rather than re-exposing stock/quantity bugs. No postcommit automatic point/order repair. Clean owned resources only; retain private RED/failure evidence.
