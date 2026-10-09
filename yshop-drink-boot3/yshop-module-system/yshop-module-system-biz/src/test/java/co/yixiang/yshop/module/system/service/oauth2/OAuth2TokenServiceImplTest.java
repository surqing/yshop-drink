package co.yixiang.yshop.module.system.service.oauth2;

import cn.hutool.core.date.LocalDateTimeUtil;
import co.yixiang.yshop.framework.common.enums.UserTypeEnum;
import co.yixiang.yshop.framework.common.exception.ErrorCode;
import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.framework.common.util.date.DateUtils;
import co.yixiang.yshop.framework.tenant.core.context.TenantContextHolder;
import co.yixiang.yshop.framework.test.core.ut.BaseDbAndRedisUnitTest;
import co.yixiang.yshop.module.system.controller.admin.oauth2.vo.token.OAuth2AccessTokenPageReqVO;
import co.yixiang.yshop.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;
import co.yixiang.yshop.module.system.dal.dataobject.oauth2.OAuth2ClientDO;
import co.yixiang.yshop.module.system.dal.dataobject.oauth2.OAuth2RefreshTokenDO;
import co.yixiang.yshop.module.system.dal.dataobject.user.AdminUserDO;
import co.yixiang.yshop.module.system.dal.mysql.oauth2.OAuth2AccessTokenMapper;
import co.yixiang.yshop.module.system.dal.mysql.oauth2.OAuth2RefreshTokenMapper;
import co.yixiang.yshop.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import co.yixiang.yshop.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.assertj.core.util.Lists;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static co.yixiang.yshop.framework.common.util.object.ObjectUtils.cloneIgnoreId;
import static co.yixiang.yshop.framework.test.core.util.AssertUtils.assertPojoEquals;
import static co.yixiang.yshop.framework.test.core.util.AssertUtils.assertServiceException;
import static co.yixiang.yshop.framework.test.core.util.RandomUtils.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link OAuth2TokenServiceImpl} 的单元测试类
 *
 * @author yshop
 */
@Import({OAuth2TokenServiceImpl.class, OAuth2AccessTokenRedisDAO.class})
public class OAuth2TokenServiceImplTest extends BaseDbAndRedisUnitTest {
    @MockBean
    private co.yixiang.yshop.module.store.dal.mysql.storeshop.StoreShopMapper storeShopMapper;

    @Resource
    private OAuth2TokenServiceImpl oauth2TokenService;

    @Resource
    private OAuth2AccessTokenMapper oauth2AccessTokenMapper;
    @Resource
    private OAuth2RefreshTokenMapper oauth2RefreshTokenMapper;

    @Resource
    private OAuth2AccessTokenRedisDAO oauth2AccessTokenRedisDAO;

    @MockBean
    private OAuth2ClientService oauth2ClientService;
    @MockBean
    private AdminUserService adminUserService;
    @Resource private javax.sql.DataSource dataSource;
    @MockBean private co.yixiang.yshop.module.system.dal.mysql.oauth2.OAuth2PrincipalMapper principals;
    @org.junit.jupiter.api.BeforeEach void principalFixture() {
        org.mockito.Mockito.when(principals.lockAdminStatus(org.mockito.ArgumentMatchers.anyLong())).thenReturn(0);
        org.mockito.Mockito.when(principals.lockMemberStatus(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(c -> {
            var rows=new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForList(
                    "SELECT status FROM yshop_user WHERE id=? AND deleted=0",Integer.class,(Object)c.getArgument(0));
            return rows.isEmpty()?null:rows.get(0);
        });
    }

    @Test public void disabledMemberCannotRefreshAnExistingSession() { memberRefreshDenied(1, false); }
    @Test public void deletedMemberCannotRefreshAnExistingSession() { memberRefreshDenied(0, true); }
    private void memberRefreshDenied(int status, boolean deleted) {
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS yshop_user(id BIGINT PRIMARY KEY,status INT,deleted BOOLEAN)");
        jdbc.update("DELETE FROM yshop_user WHERE id=331");
        jdbc.update("INSERT INTO yshop_user VALUES(331,?,?)",status,deleted);
        var refresh=randomPojo(OAuth2RefreshTokenDO.class).setUserId(331L).setUserType(1)
                .setClientId("synthetic-client").setExpiresTime(LocalDateTime.now().withNano(0).plusDays(1));
        oauth2RefreshTokenMapper.insert(refresh);
        when(oauth2ClientService.validOAuthClientFromCache("synthetic-client"))
                .thenReturn(new OAuth2ClientDO().setClientId("synthetic-client").setAccessTokenValiditySeconds(60));
        assertThrows(co.yixiang.yshop.framework.common.exception.ServiceException.class,
                () -> oauth2TokenService.refreshAccessToken(refresh.getRefreshToken(),"synthetic-client"));
        assertTrue(oauth2AccessTokenMapper.selectListByRefreshToken(refresh.getRefreshToken()).isEmpty());
        jdbc.update("DELETE FROM yshop_user WHERE id=331");
    }

    @Test
    public void revokedFamilyCannotBeAuthorizedFromStaleRedis() {
        var access = randomPojo(OAuth2AccessTokenDO.class).setExpiresTime(LocalDateTime.now().withNano(0).plusDays(1));
        // A second instance can retain this cache after database revocation.
        oauth2AccessTokenRedisDAO.set(access);
        assertThrows(co.yixiang.yshop.framework.common.exception.ServiceException.class,
                () -> oauth2TokenService.checkAccessToken(access.getAccessToken()));
    }

    @Test
    public void logoutRevokesEveryAccessTokenInTheRefreshFamily() {
        var refresh = randomPojo(OAuth2RefreshTokenDO.class).setUserType(2).setExpiresTime(LocalDateTime.now().withNano(0).plusDays(1));
        oauth2RefreshTokenMapper.insert(refresh);
        var first = randomPojo(OAuth2AccessTokenDO.class).setRefreshToken(refresh.getRefreshToken())
                .setUserId(refresh.getUserId()).setUserType(refresh.getUserType());
        var second = randomPojo(OAuth2AccessTokenDO.class).setRefreshToken(refresh.getRefreshToken())
                .setUserId(refresh.getUserId()).setUserType(refresh.getUserType());
        oauth2AccessTokenMapper.insert(first);oauth2AccessTokenMapper.insert(second);
        oauth2TokenService.removeAccessToken(first.getAccessToken());
        assertNull(oauth2AccessTokenMapper.selectByAccessToken(second.getAccessToken()));
        assertNull(oauth2RefreshTokenMapper.selectByRefreshToken(refresh.getRefreshToken()));
    }

    @Test
    public void testCreateAccessToken() {
        TenantContextHolder.setTenantId(0L);
        // 准备参数
        Long userId = randomLongId();
        Integer userType = UserTypeEnum.ADMIN.getValue();
        String clientId = randomString();
        List<String> scopes = Lists.newArrayList("read", "write");
        // mock 方法
        OAuth2ClientDO clientDO = randomPojo(OAuth2ClientDO.class).setClientId(clientId)
                .setAccessTokenValiditySeconds(30).setRefreshTokenValiditySeconds(60);
        when(oauth2ClientService.validOAuthClientFromCache(eq(clientId))).thenReturn(clientDO);
        // mock 数据（用户）
        AdminUserDO user = randomPojo(AdminUserDO.class);
        when(adminUserService.getUser(userId)).thenReturn(user);

        // 调用
        OAuth2AccessTokenDO accessTokenDO = oauth2TokenService.createAccessToken(userId, userType, clientId, scopes);
        // 断言访问令牌
        OAuth2AccessTokenDO dbAccessTokenDO = oauth2AccessTokenMapper.selectByAccessToken(accessTokenDO.getAccessToken());
        assertPojoEquals(accessTokenDO, dbAccessTokenDO, "createTime", "updateTime", "deleted");
        assertEquals(userId, accessTokenDO.getUserId());
        assertEquals(userType, accessTokenDO.getUserType());
        assertEquals(2, accessTokenDO.getUserInfo().size());
        assertEquals(user.getNickname(), accessTokenDO.getUserInfo().get("nickname"));
        assertEquals(user.getDeptId().toString(), accessTokenDO.getUserInfo().get("deptId"));
        assertEquals(clientId, accessTokenDO.getClientId());
        assertEquals(scopes, accessTokenDO.getScopes());
        assertFalse(DateUtils.isExpired(accessTokenDO.getExpiresTime()));
        // 断言访问令牌的缓存
        OAuth2AccessTokenDO redisAccessTokenDO = oauth2AccessTokenRedisDAO.get(accessTokenDO.getAccessToken());
        assertPojoEquals(accessTokenDO, redisAccessTokenDO, "createTime", "updateTime", "deleted");
        // 断言刷新令牌
        OAuth2RefreshTokenDO refreshTokenDO = oauth2RefreshTokenMapper.selectList().get(0);
        assertPojoEquals(accessTokenDO, refreshTokenDO, "id", "expiresTime", "createTime", "updateTime", "deleted");
        assertFalse(DateUtils.isExpired(refreshTokenDO.getExpiresTime()));
    }

    @Test
    public void testRefreshAccessToken_null() {
        // 准备参数
        String refreshToken = randomString();
        String clientId = randomString();
        // mock 方法

        // 调用，并断言
        assertServiceException(() -> oauth2TokenService.refreshAccessToken(refreshToken, clientId),
                new ErrorCode(400, "无效的刷新令牌"));
    }

    @Test
    public void testRefreshAccessToken_clientIdError() {
        // 准备参数
        String refreshToken = randomString();
        String clientId = randomString();
        // mock 方法
        OAuth2ClientDO clientDO = randomPojo(OAuth2ClientDO.class).setClientId(clientId);
        when(oauth2ClientService.validOAuthClientFromCache(eq(clientId))).thenReturn(clientDO);
        // mock 数据（访问令牌）
        OAuth2RefreshTokenDO refreshTokenDO = randomPojo(OAuth2RefreshTokenDO.class)
                .setRefreshToken(refreshToken).setClientId("error");
        oauth2RefreshTokenMapper.insert(refreshTokenDO);

        // 调用，并断言
        assertServiceException(() -> oauth2TokenService.refreshAccessToken(refreshToken, clientId),
                new ErrorCode(400, "刷新令牌的客户端编号不正确"));
    }

    @Test
    public void testRefreshAccessToken_expired() {
        // 准备参数
        String refreshToken = randomString();
        String clientId = randomString();
        // mock 方法
        OAuth2ClientDO clientDO = randomPojo(OAuth2ClientDO.class).setClientId(clientId);
        when(oauth2ClientService.validOAuthClientFromCache(eq(clientId))).thenReturn(clientDO);
        // mock 数据（访问令牌）
        OAuth2RefreshTokenDO refreshTokenDO = randomPojo(OAuth2RefreshTokenDO.class)
                .setRefreshToken(refreshToken).setClientId(clientId)
                .setExpiresTime(LocalDateTime.now().withNano(0).minusDays(1));
        oauth2RefreshTokenMapper.insert(refreshTokenDO);

        // 调用，并断言
        assertServiceException(() -> oauth2TokenService.refreshAccessToken(refreshToken, clientId),
                new ErrorCode(401, "刷新令牌已过期"));
        assertEquals(1, oauth2RefreshTokenMapper.selectCount());
        assertEquals(refreshTokenDO.getId(),oauth2RefreshTokenMapper.selectByRefreshToken(refreshToken).getId());
    }

    @Test
    public void testRefreshAccessToken_success() {
        TenantContextHolder.setTenantId(0L);
        // 准备参数
        String refreshToken = randomString();
        String clientId = randomString();
        // mock 方法
        OAuth2ClientDO clientDO = randomPojo(OAuth2ClientDO.class).setClientId(clientId)
                .setAccessTokenValiditySeconds(30);
        when(oauth2ClientService.validOAuthClientFromCache(eq(clientId))).thenReturn(clientDO);
        // mock 数据（访问令牌）
        OAuth2RefreshTokenDO refreshTokenDO = randomPojo(OAuth2RefreshTokenDO.class)
                .setRefreshToken(refreshToken).setClientId(clientId)
                .setExpiresTime(LocalDateTime.now().withNano(0).plusDays(1))
                .setUserType(UserTypeEnum.ADMIN.getValue());
        oauth2RefreshTokenMapper.insert(refreshTokenDO);
        // mock 数据（访问令牌）
        OAuth2AccessTokenDO accessTokenDO = randomPojo(OAuth2AccessTokenDO.class).setRefreshToken(refreshToken)
                .setUserType(refreshTokenDO.getUserType());
        oauth2AccessTokenMapper.insert(accessTokenDO);
        oauth2AccessTokenRedisDAO.set(accessTokenDO);
        // mock 数据（用户）
        AdminUserDO user = randomPojo(AdminUserDO.class);
        when(adminUserService.getUser(refreshTokenDO.getUserId())).thenReturn(user);

        // 调用
        OAuth2AccessTokenDO newAccessTokenDO = oauth2TokenService.refreshAccessToken(refreshToken, clientId);
        // 断言，老的访问令牌被删除
        assertNull(oauth2AccessTokenMapper.selectByAccessToken(accessTokenDO.getAccessToken()));
        assertNull(oauth2AccessTokenRedisDAO.get(accessTokenDO.getAccessToken()));
        // 断言，新的访问令牌
        OAuth2AccessTokenDO dbAccessTokenDO = oauth2AccessTokenMapper.selectByAccessToken(newAccessTokenDO.getAccessToken());
        assertPojoEquals(newAccessTokenDO, dbAccessTokenDO, "createTime", "updateTime", "deleted");
        assertPojoEquals(newAccessTokenDO, refreshTokenDO, "id", "expiresTime", "createTime", "updateTime", "deleted",
                "creator", "updater");
        assertFalse(DateUtils.isExpired(newAccessTokenDO.getExpiresTime()));
        // 断言，新的访问令牌的缓存
        OAuth2AccessTokenDO redisAccessTokenDO = oauth2AccessTokenRedisDAO.get(newAccessTokenDO.getAccessToken());
        assertPojoEquals(newAccessTokenDO, redisAccessTokenDO, "createTime", "updateTime", "deleted");
    }

    @Test
    public void testGetAccessToken() {
        // mock 数据（访问令牌）
        OAuth2AccessTokenDO accessTokenDO = randomPojo(OAuth2AccessTokenDO.class)
                .setExpiresTime(LocalDateTime.now().withNano(0).plusDays(1));
        oauth2AccessTokenMapper.insert(accessTokenDO);
        // 准备参数
        String accessToken = accessTokenDO.getAccessToken();

        // 调用
        OAuth2AccessTokenDO result = oauth2TokenService.getAccessToken(accessToken);
        // 断言
        assertPojoEquals(accessTokenDO, result, "createTime", "updateTime", "deleted",
                "creator", "updater");
        assertPojoEquals(accessTokenDO, oauth2AccessTokenRedisDAO.get(accessToken), "createTime", "updateTime", "deleted",
                "creator", "updater");
    }

    @Test
    public void testCheckAccessToken_null() {
        // 调研，并断言
        assertServiceException(() -> oauth2TokenService.checkAccessToken(randomString()),
                new ErrorCode(401, "访问令牌不存在"));
    }

    @Test
    public void testCheckAccessToken_expired() {
        // mock 数据（访问令牌）
        OAuth2AccessTokenDO accessTokenDO = randomPojo(OAuth2AccessTokenDO.class)
                .setExpiresTime(LocalDateTime.now().withNano(0).minusDays(1));
        oauth2AccessTokenMapper.insert(accessTokenDO);
        // 准备参数
        String accessToken = accessTokenDO.getAccessToken();

        // 调研，并断言
        assertServiceException(() -> oauth2TokenService.checkAccessToken(accessToken),
                new ErrorCode(401, "访问令牌已过期"));
    }

    @Test
    public void testCheckAccessToken_success() {
        // mock 数据（访问令牌）
        OAuth2AccessTokenDO accessTokenDO = randomPojo(OAuth2AccessTokenDO.class)
                .setUserType(2).setExpiresTime(LocalDateTime.now().withNano(0).plusDays(1));
        oauth2AccessTokenMapper.insert(accessTokenDO);
        oauth2RefreshTokenMapper.insert(randomPojo(OAuth2RefreshTokenDO.class)
                .setRefreshToken(accessTokenDO.getRefreshToken()).setUserId(accessTokenDO.getUserId()).setUserType(2)
                .setClientId(accessTokenDO.getClientId()).setExpiresTime(LocalDateTime.now().withNano(0).plusDays(2)));
        // 准备参数
        String accessToken = accessTokenDO.getAccessToken();

        // 调研，并断言
        OAuth2AccessTokenDO result = oauth2TokenService.checkAccessToken(accessToken);
        // 断言
        assertPojoEquals(accessTokenDO, result, "createTime", "updateTime", "deleted",
                "creator", "updater");
    }

    @Test
    public void testRemoveAccessToken_null() {
        // 调用，并断言
        assertNull(oauth2TokenService.removeAccessToken(randomString()));
    }

    @Test
    public void testRemoveAccessToken_success() {
        // mock 数据（访问令牌）
        OAuth2AccessTokenDO accessTokenDO = randomPojo(OAuth2AccessTokenDO.class)
                .setExpiresTime(LocalDateTime.now().withNano(0).plusDays(1));
        oauth2AccessTokenMapper.insert(accessTokenDO);
        // mock 数据（刷新令牌）
        accessTokenDO.setUserType(2);
        oauth2AccessTokenMapper.updateById(accessTokenDO);
        OAuth2RefreshTokenDO refreshTokenDO = randomPojo(OAuth2RefreshTokenDO.class)
                .setUserId(accessTokenDO.getUserId()).setUserType(2)
                .setRefreshToken(accessTokenDO.getRefreshToken());
        oauth2RefreshTokenMapper.insert(refreshTokenDO);
        // 调用
        OAuth2AccessTokenDO result = oauth2TokenService.removeAccessToken(accessTokenDO.getAccessToken());
        assertPojoEquals(accessTokenDO, result, "createTime", "updateTime", "deleted",
                "creator", "updater");
        // 断言数据
        assertNull(oauth2AccessTokenMapper.selectByAccessToken(accessTokenDO.getAccessToken()));
        assertNull(oauth2RefreshTokenMapper.selectByRefreshToken(accessTokenDO.getRefreshToken()));
        assertNull(oauth2AccessTokenRedisDAO.get(accessTokenDO.getAccessToken()));
    }


    @Test
    public void testGetAccessTokenPage() {
        // mock 数据
        OAuth2AccessTokenDO dbAccessToken = randomPojo(OAuth2AccessTokenDO.class, o -> { // 等会查询到
            o.setUserId(10L);
            o.setUserType(1);
            o.setClientId("test_client");
            o.setExpiresTime(LocalDateTime.now().withNano(0).plusDays(1));
        });
        oauth2AccessTokenMapper.insert(dbAccessToken);
        // 测试 userId 不匹配
        oauth2AccessTokenMapper.insert(cloneIgnoreId(dbAccessToken, o -> o.setUserId(20L)));
        // 测试 userType 不匹配
        oauth2AccessTokenMapper.insert(cloneIgnoreId(dbAccessToken, o -> o.setUserType(2)));
        // 测试 userType 不匹配
        oauth2AccessTokenMapper.insert(cloneIgnoreId(dbAccessToken, o -> o.setClientId("it_client")));
        // 测试 expireTime 不匹配
        oauth2AccessTokenMapper.insert(cloneIgnoreId(dbAccessToken, o -> o.setExpiresTime(LocalDateTimeUtil.now())));
        // 准备参数
        OAuth2AccessTokenPageReqVO reqVO = new OAuth2AccessTokenPageReqVO();
        reqVO.setUserId(10L);
        reqVO.setUserType(1);
        reqVO.setClientId("test");

        // 调用
        PageResult<OAuth2AccessTokenDO> pageResult = oauth2TokenService.getAccessTokenPage(reqVO);
        // 断言
        assertEquals(1, pageResult.getTotal());
        assertEquals(1, pageResult.getList().size());
        assertPojoEquals(dbAccessToken, pageResult.getList().get(0));
    }

    @Test public void nanosecondClockHasExactDatabaseRepresentableExpiry() {
        var fixed=java.time.Clock.fixed(java.time.Instant.parse("2030-01-01T00:00:00.123456789Z"),java.time.ZoneOffset.UTC);
        org.springframework.test.util.ReflectionTestUtils.setField(oauth2TokenService,"clock",fixed);
        try {
            when(oauth2ClientService.validOAuthClientFromCache("synthetic-client"))
                    .thenReturn(new OAuth2ClientDO().setClientId("synthetic-client").setAccessTokenValiditySeconds(60).setRefreshTokenValiditySeconds(3600));
            when(adminUserService.getUser(7L)).thenReturn(new AdminUserDO().setNickname("synthetic-admin").setDeptId(1L));
            var access=oauth2TokenService.createAccessToken(7L,2,"synthetic-client",List.of());
            assertEquals(LocalDateTime.of(2030,1,1,0,1),access.getExpiresTime());
            assertEquals(access.getExpiresTime(),oauth2AccessTokenMapper.selectByAccessToken(access.getAccessToken()).getExpiresTime());
            assertEquals(LocalDateTime.of(2030,1,1,1,0),oauth2RefreshTokenMapper.selectByRefreshToken(access.getRefreshToken()).getExpiresTime());
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(oauth2TokenService,"clock",java.time.Clock.systemDefaultZone());}
    }
}
