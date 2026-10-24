-- Additive crash recovery for provider operations. A caller must confirm that
-- the original sender stopped and provider work settled; no age-based release.
-- MYSQL_PREFLIGHT_BEGIN
DROP PROCEDURE IF EXISTS quality_prepare_printer_operation_recovery;
DELIMITER $$
CREATE PROCEDURE quality_prepare_printer_operation_recovery()
BEGIN
  DECLARE operation_columns INT DEFAULT 0;
  DECLARE matched_operation_columns INT DEFAULT 0;
  DECLARE prior_authorization_columns INT DEFAULT 0;
  DECLARE matched_prior_authorization_columns INT DEFAULT 0;
  DECLARE resolution_tables INT DEFAULT 0;
  DECLARE resolution_columns INT DEFAULT 0;
  DECLARE matched_resolution_columns INT DEFAULT 0;
  DECLARE resolution_indexes INT DEFAULT 0;
  DECLARE matched_resolution_indexes INT DEFAULT 0;
  DECLARE resolution_foreign_keys INT DEFAULT 0;
  DECLARE resolution_foreign_key_count INT DEFAULT 0;
  DECLARE matched_resolution_fk_columns INT DEFAULT 0;
  IF (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
      AND TABLE_NAME='yshop_printer' AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB')<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_RECOVERY_BASE_REQUIRED';
  END IF;
  SELECT COUNT(*) INTO operation_columns FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_printer'
      AND COLUMN_NAME IN ('authorization_operation_type','authorization_operation_request_id');
  SELECT COUNT(*) INTO prior_authorization_columns FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_printer'
      AND COLUMN_NAME='authorization_operation_prior_authorization';
  IF operation_columns NOT IN (0,2) OR prior_authorization_columns NOT IN (0,1)
      OR (operation_columns=0 AND prior_authorization_columns=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_RECOVERY_SCHEMA_MISMATCH';
  END IF;
  IF operation_columns=2 THEN
    SELECT COUNT(*) INTO matched_operation_columns FROM (
      SELECT 'authorization_operation_type' AS column_name,'varchar(32)' AS column_type,
        'YES' AS nullable,'utf8mb4' AS column_charset,'utf8mb4_0900_ai_ci' AS column_collation
      UNION ALL SELECT 'authorization_operation_request_id','varchar(64)','YES','ascii','ascii_bin'
    ) expected
    JOIN information_schema.COLUMNS actual
      ON actual.TABLE_SCHEMA=DATABASE() AND actual.TABLE_NAME='yshop_printer'
      AND actual.COLUMN_NAME=expected.column_name AND LOWER(actual.COLUMN_TYPE)=expected.column_type
      AND actual.IS_NULLABLE=expected.nullable AND actual.COLUMN_DEFAULT IS NULL
      AND COALESCE(actual.CHARACTER_SET_NAME,'')=expected.column_charset
      AND COALESCE(actual.COLLATION_NAME,'')=expected.column_collation AND actual.EXTRA='';
    IF matched_operation_columns<>2 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_RECOVERY_SCHEMA_MISMATCH';
    END IF;
  END IF;
  IF prior_authorization_columns=1 THEN
    SELECT COUNT(*) INTO matched_prior_authorization_columns FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_printer'
        AND COLUMN_NAME='authorization_operation_prior_authorization'
        AND LOWER(COLUMN_TYPE)='varchar(20)' AND IS_NULLABLE='YES'
        AND COLUMN_DEFAULT IS NULL
        AND COALESCE(CHARACTER_SET_NAME,'')='utf8mb4'
        AND COALESCE(COLLATION_NAME,'')='utf8mb4_0900_ai_ci' AND EXTRA='';
    IF matched_prior_authorization_columns<>1 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_RECOVERY_SCHEMA_MISMATCH';
    END IF;
  END IF;
  SELECT COUNT(*) INTO resolution_tables FROM information_schema.TABLES
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_printer_operation_resolution';
  IF resolution_tables=1 THEN
    SELECT COUNT(*) INTO resolution_columns FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_printer_operation_resolution';
    SELECT COUNT(*) INTO matched_resolution_columns FROM (
      SELECT 'id' AS column_name,'bigint' AS column_type,'NO' AS nullable,
        '' AS column_name_charset,'' AS column_collation,'auto_increment' AS extra,'' AS default_value
      UNION ALL SELECT 'tenant_id','bigint','NO','','','',''
      UNION ALL SELECT 'printer_id','bigint','NO','','','',''
      UNION ALL SELECT 'operation_token','varchar(36)','NO','ascii','ascii_bin','',''
      UNION ALL SELECT 'operation_type','varchar(32)','NO','ascii','ascii_bin','',''
      UNION ALL SELECT 'request_id','varchar(64)','YES','ascii','ascii_bin','',''
      UNION ALL SELECT 'operation_at','datetime','NO','','','',''
      UNION ALL SELECT 'prior_authorization','varchar(20)','NO','utf8mb4','utf8mb4_0900_ai_ci','',''
      UNION ALL SELECT 'prior_device_state','varchar(20)','NO','utf8mb4','utf8mb4_0900_ai_ci','',''
      UNION ALL SELECT 'outcome','varchar(40)','NO','ascii','ascii_bin','',''
      UNION ALL SELECT 'reason','varchar(500)','NO','utf8mb4','utf8mb4_0900_ai_ci','',''
      UNION ALL SELECT 'sender_stopped','tinyint(1)','NO','','','',''
      UNION ALL SELECT 'provider_work_settled','tinyint(1)','NO','','','',''
      UNION ALL SELECT 'actor_id','bigint','NO','','','',''
      UNION ALL SELECT 'create_time','datetime','NO','','','DEFAULT_GENERATED','CURRENT_TIMESTAMP'
    ) expected
    JOIN information_schema.COLUMNS actual
      ON actual.TABLE_SCHEMA=DATABASE() AND actual.TABLE_NAME='yshop_printer_operation_resolution'
      AND actual.COLUMN_NAME=expected.column_name AND LOWER(actual.COLUMN_TYPE)=expected.column_type
      AND actual.IS_NULLABLE=expected.nullable
      AND COALESCE(actual.CHARACTER_SET_NAME,'')=expected.column_name_charset
      AND COALESCE(actual.COLLATION_NAME,'')=expected.column_collation
      AND ((expected.default_value='' AND actual.COLUMN_DEFAULT IS NULL)
        OR (expected.default_value='CURRENT_TIMESTAMP'
          AND UPPER(REPLACE(COALESCE(actual.COLUMN_DEFAULT,''),' ','')) IN ('CURRENT_TIMESTAMP','CURRENT_TIMESTAMP()')))
      AND actual.EXTRA=expected.extra;
    SELECT COUNT(DISTINCT INDEX_NAME) INTO resolution_indexes FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_printer_operation_resolution';
    SELECT COUNT(*) INTO matched_resolution_indexes FROM (
      SELECT INDEX_NAME,NON_UNIQUE,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS index_columns
        FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_printer_operation_resolution'
        GROUP BY INDEX_NAME,NON_UNIQUE
    ) indexes_found
    WHERE (INDEX_NAME='PRIMARY' AND NON_UNIQUE=0 AND index_columns='id')
       OR (INDEX_NAME='uk_printer_operation_resolution_token' AND NON_UNIQUE=0 AND index_columns='tenant_id,operation_token')
       OR (INDEX_NAME='idx_printer_operation_resolution_printer' AND NON_UNIQUE=1 AND index_columns='tenant_id,printer_id,create_time')
       OR (INDEX_NAME IN ('idx_printer_operation_resolution_device','fk_printer_operation_resolution_device')
           AND NON_UNIQUE=1 AND index_columns='printer_id,tenant_id');
    SELECT COUNT(DISTINCT CONSTRAINT_NAME) INTO resolution_foreign_keys FROM information_schema.REFERENTIAL_CONSTRAINTS
      WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='yshop_printer_operation_resolution'
        AND CONSTRAINT_NAME='fk_printer_operation_resolution_device' AND REFERENCED_TABLE_NAME='yshop_printer'
        AND UPDATE_RULE IN ('RESTRICT','NO ACTION') AND DELETE_RULE IN ('RESTRICT','NO ACTION');
    SELECT COUNT(*) INTO resolution_foreign_key_count FROM information_schema.REFERENTIAL_CONSTRAINTS
      WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='yshop_printer_operation_resolution';
    SELECT COUNT(*) INTO matched_resolution_fk_columns FROM information_schema.KEY_COLUMN_USAGE
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_printer_operation_resolution'
        AND CONSTRAINT_NAME='fk_printer_operation_resolution_device' AND REFERENCED_TABLE_NAME='yshop_printer'
        AND ((COLUMN_NAME='printer_id' AND REFERENCED_COLUMN_NAME='id')
          OR (COLUMN_NAME='tenant_id' AND REFERENCED_COLUMN_NAME='tenant_id'));
    IF resolution_columns<>15 OR matched_resolution_columns<>15 OR resolution_indexes<>4 OR matched_resolution_indexes<>4
       OR resolution_foreign_keys<>1 OR resolution_foreign_key_count<>1 OR matched_resolution_fk_columns<>2
       OR (SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
           AND TABLE_NAME='yshop_printer_operation_resolution')<>'InnoDB'
       OR (SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
           AND TABLE_NAME='yshop_printer_operation_resolution')<>'utf8mb4_0900_ai_ci'
       OR (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
           AND EVENT_OBJECT_TABLE='yshop_printer_operation_resolution')<>0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_RECOVERY_SCHEMA_MISMATCH';
    END IF;
  END IF;
  IF operation_columns=0 THEN
    ALTER TABLE yshop_printer
      ADD COLUMN authorization_operation_type varchar(32) DEFAULT NULL AFTER authorization_operation_at,
      ADD COLUMN authorization_operation_request_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL AFTER authorization_operation_type,
      ADD COLUMN authorization_operation_prior_authorization varchar(20) DEFAULT NULL AFTER authorization_operation_request_id;
  ELSEIF prior_authorization_columns=0 THEN
    ALTER TABLE yshop_printer
      ADD COLUMN authorization_operation_prior_authorization varchar(20) DEFAULT NULL AFTER authorization_operation_request_id;
  END IF;
END$$
DELIMITER ;
CALL quality_prepare_printer_operation_recovery();
DROP PROCEDURE quality_prepare_printer_operation_recovery;
-- MYSQL_PREFLIGHT_END

CREATE TABLE IF NOT EXISTS yshop_printer_operation_resolution (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  printer_id BIGINT NOT NULL,
  operation_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  operation_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  operation_at DATETIME NOT NULL,
  prior_authorization VARCHAR(20) NOT NULL,
  prior_device_state VARCHAR(20) NOT NULL,
  outcome VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason VARCHAR(500) NOT NULL,
  sender_stopped TINYINT(1) NOT NULL,
  provider_work_settled TINYINT(1) NOT NULL,
  actor_id BIGINT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_printer_operation_resolution_token (tenant_id,operation_token),
  KEY idx_printer_operation_resolution_printer (tenant_id,printer_id,create_time),
  KEY idx_printer_operation_resolution_device (printer_id,tenant_id),
  CONSTRAINT fk_printer_operation_resolution_device FOREIGN KEY (printer_id,tenant_id)
    REFERENCES yshop_printer (id,tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
