-- R4 product-domain batch stock receipts. Existing operation history and inventory rows stay authoritative.
SET @r4_batch_ddl = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_product_operation' AND COLUMN_NAME='delta_value')=0,
 'ALTER TABLE yshop_product_operation ADD COLUMN delta_value INT NULL',
 'SELECT 1');
PREPARE r4_batch_stmt FROM @r4_batch_ddl;
EXECUTE r4_batch_stmt;
DEALLOCATE PREPARE r4_batch_stmt;

SET @r4_batch_ddl = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_product_operation' AND COLUMN_NAME='delta_value' AND LOWER(COLUMN_TYPE)='int' AND IS_NULLABLE='YES')=1,
 'SELECT 1',
 'SELECT * FROM R4_BATCH_DELTA_SCHEMA_INCOMPATIBLE');
PREPARE r4_batch_stmt FROM @r4_batch_ddl;
EXECUTE r4_batch_stmt;
DEALLOCATE PREPARE r4_batch_stmt;

SET @r4_batch_ddl = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_product_operation' AND COLUMN_NAME='batch_id')=0,
 'ALTER TABLE yshop_product_operation ADD COLUMN batch_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL',
 'SELECT 1');
PREPARE r4_batch_stmt FROM @r4_batch_ddl;
EXECUTE r4_batch_stmt;
DEALLOCATE PREPARE r4_batch_stmt;

SET @r4_batch_ddl = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_product_operation' AND COLUMN_NAME='batch_id' AND LOWER(COLUMN_TYPE)='char(32)' AND IS_NULLABLE='YES' AND CHARACTER_SET_NAME='ascii' AND COLLATION_NAME='ascii_bin')=1,
 'SELECT 1',
 'SELECT * FROM R4_BATCH_OPERATION_SCHEMA_INCOMPATIBLE');
PREPARE r4_batch_stmt FROM @r4_batch_ddl;
EXECUTE r4_batch_stmt;
DEALLOCATE PREPARE r4_batch_stmt;

SET @r4_batch_ddl = IF(
 (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_product_operation' AND INDEX_NAME='idx_product_operation_batch_id')=0,
 'CREATE INDEX idx_product_operation_batch_id ON yshop_product_operation(batch_id)',
 'SELECT 1');
PREPARE r4_batch_stmt FROM @r4_batch_ddl;
EXECUTE r4_batch_stmt;
DEALLOCATE PREPARE r4_batch_stmt;

SET @r4_batch_ddl = IF(
 (SELECT COUNT(*)=1 AND MIN(NON_UNIQUE)=1 AND SUM(CASE WHEN COLUMN_NAME='batch_id' AND SEQ_IN_INDEX=1 THEN 1 ELSE 0 END)=1
  FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_product_operation' AND INDEX_NAME='idx_product_operation_batch_id'),
 'SELECT 1',
 'SELECT * FROM R4_BATCH_OPERATION_INDEX_INCOMPATIBLE');
PREPARE r4_batch_stmt FROM @r4_batch_ddl;
EXECUTE r4_batch_stmt;
DEALLOCATE PREPARE r4_batch_stmt;

CREATE TABLE IF NOT EXISTS yshop_product_stock_batch (
 batch_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id BIGINT NOT NULL,
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 shop_id BIGINT NOT NULL,
 state VARCHAR(16) NOT NULL,
 result_json MEDIUMTEXT NULL,
 applied_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(batch_id),
 UNIQUE KEY uk_product_stock_batch_actor_key(actor_id,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @r4_batch_table_ok = (
 SELECT COUNT(*)=9
    AND SUM(CASE WHEN COLUMN_NAME='batch_id' AND LOWER(COLUMN_TYPE)='char(32)' AND IS_NULLABLE='NO' AND CHARACTER_SET_NAME='ascii' AND COLLATION_NAME='ascii_bin' THEN 1 ELSE 0 END)=1
    AND SUM(CASE WHEN COLUMN_NAME='actor_id' AND LOWER(COLUMN_TYPE)='bigint' AND IS_NULLABLE='NO' THEN 1 ELSE 0 END)=1
    AND SUM(CASE WHEN COLUMN_NAME='request_key' AND LOWER(COLUMN_TYPE)='varchar(64)' AND IS_NULLABLE='NO' AND CHARACTER_SET_NAME='ascii' AND COLLATION_NAME='ascii_bin' THEN 1 ELSE 0 END)=1
    AND SUM(CASE WHEN COLUMN_NAME='request_hash' AND LOWER(COLUMN_TYPE)='char(64)' AND IS_NULLABLE='NO' AND CHARACTER_SET_NAME='ascii' AND COLLATION_NAME='ascii_bin' THEN 1 ELSE 0 END)=1
    AND SUM(CASE WHEN COLUMN_NAME='shop_id' AND LOWER(COLUMN_TYPE)='bigint' AND IS_NULLABLE='NO' THEN 1 ELSE 0 END)=1
    AND SUM(CASE WHEN COLUMN_NAME='state' AND LOWER(COLUMN_TYPE)='varchar(16)' AND IS_NULLABLE='NO' THEN 1 ELSE 0 END)=1
    AND SUM(CASE WHEN COLUMN_NAME='result_json' AND LOWER(COLUMN_TYPE)='mediumtext' AND IS_NULLABLE='YES' THEN 1 ELSE 0 END)=1
    AND SUM(CASE WHEN COLUMN_NAME='applied_at' AND LOWER(COLUMN_TYPE)='datetime' AND IS_NULLABLE='YES' THEN 1 ELSE 0 END)=1
    AND SUM(CASE WHEN COLUMN_NAME='created_at' AND LOWER(COLUMN_TYPE)='datetime' AND IS_NULLABLE='NO'
      AND UPPER(COALESCE(COLUMN_DEFAULT,''))='CURRENT_TIMESTAMP'
      AND LOWER(EXTRA)='default_generated' AND GENERATION_EXPRESSION='' THEN 1 ELSE 0 END)=1
 FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_product_stock_batch');
SET @r4_batch_ddl = IF(@r4_batch_table_ok,
 'SELECT 1',
 'SELECT * FROM R4_BATCH_HEADER_SCHEMA_INCOMPATIBLE');
PREPARE r4_batch_stmt FROM @r4_batch_ddl;
EXECUTE r4_batch_stmt;
DEALLOCATE PREPARE r4_batch_stmt;

SET @r4_batch_index_ok = (
 SELECT COUNT(*)=3 AND COUNT(DISTINCT INDEX_NAME)=2
   AND SUM(CASE WHEN INDEX_NAME='PRIMARY' AND SEQ_IN_INDEX=1 AND COLUMN_NAME='batch_id' AND NON_UNIQUE=0 THEN 1 ELSE 0 END)=1
   AND SUM(CASE WHEN INDEX_NAME='uk_product_stock_batch_actor_key' AND SEQ_IN_INDEX=1 AND COLUMN_NAME='actor_id' AND NON_UNIQUE=0 THEN 1 ELSE 0 END)=1
   AND SUM(CASE WHEN INDEX_NAME='uk_product_stock_batch_actor_key' AND SEQ_IN_INDEX=2 AND COLUMN_NAME='request_key' AND NON_UNIQUE=0 THEN 1 ELSE 0 END)=1
 FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_product_stock_batch');
SET @r4_batch_ddl = IF(@r4_batch_index_ok,
 'SELECT 1',
 'SELECT * FROM R4_BATCH_HEADER_INDEX_INCOMPATIBLE');
PREPARE r4_batch_stmt FROM @r4_batch_ddl;
EXECUTE r4_batch_stmt;
DEALLOCATE PREPARE r4_batch_stmt;

SET @r4_batch_engine_ok = (
 SELECT COUNT(*)=1 FROM information_schema.TABLES
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_product_stock_batch' AND ENGINE='InnoDB');
SET @r4_batch_ddl = IF(@r4_batch_engine_ok,
 'SELECT 1',
 'SELECT * FROM R4_BATCH_HEADER_ENGINE_INCOMPATIBLE');
PREPARE r4_batch_stmt FROM @r4_batch_ddl;
EXECUTE r4_batch_stmt;
DEALLOCATE PREPARE r4_batch_stmt;
