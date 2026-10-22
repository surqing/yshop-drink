-- One row reserves a printer only while a worker owns an external send. The
-- worker creates it after provider session preparation and removes it after
-- the provider call returns. A crashed/in-flight send stays fail-closed; this
-- claim is intentionally not expired or auto-retried with the job lease.
CREATE TABLE IF NOT EXISTS yshop_order_print_device_claim (
  tenant_id BIGINT NOT NULL,
  printer_id BIGINT NOT NULL,
  job_id BIGINT NOT NULL,
  lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  shop_id BIGINT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (printer_id),
  UNIQUE KEY uk_order_print_device_claim_job (tenant_id,job_id),
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
