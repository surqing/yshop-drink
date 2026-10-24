-- MYSQL_PREFLIGHT_BEGIN
DROP PROCEDURE IF EXISTS quality_preflight_order_print_event_schema;
DELIMITER $$
CREATE PROCEDURE quality_preflight_order_print_event_schema()
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
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_fulfillment_print_event';
  IF table_count=1 THEN
    SELECT COUNT(*) INTO valid_table_count FROM information_schema.TABLES
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_fulfillment_print_event' AND TABLE_TYPE='BASE TABLE'
        AND ENGINE='InnoDB' AND TABLE_COLLATION LIKE 'utf8mb4\_%';
    IF valid_table_count<>1 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_EVENT_TABLE_SCHEMA_MISMATCH';
    END IF;
    SELECT COUNT(*) INTO actual_column_count FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_fulfillment_print_event';
SELECT COUNT(*) INTO matched_column_count FROM (
      SELECT 'id' AS column_name, 'bigint unsigned' AS column_type, 'NO' AS nullable, '' AS column_charset, '' AS column_collation, 'auto_increment' AS extra, CAST(NULL AS CHAR(128)) AS default_value, 1 AS expected_position
      UNION ALL SELECT 'tenant_id', 'bigint', 'NO', '', '', '', '0', 2
      UNION ALL SELECT 'order_id', 'bigint unsigned', 'NO', '', '', '', CAST(NULL AS CHAR(128)), 3
      UNION ALL SELECT 'shop_id', 'bigint unsigned', 'NO', '', '', '', CAST(NULL AS CHAR(128)), 4
      UNION ALL SELECT 'event_key', 'varchar(128)', 'NO', 'ascii', 'ascii_bin', '', CAST(NULL AS CHAR(128)), 5
      UNION ALL SELECT 'event_type', 'varchar(32)', 'NO', 'ascii', 'ascii_bin', '', CAST(NULL AS CHAR(128)), 6
      UNION ALL SELECT 'payload_snapshot', 'longtext', 'NO', 'TABLE_DEFAULT', 'TABLE_DEFAULT', '', CAST(NULL AS CHAR(128)), 7
      UNION ALL SELECT 'state', 'varchar(24)', 'NO', 'ascii', 'ascii_bin', '', 'PENDING', 8
      UNION ALL SELECT 'attempts', 'int', 'NO', '', '', '', '0', 9
      UNION ALL SELECT 'next_attempt_at', 'datetime', 'YES', '', '', '', CAST(NULL AS CHAR(128)), 10
      UNION ALL SELECT 'last_error', 'varchar(500)', 'YES', 'TABLE_DEFAULT', 'TABLE_DEFAULT', '', CAST(NULL AS CHAR(128)), 11
      UNION ALL SELECT 'create_time', 'datetime', 'NO', '', '', 'DEFAULT_GENERATED', 'CURRENT_TIMESTAMP', 12
      UNION ALL SELECT 'update_time', 'datetime', 'NO', '', '', 'DEFAULT_GENERATED on update CURRENT_TIMESTAMP', 'CURRENT_TIMESTAMP', 13
    ) expected
    JOIN information_schema.TABLES table_info
      ON table_info.TABLE_SCHEMA=DATABASE() AND table_info.TABLE_NAME='yshop_order_fulfillment_print_event'
    JOIN information_schema.COLUMNS actual
      ON actual.TABLE_SCHEMA=DATABASE() AND actual.TABLE_NAME='yshop_order_fulfillment_print_event'
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
    IF actual_column_count<>13 OR matched_column_count<>13 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_EVENT_COLUMN_SCHEMA_MISMATCH';
    END IF;
    SELECT COUNT(DISTINCT INDEX_NAME) INTO actual_index_count FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_fulfillment_print_event';
SELECT COUNT(*) INTO matched_index_count FROM (
      SELECT INDEX_NAME, NON_UNIQUE,
        GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS index_columns,
        SUM(SUB_PART IS NOT NULL) AS prefix_column_count,
        MIN(INDEX_TYPE) AS index_type,
        SUM(IS_VISIBLE='NO') AS invisible_count
      FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_fulfillment_print_event'
      GROUP BY INDEX_NAME,NON_UNIQUE
    ) actual
    JOIN (
      SELECT 'PRIMARY' AS index_name, 0 AS non_unique, 'id' AS index_columns
      UNION ALL SELECT 'uk_fulfillment_print_event', 0, 'tenant_id,event_key'
      UNION ALL SELECT 'idx_fulfillment_print_event_state', 1, 'state,next_attempt_at,id'
      UNION ALL SELECT 'idx_fulfillment_print_event_tenant_state', 1, 'tenant_id,state,create_time,id'
      UNION ALL SELECT 'idx_fulfillment_print_event_order', 1, 'tenant_id,order_id'
    ) expected
      ON actual.INDEX_NAME=expected.index_name AND actual.NON_UNIQUE=expected.non_unique
      AND actual.index_columns=expected.index_columns AND actual.prefix_column_count=0
      AND actual.index_type='BTREE' AND actual.invisible_count=0;
    IF actual_index_count<>5 OR matched_index_count<>5 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_EVENT_INDEX_SCHEMA_MISMATCH';
    END IF;
    SELECT COUNT(*) INTO constraint_count FROM information_schema.TABLE_CONSTRAINTS
      WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_fulfillment_print_event' AND CONSTRAINT_TYPE IN ('FOREIGN KEY','CHECK');
    SELECT COUNT(*) INTO trigger_count FROM information_schema.TRIGGERS
      WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='yshop_order_fulfillment_print_event';
    IF constraint_count<>0 OR trigger_count<>0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_EVENT_EXTRA_CONSTRAINT_SCHEMA_MISMATCH';
    END IF;
  END IF;
END$$
DELIMITER ;
CALL quality_preflight_order_print_event_schema();
DROP PROCEDURE quality_preflight_order_print_event_schema;
-- MYSQL_PREFLIGHT_END

CREATE TABLE IF NOT EXISTS yshop_order_fulfillment_print_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL DEFAULT 0,
  order_id BIGINT UNSIGNED NOT NULL,
  shop_id BIGINT UNSIGNED NOT NULL,
  event_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_snapshot LONGTEXT NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NULL,
  last_error VARCHAR(500) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_fulfillment_print_event (tenant_id,event_key),
  KEY idx_fulfillment_print_event_state (state,next_attempt_at,id),
  KEY idx_fulfillment_print_event_tenant_state (tenant_id,state,create_time,id),
  KEY idx_fulfillment_print_event_order (tenant_id,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
