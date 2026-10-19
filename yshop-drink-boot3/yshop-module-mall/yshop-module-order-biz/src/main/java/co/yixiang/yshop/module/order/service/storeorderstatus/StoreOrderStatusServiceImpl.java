package co.yixiang.yshop.module.order.service.storeorderstatus;

import co.yixiang.yshop.module.order.dal.dataobject.storeorderstatus.StoreOrderStatusDO;
import co.yixiang.yshop.module.order.dal.mysql.storeorderstatus.StoreOrderStatusMapper;
import co.yixiang.yshop.module.order.service.storeorder.AsyncStoreOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;

/**
 * 订单操作记录 Service 实现类
 *
 * @author yshop
 */
@Service
@Validated
@lombok.extern.slf4j.Slf4j
public class StoreOrderStatusServiceImpl extends ServiceImpl<StoreOrderStatusMapper, StoreOrderStatusDO> implements StoreOrderStatusService {

    @Resource
    private StoreOrderStatusMapper storeOrderStatusMapper;
    @Resource
    private AsyncStoreOrderService asyncStoreOrderService;
    /**
     * 添加订单操作记录
     * @param oid 订单id
     * @param changetype 操作状态
     * @param changeMessage 操作内容
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void create(Long uid,Long oid, String changetype, String changeMessage) {
        StoreOrderStatusDO storeOrderStatus = new StoreOrderStatusDO();
        storeOrderStatus.setOid(oid);
        storeOrderStatus.setChangeType(changetype);
        storeOrderStatus.setChangeMessage(changeMessage);
        if (this.baseMapper.insert(storeOrderStatus) != 1) throw new IllegalStateException("ORDER_STATUS_INSERT_FAILED");

        //异步统计
        if (!co.yixiang.yshop.module.order.enums.OrderLogEnum.PAY_ORDER_SUCCESS.getValue().equals(changetype)) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() {
                            try { asyncStoreOrderService.orderData(uid); }
                            catch (RuntimeException failure) { log.warn("order statistics deferred category={}", failure.getClass().getSimpleName()); }
                        }
                    });
        } // Payment statistics are triggered by PaymentEffects after commit.

    }

}
