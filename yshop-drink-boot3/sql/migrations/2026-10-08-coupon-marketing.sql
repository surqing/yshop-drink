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
