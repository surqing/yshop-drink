package co.yixiang.yshop.module.system.service.oauth2;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import co.yixiang.yshop.framework.common.enums.UserTypeEnum;
import co.yixiang.yshop.framework.common.exception.enums.GlobalErrorCodeConstants;
import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.framework.common.util.date.DateUtils;
import co.yixiang.yshop.framework.security.core.LoginUser;
import co.yixiang.yshop.framework.tenant.core.context.TenantContextHolder;
import co.yixiang.yshop.module.store.dal.dataobject.storeshop.StoreShopDO;
import co.yixiang.yshop.module.store.dal.mysql.storeshop.StoreShopMapper;
import co.yixiang.yshop.module.system.controller.admin.oauth2.vo.token.OAuth2AccessTokenPageReqVO;
import co.yixiang.yshop.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;
import co.yixiang.yshop.module.system.dal.dataobject.oauth2.OAuth2ClientDO;
import co.yixiang.yshop.module.system.dal.dataobject.oauth2.OAuth2RefreshTokenDO;
import co.yixiang.yshop.module.system.dal.dataobject.user.AdminUserDO;
import co.yixiang.yshop.module.system.dal.mysql.oauth2.OAuth2AccessTokenMapper;
import co.yixiang.yshop.module.system.dal.mysql.oauth2.OAuth2RefreshTokenMapper;
import co.yixiang.yshop.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import co.yixiang.yshop.module.system.service.user.AdminUserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import co.yixiang.yshop.framework.common.enums.CommonStatusEnum;
import co.yixiang.yshop.module.system.dal.mysql.oauth2.OAuth2PrincipalMapper;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static co.yixiang.yshop.framework.common.exception.util.ServiceExceptionUtil.exception0;
import static co.yixiang.yshop.framework.common.util.collection.CollectionUtils.convertSet;

/**
 * OAuth2.0 Token Service 实现类
 *
 * @author yshop
 */
@Service
@lombok.extern.slf4j.Slf4j
public class OAuth2TokenServiceImpl implements OAuth2TokenService {
    private java.time.Clock clock=java.time.Clock.systemDefaultZone();

    @Resource private OAuth2PrincipalMapper principals;
    @Resource
    private OAuth2AccessTokenMapper oauth2AccessTokenMapper;
    @Resource
    private OAuth2RefreshTokenMapper oauth2RefreshTokenMapper;

    @Resource
    private OAuth2AccessTokenRedisDAO oauth2AccessTokenRedisDAO;

    @Resource
    private OAuth2ClientService oauth2ClientService;
    @Resource
    @Lazy // 懒加载，避免循环依赖
    private AdminUserService adminUserService;
    @Resource
    private StoreShopMapper storeShopMapper;

    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED, rollbackFor=Exception.class)
    public OAuth2AccessTokenDO createAccessToken(Long userId, Integer userType, String clientId, List<String> scopes) {
        requireEnabledPrincipal(userId,userType);
        OAuth2ClientDO clientDO = oauth2ClientService.validOAuthClientFromCache(clientId);
        // 创建刷新令牌
        OAuth2RefreshTokenDO refreshTokenDO = createOAuth2RefreshToken(userId, userType, clientDO, scopes);
        // 创建访问令牌
        return createOAuth2AccessToken(refreshTokenDO, clientDO);
    }

    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED, rollbackFor=Exception.class)
    public OAuth2AccessTokenDO refreshAccessToken(String token, String clientId) {
        return refreshAccessToken(token,clientId,null);
    }

    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED, rollbackFor=Exception.class)
    public OAuth2AccessTokenDO refreshAccessToken(String token, String clientId, Integer expectedType) {
        OAuth2RefreshTokenDO hint=oauth2RefreshTokenMapper.selectByRefreshToken(token);
        if(hint==null) throw exception0(400,"无效的刷新令牌");
        if(!Objects.equals(clientId,hint.getClientId())) throw exception0(400,"刷新令牌的客户端编号不正确");
        requireType(hint.getUserType(),expectedType);
        if(DateUtils.isExpired(hint.getExpiresTime())) throw exception0(401,"刷新令牌已过期");
        // All lifecycle operations lock principal -> refresh family -> access rows.
        requireEnabledPrincipal(hint.getUserId(),hint.getUserType());
        OAuth2RefreshTokenDO current=oauth2RefreshTokenMapper.lockByRefreshToken(token);
        if(current==null || !Objects.equals(current.getUserId(),hint.getUserId())
                || !Objects.equals(current.getUserType(),hint.getUserType())) throw exception0(401,"刷新令牌已撤销");
        if(DateUtils.isExpired(current.getExpiresTime())) throw exception0(401,"刷新令牌已过期");
        OAuth2ClientDO client=oauth2ClientService.validOAuthClientFromCache(clientId);
        removeFamilyAccess(token);
        // Preserve the existing reusable-refresh contract; serialized rotation leaves one access token.
        return createOAuth2AccessToken(current,client);
    }

    @Override
    public OAuth2AccessTokenDO getAccessToken(String accessToken) {
        // 优先从 Redis 中获取
        OAuth2AccessTokenDO accessTokenDO = oauth2AccessTokenRedisDAO.get(accessToken);
        if (accessTokenDO != null) {
            return accessTokenDO;
        }

        // 获取不到，从 MySQL 中获取
        accessTokenDO = oauth2AccessTokenMapper.selectByAccessToken(accessToken);
        // 如果在 MySQL 存在，则往 Redis 中写入
        if (accessTokenDO != null && !DateUtils.isExpired(accessTokenDO.getExpiresTime())) {
            oauth2AccessTokenRedisDAO.set(accessTokenDO);
        }
        return accessTokenDO;
    }

    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED, rollbackFor=Exception.class)
    public OAuth2AccessTokenDO checkAccessToken(String token) {
        // Redis is an acceleration cache, never revocation authority (including across instances).
        OAuth2AccessTokenDO hint=oauth2AccessTokenMapper.selectByAccessToken(token);
        if(hint==null) throw exception0(401,"访问令牌不存在");
        if(DateUtils.isExpired(hint.getExpiresTime())) throw exception0(401,"访问令牌已过期");
        requireEnabledPrincipal(hint.getUserId(),hint.getUserType());
        OAuth2RefreshTokenDO family=oauth2RefreshTokenMapper.lockByRefreshToken(hint.getRefreshToken());
        if(family==null || DateUtils.isExpired(family.getExpiresTime())
                || !Objects.equals(family.getUserId(),hint.getUserId())
                || !Objects.equals(family.getUserType(),hint.getUserType())
                || !Objects.equals(family.getClientId(),hint.getClientId())) throw exception0(401,"访问令牌已撤销");
        OAuth2AccessTokenDO current=oauth2AccessTokenMapper.selectByAccessToken(token);
        if(current==null || DateUtils.isExpired(current.getExpiresTime())) throw exception0(401,"访问令牌已撤销");
        return current;
    }

    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED, rollbackFor=Exception.class)
    public OAuth2AccessTokenDO removeAccessToken(String token) { return removeAccessToken(token,null); }

    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED, rollbackFor=Exception.class)
    public OAuth2AccessTokenDO removeAccessToken(String token,Integer expectedType) {
        // Old rotated access tokens retain their family link for a racing logout.
        OAuth2AccessTokenDO hint=oauth2AccessTokenMapper.selectIncludingRevoked(token);
        if(hint==null) return null;
        requireType(hint.getUserType(),expectedType);
        lockPrincipal(hint.getUserId(),hint.getUserType()); // Logout is allowed for a disabled account.
        OAuth2RefreshTokenDO family=oauth2RefreshTokenMapper.lockByRefreshToken(hint.getRefreshToken());
        if(family==null) return null;
        if(!Objects.equals(hint.getUserId(),family.getUserId()) || !Objects.equals(hint.getUserType(),family.getUserType()))
            throw exception0(401,"令牌身份不一致");
        removeFamilyAccess(hint.getRefreshToken());
        oauth2RefreshTokenMapper.deleteById(family.getId());
        return hint;
    }

    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED, rollbackFor=Exception.class)
    public void revokeUserTokens(Long userId,Integer userType) {
        lockPrincipal(userId,userType);
        for(var row:oauth2RefreshTokenMapper.lockByUser(userId,userType)) {
            removeFamilyAccess(row.getRefreshToken());oauth2RefreshTokenMapper.deleteById(row.getId());
        }
    }

    private Integer lockPrincipal(Long userId,Integer type) {
        if(Objects.equals(type,UserTypeEnum.MEMBER.getValue())) return principals.lockMemberStatus(userId);
        if(Objects.equals(type,UserTypeEnum.ADMIN.getValue())) return principals.lockAdminStatus(userId);
        throw exception0(401,"无效的用户类型");
    }
    private void requireEnabledPrincipal(Long id,Integer type) {
        if(!Objects.equals(lockPrincipal(id,type),CommonStatusEnum.ENABLE.getStatus()))
            throw exception0(401,"账户不可用");
    }
    private void requireType(Integer actual,Integer expected) {
        if(expected!=null && !Objects.equals(actual,expected)) throw exception0(401,"错误的用户类型");
    }
    private void removeFamilyAccess(String token) {
        var rows=oauth2AccessTokenMapper.selectListByRefreshToken(token);
        if(!rows.isEmpty()) {
            oauth2AccessTokenMapper.deleteBatchIds(convertSet(rows,OAuth2AccessTokenDO::getId));
            cacheAfterCommit(()->oauth2AccessTokenRedisDAO.deleteList(convertSet(rows,OAuth2AccessTokenDO::getAccessToken)));
        }
    }
    private void cacheAfterCommit(Runnable write) {
        Runnable safe=()->{try{write.run();}catch(RuntimeException ignored){log.warn("TOKEN_CACHE_UPDATE_UNAVAILABLE");}};
        if(TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){safe.run();}
            });
        else safe.run();
    }

    @Override
    public PageResult<OAuth2AccessTokenDO> getAccessTokenPage(OAuth2AccessTokenPageReqVO reqVO) {
        return oauth2AccessTokenMapper.selectPage(reqVO);
    }

    private OAuth2AccessTokenDO createOAuth2AccessToken(OAuth2RefreshTokenDO refreshTokenDO, OAuth2ClientDO clientDO) {
        OAuth2AccessTokenDO accessTokenDO = new OAuth2AccessTokenDO().setAccessToken(generateAccessToken())
                .setShopId(refreshTokenDO.getShopId())
                .setUserId(refreshTokenDO.getUserId()).setUserType(refreshTokenDO.getUserType())
                .setUserInfo(buildUserInfo(refreshTokenDO.getUserId(), refreshTokenDO.getUserType()))
                .setClientId(clientDO.getClientId()).setScopes(refreshTokenDO.getScopes())
                .setRefreshToken(refreshTokenDO.getRefreshToken())
                .setExpiresTime(LocalDateTime.now(clock).withNano(0).plusSeconds(clientDO.getAccessTokenValiditySeconds()));
        accessTokenDO.setTenantId(TenantContextHolder.getTenantId()); // 手动设置租户编号，避免缓存到 Redis 的时候，无对应的租户编号
        oauth2AccessTokenMapper.insert(accessTokenDO);
        // 记录到 Redis 中
        cacheAfterCommit(()->oauth2AccessTokenRedisDAO.set(accessTokenDO));
        return accessTokenDO;
    }

    private OAuth2RefreshTokenDO createOAuth2RefreshToken(Long userId, Integer userType, OAuth2ClientDO clientDO, List<String> scopes) {
        Long shopId = Objects.equals(userType,UserTypeEnum.ADMIN.getValue()) ? getShopId(userId) : 0L;
        OAuth2RefreshTokenDO refreshToken = new OAuth2RefreshTokenDO().setRefreshToken(generateRefreshToken())
                .setShopId(shopId)
                .setUserId(userId).setUserType(userType)
                .setClientId(clientDO.getClientId()).setScopes(scopes)
                .setExpiresTime(LocalDateTime.now(clock).withNano(0).plusSeconds(clientDO.getRefreshTokenValiditySeconds()));
        oauth2RefreshTokenMapper.insert(refreshToken);
        return refreshToken;
    }

    private Long getShopId(Long userId) {
        StoreShopDO storeShopDO = storeShopMapper.selectList(new LambdaQueryWrapper<StoreShopDO>()
                .apply("FIND_IN_SET ({0},admin_id)", userId).orderByAsc(StoreShopDO::getId))
                .stream().findFirst().orElse(null);
        if (storeShopDO == null) {
            return 0L;
        }
        return storeShopDO.getId();

    }

        /**
         * 加载用户信息，方便 {@link co.yixiang.yshop.framework.security.core.LoginUser} 获取到昵称、部门等信息
         *
         * @param userId 用户编号
         * @param userType 用户类型
         * @return 用户信息
         */
    private Map<String, String> buildUserInfo(Long userId, Integer userType) {
        if (userType.equals(UserTypeEnum.ADMIN.getValue())) {
            AdminUserDO user = adminUserService.getUser(userId);
            return MapUtil.builder(LoginUser.INFO_KEY_NICKNAME, user.getNickname())
                    .put(LoginUser.INFO_KEY_DEPT_ID, StrUtil.toStringOrNull(user.getDeptId())).build();
        } else if (userType.equals(UserTypeEnum.MEMBER.getValue())) {
            // 注意：目前 Member 暂时不读取，可以按需实现
            return Collections.emptyMap();
        }
        return null;
    }

    private static String generateAccessToken() {
        return IdUtil.fastSimpleUUID();
    }

    private static String generateRefreshToken() {
        return IdUtil.fastSimpleUUID();
    }

}
