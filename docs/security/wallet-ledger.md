# Wallet ledger and synthetic recharge baseline (Phase 5C)

This baseline preserves `yshop_user.now_money` as the only current balance. It uses synthetic financial fixtures for acceptance and does not establish real merchant/payment/refund readiness.

## Balance-write audit and routing

| Existing entry | Result |
| --- | --- |
| `AppStoreOrderServiceImpl.yuePay -> MemberUserService.decPrice -> MemberUserMapper` | Order lock, `WalletService.debit`, BALANCE finalization in one transaction |
| Admin `UserServiceImpl.updateMony -> setNowMoney/updateById` | `WalletService.credit/debit`, client idempotency key, exact compatibility bill, transaction rollback on error; insufficient debit is rejected rather than clamped |
| Ordinary admin create/update through `UserConvert` | Request balance is read-only; entity insert/update strategy NEVER; new accounts use database zero default; Vue ordinary form displays a read-only balance |
| Other generic member entity updates | Cannot overwrite a concurrently changed balance because the balance field is excluded from generated writes |
| Existing balance-refund `StoreOrderServiceImpl.orderRefund -> incMoney` | Only the existing balance-write call is routed to `WalletService.credit(ORDER_REFUND)` with a canonical refund key; no refund is invoked during acceptance; WeChat refund implementation is unchanged |
| Legacy `MemberUserService.incMoney/decPrice` | Fail closed with `WALLET_SERVICE_REQUIRED`; their raw member mapper SQL is removed |
| Old recharge UserBill reference | Remains unsupported by the external payment processor; no legacy credit path is revived |
| `StoreShopMapper.incMoney` | Store revenue, not member wallet balance; outside this change |
| UserBill / app `nowMoney` projection | Compatibility history/display, not a second balance or wallet mutation authority |

The only remaining production SQL assignment to member `now_money` is `WalletMapper.change`. Business callers do not call it directly. An operator with raw database permissions can still alter balances or drop/truncate tables; database privileges must be constrained operationally. Ordinary application updates are protected, and detected balance/ledger mismatch stops a subsequent wallet operation.

## Schema and immutable evidence

Apply `yshop-drink-boot3/sql/migrations/2026-10-03-wallet-ledger.sql` before starting this branch. It adds two InnoDB tables without updating existing money:

- `yshop_member_wallet_transaction`: UUID id, uid, CREDIT/DEBIT, type, exact decimal amount, before/after balance, business ID, idempotency key and creation timestamp. Unique idempotency key and unique `(type,business_id)` prevent both replay and a second key being used for the same operation. Case-sensitive ASCII references are bounded. CHECK constraints validate direction/type, nonnegative values, positive non-opening amounts, exact balance equation and opening origin.
- `yshop_member_recharge_order`: recharge number, uid, payable/bonus/credit amounts, state, provider/transaction, paid/create/update timestamps. Credit equals pay plus bonus; provider transaction is unique. This phase deliberately accepts only SYNTHETIC orders and CREATED/SUCCESS states with corresponding transaction/timestamp constraints.

Wallet types are OPENING_BALANCE, ORDER_PAYMENT, RECHARGE, ADMIN_ADJUSTMENT and ORDER_REFUND (only to close the existing balance-refund write bypass). There is no new wallet balance column/table, payment intent or payment attempt.

The ledger mapper exposes insert and read methods, no update/delete method. BEFORE UPDATE and BEFORE DELETE triggers reject mutation, including a no-op update. [MySQL CREATE TRIGGER documentation](https://dev.mysql.com/doc/refman/8.0/en/create-trigger.html) requires migration privileges and supports IF NOT EXISTS from 8.0.29, the minimum for this migration. DDL/TRUNCATE remain privileged operator actions; triggers are not claimed to prevent them.

## Atomicity and idempotency

WalletService validates exact CNY scale using BigDecimal with UNNECESSARY rounding, positive money and the existing DECIMAL(8,2) bound. Debit/credit take `SELECT ... FOR UPDATE` on the member primary key. They establish an opening entry if absent, check the ledger against current locked money, classify an existing idempotency key (uid, direction, type, amount and business must match), then conditionally update the same balance and insert its immutable transaction in one REQUIRED transaction. A UNIQUE violation for an alternative key or another user rolls the balance change back and becomes an idempotency conflict. No Redis lock, JVM lock, floating-point balance computation or automatic partial debit is used.

Ledger reads used during mutation are locking/current reads. This matters under MySQL REPEATABLE READ: a caller may already have read its order before waiting for another user's balance operation; a stale consistent-read ledger must not be compared against the current locked balance. Diagnostic aggregate `sum()` is used for acceptance/reconciliation, not as the mutation read gate.

`yuePay` retains an explicit TransactionTemplate for its existing self-invocation path. It locks/revalidates the canonical order and ownership before member locking, debits with `order:<canonical-order>`, and calls BALANCE finalization. Finalization requires the matching ORDER_PAYMENT debit. Its BALANCE receipt joins the same transaction/connection rather than opening a nested REQUIRES_NEW connection. Debit, ledger, receipt, paid transition, payCount, status and bill commit or roll back together. This also avoids requiring an extra connection after concurrent outer requests fill the pool. External verified callback receipts remain independently durable and unchanged; cash compatibility is unchanged.

An already paid order remains a business duplicate rejection at the existing adapter; it never debits again. Failed balance finalization cannot be recovered as an external event without a debit.

The dedicated admin monetary dialog generates a key once per opened operation and preserves it during retry. The server requires a bounded key; ordinary member editing is read-only for balance. A monetary adjustment must be positive. Points arithmetic/concurrency remains the existing implementation and is not declared hardened by this wallet change.

## Opening existing balances

`WalletOpeningMigration.run()` checks the global negative count before any insert. A negative count aborts with count only. It then calls individually transactional `WalletService.open(uid)` for each existing member, including historical deleted accounts. Each user row is locked; `opening:<uid>` records current money as CREDIT from zero. Zero opening balances are supported. Existing opening entries are never rewritten; rerunning after later debits leaves the original opening unchanged. The migration is resumable and has no balance update path.

`yshop.wallet.opening-migration-enabled` is default OFF. An operator may enable it for one startup after schema migration, then turn it off; no perpetual opening job is installed. New zero-balance accounts receive their opening on the first wallet operation. Synthetic recharge is also default OFF.

For this local environment: total users=3, nonzero users=1, negative users=0; opening entries=3. A private before/after fingerprint verified all original balances unchanged; every member reconciles with zero mismatches. The one-time property was returned to false. No non-opening local wallet movement or local recharge completion is produced by tests.

## Synthetic recharge boundary

RechargeService has no HTTP controller, provider callback or prepay route. `yshop.wallet.synthetic-recharge-enabled=false` is the production/default state. Test configuration explicitly enables it. It creates a distinct recharge order with server-stored amounts; synthetic verified completion locks the order, validates paid amount/provider/transaction, credits the server-stored pay+bonus amount using `recharge:<id>`, and changes the order to SUCCESS in the same transaction. Duplicate same transaction returns without credit; changed transaction/amount or a transaction reused for another recharge fails and rolls back. Legacy UserBill is not used for recharge settlement.

Real recharge integration requires a future authenticated verified provider boundary, reconciliation, operational tooling and review. Turning on the synthetic flag is not real-provider verification.

## Reproducible acceptance

The default `PaymentDatabaseTest` factory uses isolated H2, actual production member/order/payment/wallet/recharge mappers, production transaction services/effects and field filling. `WalletDatabaseTest` adds 31 checks. Production transport/provider calls are mocked; database monetary mutations are real only in disposable synthetic fixtures.

From the repository root with the established Git-ignored local Docker/toolchain environment:

```sh
python3 tests/payment/mysql-acceptance.py --wallet
```

The runner creates a random independent schema and a temporary account granted only that schema. Current MySQL 8.0.46 / InnoDB acceptance uses the production entity DDL and both migrations. The privileged local migration helper installs only the two immutable triggers in the schema validated from that run's private JDBC file, because binlog-enabled MySQL needs elevated migration privileges. The test account is not given global SUPER privileges; binlog/trust/security settings are not changed. The disposable JDBC file is mode 600, credentials are never printed, and the database/account/config are removed at exit. All ten tables must be InnoDB. The original Phase 5B mode remains available without `--wallet`.

Acceptance: real MySQL 86 PASS (55 payment + 31 wallet/recharge); full focused H2/SDK/credential/logging 152 PASS; full backend 55 modules SUCCESS. Wallet cases include same-order balance ×20, three-order five-cent overspend, insufficient balance with no ledger/paid, repeated idempotency, conflicts in every monetary identity field, alternate key for the same business, credit ×20, post-debit/ledger/finalization rollback, BALANCE finalization without debit rejected, recharge ×20 and transaction reuse/amount conflict/status rollback, opening rerun/zero/negative preflight, ordinary member write protection and ledger reconciliation/immutability.

## Remaining scope

Real payment/prepay/recharge/refund, verified live recharge callbacks, payment v3, intents/attempts, CI, merchant/master-key rotation, promotional recharge packages, points concurrency, production reconciliation UI/operational policy and durable notification delivery are not claimed complete. Apply schema and openings with older balance-writing application processes quiesced. Monetary balance is bounded by the existing DECIMAL(8,2), and larger balances require a separately reviewed schema change.

## Financial safety statement

Real payment merchant credentials were not used.
Real payment was not invoked; no /order/pay request was made.
No WeChat prepay request was made.
No wx.requestPayment call was made.
No real recharge was completed.
No real refund was invoked.
No funds were transferred. Financial acceptance uses synthetic disposable data only.
