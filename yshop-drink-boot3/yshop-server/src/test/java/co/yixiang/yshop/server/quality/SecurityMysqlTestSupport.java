package co.yixiang.yshop.server.quality;

import java.util.*;
import java.time.Duration;
import java.nio.file.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.mybatis.spring.SqlSessionTemplate;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import co.yixiang.yshop.module.member.service.user.*;
import co.yixiang.yshop.module.member.service.auth.*;
import co.yixiang.yshop.module.system.service.sms.*;
import co.yixiang.yshop.module.system.api.sms.*;
import co.yixiang.yshop.module.system.dal.mysql.sms.SmsCodeMapper;
import co.yixiang.yshop.module.system.service.oauth2.*;
import co.yixiang.yshop.module.system.api.oauth2.*;
import co.yixiang.yshop.module.system.dal.mysql.oauth2.*;
import co.yixiang.yshop.module.system.dal.dataobject.oauth2.OAuth2ClientDO;
import co.yixiang.yshop.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import co.yixiang.yshop.module.system.service.logger.LoginLogServiceImpl;
import co.yixiang.yshop.module.system.api.logger.LoginLogApiImpl;
import co.yixiang.yshop.module.system.dal.mysql.logger.LoginLogMapper;
import co.yixiang.yshop.module.system.framework.sms.config.SmsCodeProperties;
import static org.mockito.Mockito.*;

/** Real production mappers, transactions, SMS, token SQL and Redis. Providers alone are synthetic. */
abstract class SecurityMysqlTestSupport {
    Path repo; JdbcTemplate jdbc; DataSourceTransactionManager tm; SqlSessionTemplate sql;
    MemberUserMapper members; MemberUserService users; SmsCodeService sms; SmsCodeApiImpl smsApi;
    OAuth2TokenApiImpl tokens; MemberAuthService auth; MemberAuthServiceImpl authTarget;
    LettuceConnectionFactory redisConnection;
    StringRedisTemplate redis;
    org.springframework.security.crypto.password.PasswordEncoder encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
    @SuppressWarnings("unchecked") <T> T proxied(T target) {
        var p=new ProxyFactory(target);p.setProxyTargetClass(true);
        p.addAdvice(new TransactionInterceptor(tm,new AnnotationTransactionAttributeSource()));return (T)p.getProxy();
    }
    void set(Object target,String name,Object value){ReflectionTestUtils.setField(target,name,value);}
    void setupDatabase() throws Exception {
        requestContext();
        var p=new Properties();String config=System.getenv("YSHOP_IDENTITY_ACCEPTANCE_CONFIG");
        if(config==null)throw new IllegalStateException("OWNED_MYSQL_REDIS_REQUIRED");
        try(var in=Files.newInputStream(Path.of(config))){p.load(in);}
        if(!p.getProperty("url","").matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]{2,5}/yshop_quality_identity_[a-f0-9]{16}(\\?.*)?")
                || !p.getProperty("username","").matches("qa_identity_[a-f0-9]{16}"))throw new IllegalStateException("OWNED_DATABASE_REQUIRED");
        var ds=new DriverManagerDataSource();ds.setDriverClassName("com.mysql.cj.jdbc.Driver");ds.setUrl(p.getProperty("url"));ds.setUsername(p.getProperty("username"));ds.setPassword(p.getProperty("password"));
        co.yixiang.yshop.framework.mybatis.core.enums.SqlConstants.DB_TYPE=com.baomidou.mybatisplus.annotation.DbType.MYSQL;
        repo=Path.of(p.getProperty("repo"));jdbc=new JdbcTemplate(ds);tm=new DataSourceTransactionManager(ds);
        for(String t:List.of("yshop_user","system_sms_code","system_login_log","system_oauth2_access_token","system_oauth2_refresh_token","system_social_user","system_social_user_bind","yshop_store_order","yshop_store_order_status","yshop_user_bill","yshop_member_wallet_transaction"))jdbc.execute("TRUNCATE TABLE "+t);
        jdbc.execute("DROP TABLE IF EXISTS system_social_client");
        // Migration boundary tests intentionally damage the owned fixture schema.
        // Reset its three indexes before restoring from the actual migration for each case.
        for(String name:jdbc.queryForList("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_user' AND INDEX_NAME IN ('uk_member_mobile','uk_member_openid','uk_member_routine_openid')",String.class))jdbc.execute("ALTER TABLE yshop_user DROP INDEX "+name);
        migration();
        var bean=new MybatisSqlSessionFactoryBean();bean.setDataSource(ds);var c=new MybatisConfiguration();c.setMapUnderscoreToCamelCase(true);
        var g=com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.defaults();g.setMetaObjectHandler(new co.yixiang.yshop.framework.mybatis.core.handler.DefaultDBFieldHandler());bean.setGlobalConfig(g);com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(c,g);
        for(var m:List.of(MemberUserMapper.class,SmsCodeMapper.class,co.yixiang.yshop.module.system.dal.mysql.social.SocialUserMapper.class,co.yixiang.yshop.module.system.dal.mysql.social.SocialUserBindMapper.class,co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper.class,co.yixiang.yshop.module.order.dal.mysql.storeorderstatus.StoreOrderStatusMapper.class,co.yixiang.yshop.module.order.dal.mysql.storeordercartinfo.StoreOrderCartInfoMapper.class,co.yixiang.yshop.module.store.dal.mysql.storeshop.StoreShopMapper.class,co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper.class,co.yixiang.yshop.module.member.dal.mysql.wallet.WalletMapper.class,OAuth2PrincipalMapper.class,OAuth2AccessTokenMapper.class,OAuth2RefreshTokenMapper.class,LoginLogMapper.class))c.addMapper(m);
        bean.setConfiguration(c);sql=new SqlSessionTemplate(bean.getObject());members=sql.getMapper(MemberUserMapper.class);
        var rc=new RedisStandaloneConfiguration("127.0.0.1",Integer.parseInt(p.getProperty("redisPort")));rc.setPassword(p.getProperty("redisPassword"));redisConnection=new LettuceConnectionFactory(rc);redisConnection.afterPropertiesSet();
        redis=new StringRedisTemplate(redisConnection);redis.afterPropertiesSet();try(var con=redisConnection.getConnection()){con.serverCommands().flushDb();}
        var tokenService=new OAuth2TokenServiceImpl();set(tokenService,"principals",sql.getMapper(OAuth2PrincipalMapper.class));set(tokenService,"oauth2AccessTokenMapper",sql.getMapper(OAuth2AccessTokenMapper.class));set(tokenService,"oauth2RefreshTokenMapper",sql.getMapper(OAuth2RefreshTokenMapper.class));
        var cache=new OAuth2AccessTokenRedisDAO();set(cache,"stringRedisTemplate",redis);set(tokenService,"oauth2AccessTokenRedisDAO",cache);
        var clients=mock(OAuth2ClientService.class);when(clients.validOAuthClientFromCache("default")).thenReturn(new OAuth2ClientDO().setClientId("default").setAccessTokenValiditySeconds(60).setRefreshTokenValiditySeconds(3600));set(tokenService,"oauth2ClientService",clients);
        tokens=new OAuth2TokenApiImpl();set(tokens,"oauth2TokenService",proxied(tokenService));
        sms=smsService(sql.getMapper(SmsCodeMapper.class));smsApi=new SmsCodeApiImpl();set(smsApi,"smsCodeService",sms);
        var us=new MemberUserServiceImpl();set(us,"memberUserMapper",members);set(us,"baseMapper",members);set(us,"smsCodeApi",smsApi);set(us,"passwordEncoder",encoder);set(us,"oauth2Tokens",tokens);users=proxied(us);
        var logs=new LoginLogServiceImpl();set(logs,"loginLogMapper",sql.getMapper(LoginLogMapper.class));var api=new LoginLogApiImpl();set(api,"loginLogService",logs);
        authTarget=new MemberAuthServiceImpl();set(authTarget,"userService",users);set(authTarget,"userMapper",members);set(authTarget,"smsCodeApi",smsApi);set(authTarget,"oauth2TokenApi",tokens);set(authTarget,"loginLogApi",api);set(authTarget,"passwordEncoder",encoder);
        set(authTarget,"transactionManager",tm);auth=proxied(authTarget);
    }
    SmsCodeService smsService(SmsCodeMapper mapper){
        var service=new SmsCodeServiceImpl();var props=new SmsCodeProperties();props.setExpireTimes(Duration.ofMinutes(5));props.setSendFrequency(Duration.ZERO);props.setSendMaximumQuantityPerDay(100);props.setBeginCode(100000);props.setEndCode(999999);set(service,"smsCodeProperties",props);set(service,"smsCodeMapper",mapper);set(service,"smsSendService",mock(SmsSendService.class));return proxied(service);
    }
    long member(String phone,String routine){
        jdbc.update("INSERT INTO yshop_user(username,password,nickname,mobile,routine_openid,status,integral,now_money,tenant_id) VALUES(?,?,?,?,?,0,100,50,0)","synthetic-"+UUID.randomUUID(),encoder.encode("synthetic-password"),"synthetic",phone,routine);
        return jdbc.queryForObject("SELECT MAX(id) FROM yshop_user",Long.class);
    }
    void code(String phone,int scene,String code){jdbc.update("INSERT INTO system_sms_code(mobile,code,scene,create_ip,today_index,used,tenant_id,create_time) VALUES(?,?,?,'127.0.0.1',1,0,0,?)",phone,code,scene,java.time.LocalDateTime.now());}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE deleted=0",Integer.class);}
    String phone(long uid){return jdbc.queryForObject("SELECT mobile FROM yshop_user WHERE id=?",String.class,uid);}
    long cachedSocial(String openid,String code){jdbc.update("INSERT INTO system_social_user(type,openid,raw_token_info,nickname,raw_user_info,code,state) VALUES(34,?,'{}','synthetic','{}',?,'')",openid,code);return jdbc.queryForObject("SELECT MAX(id) FROM system_social_user",Long.class);}
    void miniProof(String phone,String openid,String loginCode) throws Exception {var wx=org.mockito.Mockito.mock(cn.binarywang.wx.miniapp.api.WxMaService.class);var service=org.mockito.Mockito.mock(cn.binarywang.wx.miniapp.api.WxMaUserService.class);when(wx.getUserService()).thenReturn(service);var session=new cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult();session.setOpenid(openid);session.setSessionKey("synthetic-key");when(service.getSessionInfo(loginCode)).thenReturn(session);var number=new cn.binarywang.wx.miniapp.bean.WxMaPhoneNumberInfo();number.setPurePhoneNumber(phone);when(service.getNewPhoneNoInfo("synthetic-phone-code")).thenReturn(number);set(authTarget,"wxMaService",wx);}
    co.yixiang.yshop.module.member.controller.app.social.AppSocialUserController socialController(long uid){
        requestContext();var request=(org.springframework.web.context.request.ServletRequestAttributes)org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils.setLoginUser(new co.yixiang.yshop.framework.security.core.LoginUser().setId(uid).setUserType(1),request.getRequest());
        var controller=new co.yixiang.yshop.module.member.controller.app.social.AppSocialUserController();set(controller,"authService",auth);return controller;
    }
    co.yixiang.yshop.module.member.controller.app.social.vo.AppSocialUserBindReqVO bindRequest(String code,String sms) throws Exception {var mapper=new com.fasterxml.jackson.databind.ObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);return mapper.readValue("{\"type\":34,\"code\":\""+code+"\",\"state\":\"\",\"smsCode\":\""+sms+"\"}",co.yixiang.yshop.module.member.controller.app.social.vo.AppSocialUserBindReqVO.class);}
    co.yixiang.yshop.module.member.service.userbill.UserBillService bills(co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper mapper){var b=new co.yixiang.yshop.module.member.service.userbill.UserBillServiceImpl();set(b,"userBillMapper",mapper);set(b,"baseMapper",mapper);return proxied(b);}
    co.yixiang.yshop.module.order.service.storeorder.AppStoreOrderServiceImpl receipt(co.yixiang.yshop.module.order.dal.mysql.storeorder.StoreOrderMapper mapper,co.yixiang.yshop.module.member.service.userbill.UserBillService bills){
        var service=new co.yixiang.yshop.module.order.service.storeorder.AppStoreOrderServiceImpl();set(service,"baseMapper",mapper);set(service,"storeOrderMapper",mapper);set(service,"userService",users);set(service,"memberUserMapper",members);set(service,"billService",bills);set(service,"transactionManager",tm);
        var carts=new co.yixiang.yshop.module.order.service.storeordercartinfo.StoreOrderCartInfoServiceImpl();set(carts,"baseMapper",sql.getMapper(co.yixiang.yshop.module.order.dal.mysql.storeordercartinfo.StoreOrderCartInfoMapper.class));set(service,"storeOrderCartInfoService",carts);
        var shops=new co.yixiang.yshop.module.store.service.storeshop.AppStoreShopServiceImpl();set(shops,"baseMapper",sql.getMapper(co.yixiang.yshop.module.store.dal.mysql.storeshop.StoreShopMapper.class));set(service,"appStoreShopService",shops);
        var status=new co.yixiang.yshop.module.order.service.storeorderstatus.StoreOrderStatusServiceImpl();var sm=sql.getMapper(co.yixiang.yshop.module.order.dal.mysql.storeorderstatus.StoreOrderStatusMapper.class);set(status,"baseMapper",sm);set(status,"storeOrderStatusMapper",sm);set(status,"asyncStoreOrderService",mock(co.yixiang.yshop.module.order.service.storeorder.AsyncStoreOrderService.class));set(service,"storeOrderStatusService",proxied(status));return proxied(service);
    }
    long order(long uid){jdbc.update("INSERT INTO yshop_store_order(order_id,uid,paid,status,pay_type,mark,cost,gain_integral,create_time,tenant_id) VALUES('synthetic-receipt',?,1,1,'synthetic','',0,5,?,0)",uid,java.time.LocalDateTime.now());return jdbc.queryForObject("SELECT MAX(id) FROM yshop_store_order",Long.class);}
    void migration() throws Exception {
        String delimiter=";";var statement=new StringBuilder();
        for(String line:Files.readAllLines(repo.resolve("yshop-drink-boot3/sql/migrations/2026-10-11-member-identity.sql"))){
            String text=line.trim();if(text.startsWith("--") || text.isEmpty())continue;
            if(text.startsWith("DELIMITER ")){delimiter=text.substring(10).trim();continue;}
            statement.append(line).append('\n');if(text.endsWith(delimiter)){String command=statement.toString().trim();jdbc.execute(command.substring(0,command.length()-delimiter.length()));statement.setLength(0);}
        }
        if(!statement.toString().isBlank())throw new IllegalStateException("INCOMPLETE_MIGRATION_SCRIPT");
    }
    <T> T zeroMapper(Class<T> type,T actual,String... zeroMethods){var names=Set.of(zeroMethods);return org.mockito.Mockito.mock(type,org.mockito.Mockito.withSettings().defaultAnswer(call->{if(names.contains(call.getMethod().getName()))return 0;try{return call.getMethod().invoke(actual,call.getArguments());}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}}));}
    void requestContext(){var request=new org.springframework.mock.web.MockHttpServletRequest();request.setRemoteAddr("127.0.0.1");request.addHeader("User-Agent","synthetic-quality");org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));}
    void closeDatabase(){org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();org.springframework.security.core.context.SecurityContextHolder.clearContext();if(redisConnection!=null)redisConnection.destroy();}
}
