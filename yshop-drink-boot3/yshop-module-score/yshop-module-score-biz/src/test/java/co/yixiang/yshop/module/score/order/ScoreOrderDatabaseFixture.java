package co.yixiang.yshop.module.score.order;

import static org.junit.jupiter.api.Assertions.*;
import co.yixiang.yshop.module.score.service.scoreorder.*;
import co.yixiang.yshop.module.score.controller.app.order.param.AppScoreOrderParam;
import co.yixiang.yshop.module.score.service.scoreproduct.AppScoreProductServiceImpl;
import co.yixiang.yshop.module.score.dal.mysql.scoreproduct.ScoreProductMapper;
import co.yixiang.yshop.module.score.dal.mysql.scoreorder.ScoreOrderMapper;
import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import co.yixiang.yshop.module.member.dal.mysql.useraddress.UserAddressMapper;
import co.yixiang.yshop.module.member.dal.mysql.userbill.UserBillMapper;
import co.yixiang.yshop.module.member.service.useraddress.AppUserAddressServiceImpl;
import co.yixiang.yshop.module.member.service.userbill.UserBillServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

/** Shared test-only fixture; actual mapper/service transactions against isolated databases. */
public class ScoreOrderDatabaseFixture implements AutoCloseable {
    public JdbcTemplate db;
    public DataSourceTransactionManager tm;
    public SqlSessionTemplate sql;
    public AppScoreOrderService orders;
    public UserBillServiceImpl bills;
    public boolean mysql;
    public Path boot;
    public String suppressWrite;
    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.statement.StatementHandler.class,method="prepare",args={java.sql.Connection.class,Integer.class}))
    public class ZeroRowWrite implements org.apache.ibatis.plugin.Interceptor {
        @Override public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            var bound=((org.apache.ibatis.executor.statement.StatementHandler)invocation.getTarget()).getBoundSql();String query=bound.getSql();String normalized=query.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+"," ").trim();
            if(suppressWrite!=null && (normalized.startsWith("update "+suppressWrite+" ") || normalized.startsWith("insert into "+suppressWrite+" "))) {
                // Inject an actual SQL zero-row result, keeping production mapper execution intact.
                String changed=normalized.startsWith("update ")?query+" AND 1=0":query.replaceFirst("(?is)\\)\\s*VALUES\\s*\\(", ") SELECT ").replaceFirst("\\)\\s*$", " WHERE 1=0");
                org.apache.ibatis.reflection.SystemMetaObject.forObject(bound).setValue("sql",changed);
            }
            return invocation.proceed();
        }
    }
    public void setup(Class<?>... extraMappers) throws Exception {
        var ds=new DriverManagerDataSource();
        String file=System.getenv("YSHOP_SCORE_ACCEPTANCE_CONFIG"); mysql=file!=null;
        if(mysql) {
            var p=new Properties();try(var in=Files.newInputStream(Path.of(file))){p.load(in);}
            if(!p.getProperty("url", "").matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]{2,5}/yshop_quality_score_[a-f0-9]{16}[?].*") || !p.getProperty("username", "").matches("qa_score_[a-f0-9]{16}")) throw new IllegalStateException("ISOLATED_SCORE_DATABASE_REQUIRED");
            ds.setDriverClassName("com.mysql.cj.jdbc.Driver"); ds.setUrl(p.getProperty("url"));ds.setUsername(p.getProperty("username"));ds.setPassword(p.getProperty("password"));
        } else {ds.setDriverClassName("org.h2.Driver");ds.setUrl("jdbc:h2:mem:score_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000");ds.setUsername("sa");}
        db=new JdbcTemplate(ds);tm=new DataSourceTransactionManager(ds);
        boot=Path.of("").toAbsolutePath();while(!Files.exists(boot.resolve("sql/yixiang-drink-open.sql")))boot=boot.getParent();
        for(String table:List.of("yshop_score_order","yshop_score_product","yshop_user","yshop_user_address","yshop_user_bill")) createTable(table);
        db.update("INSERT INTO yshop_score_product(id,title,image,images,`desc`,score,stock,sales,is_switch)VALUES(1,'synthetic','','[]','',100,10,0,1)");
        for(long uid=1;uid<=2;uid++) {
            db.update("INSERT INTO yshop_user(id,integral,deleted)VALUES(?,1000,0)",uid);
            db.update("INSERT INTO yshop_user_address(id,uid,real_name,phone,province,city,district,address,detail,create_time)VALUES(?,?,'synthetic','13800000001','','','','synthetic','owned',CURRENT_TIMESTAMP)",uid,uid);
        }
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        var global=com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.defaults();global.setMetaObjectHandler(new co.yixiang.yshop.framework.mybatis.core.handler.DefaultDBFieldHandler());factory.setGlobalConfig(global);com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,global);
        for(var mapper:List.of(ScoreOrderMapper.class,ScoreProductMapper.class,MemberUserMapper.class,UserAddressMapper.class,UserBillMapper.class))config.addMapper(mapper);
        for(var mapper:extraMappers)config.addMapper(mapper);
        config.addInterceptor(new ZeroRowWrite());factory.setConfiguration(config);sql=new SqlSessionTemplate(factory.getObject());
        var product=new AppScoreProductServiceImpl();set(product,"baseMapper",sql.getMapper(ScoreProductMapper.class));set(product,"productMapper",sql.getMapper(ScoreProductMapper.class));
        var address=new AppUserAddressServiceImpl();set(address,"userAddressMapper",sql.getMapper(UserAddressMapper.class));
        bills=new UserBillServiceImpl();set(bills,"userBillMapper",sql.getMapper(UserBillMapper.class));
        var target=new AppScoreOrderServiceImpl();set(target,"baseMapper",sql.getMapper(ScoreOrderMapper.class));set(target,"orderMapper",sql.getMapper(ScoreOrderMapper.class));set(target,"scoreProductMapper",sql.getMapper(ScoreProductMapper.class));set(target,"appScoreProductService",product);set(target,"billService",bills);set(target,"appUserAddressService",address);
        set(target,"memberUserMapper",sql.getMapper(MemberUserMapper.class));
        orders=transactional(target);
    }
    public void createTable(String table) throws Exception {
        db.execute("DROP TABLE IF EXISTS "+table);
        var m=java.util.regex.Pattern.compile("CREATE TABLE `"+table+"`.*?;",java.util.regex.Pattern.DOTALL).matcher(Files.readString(boot.resolve("sql/yixiang-drink-open.sql")));assertTrue(m.find());
        String ddl=m.group();
        if(!mysql)ddl=ddl.replaceAll(" CHARACTER SET [a-zA-Z0-9_]+", "").replaceAll(" COLLATE [a-zA-Z0-9_]+", "").replaceAll("(?m)^  (?:UNIQUE )?KEY .*\\n?", "").replaceAll(",\\s*\\) ENGINE=.*", ")").replaceAll("\\) ENGINE=.*", ")").replace(" USING BTREE", "").replace(" unsigned", "").replace("b'0'", "0").replace("b'1'", "1");
        db.execute(ddl);
    }
    public static void set(Object target,String field,Object value){ReflectionTestUtils.setField(target,field,value);}
    @SuppressWarnings("unchecked") public <T> T transactional(T target){var p=new ProxyFactory(target);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(tm,new AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
    public AppScoreOrderParam request(String num){var p=new AppScoreOrderParam();p.setProductId("1");p.setAddressId("1");p.setNum(num);return p;}
    public long n(String query,Object... args){return db.queryForObject(query,Long.class,args);}
    public void decimal(String expected,String query){assertEquals(0,new BigDecimal(expected).compareTo(db.queryForObject(query,BigDecimal.class)));}
    public void unchanged(){assertEquals(0,n("SELECT COUNT(*) FROM yshop_score_order"));assertEquals(0,n("SELECT COUNT(*) FROM yshop_user_bill"));assertEquals(10,n("SELECT stock FROM yshop_score_product WHERE id=1"));assertEquals(0,n("SELECT sales FROM yshop_score_product WHERE id=1"));decimal("1000","SELECT integral FROM yshop_user WHERE id=1");}
    public List<Boolean> race(java.util.function.IntFunction<Boolean> action) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try {var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++){final int index=i;tasks.add(pool.submit(()->{start.await();return action.apply(index);}));}start.countDown();var out=new ArrayList<Boolean>();for(var t:tasks)out.add(t.get(30,TimeUnit.SECONDS));return out;}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Override public void close() throws Exception {if(!mysql && db!=null)try(var c=db.getDataSource().getConnection();var s=c.createStatement()){s.execute("SHUTDOWN");}}
}
