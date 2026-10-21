package co.yixiang.yshop.module.order.service.fulfillment;

import co.yixiang.yshop.module.member.dal.dataobject.user.MemberUserDO;
import co.yixiang.yshop.module.member.dal.mysql.user.MemberUserMapper;
import co.yixiang.yshop.module.member.enums.BillDetailEnum;
import co.yixiang.yshop.module.member.service.userbill.UserBillService;
import co.yixiang.yshop.module.order.dal.dataobject.storeorder.StoreOrderDO;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderFulfillmentRewardService {

    private final MemberUserMapper memberUserMapper;
    private final UserBillService billService;

    public void award(StoreOrderDO order) {
        if (order.getGainIntegral() == null || order.getGainIntegral().signum() <= 0) return;
        MemberUserDO user = memberUserMapper.lockById(order.getUid());
        if (user == null) throw new IllegalStateException("FULFILLMENT_MEMBER_NOT_FOUND");
        BigDecimal newIntegral = user.getIntegral().add(order.getGainIntegral());
        if (memberUserMapper.changeIntegral(user.getId(), user.getIntegral(), newIntegral) != 1)
            throw new IllegalStateException("FULFILLMENT_POINTS_UPDATE_FAILED");
        billService.incomeExact(
                user.getId(),
                "购买商品赠送积分",
                BillDetailEnum.CATEGORY_2.getValue(),
                BillDetailEnum.TYPE_9.getValue(),
                order.getGainIntegral(),
                newIntegral,
                "购买商品赠送" + order.getGainIntegral() + "积分",
                order.getId().toString());
    }
}
