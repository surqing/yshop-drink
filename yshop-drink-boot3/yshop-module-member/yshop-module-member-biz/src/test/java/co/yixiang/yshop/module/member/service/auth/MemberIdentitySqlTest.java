package co.yixiang.yshop.module.member.service.auth;

import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the actual mapper statements through the production interceptor's parser. */
class MemberIdentitySqlTest {
    void verify(String method, String column, boolean mini) throws Exception {
        var mapperMethod=java.util.Arrays.stream(MemberUserMapper.class.getMethods())
                .filter(value->value.getName().equals(method)).findFirst().orElseThrow();
        String annotation=String.join(" ",mapperMethod.getAnnotation(Update.class).value());
        var source=new XMLLanguageDriver().createSqlSource(new Configuration(),annotation,java.util.Map.class);
        for(String previous:new String[]{null,"synthetic-previous"}) {
            var parameters=new HashMap<String,Object>();
            parameters.put("uid",101L);parameters.put("next","synthetic-next");
            parameters.put("previous",previous);parameters.put("mini",mini);
            var bound=source.getBoundSql(parameters);
            String sql=bound.getSql().replaceAll("\\s+"," ").trim();
            assertDoesNotThrow(()->CCJSqlParserUtil.parse(sql),"identity write must pass the full application interceptor");
            assertTrue(sql.contains("WHERE id=? AND "+column));
            assertTrue(sql.endsWith("AND deleted=0"));
            assertTrue(sql.contains(previous==null?column+" IS NULL":column+" = ?"));
            assertEquals(previous==null?2:3,bound.getParameterMappings().size());
        }
    }
    @Test void mobileCompareAndSetParsesWithNullAndExistingOwner() throws Exception {verify("changeMobile","mobile",true);}
    @Test void miniCompareAndSetParsesWithNullAndExistingOwner() throws Exception {verify("changeProvider","routine_openid",true);}
    @Test void mpCompareAndSetParsesWithNullAndExistingOwner() throws Exception {verify("changeProvider","openid",false);}
}
