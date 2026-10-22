-- Apply after the original database import, before starting the new backend.
-- Additive and repeatable. Old uniprint_id values and unrelated menus are not rewritten.
-- MYSQL_PREFLIGHT_BEGIN
-- All checks finish before printer table DDL or menu writes. Never repair partial schemas.
DROP PROCEDURE IF EXISTS quality_check_printer_management;
DELIMITER $$
CREATE PROCEDURE quality_check_printer_management()
BEGIN
  DECLARE matched INT DEFAULT 0;
  DECLARE printer_tables INT DEFAULT 0;
  DECLARE operation_columns INT DEFAULT 0;
  IF (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
      AND TABLE_NAME IN ('system_menu','yshop_store_shop') AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB')<>2 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_LEGACY_SCHEMA_REQUIRED';
  END IF;
    SELECT COUNT(*) INTO matched FROM (
      SELECT 'system_menu' tbl,'id' col,'bigint' kind,'NO' nullable,NULL def,NULL coll,'auto_increment' extra
      UNION ALL SELECT 'system_menu','name','varchar(50)','NO',NULL,'utf8mb4_unicode_ci',''
      UNION ALL SELECT 'system_menu','permission','varchar(100)','NO','','utf8mb4_unicode_ci',''
      UNION ALL SELECT 'system_menu','type','tinyint','NO',NULL,NULL,''
      UNION ALL SELECT 'system_menu','sort','int','NO','0',NULL,''
      UNION ALL SELECT 'system_menu','parent_id','bigint','NO','0',NULL,''
      UNION ALL SELECT 'system_menu','path','varchar(200)','YES','','utf8mb4_unicode_ci',''
      UNION ALL SELECT 'system_menu','icon','varchar(100)','YES','#','utf8mb4_unicode_ci',''
      UNION ALL SELECT 'system_menu','component','varchar(255)','YES',NULL,'utf8mb4_unicode_ci',''
      UNION ALL SELECT 'system_menu','component_name','varchar(255)','YES',NULL,'utf8mb4_unicode_ci',''
      UNION ALL SELECT 'system_menu','status','tinyint','NO','0',NULL,''
      UNION ALL SELECT 'system_menu','visible','bit(1)','NO','b''1''',NULL,''
      UNION ALL SELECT 'system_menu','keep_alive','bit(1)','NO','b''1''',NULL,''
      UNION ALL SELECT 'system_menu','always_show','bit(1)','NO','b''1''',NULL,''
      UNION ALL SELECT 'system_menu','creator','varchar(64)','YES','','utf8mb4_unicode_ci',''
      UNION ALL SELECT 'system_menu','create_time','datetime','NO','CURRENT_TIMESTAMP',NULL,''
      UNION ALL SELECT 'system_menu','updater','varchar(64)','YES','','utf8mb4_unicode_ci',''
      UNION ALL SELECT 'system_menu','update_time','datetime','NO','CURRENT_TIMESTAMP',NULL,'DEFAULT_GENERATED on update CURRENT_TIMESTAMP'
      UNION ALL SELECT 'system_menu','deleted','bit(1)','NO','b''0''',NULL,''
      UNION ALL SELECT 'yshop_store_shop','id','bigint','NO',NULL,NULL,'auto_increment'
      UNION ALL SELECT 'yshop_store_shop','name','varchar(100)','NO',NULL,'utf8mb4_unicode_ci',''
      UNION ALL SELECT 'yshop_store_shop','mobile','varchar(20)','NO',NULL,'utf8mb4_unicode_ci',''
      UNION ALL SELECT 'yshop_store_shop','image','varchar(255)','NO','','utf8mb4_unicode_ci',''
      UNION ALL SELECT 'yshop_store_shop','images','text','NO',NULL,'utf8mb4_unicode_ci',''
      UNION ALL SELECT 'yshop_store_shop','address','varchar(255)','NO',NULL,'utf8mb4_unicode_ci',''
      UNION ALL SELECT 'yshop_store_shop','address_map','varchar(255)','NO',NULL,'utf8mb4_unicode_ci',''
      UNION ALL SELECT 'yshop_store_shop','start_time','datetime','NO',NULL,NULL,''
      UNION ALL SELECT 'yshop_store_shop','end_time','datetime','NO',NULL,NULL,''
      UNION ALL SELECT 'yshop_store_shop','lng','varchar(30)','NO',NULL,'utf8mb4_unicode_ci',''
      UNION ALL SELECT 'yshop_store_shop','lat','varchar(30)','NO',NULL,'utf8mb4_unicode_ci',''
      UNION ALL SELECT 'yshop_store_shop','distance','int','NO','0',NULL,''
      UNION ALL SELECT 'yshop_store_shop','min_price','decimal(10,2)','NO','0.00',NULL,''
      UNION ALL SELECT 'yshop_store_shop','delivery_price','decimal(10,2)','NO','0.00',NULL,''
      UNION ALL SELECT 'yshop_store_shop','notice','varchar(255)','NO','','utf8mb4_unicode_ci',''
      UNION ALL SELECT 'yshop_store_shop','status','tinyint(1)','NO','0',NULL,''
      UNION ALL SELECT 'yshop_store_shop','admin_id','varchar(100)','NO','','utf8mb4_unicode_ci',''
      UNION ALL SELECT 'yshop_store_shop','uniprint_id','varchar(100)','NO','','utf8mb4_unicode_ci',''
      UNION ALL SELECT 'yshop_store_shop','create_time','datetime','YES',NULL,NULL,''
      UNION ALL SELECT 'yshop_store_shop','update_time','datetime','YES',NULL,NULL,''
      UNION ALL SELECT 'yshop_store_shop','creator','varchar(64)','YES',NULL,'utf8mb3_general_ci',''
      UNION ALL SELECT 'yshop_store_shop','updater','varchar(64)','YES',NULL,'utf8mb3_general_ci',''
      UNION ALL SELECT 'yshop_store_shop','deleted','bit(1)','YES','b''0''',NULL,''
    ) expected JOIN information_schema.COLUMNS actual ON actual.TABLE_SCHEMA=DATABASE()
      AND actual.TABLE_NAME=expected.tbl AND actual.COLUMN_NAME=expected.col
      AND actual.COLUMN_TYPE=expected.kind AND actual.IS_NULLABLE=expected.nullable
      AND (CASE WHEN expected.def='CURRENT_TIMESTAMP' THEN UPPER(REPLACE(actual.COLUMN_DEFAULT,'()',''))='CURRENT_TIMESTAMP'
           ELSE BINARY actual.COLUMN_DEFAULT <=> BINARY expected.def END)
      AND (expected.coll IS NULL OR REPLACE(actual.COLLATION_NAME,'utf8_','utf8mb3_')=expected.coll)
      AND (CASE WHEN expected.extra<>'' THEN actual.EXTRA=expected.extra
           ELSE actual.EXTRA IN ('','DEFAULT_GENERATED') END);
    IF matched<>42 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_LEGACY_SCHEMA_MISMATCH';
    END IF;

  IF (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
      AND TABLE_NAME IN ('system_menu','yshop_store_shop') AND INDEX_NAME='PRIMARY' AND SEQ_IN_INDEX=1
      AND COLUMN_NAME='id' AND NON_UNIQUE=0 AND SUB_PART IS NULL AND IS_VISIBLE='YES')<>2
    OR (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
      AND TABLE_NAME IN ('system_menu','yshop_store_shop') AND INDEX_NAME='PRIMARY')<>2
    OR EXISTS(SELECT 1 FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE IN ('system_menu','yshop_store_shop')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_LEGACY_SCHEMA_MISMATCH';
  END IF;
  IF (SELECT COUNT(*) FROM system_menu WHERE id=2000 AND type=1 AND parent_id=0 AND BINARY path=BINARY '/mall'
      AND permission='' AND COALESCE(component,'')='' AND deleted=b'0' AND status=0)<>1
    OR (SELECT COUNT(*) FROM system_menu WHERE id=2308 AND type=2 AND parent_id=2000 AND BINARY path=BINARY 'web-print'
      AND permission='' AND ((BINARY component=BINARY 'store/webPrint/index' AND BINARY component_name=BINARY 'WebPrint')
        OR (BINARY component=BINARY 'mall/store/printer/index' AND BINARY component_name=BINARY 'StorePrinter')))<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_MENU_IDENTITY_CONFLICT';
  END IF;
  SELECT COUNT(*) INTO matched FROM (
      SELECT '2309' id,'store:web-print:query' permission
      UNION ALL SELECT '2310','store:web-print:create'
      UNION ALL SELECT '2311','store:web-print:update'
      UNION ALL SELECT '2312','store:web-print:delete'
      UNION ALL SELECT '2313','store:web-print:export'
  ) expected JOIN system_menu actual ON actual.id=expected.id AND BINARY actual.permission=BINARY expected.permission
    AND actual.type=3 AND actual.parent_id=2308 AND COALESCE(actual.path,'')='' AND COALESCE(actual.component,'')='';
  IF matched<>5 OR (SELECT COUNT(*) FROM system_menu WHERE permission IN ('store:web-print:query','store:web-print:create','store:web-print:update','store:web-print:delete','store:web-print:export'))<>5 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_MENU_IDENTITY_CONFLICT';
  END IF;
  IF EXISTS(SELECT 1 FROM system_menu WHERE permission IN ('store:web-print:authorize','store:web-print:binding','store:web-print:test')
      AND (type<>3 OR parent_id<>2308 OR COALESCE(path,'')<>'' OR COALESCE(component,'')<>''
        OR BINARY permission NOT IN ('store:web-print:authorize','store:web-print:binding','store:web-print:test')))
    OR EXISTS(SELECT permission FROM system_menu WHERE permission IN ('store:web-print:authorize','store:web-print:binding','store:web-print:test') GROUP BY permission HAVING COUNT(*)<>1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_MENU_IDENTITY_CONFLICT';
  END IF;
  SELECT COUNT(*) INTO printer_tables FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('yshop_printer','yshop_printer_binding','yshop_printer_test_job','yshop_printer_provider_token');
  IF printer_tables NOT IN (0,4) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_PARTIAL_SCHEMA';
  END IF;
  IF printer_tables=4 THEN
    IF (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('yshop_printer','yshop_printer_binding','yshop_printer_test_job','yshop_printer_provider_token')
        AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB' AND TABLE_COLLATION='utf8mb4_0900_ai_ci')<>4
      OR EXISTS(SELECT 1 FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE IN ('yshop_printer','yshop_printer_binding','yshop_printer_test_job','yshop_printer_provider_token')) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_SCHEMA_MISMATCH';
    END IF;
    SELECT COUNT(*) INTO matched FROM (
      SELECT 'yshop_printer' tbl,'id' col,'bigint' kind,'NO' nullable,NULL def,NULL coll,'auto_increment' extra
      UNION ALL SELECT 'yshop_printer','tenant_id','bigint','NO',NULL,NULL,''
      UNION ALL SELECT 'yshop_printer','name','varchar(80)','NO',NULL,'utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer','provider_code','varchar(40)','NO',NULL,'ascii_bin',''
      UNION ALL SELECT 'yshop_printer','device_code','varchar(100)','NO',NULL,'ascii_bin',''
      UNION ALL SELECT 'yshop_printer','model','varchar(80)','YES',NULL,'utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer','connection_type','varchar(40)','NO',NULL,'utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer','enabled','tinyint(1)','NO','1',NULL,''
      UNION ALL SELECT 'yshop_printer','authorization','varchar(20)','NO','UNAUTHORIZED','utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer','authorization_operation','varchar(36)','YES',NULL,'utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer','authorization_operation_at','datetime','YES',NULL,NULL,''
      UNION ALL SELECT 'yshop_printer','authorization_operation_type','varchar(32)','YES',NULL,'utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer','authorization_operation_request_id','varchar(64)','YES',NULL,'ascii_bin',''
      UNION ALL SELECT 'yshop_printer','device_state','varchar(20)','NO','UNKNOWN','utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer','state_checked_at','datetime','YES',NULL,NULL,''
      UNION ALL SELECT 'yshop_printer','encrypted_config','text','YES',NULL,'utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer','configured_fields','varchar(500)','NO','[]','utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer','last_error','varchar(100)','YES',NULL,'utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer','creator','varchar(64)','NO','','utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer','updater','varchar(64)','NO','','utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer','create_time','datetime','NO','CURRENT_TIMESTAMP',NULL,''
      UNION ALL SELECT 'yshop_printer','update_time','datetime','NO','CURRENT_TIMESTAMP',NULL,''
      UNION ALL SELECT 'yshop_printer','deleted','tinyint(1)','NO','0',NULL,''
      UNION ALL SELECT 'yshop_printer_binding','printer_id','bigint','NO',NULL,NULL,''
      UNION ALL SELECT 'yshop_printer_binding','tenant_id','bigint','NO',NULL,NULL,''
      UNION ALL SELECT 'yshop_printer_binding','shop_id','bigint','NO',NULL,NULL,''
      UNION ALL SELECT 'yshop_printer_binding','creator','varchar(64)','NO','','utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer_binding','create_time','datetime','NO','CURRENT_TIMESTAMP',NULL,''
      UNION ALL SELECT 'yshop_printer_test_job','id','bigint','NO',NULL,NULL,'auto_increment'
      UNION ALL SELECT 'yshop_printer_test_job','tenant_id','bigint','NO',NULL,NULL,''
      UNION ALL SELECT 'yshop_printer_test_job','request_id','varchar(64)','NO',NULL,'ascii_bin',''
      UNION ALL SELECT 'yshop_printer_test_job','printer_id','bigint','NO',NULL,NULL,''
      UNION ALL SELECT 'yshop_printer_test_job','state','varchar(20)','NO',NULL,'utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer_test_job','provider_job_id','varchar(100)','YES',NULL,'utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer_test_job','error_code','varchar(100)','YES',NULL,'utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer_test_job','creator','varchar(64)','NO','','utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer_test_job','create_time','datetime','NO','CURRENT_TIMESTAMP',NULL,''
      UNION ALL SELECT 'yshop_printer_provider_token','account_key','varchar(64)','NO',NULL,'ascii_bin',''
      UNION ALL SELECT 'yshop_printer_provider_token','encrypted_token','text','YES',NULL,'utf8mb4_0900_ai_ci',''
      UNION ALL SELECT 'yshop_printer_provider_token','update_time','datetime','NO','CURRENT_TIMESTAMP',NULL,''
    ) expected JOIN information_schema.COLUMNS actual ON actual.TABLE_SCHEMA=DATABASE()
      AND actual.TABLE_NAME=expected.tbl AND actual.COLUMN_NAME=expected.col
      AND actual.COLUMN_TYPE=expected.kind AND actual.IS_NULLABLE=expected.nullable
      AND (CASE WHEN expected.def='CURRENT_TIMESTAMP' THEN UPPER(REPLACE(actual.COLUMN_DEFAULT,'()',''))='CURRENT_TIMESTAMP'
           ELSE BINARY actual.COLUMN_DEFAULT <=> BINARY expected.def END)
      AND (expected.coll IS NULL OR REPLACE(actual.COLLATION_NAME,'utf8_','utf8mb3_')=expected.coll)
      AND (CASE WHEN expected.extra<>'' THEN actual.EXTRA=expected.extra
           ELSE actual.EXTRA IN ('','DEFAULT_GENERATED') END);
    SELECT COUNT(*) INTO operation_columns FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_printer'
      AND COLUMN_NAME IN ('authorization_operation_type','authorization_operation_request_id');
    IF matched NOT IN (38,40) OR operation_columns NOT IN (0,2)
      OR (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('yshop_printer','yshop_printer_binding','yshop_printer_test_job','yshop_printer_provider_token')) NOT IN (38,40)
      OR matched<>(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('yshop_printer','yshop_printer_binding','yshop_printer_test_job','yshop_printer_provider_token')) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_SCHEMA_MISMATCH';
    END IF;

    SELECT COUNT(*) INTO matched FROM (
      SELECT 'yshop_printer' tbl,'PRIMARY' name,'1' seq,'id' col,'0' non_unique
      UNION ALL SELECT 'yshop_printer','uk_printer_identity','1','provider_code','0'
      UNION ALL SELECT 'yshop_printer','uk_printer_identity','2','device_code','0'
      UNION ALL SELECT 'yshop_printer','uk_printer_tenant','1','id','0'
      UNION ALL SELECT 'yshop_printer','uk_printer_tenant','2','tenant_id','0'
      UNION ALL SELECT 'yshop_printer','idx_printer_tenant','1','tenant_id','1'
      UNION ALL SELECT 'yshop_printer','idx_printer_tenant','2','deleted','1'
      UNION ALL SELECT 'yshop_printer_binding','PRIMARY','1','printer_id','0'
      UNION ALL SELECT 'yshop_printer_binding','idx_printer_binding_shop','1','shop_id','1'
      UNION ALL SELECT 'yshop_printer_binding','fk_printer_binding_device','1','printer_id','1'
      UNION ALL SELECT 'yshop_printer_binding','fk_printer_binding_device','2','tenant_id','1'
      UNION ALL SELECT 'yshop_printer_test_job','PRIMARY','1','id','0'
      UNION ALL SELECT 'yshop_printer_test_job','uk_printer_test_request','1','tenant_id','0'
      UNION ALL SELECT 'yshop_printer_test_job','uk_printer_test_request','2','request_id','0'
      UNION ALL SELECT 'yshop_printer_test_job','idx_printer_test_device','1','tenant_id','1'
      UNION ALL SELECT 'yshop_printer_test_job','idx_printer_test_device','2','printer_id','1'
      UNION ALL SELECT 'yshop_printer_test_job','idx_printer_test_device','3','id','1'
      UNION ALL SELECT 'yshop_printer_test_job','fk_printer_test_device','1','printer_id','1'
      UNION ALL SELECT 'yshop_printer_test_job','fk_printer_test_device','2','tenant_id','1'
      UNION ALL SELECT 'yshop_printer_provider_token','PRIMARY','1','account_key','0'
    ) expected JOIN information_schema.STATISTICS actual ON actual.TABLE_SCHEMA=DATABASE()
      AND actual.TABLE_NAME=expected.tbl AND actual.INDEX_NAME=expected.name AND actual.SEQ_IN_INDEX=expected.seq
      AND actual.COLUMN_NAME=expected.col AND actual.NON_UNIQUE=expected.non_unique AND actual.SUB_PART IS NULL
      AND actual.INDEX_TYPE='BTREE' AND actual.IS_VISIBLE='YES' AND actual.COLLATION='A' AND actual.EXPRESSION IS NULL;
    IF matched<>20 OR (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('yshop_printer','yshop_printer_binding','yshop_printer_test_job','yshop_printer_provider_token'))<>20 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_INDEX_MISMATCH';
    END IF;
    SELECT COUNT(*) INTO matched FROM (
      SELECT 'yshop_printer' tbl,'PRIMARY' name,'PRIMARY KEY' kind
      UNION ALL SELECT 'yshop_printer','uk_printer_identity','UNIQUE'
      UNION ALL SELECT 'yshop_printer','uk_printer_tenant','UNIQUE'
      UNION ALL SELECT 'yshop_printer','chk_printer_authorization','CHECK'
      UNION ALL SELECT 'yshop_printer','chk_printer_state','CHECK'
      UNION ALL SELECT 'yshop_printer_binding','PRIMARY','PRIMARY KEY'
      UNION ALL SELECT 'yshop_printer_binding','fk_printer_binding_device','FOREIGN KEY'
      UNION ALL SELECT 'yshop_printer_binding','fk_printer_binding_shop','FOREIGN KEY'
      UNION ALL SELECT 'yshop_printer_test_job','PRIMARY','PRIMARY KEY'
      UNION ALL SELECT 'yshop_printer_test_job','uk_printer_test_request','UNIQUE'
      UNION ALL SELECT 'yshop_printer_test_job','fk_printer_test_device','FOREIGN KEY'
      UNION ALL SELECT 'yshop_printer_test_job','chk_printer_job_state','CHECK'
      UNION ALL SELECT 'yshop_printer_provider_token','PRIMARY','PRIMARY KEY'
    ) expected JOIN information_schema.TABLE_CONSTRAINTS actual ON actual.CONSTRAINT_SCHEMA=DATABASE()
      AND actual.TABLE_NAME=expected.tbl AND actual.CONSTRAINT_NAME=expected.name AND actual.CONSTRAINT_TYPE=expected.kind AND actual.ENFORCED='YES';
    IF matched<>13 OR (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME IN ('yshop_printer','yshop_printer_binding','yshop_printer_test_job','yshop_printer_provider_token'))<>13 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_CONSTRAINT_MISMATCH';
    END IF;
    SELECT COUNT(*) INTO matched FROM (
      SELECT 'yshop_printer_binding' tbl,'fk_printer_binding_device' name,'1' seq,'printer_id' col,'yshop_printer' ref_table,'id' ref_col
      UNION ALL SELECT 'yshop_printer_binding','fk_printer_binding_device','2','tenant_id','yshop_printer','tenant_id'
      UNION ALL SELECT 'yshop_printer_binding','fk_printer_binding_shop','1','shop_id','yshop_store_shop','id'
      UNION ALL SELECT 'yshop_printer_test_job','fk_printer_test_device','1','printer_id','yshop_printer','id'
      UNION ALL SELECT 'yshop_printer_test_job','fk_printer_test_device','2','tenant_id','yshop_printer','tenant_id'
    ) expected JOIN information_schema.KEY_COLUMN_USAGE actual ON actual.CONSTRAINT_SCHEMA=DATABASE()
      AND actual.TABLE_NAME=expected.tbl AND actual.CONSTRAINT_NAME=expected.name AND actual.ORDINAL_POSITION=expected.seq
      AND actual.COLUMN_NAME=expected.col AND actual.REFERENCED_TABLE_SCHEMA=DATABASE()
      AND actual.REFERENCED_TABLE_NAME=expected.ref_table AND actual.REFERENCED_COLUMN_NAME=expected.ref_col
      AND actual.POSITION_IN_UNIQUE_CONSTRAINT=expected.seq;
    IF matched<>5 OR (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME IN ('yshop_printer','yshop_printer_binding','yshop_printer_test_job','yshop_printer_provider_token') AND REFERENCED_TABLE_NAME IS NOT NULL)<>5
      OR (SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME IN ('yshop_printer','yshop_printer_binding','yshop_printer_test_job','yshop_printer_provider_token')
        AND REFERENCED_TABLE_NAME IN ('yshop_printer','yshop_store_shop') AND UPDATE_RULE IN ('RESTRICT','NO ACTION') AND DELETE_RULE IN ('RESTRICT','NO ACTION'))<>3 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_FOREIGN_KEY_MISMATCH';
    END IF;
    SELECT COUNT(*) INTO matched FROM (
      SELECT 'yshop_printer' tbl,'chk_printer_authorization' name,'authorizationin''UNAUTHORIZED'',''AUTHORIZED'',''UNKNOWN''' clause
      UNION ALL SELECT 'yshop_printer','chk_printer_state','device_statein''ONLINE'',''OFFLINE'',''PAPER_OUT'',''UNKNOWN'''
      UNION ALL SELECT 'yshop_printer_test_job','chk_printer_job_state','statein''SUBMITTING'',''ACCEPTED'',''FAILED'',''UNKNOWN'''
    ) expected JOIN information_schema.TABLE_CONSTRAINTS tc ON tc.CONSTRAINT_SCHEMA=DATABASE()
      AND tc.TABLE_NAME=expected.tbl AND tc.CONSTRAINT_NAME=expected.name AND tc.CONSTRAINT_TYPE='CHECK' AND tc.ENFORCED='YES'
      JOIN information_schema.CHECK_CONSTRAINTS actual ON actual.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND actual.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
      AND BINARY REGEXP_REPLACE(REPLACE(REPLACE(actual.CHECK_CLAUSE,'_utf8mb4',''),CHAR(92),''),'[`()[:space:]]','')=BINARY expected.clause;
    IF matched<>3 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PRINTER_CHECK_MISMATCH';
    END IF;
  END IF;
END$$
DELIMITER ;
CALL quality_check_printer_management();
DROP PROCEDURE quality_check_printer_management;
-- MYSQL_PREFLIGHT_END

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
  authorization_operation_type varchar(32) DEFAULT NULL,
  authorization_operation_request_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS yshop_printer_provider_token (
  account_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  encrypted_token text DEFAULT NULL,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (account_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

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
