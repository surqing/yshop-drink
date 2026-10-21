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
