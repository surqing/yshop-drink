package co.yixiang.yshop.module.pay.credential;

import co.yixiang.yshop.module.pay.service.merchantdetails.MerchantDetailsServiceImpl;
import co.yixiang.yshop.module.pay.controller.admin.merchantdetails.vo.*;
import co.yixiang.yshop.module.pay.dal.mysql.merchantdetails.MerchantDetailsMapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.egzosn.pay.spring.boot.core.configurers.PayMessageConfigurer;
import com.egzosn.pay.spring.boot.core.merchant.bean.CommonPaymentPlatformMerchantDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PaymentCredentialDatabaseTest {
    AnnotationConfigApplicationContext ctx;
    JdbcTemplate jdbc;
    PaymentCredentialCryptoService crypto;
    MerchantDetailsServiceImpl service;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporaryDirectory;
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() { var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1"); return ds; }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager tx(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean PaymentCredentialCryptoService crypto() { return new PaymentCredentialCryptoService(PaymentCredentialCryptoServiceTest.randomKey()); }
        @Bean PaymentCredentialMigrationService migration(JdbcTemplate jdbc, PaymentCredentialCryptoService crypto) { return new PaymentCredentialMigrationService(jdbc,crypto); }
        @Bean SqlSessionFactory sessionFactory(DataSource ds) throws Exception {
            var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds);
            var config=new MybatisConfiguration(); config.addMapper(MerchantDetailsMapper.class); factory.setConfiguration(config);
            return factory.getObject();
        }
        @Bean MerchantDetailsMapper mapper(SqlSessionFactory factory) { return new SqlSessionTemplate(factory).getMapper(MerchantDetailsMapper.class); }
        @Bean MerchantDetailsServiceImpl service() { return new MerchantDetailsServiceImpl(); }
    }
    @BeforeEach void setup() {
        ctx=new AnnotationConfigApplicationContext(Config.class); jdbc=ctx.getBean(JdbcTemplate.class);
        crypto=ctx.getBean(PaymentCredentialCryptoService.class); service=ctx.getBean(MerchantDetailsServiceImpl.class);
        jdbc.execute("CREATE TABLE merchant_details (details_id VARCHAR(32) PRIMARY KEY, appid VARCHAR(32), pay_type VARCHAR(16), mch_id VARCHAR(32), cert_store_type VARCHAR(16), key_private CLOB, key_cert_pwd CLOB, key_public CLOB, key_cert CLOB, notify_url VARCHAR(256), return_url VARCHAR(256), sign_type VARCHAR(16), seller VARCHAR(64), sub_app_id VARCHAR(32), sub_mch_id VARCHAR(32), input_charset VARCHAR(16), is_test INT, deleted BOOLEAN DEFAULT FALSE, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, creator VARCHAR(64), updater VARCHAR(64))");
    }
    @AfterEach void close() {ctx.close();}
    MerchantDetailsCreateReqVO create(String id) {
        var req=new MerchantDetailsCreateReqVO(); req.setDetailsId(id); req.setPayType("wxPay"); req.setSignType("MD5"); req.setIsTest(1); req.setAppid("synthetic-app"); req.setMchId("synthetic-merchant"); req.setInputCharset("UTF-8"); req.setNotifyUrl("http://localhost/synthetic"); return req;
    }
    String stored(String id,String field) {return jdbc.queryForObject("SELECT "+field+" FROM merchant_details WHERE details_id=?",String.class,id);}
    @Test void createAndUpdatePreserveOrExplicitlyReplace() {
        var req=create("test_merchant"); req.setKeyPrivate("synthetic-A"); req.setKeyCertPwd("synthetic-password"); req.setKeyCert("synthetic-certificate"); service.createMerchantDetails(req);
        String original=stored("test_merchant","key_private"), cert=stored("test_merchant","key_cert"), pwd=stored("test_merchant","key_cert_pwd");
        assertTrue(original.startsWith("enc:v1:")); assertEquals("synthetic-A",crypto.decrypt("test_merchant","keyPrivate",original));
        var edit=new MerchantDetailsUpdateReqVO(); edit.setDetailsId("test_merchant"); edit.setNotifyUrl("http://localhost/changed"); service.updateMerchantDetails(edit);
        assertEquals(original,stored("test_merchant","key_private"));
        edit.setKeyPrivate(""); edit.setKeyCert("  "); edit.setKeyCertPwd(""); service.updateMerchantDetails(edit);
        assertEquals(original,stored("test_merchant","key_private")); assertEquals(cert,stored("test_merchant","key_cert")); assertEquals(pwd,stored("test_merchant","key_cert_pwd"));
        edit.setKeyPrivate("synthetic-B"); service.updateMerchantDetails(edit);
        assertNotEquals(original,stored("test_merchant","key_private")); assertEquals("synthetic-B",crypto.decrypt("test_merchant","keyPrivate",stored("test_merchant","key_private")));
        assertEquals(cert,stored("test_merchant","key_cert")); assertEquals(pwd,stored("test_merchant","key_cert_pwd"));
    }
    @Test void migrationIsVerifiedIdempotentAndIncludesDeletedRows() {
        jdbc.update("INSERT INTO merchant_details(details_id,key_private,key_cert_pwd,key_cert,deleted) VALUES(?,?,?,?,?)", "legacy", "synthetic-legacy", "synthetic-pwd", "synthetic-cert", true);
        var migration=ctx.getBean(PaymentCredentialMigrationService.class); var result=migration.migrate(); assertEquals(3,result.migratedFields());
        String cipher=stored("legacy","key_private"); assertEquals("synthetic-legacy",crypto.decrypt("legacy","keyPrivate",cipher));
        var rerun=migration.migrate(); assertEquals(0,rerun.migratedFields()); assertEquals(3,rerun.verifiedEncryptedFields()); assertEquals(cipher,stored("legacy","key_private"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM merchant_details",Integer.class));
    }
    @Test void migrationRollsBackOnMalformedLaterRecord() {
        jdbc.update("INSERT INTO merchant_details(details_id,key_private) VALUES(?,?)","a_legacy","synthetic-legacy");
        jdbc.update("INSERT INTO merchant_details(details_id,key_private) VALUES(?,?)","z_bad","enc:v1:malformed");
        assertThrows(IllegalStateException.class,()->ctx.getBean(PaymentCredentialMigrationService.class).migrate());
        assertEquals("synthetic-legacy",stored("a_legacy","key_private")); assertEquals("enc:v1:malformed",stored("z_bad","key_private"));
    }
    @Test void runtimeSdkReceivesPlaintextWithoutCallingPayment() throws Exception {
        com.egzosn.pay.spring.boot.core.provider.merchant.platform.PaymentPlatforms.loadPaymentPlatform(new com.egzosn.pay.spring.boot.core.provider.merchant.platform.WxPaymentPlatform());
        var certificate = temporaryDirectory.resolve("synthetic-client.p12");
        var store = java.security.KeyStore.getInstance("PKCS12");
        char[] password = "synthetic-sdk-password".toCharArray();
        store.load(null, password);
        try (var output = java.nio.file.Files.newOutputStream(certificate)) { store.store(output, password); }
        var req=create("test_merchant"); req.setKeyPrivate("synthetic-test-secret");
        req.setKeyCertPwd("synthetic-sdk-password"); req.setKeyCert(certificate.toString()); req.setCertStoreType("PATH");
        service.createMerchantDetails(req);
        var builder=new EncryptedMerchantDetailsServiceBuilder(jdbc,crypto); builder.setConfigurer(org.mockito.Mockito.mock(PayMessageConfigurer.class));
        var merchant=(CommonPaymentPlatformMerchantDetails)builder.build().loadMerchantByMerchantId("test_merchant");
        assertEquals("synthetic-test-secret",merchant.getKeyPrivate());
        assertEquals("synthetic-sdk-password",merchant.getKeystorePwd());
        assertEquals(certificate.toString(),merchant.getKeyCert());
        assertTrue(stored("test_merchant","key_cert").startsWith("enc:v1:"));
        assertTrue(stored("test_merchant","key_cert_pwd").startsWith("enc:v1:"));
        assertEquals("synthetic-test-secret",merchant.getPayService().getPayConfigStorage().getKeyPrivate());
        assertFalse(merchant.toString().contains("synthetic-test-secret"));
        String json=new ObjectMapper().writeValueAsString(merchant); assertFalse(json.contains("synthetic-test-secret")); assertFalse(json.contains("enc:v1:"));
        assertTrue(stored("test_merchant","key_private").startsWith("enc:v1:"));
        assertNotSame(merchant,builder.build().loadMerchantByMerchantId("test_merchant")); // No plaintext merchant cache.
    }
    @Test void runtimeRejectsLegacyAndDeletedMerchant() {
        var req=create("test_merchant"); service.createMerchantDetails(req);
        jdbc.update("UPDATE merchant_details SET key_private=? WHERE details_id=?", "synthetic-legacy","test_merchant");
        var builder=new EncryptedMerchantDetailsServiceBuilder(jdbc,crypto);
        assertThrows(IllegalStateException.class,()->builder.build().loadMerchantByMerchantId("test_merchant"));
        jdbc.update("UPDATE merchant_details SET key_private=NULL,deleted=TRUE WHERE details_id=?", "test_merchant");
        assertThrows(IllegalStateException.class,()->builder.build().loadMerchantByMerchantId("test_merchant"));
    }
}
