-- Apply before enabling Phase 5C. InnoDB and MySQL >= 8.0.29 are required.
-- No balance updates or opening data inserts in this schema migration.
CREATE TABLE IF NOT EXISTS yshop_member_wallet_transaction (
 id varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 uid bigint unsigned NOT NULL,
 direction varchar(8) NOT NULL,
 type varchar(24) NOT NULL,
 amount decimal(8,2) NOT NULL,
 balance_before decimal(8,2) NOT NULL,
 balance_after decimal(8,2) NOT NULL,
 business_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 idempotency_key varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 create_time datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_wallet_idempotency(idempotency_key),
 UNIQUE KEY uk_wallet_business(type,business_id),
 KEY idx_wallet_member(uid,create_time),
 CONSTRAINT chk_wallet_direction CHECK(direction IN ('CREDIT','DEBIT')),
 CONSTRAINT chk_wallet_type CHECK(type IN ('OPENING_BALANCE','ORDER_PAYMENT','RECHARGE','ADMIN_ADJUSTMENT','ORDER_REFUND')),
 CONSTRAINT chk_wallet_nonnegative CHECK(balance_before>=0 AND balance_after>=0 AND (amount>0 OR (type='OPENING_BALANCE' AND amount=0))),
 CONSTRAINT chk_wallet_equation CHECK((direction='CREDIT' AND balance_after=balance_before+amount) OR (direction='DEBIT' AND balance_after=balance_before-amount)),
 CONSTRAINT chk_wallet_opening CHECK(type<>'OPENING_BALANCE' OR (direction='CREDIT' AND balance_before=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS yshop_member_recharge_order (
 recharge_no varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 uid bigint unsigned NOT NULL,
 pay_amount decimal(8,2) NOT NULL,
 bonus_amount decimal(8,2) NOT NULL,
 credit_amount decimal(8,2) NOT NULL,
 status varchar(16) NOT NULL,
 provider varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 provider_transaction_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
 paid_at datetime(6) DEFAULT NULL,
 create_time datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 update_time datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(recharge_no), UNIQUE KEY uk_recharge_transaction(provider,provider_transaction_id),
 CONSTRAINT chk_recharge_amount CHECK(pay_amount>0 AND bonus_amount>=0 AND credit_amount=pay_amount+bonus_amount),
 CONSTRAINT chk_recharge_provider CHECK(provider='SYNTHETIC'),
 CONSTRAINT chk_recharge_status CHECK((status='CREATED' AND provider_transaction_id IS NULL AND paid_at IS NULL) OR (status='SUCCESS' AND provider_transaction_id IS NOT NULL AND paid_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Immutable ledger, including no-op UPDATE. MySQL 8.0.29+ supports trigger IF NOT EXISTS.
CREATE TRIGGER IF NOT EXISTS wallet_no_update BEFORE UPDATE ON yshop_member_wallet_transaction FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='IMMUTABLE_WALLET_LEDGER';
CREATE TRIGGER IF NOT EXISTS wallet_no_delete BEFORE DELETE ON yshop_member_wallet_transaction FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='IMMUTABLE_WALLET_LEDGER';
