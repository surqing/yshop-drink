package co.yixiang.yshop.module.store.printer;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** MySQL-only acceptance: production preflight, upstream DDL, and fail-before-write oracles. */
class PrinterMigrationDatabaseTest {
    JdbcTemplate jdbc;
    @BeforeEach void fixture() throws Exception { jdbc=PrinterMigrationSupport.mysql(); PrinterMigrationSupport.upstream(jdbc); }
    @AfterEach void cleanup() throws Exception { PrinterMigrationSupport.upstream(jdbc); PrinterMigrationSupport.migrate(jdbc,true); }
    void migrate() throws Exception { PrinterMigrationSupport.migrate(jdbc,true); }
    Map<String,Object> snapshot() {
        Map<String,Object> result=new TreeMap<>();
        for(String name:jdbc.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME",String.class)) {
            result.put(name+".ddl",jdbc.queryForMap("SHOW CREATE TABLE "+name).get("Create Table"));
            result.put(name+".data",jdbc.queryForList("SELECT * FROM "+name));
        }
        result.put("triggers",jdbc.queryForList("SELECT TRIGGER_NAME,ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() ORDER BY TRIGGER_NAME"));
        return result;
    }
    void rejectedUnchanged() {
        var before=snapshot();
        assertThrows(Exception.class,this::migrate);
        assertEquals(before,snapshot(),"Rejected migration must preserve all business DDL, rows, and menus");
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
            assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'yshop_printer%' AND ENGINE='InnoDB'",Integer.class));
            assertEquals("mall/store/printer/index",jdbc.queryForObject("SELECT component FROM system_menu WHERE id=2308",String.class));
            assertEquals(1,jdbc.queryForObject("SELECT deleted+0 FROM system_menu WHERE id=2313",Integer.class));
        } finally {jdbc.execute("DROP TABLE system_role_menu");}
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
