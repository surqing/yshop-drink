-- Phase 6B additive catalog metadata. No financial/history rewrite.
SET @catalog_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_store_product' AND COLUMN_NAME='catalog_version')=0,
 'ALTER TABLE yshop_store_product ADD COLUMN catalog_version BIGINT NOT NULL DEFAULT 0, ADD COLUMN catalog_config MEDIUMTEXT NULL', 'SELECT 1');
PREPARE catalog_stmt FROM @catalog_ddl;
EXECUTE catalog_stmt;
DEALLOCATE PREPARE catalog_stmt;
SET @catalog_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_store_product_attr_value' AND COLUMN_NAME='is_show')=0,
 'ALTER TABLE yshop_store_product_attr_value ADD COLUMN is_show TINYINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE catalog_stmt FROM @catalog_ddl;
EXECUTE catalog_stmt;
DEALLOCATE PREPARE catalog_stmt;

-- Product stock/price operation evidence, NOT a wallet/financial ledger.
CREATE TABLE IF NOT EXISTS yshop_product_operation (
 operation_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 actor_id BIGINT NOT NULL, request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) NOT NULL, product_id BIGINT NOT NULL, sku_id BIGINT NULL,
 kind VARCHAR(16) NOT NULL, before_value DECIMAL(18,2) NULL, after_value DECIMAL(18,2) NULL,
 reason VARCHAR(200) NOT NULL, result_product_id BIGINT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_product_operation(actor_id,request_key), KEY idx_product_history(product_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
