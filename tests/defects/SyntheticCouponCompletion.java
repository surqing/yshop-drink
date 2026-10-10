package co.yixiang.yshop.module.order.payment;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import co.yixiang.yshop.module.order.service.payment.PaymentFinalizationService;
import co.yixiang.yshop.module.order.service.payment.attempt.PaymentAttemptService;
import co.yixiang.yshop.module.pay.callback.*;

/** Test-only bridge. Reuses existing real mapper/transaction/effects configuration; transport is fake. */
public class SyntheticCouponCompletion {
    static Properties settings;
    @Configuration
    static class OwnedConfig extends PaymentDatabaseTest.Config {
        @Override @Bean DataSource dataSource() {
            String url=settings.getProperty("url"),user=settings.getProperty("username");
            if(!url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/yshop_quality_business_[a-f0-9]{16}\\?.*")
                || !user.matches("qa_business_[a-f0-9]{16}")) throw new IllegalStateException("OWNED_SYNTHETIC_DATABASE_REQUIRED");
            var ds=new DriverManagerDataSource();ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
            ds.setUrl(url);ds.setUsername(user);ds.setPassword(settings.getProperty("password"));return ds;
        }
    }
    public static void main(String[] args) throws Exception {
        settings=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){settings.load(in);}
        if(!"false".equals(System.getenv("YSHOP_PAY_WECHAT_V3_ENABLED")) || !"false".equals(System.getenv("YSHOP_PAY_WECHAT_V3_RECONCILIATION_ENABLED")))throw new IllegalStateException("FINANCIAL_FREEZE_REQUIRED");
        try(var ctx=new AnnotationConfigApplicationContext(OwnedConfig.class)){
            var db=ctx.getBean(JdbcTemplate.class);String order=args[1];
            if(!order.matches("[a-zA-Z0-9_-]{1,64}"))throw new IllegalStateException("INVALID_OWNED_ORDER");
            if(db.queryForObject("SELECT COUNT(*) FROM yshop_store_order WHERE order_id=? AND uid=101 AND shop_id=101 AND paid=0 AND ordering_version=1",Long.class,order)!=1L)throw new IllegalStateException("OWNED_ORDER_REQUIRED");
            db.update("INSERT INTO merchant_details(details_id,pay_type,appid,mch_id,seller,sign_type,is_test) VALUES('synthetic-discovery','wxPay','synthetic-app','synthetic-merchant','synthetic-seller','RSA',1)");
            var attempt=ctx.getBean(PaymentAttemptService.class).createOrGet(101L,order,PaymentSuccessEvent.Provider.WECHAT,"synthetic-discovery","synthetic-discovery");
            var event=new PaymentSuccessEvent(PaymentSuccessEvent.Provider.WECHAT,attempt.getMerchantDetailsId(),attempt.getProviderOrderReference(),"synthetic-discovery-transaction",attempt.getAmountCents(),attempt.getAppid(),attempt.getMerchantIdentity(),"SUCCESS",LocalDateTime.now());
            var service=ctx.getBean(PaymentFinalizationService.class);
            var first=service.acceptAttemptVerified(event);var duplicate=service.acceptAttemptVerified(event);
            if(first!=PaymentResult.FIRST_SUCCESS || duplicate!=PaymentResult.IDEMPOTENT_DUPLICATE)throw new AssertionError("SYNTHETIC_COMPLETION_OR_DUPLICATE_FAILED:"+first+":"+duplicate);
            Files.writeString(Path.of(args[2]),"{\"first\":\"FIRST_SUCCESS\",\"duplicate\":\"IDEMPOTENT_DUPLICATE\",\"providerRequests\":0}");
        }
    }
}
