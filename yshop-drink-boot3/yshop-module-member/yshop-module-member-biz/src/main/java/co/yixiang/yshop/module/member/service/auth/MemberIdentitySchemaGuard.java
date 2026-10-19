package co.yixiang.yshop.module.member.service.auth;

import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Refuse identity traffic on a schema lacking the migration's full-value guarantees. */
@Component
public class MemberIdentitySchemaGuard implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final boolean tenantEnabled;
    private final String miniAppId,mpAppId;
    public MemberIdentitySchemaGuard(JdbcTemplate jdbc,@Value("${yshop.tenant.enable:false}") boolean tenantEnabled,
                                    @Value("${wx.miniapp.appid:}") String miniAppId,@Value("${wx.mp.app-id:}") String mpAppId) {
        this.jdbc=jdbc;this.tenantEnabled=tenantEnabled;this.miniAppId=miniAppId;this.mpAppId=mpAppId;
    }
    @Override public void run(ApplicationArguments args){verify();}
    public void verify() {
        if(tenantEnabled)fail();
        var expected=Map.of("uk_member_mobile","mobile","uk_member_openid","openid","uk_member_routine_openid","routine_openid");
        var indexes=jdbc.queryForList("SELECT INDEX_NAME,COLUMN_NAME,NON_UNIQUE,SEQ_IN_INDEX,SUB_PART FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_user' AND INDEX_NAME IN ('uk_member_mobile','uk_member_openid','uk_member_routine_openid')");
        if(indexes.size()!=3)fail();
        var seen=new HashSet<String>();
        for(var row:indexes) {
            String name=(String)row.get("INDEX_NAME");seen.add(name);
            if(!Objects.equals(expected.get(name),row.get("COLUMN_NAME")) || ((Number)row.get("NON_UNIQUE")).intValue()!=0
                    || ((Number)row.get("SEQ_IN_INDEX")).intValue()!=1 || row.get("SUB_PART")!=null)fail();
        }
        if(!seen.equals(expected.keySet()))fail();
        var columns=jdbc.queryForList("SELECT COLUMN_NAME,COLLATION_NAME,IS_NULLABLE,CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_user' AND COLUMN_NAME IN ('mobile','openid','routine_openid')");
        if(columns.size()!=3)fail();
        for(var row:columns)if(!"ascii_bin".equals(row.get("COLLATION_NAME")) || !"YES".equals(row.get("IS_NULLABLE"))
                || ((Number)row.get("CHARACTER_MAXIMUM_LENGTH")).intValue()!=("mobile".equals(row.get("COLUMN_NAME"))?11:100))fail();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM yshop_user WHERE COALESCE(tenant_id,0)<>0",Long.class)!=0
                || jdbc.queryForObject("SELECT COUNT(*) FROM system_social_user_bind WHERE user_type=1",Long.class)!=0)fail();
        // Some original installations have no social-client table. No MEMBER overrides
        // exist there. If present, every enabled MEMBER namespace must match the fixed SDK.
        if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='system_social_client'",Long.class)!=0) {
            var clients=jdbc.queryForList("SELECT social_type,client_id,tenant_id FROM system_social_client WHERE user_type=1 AND status=0 AND deleted=0");
            for(var client:clients){int type=((Number)client.get("social_type")).intValue();String configured=type==34?miniAppId:type==31?mpAppId:"";
                if(configured.isBlank() || !configured.equals(client.get("client_id")) || (client.get("tenant_id")!=null && ((Number)client.get("tenant_id")).longValue()!=0))fail();}
        }
    }
    private static void fail(){throw new IllegalStateException("MEMBER_IDENTITY_MIGRATION_OR_NAMESPACE_REQUIRED");}
}
