package co.yixiang.yshop.module.system.dal.mysql.oauth2;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import co.yixiang.yshop.framework.tenant.core.aop.TenantIgnore;

/** Canonical identity comes from the token, never a request UID or Redis snapshot. */
@Mapper
public interface OAuth2PrincipalMapper {
    @TenantIgnore
    // Lock the same principal even if incomplete, so logout/revocation remain serialized.
    @Select("SELECT CASE WHEN mobile REGEXP '^1(3[0-9]|4[014-9]|5[0-35-9]|6[25-7]|7[0-8]|8[0-9]|9[0-35-9])[0-9]{8}$' "
            + "AND routine_openid REGEXP '^[A-Za-z0-9_-]{1,100}$' AND routine_openid NOT REGEXP '[^A-Za-z0-9_-]' THEN status ELSE NULL END "
            + "FROM yshop_user WHERE id=#{id} AND deleted=0 FOR UPDATE")
    Integer lockMemberStatus(Long id);
    @TenantIgnore
    @Select("SELECT status FROM system_users WHERE id=#{id} AND deleted=0 FOR UPDATE")
    Integer lockAdminStatus(Long id);
}
