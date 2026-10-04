-- Additive, rerunnable. Run with operator DDL privileges before deploying the new mapper.
-- Does not rewrite payment records or create attempts for historical orders.
CREATE TABLE IF NOT EXISTS yshop_order_payment_attempt (
  attempt_id varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  uid bigint NOT NULL,
  idempotency_key varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  merchant_details_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount_cents bigint NOT NULL,
  currency varchar(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
  appid varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  merchant_identity varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider_order_reference varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  prepay_reference varchar(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  provider_transaction_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  status varchar(16) NOT NULL,
  payment_event_id varchar(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  paid_at datetime(6) DEFAULT NULL,
  create_time datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  update_time datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  active_order_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS
    (CASE WHEN status IN ('CREATED','PREPAY_CREATED') THEN order_id ELSE NULL END) STORED,
  PRIMARY KEY(attempt_id),
  UNIQUE KEY uk_attempt_key(order_id,idempotency_key),
  UNIQUE KEY uk_attempt_active(active_order_id),
  UNIQUE KEY uk_attempt_order_ref(provider_order_reference),
  UNIQUE KEY uk_attempt_prepay(provider,prepay_reference),
  UNIQUE KEY uk_attempt_transaction(provider,provider_transaction_id),
  UNIQUE KEY uk_attempt_event(payment_event_id),
  KEY idx_attempt_history(order_id,create_time),
  CONSTRAINT chk_attempt_identity CHECK (CHAR_LENGTH(attempt_id)=32 AND provider IN ('WECHAT','ALIPAY') AND amount_cents>0 AND currency='CNY' AND provider_order_reference=attempt_id),
  CONSTRAINT chk_attempt_state CHECK (status IN ('CREATED','PREPAY_CREATED','PAID','FAILED','EXPIRED','CANCELED')),
  CONSTRAINT chk_attempt_paid CHECK ((status='PAID' AND provider_transaction_id IS NOT NULL AND payment_event_id IS NOT NULL AND paid_at IS NOT NULL) OR
      (status<>'PAID' AND provider_transaction_id IS NULL AND payment_event_id IS NULL AND paid_at IS NULL)),
  CONSTRAINT chk_attempt_prepay CHECK ((status<>'CREATED' OR prepay_reference IS NULL) AND (status<>'PREPAY_CREATED' OR prepay_reference IS NOT NULL)),
  CONSTRAINT chk_attempt_terminal CHECK (status NOT IN ('FAILED','EXPIRED','CANCELED') OR prepay_reference IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- MySQL 8 does not support ADD COLUMN IF NOT EXISTS; check metadata in the same session.
SET @attempt_column_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_payment' AND COLUMN_NAME='attempt_id')=0,
  'ALTER TABLE yshop_order_payment ADD COLUMN attempt_id varchar(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL', 'SELECT 1');
PREPARE attempt_migration FROM @attempt_column_ddl;
EXECUTE attempt_migration;
DEALLOCATE PREPARE attempt_migration;
SET @attempt_index_ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_payment' AND INDEX_NAME='idx_payment_attempt')=0,
  'ALTER TABLE yshop_order_payment ADD INDEX idx_payment_attempt(attempt_id)', 'SELECT 1');
PREPARE attempt_migration FROM @attempt_index_ddl;
EXECUTE attempt_migration;
DEALLOCATE PREPARE attempt_migration;

DELIMITER $$
CREATE TRIGGER IF NOT EXISTS payment_attempt_immutable BEFORE UPDATE ON yshop_order_payment_attempt
FOR EACH ROW BEGIN
  IF NOT (OLD.attempt_id <=> NEW.attempt_id) OR NOT (OLD.order_id <=> NEW.order_id) OR
     NOT (OLD.uid <=> NEW.uid) OR NOT (OLD.idempotency_key <=> NEW.idempotency_key) OR
     NOT (OLD.provider <=> NEW.provider) OR NOT (OLD.merchant_details_id <=> NEW.merchant_details_id) OR
     NOT (OLD.amount_cents <=> NEW.amount_cents) OR NOT (OLD.currency <=> NEW.currency) OR
     NOT (OLD.appid <=> NEW.appid) OR NOT (OLD.merchant_identity <=> NEW.merchant_identity) OR
     NOT (OLD.provider_order_reference <=> NEW.provider_order_reference) OR NOT (OLD.create_time <=> NEW.create_time) OR
     (OLD.prepay_reference IS NOT NULL AND NOT (OLD.prepay_reference <=> NEW.prepay_reference)) OR
     (OLD.provider_transaction_id IS NOT NULL AND NOT (OLD.provider_transaction_id <=> NEW.provider_transaction_id)) OR
     (OLD.payment_event_id IS NOT NULL AND NOT (OLD.payment_event_id <=> NEW.payment_event_id)) OR
     (OLD.paid_at IS NOT NULL AND NOT (OLD.paid_at <=> NEW.paid_at)) OR
     (OLD.status <> NEW.status AND NOT (
       (OLD.status='CREATED' AND NEW.status IN ('PREPAY_CREATED','PAID','FAILED','EXPIRED','CANCELED')) OR
       (OLD.status='PREPAY_CREATED' AND NEW.status='PAID'))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='IMMUTABLE_PAYMENT_ATTEMPT';
  END IF;
END$$
CREATE TRIGGER IF NOT EXISTS payment_attempt_no_delete BEFORE DELETE ON yshop_order_payment_attempt
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='IMMUTABLE_PAYMENT_ATTEMPT'$$
DELIMITER ;
