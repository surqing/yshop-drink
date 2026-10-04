-- Additive and rerunnable; no live credentials or financial writes. Execute in one MySQL session.
SET @v3_ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='merchant_details' AND COLUMN_NAME='api_v3_key')=0, 'ALTER TABLE merchant_details ADD COLUMN api_v3_key MEDIUMTEXT NULL', 'SELECT 1');
PREPARE v3_migration FROM @v3_ddl; EXECUTE v3_migration; DEALLOCATE PREPARE v3_migration;
SET @v3_ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='merchant_details' AND COLUMN_NAME='wechat_api_version')=0, 'ALTER TABLE merchant_details ADD COLUMN wechat_api_version VARCHAR(8) NULL', 'SELECT 1');
PREPARE v3_migration FROM @v3_ddl; EXECUTE v3_migration; DEALLOCATE PREPARE v3_migration;
SET @v3_ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='merchant_details' AND COLUMN_NAME='merchant_certificate_serial')=0, 'ALTER TABLE merchant_details ADD COLUMN merchant_certificate_serial VARCHAR(64) NULL', 'SELECT 1');
PREPARE v3_migration FROM @v3_ddl; EXECUTE v3_migration; DEALLOCATE PREPARE v3_migration;
SET @v3_ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='merchant_details' AND COLUMN_NAME='platform_public_key_id')=0, 'ALTER TABLE merchant_details ADD COLUMN platform_public_key_id VARCHAR(64) NULL', 'SELECT 1');
PREPARE v3_migration FROM @v3_ddl; EXECUTE v3_migration; DEALLOCATE PREPARE v3_migration;
SET @v3_ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_payment_attempt' AND COLUMN_NAME='prepay_requested_at')=0, 'ALTER TABLE yshop_order_payment_attempt ADD COLUMN prepay_requested_at DATETIME(6) NULL', 'SELECT 1');
PREPARE v3_migration FROM @v3_ddl; EXECUTE v3_migration; DEALLOCATE PREPARE v3_migration;
DELIMITER $$
CREATE TRIGGER IF NOT EXISTS payment_attempt_v3_request_immutable BEFORE UPDATE ON yshop_order_payment_attempt
FOR EACH ROW BEGIN
  IF (OLD.prepay_requested_at IS NOT NULL AND NOT (OLD.prepay_requested_at <=> NEW.prepay_requested_at)) OR
     (NEW.prepay_requested_at IS NOT NULL AND NEW.status IN ('FAILED','EXPIRED','CANCELED')) OR
     (OLD.prepay_requested_at IS NULL AND NEW.prepay_requested_at IS NOT NULL AND OLD.status<>'CREATED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='IMMUTABLE_PREPAY_REQUEST';
  END IF;
END$$
DELIMITER ;
