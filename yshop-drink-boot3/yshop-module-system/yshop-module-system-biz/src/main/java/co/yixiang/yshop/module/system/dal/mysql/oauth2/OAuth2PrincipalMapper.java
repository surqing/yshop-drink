package co.yixiang.yshop.module.system.dal.mysql.oauth2;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import co.yixiang.yshop.framework.tenant.core.aop.TenantIgnore;

/** Canonical identity comes from the token, never a request UID or Redis snapshot. */
@Mapper
public interface OAuth2PrincipalMapper {
    @TenantIgnore
    @Select("SELECT status FROM yshop_user WHERE id=#{id} AND deleted=0 FOR UPDATE")
    Integer lockMemberStatus(Long id);
    @TenantIgnore
    @Select("SELECT status FROM system_users WHERE id=#{id} AND deleted=0 FOR UPDATE")
    Integer lockAdminStatus(Long id);
}
