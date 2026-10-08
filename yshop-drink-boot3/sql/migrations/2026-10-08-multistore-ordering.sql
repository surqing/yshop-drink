-- Phase 6A. Run with payment admission stopped, after the Phase 5B-5F migrations.
-- No historical order, balance, ledger or payment data is rewritten.
SET @phase6a_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_store_order' AND COLUMN_NAME='ordering_version')=0,
  'ALTER TABLE yshop_store_order ADD COLUMN ordering_version TINYINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE phase6a_stmt FROM @phase6a_sql;
EXECUTE phase6a_stmt;
DEALLOCATE PREPARE phase6a_stmt;
SET @phase6a_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_coupon_user' AND COLUMN_NAME='reserved_order_id')=0,
  'ALTER TABLE yshop_coupon_user ADD COLUMN reserved_order_id VARCHAR(32) NULL', 'SELECT 1');
PREPARE phase6a_stmt FROM @phase6a_sql;
EXECUTE phase6a_stmt;
DEALLOCATE PREPARE phase6a_stmt;

CREATE TABLE IF NOT EXISTS yshop_order_submission (
  uid BIGINT NOT NULL,
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_id VARCHAR(32) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(uid,idempotency_key), UNIQUE KEY uk_submission_order(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS yshop_order_inventory_reservation (
  order_id VARCHAR(32) NOT NULL,
  line_no INT NOT NULL,
  product_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  released_at DATETIME NULL,
  PRIMARY KEY(order_id,line_no),
  CONSTRAINT chk_order_inventory_quantity CHECK(quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Bound expiry scans and reservation lookup as the order history grows.
SET @phase6a_sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_store_order' AND INDEX_NAME='idx_ordering_expiry')=0,
  'ALTER TABLE yshop_store_order ADD INDEX idx_ordering_expiry(ordering_version,paid,deleted,status,refund_status,create_time)', 'SELECT 1');
PREPARE phase6a_stmt FROM @phase6a_sql;
EXECUTE phase6a_stmt;
DEALLOCATE PREPARE phase6a_stmt;
SET @phase6a_sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_inventory_reservation' AND INDEX_NAME='idx_inventory_product')=0,
  'ALTER TABLE yshop_order_inventory_reservation ADD INDEX idx_inventory_product(product_id,released_at)', 'SELECT 1');
PREPARE phase6a_stmt FROM @phase6a_sql;
EXECUTE phase6a_stmt;
DEALLOCATE PREPARE phase6a_stmt;
