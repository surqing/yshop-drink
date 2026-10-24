package co.yixiang.yshop.module.system.service.permission;

import cn.hutool.extra.spring.SpringUtil;
import co.yixiang.yshop.framework.common.enums.CommonStatusEnum;
import co.yixiang.yshop.framework.test.core.ut.BaseDbUnitTest;
import co.yixiang.yshop.module.system.dal.dataobject.permission.RoleDO;
import co.yixiang.yshop.module.system.dal.dataobject.permission.UserRoleDO;
import co.yixiang.yshop.module.system.dal.mysql.permission.UserRoleMapper;
import co.yixiang.yshop.module.system.dal.redis.RedisKeyConstants;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static co.yixiang.yshop.framework.test.core.util.RandomUtils.randomLongId;
import static co.yixiang.yshop.framework.test.core.util.RandomUtils.randomPojo;
import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@Import({PermissionServiceImpl.class, PermissionServiceRoleCacheEvictionTest.CacheTestConfiguration.class})
class PermissionServiceRoleCacheEvictionTest extends BaseDbUnitTest {

    @Configuration
    @EnableCaching(proxyTargetClass = true)
    static class CacheTestConfiguration {
        @Bean
        CacheManager permissionCacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }

    @Resource
    private PermissionServiceImpl permissionService;
    @Resource
    private UserRoleMapper userRoleMapper;
    @Resource
    private CacheManager cacheManager;

    @MockBean
    private RoleService roleService;
    @MockBean
    private MenuService menuService;
    @MockBean
    private co.yixiang.yshop.module.system.service.dept.DeptService deptService;
    @MockBean
    private co.yixiang.yshop.module.system.service.user.AdminUserService userService;

    @Test
    void replacingUserRolesEvictsCachedHeadquartersAuthorization() {
        try (MockedStatic<SpringUtil> springUtil = mockStatic(SpringUtil.class)) {
            springUtil.when(() -> SpringUtil.getBean(eq(PermissionServiceImpl.class)))
                    .thenReturn(permissionService);

            Long userId = randomLongId();
            userRoleMapper.insert(randomPojo(UserRoleDO.class).setUserId(userId).setRoleId(100L));
            RoleDO headquarters = randomPojo(RoleDO.class, role -> role.setId(100L)
                    .setCode("business_headquarters")
                    .setStatus(CommonStatusEnum.ENABLE.getStatus()));
            when(roleService.getRoleListFromCache(eq(singleton(100L))))
                    .thenReturn(new ArrayList<>(Collections.singletonList(headquarters)));
            when(roleService.getRoleListFromCache(eq(Collections.emptySet())))
                    .thenReturn(new ArrayList<>());

            assertTrue(permissionService.hasAnyRoles(userId, "business_headquarters"));
            assertEquals(Collections.singleton(100L), cacheManager.getCache(RedisKeyConstants.USER_ROLE_ID_LIST)
                    .get(userId, java.util.Set.class));

            permissionService.assignUserRole(userId, Collections.emptySet());

            assertNull(cacheManager.getCache(RedisKeyConstants.USER_ROLE_ID_LIST).get(userId));
            assertFalse(permissionService.hasAnyRoles(userId, "business_headquarters"));
        }
    }
}
