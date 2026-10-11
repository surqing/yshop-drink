package co.yixiang.yshop.module.member.dal.mysql.user;

import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.framework.mybatis.core.mapper.BaseMapperX;
import co.yixiang.yshop.framework.mybatis.core.query.LambdaQueryWrapperX;
import co.yixiang.yshop.module.member.controller.admin.user.vo.UserExportReqVO;
import co.yixiang.yshop.module.member.controller.admin.user.vo.UserPageReqVO;
import co.yixiang.yshop.module.member.dal.dataobject.user.MemberUserDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * 会员 User Mapper
 *
 * @author yshop
 */
@Mapper
public interface MemberUserMapper extends BaseMapperX<MemberUserDO> {

    default MemberUserDO selectByProvider(String identity, boolean mini) {
        return selectOne(new LambdaQueryWrapperX<MemberUserDO>()
                .eq(mini ? MemberUserDO::getRoutineOpenid : MemberUserDO::getOpenid, identity));
    }

    @Update("<script>UPDATE yshop_user SET mobile=#{next} WHERE id=#{uid} AND mobile &lt;=&gt; #{previous} AND deleted=0</script>")
    int changeMobile(@Param("uid") Long uid, @Param("previous") String previous, @Param("next") String next);

    @Update({"<script>UPDATE yshop_user SET <choose><when test='mini'>routine_openid</when><otherwise>openid</otherwise></choose> = #{next}",
            "WHERE id=#{uid} AND <choose><when test='mini'>routine_openid</when><otherwise>openid</otherwise></choose> &lt;=&gt; #{previous} AND deleted=0</script>"})
    int changeProvider(@Param("uid") Long uid, @Param("previous") String previous, @Param("next") String next, @Param("mini") boolean mini);

    default MemberUserDO lockById(Long uid) {
        return selectOne(new LambdaQueryWrapperX<MemberUserDO>()
                .eq(MemberUserDO::getId, uid).last("FOR UPDATE"));
    }

    @Update("UPDATE yshop_user SET integral=#{next},update_time=CURRENT_TIMESTAMP WHERE id=#{uid} AND integral=#{previous} AND deleted=0")
    int changeIntegral(@Param("uid") Long uid, @Param("previous") BigDecimal previous, @Param("next") BigDecimal next);

    default MemberUserDO selectByMobile(String mobile) {
        return selectOne(MemberUserDO::getMobile, mobile);
    }

    default List<MemberUserDO> selectListByNicknameLike(String nickname) {
        return selectList(new LambdaQueryWrapperX<MemberUserDO>()
                .likeIfPresent(MemberUserDO::getNickname, nickname));
    }

    default PageResult<MemberUserDO> selectPage(UserPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MemberUserDO>()
                .likeIfPresent(MemberUserDO::getLoginType, reqVO.getLoginType())
                .likeIfPresent(MemberUserDO::getUsername, reqVO.getUsername())
                .likeIfPresent(MemberUserDO::getRealName, reqVO.getRealName())
                .likeIfPresent(MemberUserDO::getNickname, reqVO.getNickname())
                .likeIfPresent(MemberUserDO::getMobile, reqVO.getPhone())
                .betweenIfPresent(MemberUserDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(MemberUserDO::getId));
    }

    default List<MemberUserDO> selectList(UserExportReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<MemberUserDO>()
                .likeIfPresent(MemberUserDO::getUsername, reqVO.getUsername())
                .likeIfPresent(MemberUserDO::getRealName, reqVO.getRealName())
                .likeIfPresent(MemberUserDO::getNickname, reqVO.getNickname())
                .eqIfPresent(MemberUserDO::getMobile, reqVO.getPhone())
                .betweenIfPresent(MemberUserDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(MemberUserDO::getId));
    }


    @Update("update yshop_user set integral=integral-#{score}" +
            " where id=#{uid}")
    int decScore(@Param("score") Integer score, @Param("uid") Long uid);

    @Update("update yshop_user set pay_count=pay_count+1" +
            " where id=#{uid}")
    int incPayCount(@Param("uid") Long uid);


}
