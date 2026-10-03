-- Apply once before deploying Phase 5B. Additive only; no existing data rewritten.
CREATE TABLE IF NOT EXISTS yshop_order_payment (
  id varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  merchant_details_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  out_trade_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider_transaction_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount_cents bigint NOT NULL,
  appid varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  mch_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  result_code varchar(16) NOT NULL,
  status varchar(40) NOT NULL,
  failure_reason varchar(64) DEFAULT NULL,
  success_order_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  received_at datetime(6) NOT NULL,
  last_seen_at datetime(6) NOT NULL,
  duplicate_count bigint NOT NULL DEFAULT 0,
  processed_at datetime(6) DEFAULT NULL,
  create_time datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  update_time datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_transaction (provider,provider_transaction_id),
  UNIQUE KEY uk_payment_success_order (success_order_id),
  KEY idx_payment_recovery (status,update_time),
  CONSTRAINT chk_payment_amount CHECK (amount_cents > 0),
  CONSTRAINT chk_payment_success CHECK ((status = 'SUCCESS' AND success_order_id IS NOT NULL AND success_order_id = order_id) OR
                                       (status <> 'SUCCESS' AND success_order_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A reused transaction must not overwrite its original, possibly successful, event.
CREATE TABLE IF NOT EXISTS yshop_order_payment_conflict (
  id varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_id varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  claimed_order_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason varchar(64) NOT NULL,
  received_at datetime(6) NOT NULL,
  PRIMARY KEY (id), KEY idx_payment_conflict_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
