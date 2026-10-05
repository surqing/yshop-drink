-- Apply after the original database import, before starting the new backend.
-- Additive and repeatable. Old uniprint_id values and unrelated menus are not rewritten.
CREATE TABLE IF NOT EXISTS yshop_printer (
  id bigint NOT NULL AUTO_INCREMENT,
  tenant_id bigint NOT NULL,
  name varchar(80) NOT NULL,
  provider_code varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  device_code varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  model varchar(80) DEFAULT NULL,
  connection_type varchar(40) NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  authorization varchar(20) NOT NULL DEFAULT 'UNAUTHORIZED',
  authorization_operation varchar(36) DEFAULT NULL,
  authorization_operation_at datetime DEFAULT NULL,
  device_state varchar(20) NOT NULL DEFAULT 'UNKNOWN',
  state_checked_at datetime DEFAULT NULL,
  encrypted_config text DEFAULT NULL,
  configured_fields varchar(500) NOT NULL DEFAULT '[]',
  last_error varchar(100) DEFAULT NULL,
  creator varchar(64) NOT NULL DEFAULT '',
  updater varchar(64) NOT NULL DEFAULT '',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted boolean NOT NULL DEFAULT false,
  PRIMARY KEY (id),
  UNIQUE KEY uk_printer_identity (provider_code,device_code),
  UNIQUE KEY uk_printer_tenant (id,tenant_id),
  KEY idx_printer_tenant (tenant_id,deleted),
  CONSTRAINT chk_printer_authorization CHECK (authorization IN ('UNAUTHORIZED','AUTHORIZED','UNKNOWN')),
  CONSTRAINT chk_printer_state CHECK (device_state IN ('ONLINE','OFFLINE','PAPER_OUT','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS yshop_printer_binding (
  printer_id bigint NOT NULL,
  tenant_id bigint NOT NULL,
  shop_id bigint NOT NULL,
  creator varchar(64) NOT NULL DEFAULT '',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (printer_id),
  KEY idx_printer_binding_shop (shop_id),
  CONSTRAINT fk_printer_binding_device FOREIGN KEY (printer_id,tenant_id) REFERENCES yshop_printer (id,tenant_id),
  CONSTRAINT fk_printer_binding_shop FOREIGN KEY (shop_id) REFERENCES yshop_store_shop (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS yshop_printer_test_job (
  id bigint NOT NULL AUTO_INCREMENT,
  tenant_id bigint NOT NULL,
  request_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  printer_id bigint NOT NULL,
  state varchar(20) NOT NULL,
  provider_job_id varchar(100) DEFAULT NULL,
  error_code varchar(100) DEFAULT NULL,
  creator varchar(64) NOT NULL DEFAULT '',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_printer_test_request (tenant_id,request_id),
  KEY idx_printer_test_device (tenant_id,printer_id,id),
  CONSTRAINT fk_printer_test_device FOREIGN KEY (printer_id,tenant_id) REFERENCES yshop_printer (id,tenant_id),
  CONSTRAINT chk_printer_job_state CHECK (state IN ('SUBMITTING','ACCEPTED','FAILED','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS yshop_printer_provider_token (
  account_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  encrypted_token text DEFAULT NULL,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (account_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Restore the historical menu identity; existing role assignments still apply.
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,deleted)
VALUES (2308,'打印机管理','',2,9,2000,'web-print','ep:printer','mall/store/printer/index','StorePrinter',0,b'1',b'1',b'1',b'0')
ON DUPLICATE KEY UPDATE name='打印机管理',parent_id=2000,path='web-print',icon='ep:printer',component='mall/store/printer/index',
  component_name='StorePrinter',status=0,visible=b'1',deleted=b'0';
UPDATE system_menu SET deleted=b'0',status=0 WHERE id IN (2309,2310,2311,2312) AND parent_id=2308;
UPDATE system_menu SET deleted=b'1' WHERE id=2313 AND parent_id=2308;

INSERT INTO system_menu (name,permission,type,sort,parent_id)
SELECT '打印机授权','store:web-print:authorize',3,6,2308
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission='store:web-print:authorize');
INSERT INTO system_menu (name,permission,type,sort,parent_id)
SELECT '打印机门店绑定','store:web-print:binding',3,7,2308
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission='store:web-print:binding');
INSERT INTO system_menu (name,permission,type,sort,parent_id)
SELECT '打印机测试打印','store:web-print:test',3,8,2308
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission='store:web-print:test');
UPDATE system_menu SET parent_id=2308,status=0,deleted=b'0' WHERE permission IN
  ('store:web-print:authorize','store:web-print:binding','store:web-print:test');
-- No role grants: super administrators see restored menus; other roles must be explicitly assigned.
