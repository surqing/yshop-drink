-- One row reserves a printer only while a worker owns an external send. The
-- worker creates it after provider session preparation and removes it after
-- the provider call returns. A crashed/in-flight send stays fail-closed; this
-- claim is intentionally not expired or auto-retried with the job lease.
-- MYSQL_PREFLIGHT_BEGIN
DROP PROCEDURE IF EXISTS quality_prepare_order_print_device_claim;
DELIMITER $$
CREATE PROCEDURE quality_prepare_order_print_device_claim()
BEGIN
  DECLARE claim_table_count INT DEFAULT 0;
  DECLARE claim_column_count INT DEFAULT 0;
  DECLARE matched_claim_columns INT DEFAULT 0;
  DECLARE claim_index_count INT DEFAULT 0;
  DECLARE matched_claim_indexes INT DEFAULT 0;
  DECLARE claim_fk_count INT DEFAULT 0;
  DECLARE matched_claim_fk_count INT DEFAULT 0;
  DECLARE matched_claim_fk_columns INT DEFAULT 0;
  DECLARE resolution_table_count INT DEFAULT 0;
  DECLARE resolution_column_count INT DEFAULT 0;
  DECLARE matched_resolution_columns INT DEFAULT 0;
  DECLARE resolution_index_count INT DEFAULT 0;
  DECLARE matched_resolution_indexes INT DEFAULT 0;
  DECLARE resolution_fk_count INT DEFAULT 0;
  DECLARE resolution_trigger_count INT DEFAULT 0;
  DECLARE claim_trigger_count INT DEFAULT 0;
  DECLARE failure_detail VARCHAR(128) DEFAULT '';

  IF (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
      AND TABLE_NAME='yshop_printer' AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB')<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_CLAIM_PRINTER_REQUIRED';
  END IF;

  SELECT COUNT(*) INTO claim_table_count FROM information_schema.TABLES
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_device_claim';
  IF claim_table_count NOT IN (0,1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_CLAIM_SCHEMA_MISMATCH';
  END IF;
  IF claim_table_count=1 THEN
    SELECT COUNT(*) INTO claim_column_count FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_device_claim';
    SELECT COUNT(*),GROUP_CONCAT(expected.column_name ORDER BY expected.column_name SEPARATOR ',')
      INTO matched_claim_columns,failure_detail FROM (
      SELECT 'tenant_id' AS column_name,'bigint' AS column_type,'NO' AS nullable,'' AS column_charset,
        CAST(NULL AS CHAR(64)) AS column_collation,'' AS extra,'' AS default_value
      UNION ALL SELECT 'printer_id','bigint','NO','','','',''
      UNION ALL SELECT 'job_id','bigint','NO','','','',''
      UNION ALL SELECT 'lease_token','varchar(64)','NO','ascii','ascii_bin','',''
      UNION ALL SELECT 'shop_id','bigint','NO','','','',''
      UNION ALL SELECT 'create_time','datetime','NO','','','DEFAULT_GENERATED','CURRENT_TIMESTAMP'
    ) expected
    JOIN information_schema.TABLES table_info
      ON table_info.TABLE_SCHEMA=DATABASE() AND table_info.TABLE_NAME='yshop_order_print_device_claim'
    JOIN information_schema.COLUMNS actual
      ON actual.TABLE_SCHEMA=DATABASE() AND actual.TABLE_NAME='yshop_order_print_device_claim'
      AND actual.COLUMN_NAME=expected.column_name AND LOWER(actual.COLUMN_TYPE)=expected.column_type
      AND actual.IS_NULLABLE=expected.nullable
      AND COALESCE(actual.CHARACTER_SET_NAME,'')=expected.column_charset
      AND COALESCE(actual.COLLATION_NAME,'')=COALESCE(expected.column_collation,'')
      AND ((expected.default_value='' AND actual.COLUMN_DEFAULT IS NULL)
        OR (expected.default_value='CURRENT_TIMESTAMP'
          AND UPPER(REPLACE(COALESCE(actual.COLUMN_DEFAULT,''),' ','')) IN ('CURRENT_TIMESTAMP','CURRENT_TIMESTAMP()')))
      AND actual.EXTRA=expected.extra;
    SELECT COUNT(DISTINCT INDEX_NAME) INTO claim_index_count FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_device_claim';
    SELECT COUNT(*) INTO matched_claim_indexes FROM (
      SELECT INDEX_NAME,NON_UNIQUE,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS index_columns
        FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_device_claim'
        GROUP BY INDEX_NAME,NON_UNIQUE
    ) indexes_found
    WHERE (INDEX_NAME='PRIMARY' AND NON_UNIQUE=0 AND index_columns='printer_id')
       OR (INDEX_NAME='uk_order_print_device_claim_job' AND NON_UNIQUE=0 AND index_columns='tenant_id,job_id')
       OR (INDEX_NAME IN ('fk_order_print_device_claim_printer','idx_order_print_device_claim_printer')
           AND NON_UNIQUE=1 AND index_columns='printer_id,tenant_id');
    SELECT COUNT(*) INTO claim_fk_count FROM information_schema.REFERENTIAL_CONSTRAINTS
      WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_device_claim';
    SELECT COUNT(*) INTO matched_claim_fk_count FROM information_schema.REFERENTIAL_CONSTRAINTS
      WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_device_claim'
        AND CONSTRAINT_NAME='fk_order_print_device_claim_printer' AND REFERENCED_TABLE_NAME='yshop_printer'
        AND UPDATE_RULE IN ('RESTRICT','NO ACTION') AND DELETE_RULE IN ('RESTRICT','NO ACTION');
    SELECT COUNT(*) INTO matched_claim_fk_columns FROM information_schema.KEY_COLUMN_USAGE
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_device_claim'
        AND CONSTRAINT_NAME='fk_order_print_device_claim_printer' AND REFERENCED_TABLE_NAME='yshop_printer'
        AND ((COLUMN_NAME='printer_id' AND REFERENCED_COLUMN_NAME='id' AND ORDINAL_POSITION=1)
          OR (COLUMN_NAME='tenant_id' AND REFERENCED_COLUMN_NAME='tenant_id' AND ORDINAL_POSITION=2));
    SELECT COUNT(*) INTO claim_trigger_count FROM information_schema.TRIGGERS
      WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='yshop_order_print_device_claim';
    IF claim_column_count<>6 THEN
      SET failure_detail=CONCAT('ORDER_PRINT_CLAIM_COLUMN_COUNT:',claim_column_count);
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=failure_detail;
    END IF;
    IF matched_claim_columns<>6 THEN
      SET failure_detail=CONCAT('ORDER_PRINT_CLAIM_COLUMN_MATCH:',matched_claim_columns,':',COALESCE(failure_detail,''));
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=failure_detail;
    END IF;
    IF claim_index_count<>3 OR matched_claim_indexes<>3 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_CLAIM_INDEX_SCHEMA_MISMATCH';
    END IF;
    IF claim_fk_count<>1 OR matched_claim_fk_count<>1 OR matched_claim_fk_columns<>2 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_CLAIM_FOREIGN_KEY_SCHEMA_MISMATCH';
    END IF;
    IF claim_trigger_count<>0 OR COALESCE((SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
           AND TABLE_NAME='yshop_order_print_device_claim'),'')<>'InnoDB'
       OR COALESCE((SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
           AND TABLE_NAME='yshop_order_print_device_claim'),'') NOT LIKE 'utf8mb4\_%' THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_CLAIM_TABLE_SCHEMA_MISMATCH';
    END IF;
  END IF;

  SELECT COUNT(*) INTO resolution_table_count FROM information_schema.TABLES
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_device_claim_resolution';
  IF resolution_table_count NOT IN (0,1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_CLAIM_RESOLUTION_SCHEMA_MISMATCH';
  END IF;
  IF resolution_table_count=1 THEN
    SELECT COUNT(*) INTO resolution_column_count FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_device_claim_resolution';
    SELECT COUNT(*),GROUP_CONCAT(expected.column_name ORDER BY expected.column_name SEPARATOR ',')
      INTO matched_resolution_columns,failure_detail FROM (
      SELECT 'id' AS column_name,'bigint' AS column_type,'NO' AS nullable,'' AS column_charset,
        CAST(NULL AS CHAR(64)) AS column_collation,'auto_increment' AS extra,'' AS default_value
      UNION ALL SELECT 'tenant_id','bigint','NO','','','',''
      UNION ALL SELECT 'printer_id','bigint','NO','','','',''
      UNION ALL SELECT 'job_id','bigint','NO','','','',''
      UNION ALL SELECT 'request_key','varchar(64)','NO','ascii','ascii_bin','',''
      UNION ALL SELECT 'request_hash','char(64)','NO','ascii','ascii_bin','',''
      UNION ALL SELECT 'outcome','varchar(40)','NO','ascii','ascii_bin','',''
      UNION ALL SELECT 'claim_was_present','tinyint(1)','NO','','','',''
      UNION ALL SELECT 'reason','varchar(255)','NO','utf8mb4',NULL,'',''
      UNION ALL SELECT 'actor_id','bigint','NO','','','',''
      UNION ALL SELECT 'prior_error','varchar(64)','YES','utf8mb4',NULL,'',''
      UNION ALL SELECT 'create_time','datetime','NO','','','DEFAULT_GENERATED','CURRENT_TIMESTAMP'
    ) expected
    JOIN information_schema.TABLES table_info
      ON table_info.TABLE_SCHEMA=DATABASE() AND table_info.TABLE_NAME='yshop_order_print_device_claim_resolution'
    JOIN information_schema.COLUMNS actual
      ON actual.TABLE_SCHEMA=DATABASE() AND actual.TABLE_NAME='yshop_order_print_device_claim_resolution'
      AND actual.COLUMN_NAME=expected.column_name AND LOWER(actual.COLUMN_TYPE)=expected.column_type
      AND actual.IS_NULLABLE=expected.nullable
      AND COALESCE(actual.CHARACTER_SET_NAME,'')=expected.column_charset
      AND ((expected.column_charset='utf8mb4' AND actual.COLLATION_NAME=table_info.TABLE_COLLATION)
        OR (expected.column_charset<>'utf8mb4'
          AND COALESCE(actual.COLLATION_NAME,'')=COALESCE(expected.column_collation,'')))
      AND ((expected.default_value='' AND actual.COLUMN_DEFAULT IS NULL)
        OR (expected.default_value='CURRENT_TIMESTAMP'
          AND UPPER(REPLACE(COALESCE(actual.COLUMN_DEFAULT,''),' ','')) IN ('CURRENT_TIMESTAMP','CURRENT_TIMESTAMP()')))
      AND actual.EXTRA=expected.extra;
    SELECT COUNT(DISTINCT INDEX_NAME) INTO resolution_index_count FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_device_claim_resolution';
    SELECT COUNT(*) INTO matched_resolution_indexes FROM (
      SELECT INDEX_NAME,NON_UNIQUE,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS index_columns
        FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_device_claim_resolution'
        GROUP BY INDEX_NAME,NON_UNIQUE
    ) indexes_found
    WHERE (INDEX_NAME='PRIMARY' AND NON_UNIQUE=0 AND index_columns='id')
       OR (INDEX_NAME='uk_print_claim_resolution_request' AND NON_UNIQUE=0 AND index_columns='tenant_id,request_key')
       OR (INDEX_NAME='uk_print_claim_resolution_job' AND NON_UNIQUE=0 AND index_columns='tenant_id,job_id')
       OR (INDEX_NAME='idx_print_claim_resolution_printer' AND NON_UNIQUE=1 AND index_columns='tenant_id,printer_id,create_time');
    SELECT COUNT(*) INTO resolution_fk_count FROM information_schema.REFERENTIAL_CONSTRAINTS
      WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_device_claim_resolution';
    SELECT COUNT(*) INTO resolution_trigger_count FROM information_schema.TRIGGERS
      WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='yshop_order_print_device_claim_resolution';
    IF resolution_column_count<>12 THEN
      SET failure_detail=CONCAT('ORDER_PRINT_CLAIM_RESOLUTION_COLUMN_COUNT:',resolution_column_count);
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=failure_detail;
    END IF;
    IF matched_resolution_columns<>12 THEN
      SET failure_detail=CONCAT('ORDER_PRINT_CLAIM_RESOLUTION_COLUMN_MATCH:',matched_resolution_columns,':',COALESCE(failure_detail,''));
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=failure_detail;
    END IF;
    IF resolution_index_count<>4 OR matched_resolution_indexes<>4 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_CLAIM_RESOLUTION_INDEX_SCHEMA_MISMATCH';
    END IF;
    IF resolution_fk_count<>0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_CLAIM_RESOLUTION_FOREIGN_KEY_SCHEMA_MISMATCH';
    END IF;
    IF resolution_trigger_count<>0 OR COALESCE((SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
           AND TABLE_NAME='yshop_order_print_device_claim_resolution'),'')<>'InnoDB'
       OR COALESCE((SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
           AND TABLE_NAME='yshop_order_print_device_claim_resolution'),'') NOT LIKE 'utf8mb4\_%' THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_PRINT_CLAIM_RESOLUTION_TABLE_SCHEMA_MISMATCH';
    END IF;
  END IF;
END$$
DELIMITER ;
CALL quality_prepare_order_print_device_claim();
DROP PROCEDURE quality_prepare_order_print_device_claim;
-- MYSQL_PREFLIGHT_END

CREATE TABLE IF NOT EXISTS yshop_order_print_device_claim (
  tenant_id BIGINT NOT NULL,
  printer_id BIGINT NOT NULL,
  job_id BIGINT NOT NULL,
  lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  shop_id BIGINT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (printer_id),
  UNIQUE KEY uk_order_print_device_claim_job (tenant_id,job_id),
  KEY idx_order_print_device_claim_printer (printer_id,tenant_id),
  CONSTRAINT fk_order_print_device_claim_printer FOREIGN KEY (printer_id,tenant_id)
    REFERENCES yshop_printer (id,tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A stale UNKNOWN send can be released only by an explicit, audited operator
-- confirmation that the old sender stopped and the provider/device confirms no
-- receipt was produced. This never expires claims automatically.
CREATE TABLE IF NOT EXISTS yshop_order_print_device_claim_resolution (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  printer_id BIGINT NOT NULL,
  job_id BIGINT NOT NULL,
  request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  outcome VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  claim_was_present TINYINT(1) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  actor_id BIGINT NOT NULL,
  prior_error VARCHAR(64) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_print_claim_resolution_request (tenant_id,request_key),
  UNIQUE KEY uk_print_claim_resolution_job (tenant_id,job_id),
  KEY idx_print_claim_resolution_printer (tenant_id,printer_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
