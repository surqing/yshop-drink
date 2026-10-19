-- Fail closed before any persistent table/data mutation. Apply only to a fully
-- legacy schema or this complete migration; investigate partial schemas manually.
DROP PROCEDURE IF EXISTS quality_check_coupon_schema;
DELIMITER $$
CREATE PROCEDURE quality_check_coupon_schema()
BEGIN
  DECLARE present_parts INT DEFAULT 0;
  DECLARE matched_parts INT DEFAULT 0;
  IF (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
      AND TABLE_NAME IN ('yshop_coupon','yshop_coupon_user') AND ENGINE='InnoDB')<>2 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='COUPON_SCHEMA_PARTIAL_OR_MISMATCH';
  END IF;
  SELECT
    (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND
      ((TABLE_NAME='yshop_coupon' AND COLUMN_NAME IN ('template_version','claim_start_time','claim_end_time','coupon_kind','claim_mode','redemption_code_hash')) OR
       (TABLE_NAME='yshop_coupon_user' AND COLUMN_NAME IN ('template_version','redeemed_order_id','redeemed_at','invalid_reason')))) +
    (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('yshop_coupon_claim','yshop_coupon_newcomer','yshop_coupon_operation')) +
    (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND
      ((TABLE_NAME='yshop_coupon' AND INDEX_NAME='uk_coupon_code_hash') OR
       (TABLE_NAME='yshop_coupon_user' AND INDEX_NAME IN ('idx_coupon_claim_limit','idx_coupon_redemption'))))
    INTO present_parts;
  IF present_parts NOT IN (0,17) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='COUPON_SCHEMA_PARTIAL_OR_MISMATCH';
  END IF;
  IF present_parts=17 THEN
    SELECT COUNT(*) INTO matched_parts FROM (
      SELECT 'yshop_coupon' table_name,'template_version' column_name,'bigint' column_type,'NO' nullable,'0' default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon' table_name,'claim_start_time' column_name,'datetime' column_type,'YES' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon' table_name,'claim_end_time' column_name,'datetime' column_type,'YES' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon' table_name,'coupon_kind' column_name,'varchar(16)' column_type,'NO' nullable,'REGULAR' default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon' table_name,'claim_mode' column_name,'varchar(16)' column_type,'NO' nullable,'PUBLIC' default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon' table_name,'redemption_code_hash' column_name,'char(64)' column_type,'YES' nullable,NULL default_value,'ascii_bin' collation_name
      UNION ALL SELECT 'yshop_coupon_user' table_name,'template_version' column_name,'bigint' column_type,'NO' nullable,'0' default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_user' table_name,'redeemed_order_id' column_name,'varchar(32)' column_type,'YES' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_user' table_name,'redeemed_at' column_name,'datetime' column_type,'YES' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_user' table_name,'invalid_reason' column_name,'varchar(200)' column_type,'YES' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_claim' table_name,'user_id' column_name,'bigint' column_type,'NO' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_claim' table_name,'request_key' column_name,'varchar(64)' column_type,'NO' nullable,NULL default_value,'ascii_bin' collation_name
      UNION ALL SELECT 'yshop_coupon_claim' table_name,'coupon_id' column_name,'bigint' column_type,'NO' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_claim' table_name,'coupon_user_id' column_name,'bigint' column_type,'NO' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_claim' table_name,'claim_mode' column_name,'varchar(16)' column_type,'NO' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_claim' table_name,'request_hash' column_name,'char(64)' column_type,'NO' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_claim' table_name,'create_time' column_name,'datetime' column_type,'NO' nullable,'CURRENT_TIMESTAMP' default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_newcomer' table_name,'user_id' column_name,'bigint' column_type,'NO' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_newcomer' table_name,'coupon_id' column_name,'bigint' column_type,'NO' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_newcomer' table_name,'coupon_user_id' column_name,'bigint' column_type,'NO' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_newcomer' table_name,'registered_at' column_name,'datetime' column_type,'NO' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_newcomer' table_name,'create_time' column_name,'datetime' column_type,'NO' nullable,'CURRENT_TIMESTAMP' default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_operation' table_name,'event_key' column_name,'varchar(96)' column_type,'NO' nullable,NULL default_value,'ascii_bin' collation_name
      UNION ALL SELECT 'yshop_coupon_operation' table_name,'coupon_id' column_name,'bigint' column_type,'NO' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_operation' table_name,'coupon_user_id' column_name,'bigint' column_type,'YES' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_operation' table_name,'order_id' column_name,'varchar(32)' column_type,'YES' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_operation' table_name,'actor_id' column_name,'bigint' column_type,'YES' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_operation' table_name,'actor_type' column_name,'varchar(16)' column_type,'NO' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_operation' table_name,'kind' column_name,'varchar(24)' column_type,'NO' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_operation' table_name,'reason' column_name,'varchar(200)' column_type,'NO' nullable,NULL default_value,NULL collation_name
      UNION ALL SELECT 'yshop_coupon_operation' table_name,'create_time' column_name,'datetime' column_type,'NO' nullable,'CURRENT_TIMESTAMP' default_value,NULL collation_name
    ) expected JOIN information_schema.COLUMNS actual
      ON actual.TABLE_SCHEMA=DATABASE() AND actual.TABLE_NAME=expected.table_name AND actual.COLUMN_NAME=expected.column_name
      AND actual.COLUMN_TYPE=expected.column_type AND actual.IS_NULLABLE=expected.nullable
      AND (CASE WHEN expected.default_value='CURRENT_TIMESTAMP' THEN UPPER(REPLACE(actual.COLUMN_DEFAULT,'()',''))='CURRENT_TIMESTAMP'
           ELSE BINARY actual.COLUMN_DEFAULT <=> BINARY expected.default_value END)
      AND (expected.collation_name IS NULL OR actual.COLLATION_NAME=expected.collation_name)
      AND actual.EXTRA IN ('','DEFAULT_GENERATED');
    IF matched_parts<>31 OR
       (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('yshop_coupon_claim','yshop_coupon_newcomer','yshop_coupon_operation'))<>21 OR
       (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('yshop_coupon_claim','yshop_coupon_newcomer','yshop_coupon_operation') AND ENGINE='InnoDB')<>3 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='COUPON_SCHEMA_PARTIAL_OR_MISMATCH';
    END IF;
    SELECT COUNT(*) INTO matched_parts FROM (
      SELECT 'yshop_coupon' table_name,'uk_coupon_code_hash' index_name,0 non_unique,1 seq_in_index,'redemption_code_hash' column_name
      UNION ALL SELECT 'yshop_coupon_user' table_name,'idx_coupon_claim_limit' index_name,1 non_unique,1 seq_in_index,'coupon_id' column_name
      UNION ALL SELECT 'yshop_coupon_user' table_name,'idx_coupon_claim_limit' index_name,1 non_unique,2 seq_in_index,'user_id' column_name
      UNION ALL SELECT 'yshop_coupon_user' table_name,'idx_coupon_redemption' index_name,1 non_unique,1 seq_in_index,'redeemed_order_id' column_name
      UNION ALL SELECT 'yshop_coupon_claim' table_name,'PRIMARY' index_name,0 non_unique,1 seq_in_index,'user_id' column_name
      UNION ALL SELECT 'yshop_coupon_claim' table_name,'PRIMARY' index_name,0 non_unique,2 seq_in_index,'request_key' column_name
      UNION ALL SELECT 'yshop_coupon_claim' table_name,'uk_claim_instance' index_name,0 non_unique,1 seq_in_index,'coupon_user_id' column_name
      UNION ALL SELECT 'yshop_coupon_newcomer' table_name,'PRIMARY' index_name,0 non_unique,1 seq_in_index,'user_id' column_name
      UNION ALL SELECT 'yshop_coupon_operation' table_name,'PRIMARY' index_name,0 non_unique,1 seq_in_index,'event_key' column_name
      UNION ALL SELECT 'yshop_coupon_operation' table_name,'idx_coupon_operation' index_name,1 non_unique,1 seq_in_index,'coupon_id' column_name
      UNION ALL SELECT 'yshop_coupon_operation' table_name,'idx_coupon_operation' index_name,1 non_unique,2 seq_in_index,'create_time' column_name
    ) expected JOIN information_schema.STATISTICS actual
      ON actual.TABLE_SCHEMA=DATABASE() AND actual.TABLE_NAME=expected.table_name AND actual.INDEX_NAME=expected.index_name
      AND actual.NON_UNIQUE=expected.non_unique AND actual.SEQ_IN_INDEX=expected.seq_in_index
      AND actual.COLUMN_NAME=expected.column_name AND actual.SUB_PART IS NULL AND actual.INDEX_TYPE='BTREE';
    IF matched_parts<>11 OR
       (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('yshop_coupon_claim','yshop_coupon_newcomer','yshop_coupon_operation'))<>7 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='COUPON_SCHEMA_PARTIAL_OR_MISMATCH';
    END IF;
  END IF;
END$$
DELIMITER ;
CALL quality_check_coupon_schema();
DROP PROCEDURE quality_check_coupon_schema;

-- Additive coupon rights and operational evidence only. No historical status or money rewrite.
SET @coupon_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_coupon' AND COLUMN_NAME='template_version')=0,
 'ALTER TABLE yshop_coupon ADD COLUMN template_version BIGINT NOT NULL DEFAULT 0, ADD COLUMN claim_start_time DATETIME NULL, ADD COLUMN claim_end_time DATETIME NULL, ADD COLUMN coupon_kind VARCHAR(16) NOT NULL DEFAULT ''REGULAR'', ADD COLUMN claim_mode VARCHAR(16) NOT NULL DEFAULT ''PUBLIC'', ADD COLUMN redemption_code_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD UNIQUE KEY uk_coupon_code_hash(redemption_code_hash)', 'SELECT 1');
PREPARE coupon_stmt FROM @coupon_ddl;
EXECUTE coupon_stmt;
DEALLOCATE PREPARE coupon_stmt;
SET @coupon_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_coupon_user' AND COLUMN_NAME='template_version')=0,
 'ALTER TABLE yshop_coupon_user ADD COLUMN template_version BIGINT NOT NULL DEFAULT 0, ADD COLUMN redeemed_order_id VARCHAR(32) NULL, ADD COLUMN redeemed_at DATETIME NULL, ADD COLUMN invalid_reason VARCHAR(200) NULL, ADD KEY idx_coupon_claim_limit(coupon_id,user_id), ADD KEY idx_coupon_redemption(redeemed_order_id)', 'SELECT 1');
PREPARE coupon_stmt FROM @coupon_ddl;
EXECUTE coupon_stmt;
DEALLOCATE PREPARE coupon_stmt;

CREATE TABLE IF NOT EXISTS yshop_coupon_claim (
 user_id BIGINT NOT NULL, request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 coupon_id BIGINT NOT NULL, coupon_user_id BIGINT NOT NULL, claim_mode VARCHAR(16) NOT NULL, request_hash CHAR(64) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(user_id,request_key), UNIQUE KEY uk_claim_instance(coupon_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS yshop_coupon_newcomer (
 user_id BIGINT NOT NULL PRIMARY KEY, coupon_id BIGINT NOT NULL, coupon_user_id BIGINT NOT NULL,
 registered_at DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Coupon operation evidence is not a financial ledger. No raw code or personal identity material.
CREATE TABLE IF NOT EXISTS yshop_coupon_operation (
 event_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 coupon_id BIGINT NOT NULL, coupon_user_id BIGINT NULL, order_id VARCHAR(32) NULL,
 actor_id BIGINT NULL, actor_type VARCHAR(16) NOT NULL, kind VARCHAR(24) NOT NULL,
 reason VARCHAR(200) NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_coupon_operation(coupon_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
