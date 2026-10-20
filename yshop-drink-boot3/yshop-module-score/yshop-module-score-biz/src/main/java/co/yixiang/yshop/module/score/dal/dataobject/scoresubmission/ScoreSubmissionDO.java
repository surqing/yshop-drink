package co.yixiang.yshop.module.score.dal.dataobject.scoresubmission;

import lombok.Data;

/** Persisted with the complete redemption transaction; never expires. */
@Data
public class ScoreSubmissionDO {
    private String requestHash;
    private String orderId;
}
