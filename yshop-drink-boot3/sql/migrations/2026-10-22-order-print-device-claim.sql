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
