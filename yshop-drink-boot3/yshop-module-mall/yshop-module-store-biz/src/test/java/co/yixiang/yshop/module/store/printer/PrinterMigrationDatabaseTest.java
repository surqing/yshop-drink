package co.yixiang.yshop.module.store.printer;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** MySQL-only acceptance: production preflight, upstream DDL, and fail-before-write oracles. */
class PrinterMigrationDatabaseTest {
    JdbcTemplate jdbc;
    SingleConnectionDataSource source;
    @BeforeEach void fixture() throws Exception { source=PrinterMigrationSupport.mysqlSingleConnection(); jdbc=new JdbcTemplate(source); PrinterMigrationSupport.upstream(jdbc); }
    @AfterEach void cleanup() throws Exception {
        try {PrinterMigrationSupport.upstream(jdbc); PrinterMigrationSupport.migrate(jdbc,true);}
        finally {if(source!=null) source.destroy();}
    }
    void migrate() throws Exception { PrinterMigrationSupport.migrate(jdbc,true); }
    Map<String,Object> snapshot() {
        Map<String,Object> result=new TreeMap<>();
        for(String name:jdbc.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME",String.class)) {
            result.put(name+".ddl",jdbc.queryForMap("SHOW CREATE TABLE "+name).get("Create Table"));
            result.put(name+".data",jdbc.queryForList("SELECT * FROM "+name));
        }
        result.put("triggers",jdbc.queryForList("SELECT TRIGGER_NAME,ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() ORDER BY TRIGGER_NAME"));
        result.put("routines",jdbc.queryForList("SELECT ROUTINE_NAME,ROUTINE_TYPE FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() ORDER BY ROUTINE_NAME"));
        return result;
    }
    void rejectedUnchanged() {
        var before=snapshot();
        assertThrows(Exception.class,this::migrate);
        assertEquals(before,snapshot(),"Rejected migration must preserve all business DDL, rows, and menus");
    }
    void rejectedRecoveryUnchanged() throws Exception {
        var before=snapshot();
        assertThrows(Exception.class,()->PrinterMigrationSupport.recoveryOnly(jdbc));
        assertEquals(before,snapshot(),"Rejected recovery migration must preserve all business DDL, rows, and menus");
    }
    @Test void firstMigrationAndCompleteRepeatPreserveLegacyDataAndRoleAssignments() throws Exception {
        jdbc.execute("CREATE TABLE system_role_menu (role_id bigint,menu_id bigint) ENGINE=InnoDB");
        try {
            jdbc.update("INSERT INTO system_role_menu VALUES (99,2308),(99,2313)");
            jdbc.update("UPDATE yshop_store_shop SET uniprint_id='synthetic-legacy-reference' WHERE id=1");
            var shops=jdbc.queryForList("SELECT * FROM yshop_store_shop ORDER BY id");
            var grants=jdbc.queryForList("SELECT * FROM system_role_menu ORDER BY menu_id");
            migrate();
            jdbc.update("INSERT INTO yshop_printer (id,tenant_id,name,provider_code,device_code,connection_type,encrypted_config) VALUES (1,7,'synthetic history','SYNTHETIC','synthetic-device','CLOUD','synthetic-encrypted-history')");
            jdbc.update("INSERT INTO yshop_printer_binding (printer_id,tenant_id,shop_id) VALUES (1,7,1)");
            jdbc.update("INSERT INTO yshop_printer_test_job (tenant_id,request_id,printer_id,state) VALUES (7,'synthetic-history-request',1,'UNKNOWN')");
            jdbc.update("INSERT INTO yshop_printer_provider_token (account_key,encrypted_token) VALUES ('synthetic-history-account','synthetic-encrypted-token')");
            var complete=snapshot(); migrate(); assertEquals(complete,snapshot());
            assertEquals(shops,jdbc.queryForList("SELECT * FROM yshop_store_shop ORDER BY id"));
            assertEquals(grants,jdbc.queryForList("SELECT * FROM system_role_menu ORDER BY menu_id"));
            assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'yshop_printer%' AND ENGINE='InnoDB'",Integer.class));
            assertEquals("InnoDB",jdbc.queryForObject("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_printer_operation_resolution'",String.class));
            assertEquals("InnoDB",jdbc.queryForObject("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_order_print_job'",String.class));
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM yshop_order_print_job",Long.class));
            assertEquals("mall/store/printer/index",jdbc.queryForObject("SELECT component FROM system_menu WHERE id=2308",String.class));
            assertEquals(1,jdbc.queryForObject("SELECT deleted+0 FROM system_menu WHERE id=2313",Integer.class));
        } finally {jdbc.execute("DROP TABLE system_role_menu");}
    }
    @Test void wrongSameNameOrderPrintJobColumnIsRejectedBeforeSchemaChanges() throws Exception {
        migrate();
        jdbc.execute("ALTER TABLE yshop_order_print_job MODIFY request_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
        rejectedUnchanged();
    }
    @Test void wrongSameNameOrderPrintJobIndexIsRejectedBeforeSchemaChanges() throws Exception {
        migrate();
        jdbc.execute("ALTER TABLE yshop_order_print_job DROP INDEX uk_order_print_request");
        jdbc.execute("ALTER TABLE yshop_order_print_job ADD UNIQUE KEY uk_order_print_request(tenant_id,order_id)");
        rejectedUnchanged();
    }
    @Test void existingPrinterSchemaUpgradePreservesOperationAndAddsRecoveryAudit() throws Exception {
        migrate();
        String token="12345678-1234-1234-1234-123456789012";
        jdbc.update("INSERT INTO yshop_printer(id,tenant_id,name,provider_code,device_code,connection_type,authorization,authorization_operation,authorization_operation_at,authorization_operation_type,authorization_operation_request_id) "
                +"VALUES(1,7,'synthetic pending','SYNTHETIC','legacy-pending-device','CLOUD','UNKNOWN',?,NOW(),'DEAUTHORIZE','legacy-revoke-request')",token);
        jdbc.execute("DROP TABLE yshop_printer_operation_resolution");
        jdbc.execute("ALTER TABLE yshop_printer DROP COLUMN authorization_operation_prior_authorization");
        migrate();
        assertEquals(token,jdbc.queryForObject("SELECT authorization_operation FROM yshop_printer WHERE id=1",String.class));
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT authorization FROM yshop_printer WHERE id=1",String.class));
        assertEquals("DEAUTHORIZE",jdbc.queryForObject("SELECT authorization_operation_type FROM yshop_printer WHERE id=1",String.class));
        assertEquals("legacy-revoke-request",jdbc.queryForObject("SELECT authorization_operation_request_id FROM yshop_printer WHERE id=1",String.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_printer' "
                +"AND COLUMN_NAME IN ('authorization_operation_type','authorization_operation_request_id','authorization_operation_prior_authorization')",Integer.class));
        assertNull(jdbc.queryForObject("SELECT authorization_operation_prior_authorization FROM yshop_printer WHERE id=1",String.class));
        assertEquals("InnoDB",jdbc.queryForObject("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_printer_operation_resolution'",String.class));
        var complete=snapshot(); migrate(); assertEquals(complete,snapshot());

        jdbc.execute("DROP TABLE yshop_printer_operation_resolution");
        jdbc.execute("ALTER TABLE yshop_printer DROP COLUMN authorization_operation_request_id, DROP COLUMN authorization_operation_type, DROP COLUMN authorization_operation_prior_authorization");
        migrate();
        assertEquals(token,jdbc.queryForObject("SELECT authorization_operation FROM yshop_printer WHERE id=1",String.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_printer' "
                +"AND COLUMN_NAME IN ('authorization_operation_type','authorization_operation_request_id','authorization_operation_prior_authorization')",Integer.class));
        var rebuilt=snapshot(); migrate(); assertEquals(rebuilt,snapshot());
    }
    @Test void partialRecoveryMetadataIsRejectedBeforeSchemaChanges() throws Exception {
        migrate(); jdbc.execute("ALTER TABLE yshop_printer DROP COLUMN authorization_operation_request_id"); rejectedRecoveryUnchanged();
    }
    @Test void wrongOperationTypeDefinitionIsRejectedByRecoveryMigrationAlone() throws Exception {
        migrate(); jdbc.execute("ALTER TABLE yshop_printer MODIFY authorization_operation_type varchar(31) DEFAULT NULL"); rejectedRecoveryUnchanged();
    }
    @Test void wrongOperationRequestIdCollationIsRejectedByRecoveryMigrationAlone() throws Exception {
        migrate(); jdbc.execute("ALTER TABLE yshop_printer MODIFY authorization_operation_request_id varchar(64) CHARACTER SET ascii COLLATE ascii_general_ci DEFAULT NULL"); rejectedRecoveryUnchanged();
    }
    @Test void priorAuthorizationDefaultIsRejectedByRecoveryMigrationAlone() throws Exception {
        migrate(); jdbc.execute("ALTER TABLE yshop_printer ALTER authorization_operation_prior_authorization SET DEFAULT 'AUTHORIZED'"); rejectedRecoveryUnchanged();
    }
    @Test void missingOperationResolutionCreateTimeDefaultIsRejectedByRecoveryMigrationAlone() throws Exception {
        migrate(); jdbc.execute("ALTER TABLE yshop_printer_operation_resolution ALTER COLUMN create_time DROP DEFAULT"); rejectedRecoveryUnchanged();
    }
    @Test void operationResolutionCreateTimeOnUpdateIsRejectedByRecoveryMigrationAlone() throws Exception {
        migrate(); jdbc.execute("ALTER TABLE yshop_printer_operation_resolution MODIFY create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"); rejectedRecoveryUnchanged();
    }
    @Test void partialOperationResolutionTableIsRejectedBeforeSchemaChanges() throws Exception {
        migrate(); jdbc.execute("DROP TABLE yshop_printer_operation_resolution");
        jdbc.execute("CREATE TABLE yshop_printer_operation_resolution(id bigint PRIMARY KEY) ENGINE=InnoDB");
        rejectedUnchanged();
    }
    @Test void partialOrderPrintDeviceClaimTableIsRejectedBeforeSchemaChanges() throws Exception {
        migrate(); jdbc.execute("DROP TABLE yshop_order_print_device_claim");
        jdbc.execute("CREATE TABLE yshop_order_print_device_claim(id bigint PRIMARY KEY) ENGINE=InnoDB");
        rejectedUnchanged();
    }
    @Test void partialOrderPrintDeviceClaimResolutionTableIsRejectedBeforeSchemaChanges() throws Exception {
        migrate(); jdbc.execute("DROP TABLE yshop_order_print_device_claim_resolution");
        jdbc.execute("CREATE TABLE yshop_order_print_device_claim_resolution(id bigint PRIMARY KEY) ENGINE=InnoDB");
        rejectedUnchanged();
    }
    @Test void missingOrderPrintDeviceClaimJobUniquenessIsRejected() throws Exception {
        migrate(); jdbc.execute("ALTER TABLE yshop_order_print_device_claim DROP INDEX uk_order_print_device_claim_job");
        rejectedUnchanged();
    }
    @Test void cascadingOrderPrintDeviceClaimForeignKeyIsRejected() throws Exception {
        migrate(); jdbc.execute("ALTER TABLE yshop_order_print_device_claim DROP FOREIGN KEY fk_order_print_device_claim_printer");
        jdbc.execute("ALTER TABLE yshop_order_print_device_claim ADD CONSTRAINT fk_order_print_device_claim_printer "
                +"FOREIGN KEY(printer_id,tenant_id) REFERENCES yshop_printer(id,tenant_id) ON DELETE CASCADE");
        rejectedUnchanged();
    }
    @Test void orderPrintDeviceClaimCreateTimeOnUpdateIsRejected() throws Exception {
        migrate(); jdbc.execute("ALTER TABLE yshop_order_print_device_claim MODIFY create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        rejectedUnchanged();
    }
    @Test void missingOrderPrintDeviceClaimResolutionJobUniquenessIsRejected() throws Exception {
        migrate(); jdbc.execute("ALTER TABLE yshop_order_print_device_claim_resolution DROP INDEX uk_print_claim_resolution_job");
        rejectedUnchanged();
    }
    @Test void prefixedOrderPrintDeviceClaimResolutionRequestUniquenessIsRejected() throws Exception {
        migrate();
        jdbc.execute("ALTER TABLE yshop_order_print_device_claim_resolution DROP INDEX uk_print_claim_resolution_request");
        jdbc.execute("ALTER TABLE yshop_order_print_device_claim_resolution ADD UNIQUE KEY uk_print_claim_resolution_request (tenant_id,request_key(8))");
        rejectedUnchanged();
    }
    @Test void missingOrderPrintDeviceClaimResolutionCreateTimeDefaultIsRejected() throws Exception {
        migrate(); jdbc.execute("ALTER TABLE yshop_order_print_device_claim_resolution ALTER COLUMN create_time DROP DEFAULT");
        rejectedUnchanged();
    }
    @Test void nonTransactionalOrderPrintDeviceClaimResolutionIsRejected() throws Exception {
        migrate(); jdbc.execute("ALTER TABLE yshop_order_print_device_claim_resolution ENGINE=MyISAM");
        rejectedUnchanged();
    }
    @Test void wrongFifteenColumnResolutionTableIsRejectedBeforeSchemaChanges() throws Exception {
        migrate(); jdbc.execute("DROP TABLE yshop_printer_operation_resolution");
        String legacyColumns=java.util.stream.IntStream.rangeClosed(1,14)
                .mapToObj(index->"legacy_"+String.format("%02d",index)+" int")
                .collect(java.util.stream.Collectors.joining(","));
        jdbc.execute("CREATE TABLE yshop_printer_operation_resolution(id bigint PRIMARY KEY,"+legacyColumns+") ENGINE=InnoDB");
        rejectedUnchanged();
    }
    @Test void missingOperationResolutionTokenUniquenessIsRejected() throws Exception {
        migrate(); jdbc.execute("ALTER TABLE yshop_printer_operation_resolution DROP INDEX uk_printer_operation_resolution_token");
        rejectedUnchanged();
    }
    @Test void cascadingOperationResolutionForeignKeyIsRejected() throws Exception {
        migrate(); jdbc.execute("ALTER TABLE yshop_printer_operation_resolution DROP FOREIGN KEY fk_printer_operation_resolution_device");
        jdbc.execute("ALTER TABLE yshop_printer_operation_resolution ADD CONSTRAINT fk_printer_operation_resolution_device "
                +"FOREIGN KEY(printer_id,tenant_id) REFERENCES yshop_printer(id,tenant_id) ON DELETE CASCADE");
        rejectedUnchanged();
    }
    @Test void partialPrinterSchemasAreRejectedBeforeCreatingTheMissingTables() throws Exception {
        migrate(); jdbc.execute("DROP TABLE yshop_printer_provider_token"); rejectedUnchanged();
    }
    @Test void missingLegacyTableIsRejectedBeforePrinterDdl() {jdbc.execute("DROP TABLE yshop_store_shop");rejectedUnchanged();}
    @Test void incompatibleLegacyKeyIsRejectedBeforePrinterDdl() {jdbc.execute("ALTER TABLE yshop_store_shop MODIFY id bigint unsigned NOT NULL AUTO_INCREMENT");rejectedUnchanged();}
    @Test void legacyNonTransactionalEngineIsRejectedBeforePrinterDdl() {jdbc.execute("ALTER TABLE yshop_store_shop ENGINE=MyISAM");rejectedUnchanged();}
    @Test void parentMenuMustKeepItsMallIdentity() {jdbc.update("UPDATE system_menu SET path='/unrelated' WHERE id=2000");rejectedUnchanged();}
    @Test void parentMenuIdentityIsCaseSensitive() {jdbc.update("UPDATE system_menu SET path='/MALL' WHERE id=2000");rejectedUnchanged();}
    @Test void missingParentMenuIsRejected() {jdbc.update("DELETE FROM system_menu WHERE id=2000");rejectedUnchanged();}
    @Test void printerMenuCannotOverwriteAnUnrelatedId() {jdbc.update("UPDATE system_menu SET component='other/page',path='other' WHERE id=2308");rejectedUnchanged();}
    @Test void missingHistoricalPrinterMenuIsRejected() {jdbc.update("DELETE FROM system_menu WHERE id=2308");rejectedUnchanged();}
    @Test void historicalPermissionIdsCannotBeRepurposed() {jdbc.update("UPDATE system_menu SET permission='store:other:delete' WHERE id=2312");rejectedUnchanged();}
    @Test void duplicateHistoricalPermissionIsRejected() {jdbc.update("INSERT INTO system_menu (name,permission,type,parent_id) VALUES ('synthetic duplicate','store:web-print:query',3,2308)");rejectedUnchanged();}
    @Test void newPermissionCannotBelongToAnotherParent() {jdbc.update("INSERT INTO system_menu (name,permission,type,parent_id) VALUES ('synthetic other','store:web-print:test',3,2000)");rejectedUnchanged();}
    @Test void newPermissionIdentityIsCaseSensitive() {jdbc.update("INSERT INTO system_menu (name,permission,type,parent_id) VALUES ('synthetic conflict','STORE:web-print:test',3,2308)");rejectedUnchanged();}
    @Test void duplicateNewPermissionIsRejected() {
        jdbc.update("INSERT INTO system_menu (name,permission,type,parent_id) VALUES ('synthetic first','store:web-print:test',3,2308),('synthetic second','store:web-print:test',3,2308)");rejectedUnchanged();
    }
    @Test void incompatibleColumnIsRejectedBeforeMenuWrites() throws Exception {migrate();jdbc.execute("ALTER TABLE yshop_printer MODIFY name varchar(79) NOT NULL");rejectedUnchanged();}
    @Test void incompatibleColumnDefaultIsRejected() throws Exception {migrate();jdbc.execute("ALTER TABLE yshop_printer ALTER authorization SET DEFAULT 'AUTHORIZED'");rejectedUnchanged();}
    @Test void incorrectBinaryIdentityCollationIsRejected() throws Exception {migrate();jdbc.execute("ALTER TABLE yshop_printer MODIFY device_code varchar(100) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL");rejectedUnchanged();}
    @Test void missingUniqueIndexIsRejected() throws Exception {migrate();jdbc.execute("ALTER TABLE yshop_printer DROP INDEX uk_printer_identity");rejectedUnchanged();}
    @Test void extraIndexIsRejected() throws Exception {migrate();jdbc.execute("CREATE INDEX unexpected_printer_index ON yshop_printer(name)");rejectedUnchanged();}
    @Test void missingForeignKeyIsRejected() throws Exception {migrate();jdbc.execute("ALTER TABLE yshop_printer_binding DROP FOREIGN KEY fk_printer_binding_shop");rejectedUnchanged();}
    @Test void cascadingForeignKeyIsRejected() throws Exception {
        migrate();jdbc.execute("ALTER TABLE yshop_printer_binding DROP FOREIGN KEY fk_printer_binding_shop");
        jdbc.execute("ALTER TABLE yshop_printer_binding ADD CONSTRAINT fk_printer_binding_shop FOREIGN KEY(shop_id) REFERENCES yshop_store_shop(id) ON DELETE CASCADE");rejectedUnchanged();
    }
    @Test void missingCheckConstraintIsRejected() throws Exception {migrate();jdbc.execute("ALTER TABLE yshop_printer DROP CHECK chk_printer_state");rejectedUnchanged();}
    @Test void alteredCheckConstraintIsRejected() throws Exception {
        migrate();jdbc.execute("ALTER TABLE yshop_printer DROP CHECK chk_printer_state");
        jdbc.execute("ALTER TABLE yshop_printer ADD CONSTRAINT chk_printer_state CHECK (device_state IN ('ONLINE','OFFLINE','PAPER_OUT','UNKNOWN','BROKEN'))");rejectedUnchanged();
    }
    @Test void unenforcedCheckConstraintIsRejected() throws Exception {migrate();jdbc.execute("ALTER TABLE yshop_printer ALTER CHECK chk_printer_state NOT ENFORCED");rejectedUnchanged();}
    @Test void printerTriggerIsRejected() throws Exception {
        migrate();jdbc.execute("CREATE TRIGGER synthetic_printer_trigger BEFORE INSERT ON yshop_printer FOR EACH ROW SET NEW.name=NEW.name");rejectedUnchanged();
    }
    @Test void nonTransactionalPrinterTableIsRejected() throws Exception {migrate();jdbc.execute("ALTER TABLE yshop_printer_provider_token ENGINE=MyISAM");rejectedUnchanged();}
}
