package co.yixiang.yshop.module.score.dal.mysql.scoresubmission;

import co.yixiang.yshop.module.score.dal.dataobject.scoresubmission.ScoreSubmissionDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ScoreSubmissionMapper {
    // Acquire the unique row by insertion/upsert before any product or member lock.
    @Insert("INSERT INTO yshop_score_submission(uid,idempotency_key,request_hash,order_id) "
            + "VALUES(#{uid},#{key},#{hash},#{orderId}) ON DUPLICATE KEY UPDATE uid=uid")
    int claim(@Param("uid") Long uid, @Param("key") String key,
              @Param("hash") String hash, @Param("orderId") String orderId);

    @Select("SELECT request_hash,order_id FROM yshop_score_submission "
            + "WHERE uid=#{uid} AND idempotency_key=#{key} FOR UPDATE")
    ScoreSubmissionDO lock(@Param("uid") Long uid, @Param("key") String key);
}
