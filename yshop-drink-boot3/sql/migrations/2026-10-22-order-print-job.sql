-- MYSQL_PREFLIGHT_BEGIN
DROP PROCEDURE IF EXISTS quality_preflight_order_print_job_schema;
DELIMITER $$
CREATE PROCEDURE quality_preflight_order_print_job_schema()
BEGIN
  DECLARE table_count INT DEFAULT 0;
  DECLARE valid_table_count INT DEFAULT 0;
  DECLARE actual_column_count INT DEFAULT 0;
  DECLARE matched_column_count INT DEFAULT 0;
  DECLARE actual_index_count INT DEFAULT 0;
  DECLARE matched_index_count INT DEFAULT 0;
  DECLARE constraint_count INT DEFAULT 0;
  DECLARE trigger_count INT DEFAULT 0;

  SELECT COUNT(*) INTO table_count FROM information_schema.TABLES
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_job';
  IF table_count=1 THEN
    SELECT COUNT(*) INTO valid_table_count FROM information_schema.TABLES
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_job' AND TABLE_TYPE='BASE TABLE'
        AND ENGINE='InnoDB' AND TABLE_COLLATION LIKE 'utf8mb4\_%';
    IF valid_table_count<>1 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_JOB_TABLE_SCHEMA_MISMATCH';
    END IF;
    SELECT COUNT(*) INTO actual_column_count FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_job';
SELECT COUNT(*) INTO matched_column_count FROM (
      SELECT 'id' AS column_name, 'bigint' AS column_type, 'NO' AS nullable, '' AS column_charset, '' AS column_collation, 'auto_increment' AS extra, CAST(NULL AS CHAR(128)) AS default_value, 1 AS expected_position
      UNION ALL SELECT 'tenant_id', 'bigint', 'NO', '', '', '', '0', 2
      UNION ALL SELECT 'event_id', 'bigint', 'NO', '', '', '', CAST(NULL AS CHAR(128)), 3
      UNION ALL SELECT 'event_key', 'varchar(128)', 'NO', 'TABLE_DEFAULT', 'TABLE_DEFAULT', '', CAST(NULL AS CHAR(128)), 4
      UNION ALL SELECT 'order_id', 'bigint', 'NO', '', '', '', CAST(NULL AS CHAR(128)), 5
      UNION ALL SELECT 'shop_id', 'bigint', 'NO', '', '', '', CAST(NULL AS CHAR(128)), 6
      UNION ALL SELECT 'printer_id', 'bigint', 'NO', '', '', '', CAST(NULL AS CHAR(128)), 7
      UNION ALL SELECT 'printer_name_snapshot', 'varchar(80)', 'NO', 'TABLE_DEFAULT', 'TABLE_DEFAULT', '', CAST(NULL AS CHAR(128)), 8
      UNION ALL SELECT 'provider_code_snapshot', 'varchar(40)', 'NO', 'TABLE_DEFAULT', 'TABLE_DEFAULT', '', CAST(NULL AS CHAR(128)), 9
      UNION ALL SELECT 'device_code_snapshot', 'varchar(100)', 'NO', 'TABLE_DEFAULT', 'TABLE_DEFAULT', '', CAST(NULL AS CHAR(128)), 10
      UNION ALL SELECT 'request_key', 'varchar(64)', 'NO', 'ascii', 'ascii_bin', '', CAST(NULL AS CHAR(128)), 11
      UNION ALL SELECT 'request_hash', 'char(64)', 'NO', 'ascii', 'ascii_bin', '', CAST(NULL AS CHAR(128)), 12
      UNION ALL SELECT 'purpose', 'varchar(16)', 'NO', 'ascii', 'ascii_bin', '', CAST(NULL AS CHAR(128)), 13
      UNION ALL SELECT 'purpose_key', 'varchar(16)', 'YES', 'ascii', 'ascii_bin', '', CAST(NULL AS CHAR(128)), 14
      UNION ALL SELECT 'state', 'varchar(16)', 'NO', 'ascii', 'ascii_bin', '', 'PENDING', 15
      UNION ALL SELECT 'payload_snapshot', 'longtext', 'NO', 'TABLE_DEFAULT', 'TABLE_DEFAULT', '', CAST(NULL AS CHAR(128)), 16
      UNION ALL SELECT 'provider_job_id', 'varchar(128)', 'YES', 'TABLE_DEFAULT', 'TABLE_DEFAULT', '', CAST(NULL AS CHAR(128)), 17
      UNION ALL SELECT 'last_error', 'varchar(64)', 'YES', 'TABLE_DEFAULT', 'TABLE_DEFAULT', '', CAST(NULL AS CHAR(128)), 18
      UNION ALL SELECT 'attempts', 'int', 'NO', '', '', '', '0', 19
      UNION ALL SELECT 'next_attempt_at', 'datetime', 'YES', '', '', '', CAST(NULL AS CHAR(128)), 20
      UNION ALL SELECT 'lease_token', 'varchar(64)', 'YES', 'ascii', 'ascii_bin', '', CAST(NULL AS CHAR(128)), 21
      UNION ALL SELECT 'lease_expires_at', 'datetime', 'YES', '', '', '', CAST(NULL AS CHAR(128)), 22
      UNION ALL SELECT 'parent_job_id', 'bigint', 'YES', '', '', '', CAST(NULL AS CHAR(128)), 23
      UNION ALL SELECT 'reprint_reason', 'varchar(255)', 'YES', 'TABLE_DEFAULT', 'TABLE_DEFAULT', '', CAST(NULL AS CHAR(128)), 24
      UNION ALL SELECT 'created_by', 'bigint', 'YES', '', '', '', CAST(NULL AS CHAR(128)), 25
      UNION ALL SELECT 'create_time', 'datetime', 'NO', '', '', 'DEFAULT_GENERATED', 'CURRENT_TIMESTAMP', 26
      UNION ALL SELECT 'update_time', 'datetime', 'NO', '', '', 'DEFAULT_GENERATED on update CURRENT_TIMESTAMP', 'CURRENT_TIMESTAMP', 27
    ) expected
    JOIN information_schema.TABLES table_info
      ON table_info.TABLE_SCHEMA=DATABASE() AND table_info.TABLE_NAME='yshop_order_print_job'
    JOIN information_schema.COLUMNS actual
      ON actual.TABLE_SCHEMA=DATABASE() AND actual.TABLE_NAME='yshop_order_print_job'
      AND actual.COLUMN_NAME=expected.column_name
      AND LOWER(actual.COLUMN_TYPE)=expected.column_type
      AND actual.IS_NULLABLE=expected.nullable
      AND ((expected.column_charset='' AND COALESCE(actual.CHARACTER_SET_NAME,'')='')
        OR (expected.column_charset='ascii' AND actual.CHARACTER_SET_NAME='ascii' AND actual.COLLATION_NAME='ascii_bin')
        OR (expected.column_charset='TABLE_DEFAULT' AND actual.CHARACTER_SET_NAME=SUBSTRING_INDEX(table_info.TABLE_COLLATION,'_',1)
          AND actual.COLLATION_NAME=table_info.TABLE_COLLATION))
      AND ((expected.column_collation='' AND actual.COLLATION_NAME IS NULL)
        OR (expected.column_collation='TABLE_DEFAULT' AND actual.COLLATION_NAME=table_info.TABLE_COLLATION)
        OR actual.COLLATION_NAME=expected.column_collation)
      AND ((expected.default_value IS NULL AND actual.COLUMN_DEFAULT IS NULL)
        OR (expected.default_value='CURRENT_TIMESTAMP'
          AND UPPER(REPLACE(COALESCE(actual.COLUMN_DEFAULT,''),' ','')) IN ('CURRENT_TIMESTAMP','CURRENT_TIMESTAMP()'))
        OR (expected.default_value IS NOT NULL AND expected.default_value<>'CURRENT_TIMESTAMP'
          AND BINARY actual.COLUMN_DEFAULT <=> BINARY expected.default_value))
      AND LOWER(actual.EXTRA)=LOWER(expected.extra)
        AND actual.ORDINAL_POSITION=expected.expected_position;
    IF actual_column_count<>27 OR matched_column_count<>27 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_JOB_COLUMN_SCHEMA_MISMATCH';
    END IF;
    SELECT COUNT(DISTINCT INDEX_NAME) INTO actual_index_count FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_job';
SELECT COUNT(*) INTO matched_index_count FROM (
      SELECT INDEX_NAME, NON_UNIQUE,
        GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS index_columns,
        SUM(SUB_PART IS NOT NULL) AS prefix_column_count,
        MIN(INDEX_TYPE) AS index_type,
        SUM(IS_VISIBLE='NO') AS invisible_count
      FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_job'
      GROUP BY INDEX_NAME,NON_UNIQUE
    ) actual
    JOIN (
      SELECT 'PRIMARY' AS index_name, 0 AS non_unique, 'id' AS index_columns
      UNION ALL SELECT 'uk_order_print_request', 0, 'tenant_id,request_key'
      UNION ALL SELECT 'uk_order_print_event_target', 0, 'tenant_id,event_id,printer_id,purpose,purpose_key'
      UNION ALL SELECT 'idx_order_print_dispatch', 1, 'state,next_attempt_at,id'
      UNION ALL SELECT 'idx_order_print_page', 1, 'tenant_id,shop_id,create_time,id'
      UNION ALL SELECT 'idx_order_print_order', 1, 'tenant_id,order_id,id'
      UNION ALL SELECT 'idx_order_print_parent', 1, 'tenant_id,parent_job_id'
    ) expected
      ON actual.INDEX_NAME=expected.index_name AND actual.NON_UNIQUE=expected.non_unique
      AND actual.index_columns=expected.index_columns AND actual.prefix_column_count=0
      AND actual.index_type='BTREE' AND actual.invisible_count=0;
    IF actual_index_count<>7 OR matched_index_count<>7 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_JOB_INDEX_SCHEMA_MISMATCH';
    END IF;
    SELECT COUNT(*) INTO constraint_count FROM information_schema.TABLE_CONSTRAINTS
      WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_job' AND CONSTRAINT_TYPE IN ('FOREIGN KEY','CHECK');
    SELECT COUNT(*) INTO trigger_count FROM information_schema.TRIGGERS
      WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='yshop_order_print_job';
    IF constraint_count<>0 OR trigger_count<>0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_JOB_EXTRA_CONSTRAINT_SCHEMA_MISMATCH';
    END IF;
  END IF;
END$$
DELIMITER ;
CALL quality_preflight_order_print_job_schema();
DROP PROCEDURE quality_preflight_order_print_job_schema;
-- MYSQL_PREFLIGHT_END

CREATE TABLE IF NOT EXISTS yshop_order_print_job (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL DEFAULT 0,
  event_id BIGINT NOT NULL,
  event_key VARCHAR(128) NOT NULL,
  order_id BIGINT NOT NULL,
  shop_id BIGINT NOT NULL,
  printer_id BIGINT NOT NULL,
  printer_name_snapshot VARCHAR(80) NOT NULL,
  provider_code_snapshot VARCHAR(40) NOT NULL,
  device_code_snapshot VARCHAR(100) NOT NULL,
  request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  purpose_key VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
  payload_snapshot LONGTEXT NOT NULL,
  provider_job_id VARCHAR(128) NULL,
  last_error VARCHAR(64) NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NULL,
  lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_expires_at DATETIME NULL,
  parent_job_id BIGINT NULL,
  reprint_reason VARCHAR(255) NULL,
  created_by BIGINT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_print_request (tenant_id,request_key),
  UNIQUE KEY uk_order_print_event_target (tenant_id,event_id,printer_id,purpose,purpose_key),
  KEY idx_order_print_dispatch (state,next_attempt_at,id),
  KEY idx_order_print_page (tenant_id,shop_id,create_time,id),
  KEY idx_order_print_order (tenant_id,order_id,id),
  KEY idx_order_print_parent (tenant_id,parent_job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
