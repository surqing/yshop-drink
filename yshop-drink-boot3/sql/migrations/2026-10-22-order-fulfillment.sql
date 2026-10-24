-- MYSQL_PREFLIGHT_BEGIN
DROP PROCEDURE IF EXISTS quality_preflight_order_fulfillment_schema;
DELIMITER $$
CREATE PROCEDURE quality_preflight_order_fulfillment_schema()
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
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_store_order';
  SELECT COUNT(*) INTO valid_table_count FROM information_schema.TABLES
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_store_order' AND TABLE_TYPE='BASE TABLE'
      AND ENGINE='InnoDB';
  IF table_count<>1 OR valid_table_count<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_FULFILLMENT_ORDER_TABLE_REQUIRED';
  END IF;
  SELECT COUNT(*) INTO actual_column_count FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_store_order'
      AND COLUMN_NAME IN ('fulfillment_status','fulfillment_version');
SELECT COUNT(*) INTO matched_column_count FROM (
      SELECT 'fulfillment_status' AS column_name, 'varchar(24)' AS column_type, 'NO' AS nullable, 'TABLE_DEFAULT' AS column_charset, 'TABLE_DEFAULT' AS column_collation, '' AS extra, 'LEGACY' AS default_value
      UNION ALL SELECT 'fulfillment_version', 'int', 'NO', '', '', '', '0'
    ) expected
    JOIN information_schema.TABLES table_info
      ON table_info.TABLE_SCHEMA=DATABASE() AND table_info.TABLE_NAME='yshop_store_order'
    JOIN information_schema.COLUMNS actual
      ON actual.TABLE_SCHEMA=DATABASE() AND actual.TABLE_NAME='yshop_store_order'
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
      AND LOWER(actual.EXTRA)=LOWER(expected.extra);
  IF actual_column_count NOT IN (0,2) OR (actual_column_count=2 AND matched_column_count<>2) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_FULFILLMENT_COLUMN_SCHEMA_MISMATCH';
  END IF;
  SELECT COUNT(DISTINCT INDEX_NAME) INTO actual_index_count FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_store_order' AND INDEX_NAME='idx_fulfillment_queue';
SELECT COUNT(*) INTO matched_index_count FROM (
      SELECT INDEX_NAME, NON_UNIQUE,
        GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS index_columns,
        SUM(SUB_PART IS NOT NULL) AS prefix_column_count,
        MIN(INDEX_TYPE) AS index_type,
        SUM(IS_VISIBLE='NO') AS invisible_count
      FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_store_order'
      GROUP BY INDEX_NAME,NON_UNIQUE
    ) actual
    JOIN (
      SELECT 'idx_fulfillment_queue' AS index_name, 1 AS non_unique, 'fulfillment_status,paid,shop_id,update_time' AS index_columns
    ) expected
      ON actual.INDEX_NAME=expected.index_name AND actual.NON_UNIQUE=expected.non_unique
      AND actual.index_columns=expected.index_columns AND actual.prefix_column_count=0
      AND actual.index_type='BTREE' AND actual.invisible_count=0;
  IF actual_index_count<>matched_index_count THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_FULFILLMENT_QUEUE_INDEX_SCHEMA_MISMATCH';
  END IF;
  SELECT COUNT(*) INTO table_count FROM information_schema.TABLES
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_fulfillment_log';
  IF table_count=1 THEN
    SELECT COUNT(*) INTO valid_table_count FROM information_schema.TABLES
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_fulfillment_log' AND TABLE_TYPE='BASE TABLE'
        AND ENGINE='InnoDB' AND TABLE_COLLATION LIKE 'utf8mb4\_%';
    IF valid_table_count<>1 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_FULFILLMENT_LOG_TABLE_SCHEMA_MISMATCH';
    END IF;
    SELECT COUNT(*) INTO actual_column_count FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_fulfillment_log';
SELECT COUNT(*) INTO matched_column_count FROM (
      SELECT 'id' AS column_name, 'bigint unsigned' AS column_type, 'NO' AS nullable, '' AS column_charset, '' AS column_collation, 'auto_increment' AS extra, CAST(NULL AS CHAR(128)) AS default_value, 1 AS expected_position
      UNION ALL SELECT 'order_id', 'bigint unsigned', 'NO', '', '', '', CAST(NULL AS CHAR(128)), 2
      UNION ALL SELECT 'request_key', 'varchar(64)', 'NO', 'ascii', 'ascii_bin', '', CAST(NULL AS CHAR(128)), 3
      UNION ALL SELECT 'request_hash', 'char(64)', 'NO', 'ascii', 'ascii_bin', '', CAST(NULL AS CHAR(128)), 4
      UNION ALL SELECT 'action', 'varchar(24)', 'NO', 'ascii', 'ascii_bin', '', CAST(NULL AS CHAR(128)), 5
      UNION ALL SELECT 'from_status', 'varchar(24)', 'NO', 'ascii', 'ascii_bin', '', CAST(NULL AS CHAR(128)), 6
      UNION ALL SELECT 'to_status', 'varchar(24)', 'NO', 'ascii', 'ascii_bin', '', CAST(NULL AS CHAR(128)), 7
      UNION ALL SELECT 'expected_version', 'int', 'YES', '', '', '', CAST(NULL AS CHAR(128)), 8
      UNION ALL SELECT 'result_version', 'int', 'NO', '', '', '', CAST(NULL AS CHAR(128)), 9
      UNION ALL SELECT 'actor_id', 'bigint', 'NO', '', '', '', CAST(NULL AS CHAR(128)), 10
      UNION ALL SELECT 'actor_type', 'varchar(16)', 'NO', 'ascii', 'ascii_bin', '', CAST(NULL AS CHAR(128)), 11
      UNION ALL SELECT 'create_time', 'datetime', 'NO', '', '', 'DEFAULT_GENERATED', 'CURRENT_TIMESTAMP', 12
    ) expected
    JOIN information_schema.TABLES table_info
      ON table_info.TABLE_SCHEMA=DATABASE() AND table_info.TABLE_NAME='yshop_order_fulfillment_log'
    JOIN information_schema.COLUMNS actual
      ON actual.TABLE_SCHEMA=DATABASE() AND actual.TABLE_NAME='yshop_order_fulfillment_log'
      AND actual.COLUMN_NAME=expected.column_name
      AND LOWER(actual.COLUMN_TYPE)=expected.column_type
      AND actual.IS_NULLABLE=expected.nullable
      AND ((expected.column_charset='' AND COALESCE(actual.CHARACTER_SET_NAME,'')='')
        OR (expected.column_charset='ascii' AND actual.CHARACTER_SET_NAME='ascii' AND actual.COLLATION_NAME='ascii_bin')
        OR (expected.column_charset='TABLE_DEFAULT' AND actual.CHARACTER_SET_NAME='utf8mb4'
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
    IF actual_column_count<>12 OR matched_column_count<>12 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_FULFILLMENT_LOG_COLUMN_SCHEMA_MISMATCH';
    END IF;
    SELECT COUNT(DISTINCT INDEX_NAME) INTO actual_index_count FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_fulfillment_log';
SELECT COUNT(*) INTO matched_index_count FROM (
      SELECT INDEX_NAME, NON_UNIQUE,
        GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS index_columns,
        SUM(SUB_PART IS NOT NULL) AS prefix_column_count,
        MIN(INDEX_TYPE) AS index_type,
        SUM(IS_VISIBLE='NO') AS invisible_count
      FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_fulfillment_log'
      GROUP BY INDEX_NAME,NON_UNIQUE
    ) actual
    JOIN (
      SELECT 'PRIMARY' AS index_name, 0 AS non_unique, 'id' AS index_columns
      UNION ALL SELECT 'uk_fulfillment_request_key', 0, 'request_key'
      UNION ALL SELECT 'idx_fulfillment_order_history', 1, 'order_id,id'
      UNION ALL SELECT 'idx_fulfillment_status_history', 1, 'to_status,create_time'
    ) expected
      ON actual.INDEX_NAME=expected.index_name AND actual.NON_UNIQUE=expected.non_unique
      AND actual.index_columns=expected.index_columns AND actual.prefix_column_count=0
      AND actual.index_type='BTREE' AND actual.invisible_count=0;
    IF actual_index_count<>4 OR matched_index_count<>4 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_FULFILLMENT_LOG_INDEX_SCHEMA_MISMATCH';
    END IF;
    SELECT COUNT(*) INTO constraint_count FROM information_schema.TABLE_CONSTRAINTS
      WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_fulfillment_log' AND CONSTRAINT_TYPE IN ('FOREIGN KEY','CHECK');
    SELECT COUNT(*) INTO trigger_count FROM information_schema.TRIGGERS
      WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='yshop_order_fulfillment_log';
    IF constraint_count<>0 OR trigger_count<>0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_FULFILLMENT_LOG_EXTRA_CONSTRAINT_SCHEMA_MISMATCH';
    END IF;
  END IF;
END$$
DELIMITER ;
CALL quality_preflight_order_fulfillment_schema();
DROP PROCEDURE quality_preflight_order_fulfillment_schema;
-- MYSQL_PREFLIGHT_END

-- Add explicit pickup fulfillment state without guessing historical order progress.
SET @fulfillment_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_store_order' AND COLUMN_NAME='fulfillment_status')=0,
  'ALTER TABLE yshop_store_order ADD COLUMN fulfillment_status VARCHAR(24) NOT NULL DEFAULT ''LEGACY''', 'SELECT 1');
PREPARE fulfillment_stmt FROM @fulfillment_sql;
EXECUTE fulfillment_stmt;
DEALLOCATE PREPARE fulfillment_stmt;

SET @fulfillment_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_store_order' AND COLUMN_NAME='fulfillment_version')=0,
  'ALTER TABLE yshop_store_order ADD COLUMN fulfillment_version INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE fulfillment_stmt FROM @fulfillment_sql;
EXECUTE fulfillment_stmt;
DEALLOCATE PREPARE fulfillment_stmt;

CREATE TABLE IF NOT EXISTS yshop_order_fulfillment_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id BIGINT UNSIGNED NOT NULL,
  request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  from_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  to_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expected_version INT NULL,
  result_version INT NOT NULL,
  actor_id BIGINT NOT NULL,
  actor_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_fulfillment_request_key (request_key),
  KEY idx_fulfillment_order_history (order_id, id),
  KEY idx_fulfillment_status_history (to_status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @fulfillment_sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_store_order' AND INDEX_NAME='idx_fulfillment_queue')=0,
  'ALTER TABLE yshop_store_order ADD INDEX idx_fulfillment_queue(fulfillment_status,paid,shop_id,update_time)', 'SELECT 1');
PREPARE fulfillment_stmt FROM @fulfillment_sql;
EXECUTE fulfillment_stmt;
DEALLOCATE PREPARE fulfillment_stmt;
