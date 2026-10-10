package co.yixiang.yshop.module.system.service.oauth2;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.mybatis.spring.SqlSessionTemplate;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import co.yixiang.yshop.module.system.dal.mysql.oauth2.*;
import co.yixiang.yshop.module.system.dal.dataobject.oauth2.*;
import co.yixiang.yshop.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import co.yixiang.yshop.module.system.service.user.AdminUserService;
import co.yixiang.yshop.module.system.api.oauth2.OAuth2TokenApiImpl;
import co.yixiang.yshop.framework.common.exception.ServiceException;

/** Actual API/service/mapper/transactions, two independent cache snapshots; no provider calls. */
class OAuth2LifecycleDatabaseTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager tm;
    SqlSessionTemplate sql;
    OAuth2TokenService one,two;
    OAuth2TokenApiImpl api;
    boolean mysql;
    Map<String,OAuth2AccessTokenDO> cache1=new ConcurrentHashMap<>(),cache2=new ConcurrentHashMap<>();

    @BeforeEach void setup() throws Exception {
        String file=System.getenv("YSHOP_AUTH_ACCEPTANCE_CONFIG");mysql=file!=null;
        var ds=new DriverManagerDataSource();
        if(mysql) {
            var p=new Properties();try(var in=java.nio.file.Files.newInputStream(java.nio.file.Path.of(file))){p.load(in);}
            String url=p.getProperty("url","");
            if(!url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]{2,5}/yshop_quality_auth_[a-f0-9]{16}(\\?.*)?")
                    || !p.getProperty("username","").matches("qa_auth_[a-f0-9]{16}")) throw new IllegalStateException("ISOLATED_AUTH_DATABASE_REQUIRED");
            ds.setDriverClassName("com.mysql.cj.jdbc.Driver");ds.setUrl(url);ds.setUsername(p.getProperty("username"));ds.setPassword(p.getProperty("password"));
        } else {ds.setDriverClassName("org.h2.Driver");ds.setUrl("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000");}
        jdbc=new JdbcTemplate(ds);tm=new DataSourceTransactionManager(ds);
        for(String table:List.of("system_oauth2_access_token","system_oauth2_refresh_token","system_users","yshop_user")) jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("CREATE TABLE yshop_user(id BIGINT PRIMARY KEY,status INT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE system_users(id BIGINT PRIMARY KEY,status INT,deleted INT DEFAULT 0,username VARCHAR(30),password VARCHAR(100),nickname VARCHAR(30),remark VARCHAR(500),dept_id BIGINT,post_ids VARCHAR(255),email VARCHAR(50),mobile VARCHAR(11),sex INT,avatar VARCHAR(100),login_ip VARCHAR(50),login_date DATETIME,creator VARCHAR(64),updater VARCHAR(64),create_time DATETIME,update_time DATETIME,tenant_id BIGINT DEFAULT 0)");
        String tail=",tenant_id BIGINT DEFAULT 0,shop_id BIGINT,creator VARCHAR(64),updater VARCHAR(64),create_time DATETIME,update_time DATETIME,deleted INT DEFAULT 0";
        jdbc.execute("CREATE TABLE system_oauth2_refresh_token(id BIGINT PRIMARY KEY,refresh_token VARCHAR(64) UNIQUE,user_id BIGINT,user_type INT,client_id VARCHAR(64),scopes VARCHAR(2000),expires_time DATETIME"+tail+")");
        jdbc.execute("CREATE TABLE system_oauth2_access_token(id BIGINT PRIMARY KEY,access_token VARCHAR(64) UNIQUE,refresh_token VARCHAR(64),user_id BIGINT,user_type INT,user_info VARCHAR(2000),client_id VARCHAR(64),scopes VARCHAR(2000),expires_time DATETIME"+tail+")");
        jdbc.update("INSERT INTO yshop_user VALUES(1,0,0)");jdbc.update("INSERT INTO system_users(id,username,password,nickname,status,deleted) VALUES(1,'synthetic-admin','synthetic-unused','synthetic-admin',0,0)");
        var bean=new MybatisSqlSessionFactoryBean();bean.setDataSource(ds);
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        var global=com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.defaults();
        global.setMetaObjectHandler(new co.yixiang.yshop.framework.mybatis.core.handler.DefaultDBFieldHandler());
        bean.setGlobalConfig(global);com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,global);
        for(var c:List.of(OAuth2PrincipalMapper.class,OAuth2AccessTokenMapper.class,OAuth2RefreshTokenMapper.class,co.yixiang.yshop.module.system.dal.mysql.user.AdminUserMapper.class)) config.addMapper(c);
        bean.setConfiguration(config);sql=new SqlSessionTemplate(bean.getObject());
        one=instance(cache1);two=instance(cache2);api=new OAuth2TokenApiImpl();ReflectionTestUtils.setField(api,"oauth2TokenService",one);
    }
    @AfterEach void close() throws Exception {
        // SHUTDOWN closes the connection itself; JdbcTemplate then reads warnings on
        // that closed statement in H2 2.2.224. Execute directly without masking errors.
        if(!mysql && jdbc!=null) try(var connection=jdbc.getDataSource().getConnection();
                                     var statement=connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }
    OAuth2TokenService instance(Map<String,OAuth2AccessTokenDO> cache) {
        var target=new OAuth2TokenServiceImpl();
        ReflectionTestUtils.setField(target,"principals",sql.getMapper(OAuth2PrincipalMapper.class));
        ReflectionTestUtils.setField(target,"oauth2AccessTokenMapper",sql.getMapper(OAuth2AccessTokenMapper.class));
        ReflectionTestUtils.setField(target,"oauth2RefreshTokenMapper",sql.getMapper(OAuth2RefreshTokenMapper.class));
        var redis=mock(OAuth2AccessTokenRedisDAO.class);
        when(redis.get(anyString())).thenAnswer(c->cache.get(c.getArgument(0)));
        doAnswer(c->{var row=(OAuth2AccessTokenDO)c.getArgument(0);cache.put(row.getAccessToken(),row);return null;}).when(redis).set(any());
        doAnswer(c->{for(var token:(Collection<String>)c.getArgument(0))cache.remove(token);return null;}).when(redis).deleteList(any());
        ReflectionTestUtils.setField(target,"oauth2AccessTokenRedisDAO",redis);
        var clients=mock(OAuth2ClientService.class);
        when(clients.validOAuthClientFromCache("synthetic-client")).thenReturn(new OAuth2ClientDO().setClientId("synthetic-client").setAccessTokenValiditySeconds(60).setRefreshTokenValiditySeconds(3600));
        ReflectionTestUtils.setField(target,"oauth2ClientService",clients);
        var admins=mock(AdminUserService.class);
        when(admins.getUser(1L)).thenReturn(new co.yixiang.yshop.module.system.dal.dataobject.user.AdminUserDO().setId(1L).setNickname("synthetic-admin").setDeptId(1L));
        ReflectionTestUtils.setField(target,"adminUserService",admins);
        ReflectionTestUtils.setField(target,"storeShopMapper",mock(co.yixiang.yshop.module.store.dal.mysql.storeshop.StoreShopMapper.class));
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(tm,new AnnotationTransactionAttributeSource()));return (OAuth2TokenService)proxy.getProxy();
    }
    OAuth2AccessTokenDO member(){return one.createAccessToken(1L,1,"synthetic-client",List.of());}
    co.yixiang.yshop.module.system.service.user.AdminUserService adminLifecycle(OAuth2TokenService tokens) {
        var target=new co.yixiang.yshop.module.system.service.user.AdminUserServiceImpl();
        ReflectionTestUtils.setField(target,"userMapper",sql.getMapper(co.yixiang.yshop.module.system.dal.mysql.user.AdminUserMapper.class));
        ReflectionTestUtils.setField(target,"oauth2Tokens",tokens);
        ReflectionTestUtils.setField(target,"permissionService",mock(co.yixiang.yshop.module.system.service.permission.PermissionService.class));
        ReflectionTestUtils.setField(target,"userPostMapper",mock(co.yixiang.yshop.module.system.dal.mysql.dept.UserPostMapper.class));
        ReflectionTestUtils.setField(target,"storeShopMapper",mock(co.yixiang.yshop.module.store.dal.mysql.storeshop.StoreShopMapper.class));
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(tm,new AnnotationTransactionAttributeSource()));
        return (co.yixiang.yshop.module.system.service.user.AdminUserService)proxy.getProxy();
    }
    @Test void adminDisableReenableCannotResurrectOldCredentials() {
        var member=member();var admin=one.createAccessToken(1L,2,"synthetic-client",List.of());
        cache2.put(admin.getAccessToken(),admin);
        var users=adminLifecycle(one);users.updateUserStatus(1L,1);
        denied(()->two.checkAccessToken(admin.getAccessToken()));
        users.updateUserStatus(1L,0);
        denied(()->two.refreshAccessToken(admin.getRefreshToken(),"synthetic-client",2));
        denied(()->two.checkAccessToken(admin.getAccessToken()));
        assertEquals(1L,two.checkAccessToken(member.getAccessToken()).getUserId());
        var fresh=one.createAccessToken(1L,2,"synthetic-client",List.of());users.deleteUser(1L);
        denied(()->two.refreshAccessToken(fresh.getRefreshToken(),"synthetic-client",2));
        assertEquals(1,count("system_oauth2_refresh_token")); // Member identity with same id survives.
    }
    @Test void adminRevocationFailureRollsBackStatusAndCredentials() {
        var admin=one.createAccessToken(1L,2,"synthetic-client",List.of());
        var failing=mock(OAuth2TokenService.class);
        doAnswer(c->{one.revokeUserTokens(1L,2);throw new IllegalStateException("SYNTHETIC_AFTER_REVOKE");}).when(failing).revokeUserTokens(1L,2);
        assertThrows(IllegalStateException.class,()->adminLifecycle(failing).updateUserStatus(1L,1));
        assertEquals(0,jdbc.queryForObject("SELECT status FROM system_users WHERE id=1",Integer.class));
        assertEquals(1,count("system_oauth2_refresh_token"));
        assertEquals(admin.getAccessToken(),two.checkAccessToken(admin.getAccessToken()).getAccessToken());
        assertNotNull(two.refreshAccessToken(admin.getRefreshToken(),"synthetic-client",2));
    }
    @Test void actualApiOverloadsAndRevokeKeepCanonicalMemberIdentity() {
        var req=new co.yixiang.yshop.module.system.api.oauth2.dto.OAuth2AccessTokenCreateReqDTO().setUserId(1L).setUserType(1).setClientId("synthetic-client").setScopes(List.of());
        var token=api.createAccessToken(req);assertEquals(1,api.checkAccessToken(token.getAccessToken()).getUserType());
        var next=api.refreshAccessToken(token.getRefreshToken(),"synthetic-client");
        denied(()->two.checkAccessToken(token.getAccessToken()));assertEquals(1L,next.getUserId());
        assertNotNull(api.removeAccessToken(next.getAccessToken()));
        denied(()->api.refreshAccessToken(token.getRefreshToken(),"synthetic-client"));
        var fresh=api.createAccessToken(req);api.revokeUserTokens(1L,1);
        denied(()->api.checkAccessToken(fresh.getAccessToken()));assertEquals(0,count("system_oauth2_refresh_token"));
    }
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE deleted=0",Integer.class);}
    void denied(Runnable action){assertThrows(ServiceException.class,action::run);}
    void disable(boolean delete) {
        var tx=new TransactionTemplate(tm);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(s->{jdbc.update("UPDATE yshop_user SET status=1,deleted=? WHERE id=1",delete?1:0);one.revokeUserTokens(1L,1);});
    }
    @Test void actualApiRefreshChecksCurrentMemberAndCanonicalIdentity() {
        var old=member();var next=api.refreshAccessToken(old.getRefreshToken(),"synthetic-client",1);
        assertEquals(1L,next.getUserId());assertNotEquals(old.getAccessToken(),next.getAccessToken());
        denied(()->two.checkAccessToken(old.getAccessToken()));assertEquals(1L,two.checkAccessToken(next.getAccessToken()).getUserId());
        assertEquals(1,count("system_oauth2_access_token"));assertEquals(1,count("system_oauth2_refresh_token"));
    }
    @Test void disabledAndDeletedMemberCannotRefreshOrUseOldTokens() {
        var old=member();disable(false);denied(()->two.refreshAccessToken(old.getRefreshToken(),"synthetic-client",1));denied(()->two.checkAccessToken(old.getAccessToken()));
        jdbc.update("UPDATE yshop_user SET status=0 WHERE id=1");
        denied(()->one.refreshAccessToken(old.getRefreshToken(),"synthetic-client",1)); // Re-enable cannot resurrect revocation.
        var fresh=member();disable(true);denied(()->two.refreshAccessToken(fresh.getRefreshToken(),"synthetic-client",1));
        assertEquals(0,count("system_oauth2_access_token"));assertEquals(0,count("system_oauth2_refresh_token"));
    }
    @Test void directStatusOrDeletionEvidenceFailsClosedWithoutCacheInvalidation() {
        var old=member();cache2.put(old.getAccessToken(),old);
        jdbc.update("UPDATE yshop_user SET status=1 WHERE id=1");
        denied(()->two.refreshAccessToken(old.getRefreshToken(),"synthetic-client",1));denied(()->two.checkAccessToken(old.getAccessToken()));
        jdbc.update("UPDATE yshop_user SET status=0,deleted=1 WHERE id=1");
        denied(()->one.refreshAccessToken(old.getRefreshToken(),"synthetic-client",1));
    }
    @Test void logoutRevokesStaleRemoteCacheAndBothCredentials() {
        var old=member();cache2.put(old.getAccessToken(),old);api.removeAccessToken(old.getAccessToken(),1);
        assertNotNull(cache2.get(old.getAccessToken())); // Actual stale independent cache.
        denied(()->two.checkAccessToken(old.getAccessToken()));denied(()->api.refreshAccessToken(old.getRefreshToken(),"synthetic-client",1));
        assertNull(api.removeAccessToken(old.getAccessToken(),1));assertEquals(0,count("system_oauth2_access_token"));
    }
    @Test void oldRotatedTokenLogoutRevokesTheCurrentFamily() {
        var old=member();var fresh=two.refreshAccessToken(old.getRefreshToken(),"synthetic-client",1);
        assertNotNull(api.removeAccessToken(old.getAccessToken(),1));denied(()->one.checkAccessToken(fresh.getAccessToken()));
    }
    @Test void invalidExpiredClientAndUserTypeCannotAlterFamily() {
        var old=member();denied(()->api.refreshAccessToken("synthetic-invalid","synthetic-client",1));
        denied(()->api.refreshAccessToken(old.getRefreshToken(),"wrong-client",1));
        denied(()->api.refreshAccessToken(old.getRefreshToken(),"synthetic-client",2));
        denied(()->api.removeAccessToken(old.getAccessToken(),2));assertEquals(1,count("system_oauth2_access_token"));
        jdbc.update("UPDATE system_oauth2_refresh_token SET expires_time=?",java.time.LocalDateTime.of(2000,1,1,0,0));
        denied(()->two.refreshAccessToken(old.getRefreshToken(),"synthetic-client",1));denied(()->two.checkAccessToken(old.getAccessToken()));
        assertEquals(1,count("system_oauth2_refresh_token"));
    }
    @Test void disabledPrincipalCannotCreateNewAccessOrRefresh() {
        jdbc.update("UPDATE yshop_user SET status=1");denied(this::member);
        assertEquals(0,count("system_oauth2_access_token"));assertEquals(0,count("system_oauth2_refresh_token"));
    }
    @Test void issuanceAndCacheDoNotEscapeDatabaseRollback() {
        var tx=new TransactionTemplate(tm);tx.setIsolationLevel(2);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{member();throw new IllegalStateException("SYNTHETIC_ROLLBACK");}));
        assertEquals(0,count("system_oauth2_access_token"));assertEquals(0,count("system_oauth2_refresh_token"));assertTrue(cache1.isEmpty());
    }
    @Test void realRequestFilterRejectsWrongUserTypeAndRevokedIdentity() throws Exception {
        var token=member();var properties=new co.yixiang.yshop.framework.security.config.SecurityProperties();
        var handler=mock(co.yixiang.yshop.framework.web.core.handler.GlobalExceptionHandler.class);
        when(handler.allExceptionHandler(any(),any())).thenReturn(co.yixiang.yshop.framework.common.pojo.CommonResult.error(403,"forbidden"));
        var filter=new co.yixiang.yshop.framework.security.core.filter.TokenAuthenticationFilter(properties,handler,api);
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.addHeader("Authorization","Bearer "+token.getAccessToken());
        co.yixiang.yshop.framework.web.core.util.WebFrameworkUtils.setLoginUserType(request,2);
        var response=new org.springframework.mock.web.MockHttpServletResponse();var called=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            filter.doFilter(request,response,(r,s)->called.set(true));assertFalse(called.get());assertFalse(response.getContentAsString().contains(token.getAccessToken()));
            one.removeAccessToken(token.getAccessToken(),1);
            var revoked=new org.springframework.mock.web.MockHttpServletRequest();revoked.addHeader("Authorization","Bearer "+token.getAccessToken());
            co.yixiang.yshop.framework.web.core.util.WebFrameworkUtils.setLoginUserType(revoked,1);
            filter.doFilter(revoked,new org.springframework.mock.web.MockHttpServletResponse(),(r,s)->{
                assertNull(co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId());called.set(true);
            });assertTrue(called.get());
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    List<String> compete(java.util.function.IntFunction<String> action) throws Exception {
        var pool=Executors.newFixedThreadPool(20);var ready=new CountDownLatch(20);var go=new CountDownLatch(1);
        var connections=ConcurrentHashMap.<Long>newKeySet();
        try {
            var futures=new ArrayList<Future<String>>();
            for(int i=0;i<20;i++){final int n=i;futures.add(pool.submit(()->{
                var tx=new TransactionTemplate(tm);tx.setIsolationLevel(2);
                try{return tx.execute(s->{connections.add(jdbc.queryForObject(mysql?"SELECT CONNECTION_ID()":"SELECT SESSION_ID()",Long.class));ready.countDown();
                    try{assertTrue(go.await(30,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}
                    return action.apply(n);});}catch(ServiceException denied){return "denied";}}));}
            assertTrue(ready.await(30,TimeUnit.SECONDS));assertEquals(20,connections.size());go.countDown();
            var values=new ArrayList<String>();for(var f:futures)values.add(f.get(60,TimeUnit.SECONDS));return values;
        } finally {go.countDown();pool.shutdownNow();}
    }
    @RepeatedTest(20) void twentyRefreshesHaveOnlyOneCurrentAccessAcrossInstances() throws Exception {
        var original=member();var all=compete(n->(n%2==0?one:two).refreshAccessToken(original.getRefreshToken(),"synthetic-client",1).getAccessToken());
        assertEquals(20,new HashSet<>(all).size());assertEquals(1,count("system_oauth2_access_token"));assertEquals(1,count("system_oauth2_refresh_token"));
        String current=jdbc.queryForObject("SELECT access_token FROM system_oauth2_access_token WHERE deleted=0",String.class);
        for(String token:all)if(!token.equals(current))denied(()->two.checkAccessToken(token));assertEquals(1L,one.checkAccessToken(current).getUserId());
    }
    @RepeatedTest(20) void disableAndRefreshRaceEndsWithNoUsableCredentials() throws Exception {
        var original=member();compete(n->{if(n==0){disable(false);return "disabled";}return two.refreshAccessToken(original.getRefreshToken(),"synthetic-client",1).getAccessToken();});
        assertEquals(0,count("system_oauth2_access_token"));assertEquals(0,count("system_oauth2_refresh_token"));denied(()->one.checkAccessToken(original.getAccessToken()));
    }
    @RepeatedTest(20) void logoutAndRefreshRaceEndsWithNoUsableCredentials() throws Exception {
        var original=member();compete(n->{if(n==0){one.removeAccessToken(original.getAccessToken(),1);return "logout";}return two.refreshAccessToken(original.getRefreshToken(),"synthetic-client",1).getAccessToken();});
        assertEquals(0,count("system_oauth2_access_token"));assertEquals(0,count("system_oauth2_refresh_token"));
    }
}
