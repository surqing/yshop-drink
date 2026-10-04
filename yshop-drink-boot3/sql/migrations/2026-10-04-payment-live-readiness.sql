-- Apply after Phase 5D/5E migrations, in one maintenance-session before deploying workers.
-- Additive metadata; replacing prior guards permits only evidence-backed remote termination.
SET @recovery_ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_payment_attempt' AND COLUMN_NAME='reconciliation_token')=0,'ALTER TABLE yshop_order_payment_attempt ADD COLUMN reconciliation_token VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL','SELECT 1');
PREPARE recovery_migration FROM @recovery_ddl; EXECUTE recovery_migration; DEALLOCATE PREPARE recovery_migration;
SET @recovery_ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_payment_attempt' AND COLUMN_NAME='reconciliation_lease_until')=0,'ALTER TABLE yshop_order_payment_attempt ADD COLUMN reconciliation_lease_until DATETIME(6) NULL','SELECT 1');
PREPARE recovery_migration FROM @recovery_ddl; EXECUTE recovery_migration; DEALLOCATE PREPARE recovery_migration;
SET @recovery_ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_payment_attempt' AND COLUMN_NAME='remote_terminal_state')=0,'ALTER TABLE yshop_order_payment_attempt ADD COLUMN remote_terminal_state VARCHAR(16) NULL','SELECT 1');
PREPARE recovery_migration FROM @recovery_ddl; EXECUTE recovery_migration; DEALLOCATE PREPARE recovery_migration;
SET @recovery_ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_payment_attempt' AND COLUMN_NAME='remote_confirmed_at')=0,'ALTER TABLE yshop_order_payment_attempt ADD COLUMN remote_confirmed_at DATETIME(6) NULL','SELECT 1');
PREPARE recovery_migration FROM @recovery_ddl; EXECUTE recovery_migration; DEALLOCATE PREPARE recovery_migration;
SET @recovery_ddl=IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_payment_attempt' AND CONSTRAINT_NAME='chk_attempt_terminal')>0,'ALTER TABLE yshop_order_payment_attempt DROP CHECK chk_attempt_terminal','SELECT 1');
PREPARE recovery_migration FROM @recovery_ddl; EXECUTE recovery_migration; DEALLOCATE PREPARE recovery_migration;
SET @recovery_ddl=IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_payment_attempt' AND CONSTRAINT_NAME='chk_attempt_remote_terminal')=0,'ALTER TABLE yshop_order_payment_attempt ADD CONSTRAINT chk_attempt_remote_terminal CHECK ((status NOT IN (''FAILED'',''EXPIRED'',''CANCELED'') OR prepay_reference IS NULL OR remote_confirmed_at IS NOT NULL) AND ((remote_terminal_state IS NULL AND remote_confirmed_at IS NULL) OR (provider=''WECHAT'' AND remote_confirmed_at IS NOT NULL AND ((status=''CANCELED'' AND remote_terminal_state IN (''CLOSED'',''REVOKED'')) OR (status=''FAILED'' AND remote_terminal_state=''PAYERROR'')))))','SELECT 1');
PREPARE recovery_migration FROM @recovery_ddl; EXECUTE recovery_migration; DEALLOCATE PREPARE recovery_migration;
DROP TRIGGER IF EXISTS payment_attempt_immutable;
DROP TRIGGER IF EXISTS payment_attempt_v3_request_immutable;
DELIMITER $$
CREATE TRIGGER payment_attempt_immutable BEFORE UPDATE ON yshop_order_payment_attempt
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
     (OLD.remote_terminal_state IS NOT NULL AND NOT (OLD.remote_terminal_state <=> NEW.remote_terminal_state)) OR
     (OLD.remote_confirmed_at IS NOT NULL AND NOT (OLD.remote_confirmed_at <=> NEW.remote_confirmed_at)) OR
     (OLD.remote_terminal_state IS NULL AND NEW.remote_terminal_state IS NOT NULL AND
       (OLD.provider<>'WECHAT' OR OLD.status NOT IN ('CREATED','PREPAY_CREATED') OR NEW.status NOT IN ('FAILED','CANCELED'))) OR
     (OLD.status <> NEW.status AND NOT (
       (OLD.status='CREATED' AND NEW.status IN ('PREPAY_CREATED','PAID','FAILED','EXPIRED','CANCELED')) OR
       (OLD.status='PREPAY_CREATED' AND (NEW.status='PAID' OR (NEW.status IN ('FAILED','CANCELED') AND NEW.remote_terminal_state IS NOT NULL AND NEW.remote_confirmed_at IS NOT NULL))))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='IMMUTABLE_PAYMENT_ATTEMPT';
  END IF;
END$$
CREATE TRIGGER payment_attempt_v3_request_immutable BEFORE UPDATE ON yshop_order_payment_attempt
FOR EACH ROW BEGIN
  IF (OLD.prepay_requested_at IS NOT NULL AND NOT (OLD.prepay_requested_at <=> NEW.prepay_requested_at)) OR
     (NEW.prepay_requested_at IS NOT NULL AND NEW.status IN ('FAILED','EXPIRED','CANCELED') AND NEW.remote_confirmed_at IS NULL) OR
     (OLD.prepay_requested_at IS NULL AND NEW.prepay_requested_at IS NOT NULL AND OLD.status<>'CREATED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='IMMUTABLE_PREPAY_REQUEST';
  END IF;
END$$
DELIMITER ;
