package co.yixiang.yshop.module.pay.service.merchantdetails;

import org.springframework.stereotype.Service;
import co.yixiang.yshop.module.pay.credential.PaymentCredentialCryptoService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;

import java.util.*;
import co.yixiang.yshop.module.pay.controller.admin.merchantdetails.vo.*;
import co.yixiang.yshop.module.pay.dal.dataobject.merchantdetails.MerchantDetailsDO;
import co.yixiang.yshop.framework.common.pojo.PageResult;

import co.yixiang.yshop.module.pay.convert.merchantdetails.MerchantDetailsConvert;
import co.yixiang.yshop.module.pay.dal.mysql.merchantdetails.MerchantDetailsMapper;

import static co.yixiang.yshop.framework.common.exception.util.ServiceExceptionUtil.exception;
import static co.yixiang.yshop.module.pay.enums.ErrorCodeConstants.*;

/**
 * 支付服务商配置 Service 实现类
 *
 * @author yshop
 */
@Service
@Validated
public class MerchantDetailsServiceImpl implements MerchantDetailsService {

    @Resource
    private MerchantDetailsMapper merchantDetailsMapper;

    @Resource
    private PaymentCredentialCryptoService credentialCrypto;

    @Override
    public String createMerchantDetails(MerchantDetailsCreateReqVO createReqVO) {
        // 插入
        MerchantDetailsDO merchantDetails = MerchantDetailsConvert.INSTANCE.convert(createReqVO);
        encryptInputs(merchantDetails, createReqVO);
        if (Boolean.TRUE.equals(createReqVO.getRestoreEmptyPlaceholder())) {
            if (!"wxPay".equals(merchantDetails.getPayType())
                    || !"V3".equals(merchantDetails.getWechatApiVersion())
                    || !Integer.valueOf(0).equals(merchantDetails.getIsTest())
                    || PaymentCredentialCryptoService.blank(merchantDetails.getKeyPrivate())
                    || PaymentCredentialCryptoService.blank(merchantDetails.getApiV3Key())
                    || merchantDetailsMapper.provisionEmptyPlaceholder(merchantDetails) != 1)
                throw PaymentCredentialCryptoService.failure(
                        "Sanitized merchant placeholder unavailable");
        } else {
            merchantDetailsMapper.insert(merchantDetails);
        }
        // 返回
        return merchantDetails.getDetailsId();
    }

    @Override
    public void updateMerchantDetails(MerchantDetailsUpdateReqVO updateReqVO) {
        // 校验存在
        validateMerchantDetailsExists(updateReqVO.getDetailsId());
        // 更新
        MerchantDetailsDO updateObj = MerchantDetailsConvert.INSTANCE.convert(updateReqVO);
        encryptInputs(updateObj, updateReqVO);
        merchantDetailsMapper.updateById(updateObj);
    }

    @Override
    public void deleteMerchantDetails(String id) {
        // 校验存在
        validateMerchantDetailsExists(id);
        // 删除
        merchantDetailsMapper.deleteById(id);
    }

    private void validateMerchantDetailsExists(String id) {
        if (merchantDetailsMapper.selectById(id) == null) {
            throw exception(MERCHANT_DETAILS_NOT_EXISTS);
        }
    }

    @Override
    public MerchantDetailsDO getMerchantDetails(String id) {
        return merchantDetailsMapper.selectById(id);
    }

    @Override
    public List<MerchantDetailsDO> getMerchantDetailsList(Collection<String> ids) {
        return merchantDetailsMapper.selectBatchIds(ids);
    }

    @Override
    public PageResult<MerchantDetailsDO> getMerchantDetailsPage(MerchantDetailsPageReqVO pageReqVO) {
        return merchantDetailsMapper.selectPage(pageReqVO);
    }

    @Override
    public List<MerchantDetailsDO> getMerchantDetailsList(MerchantDetailsExportReqVO exportReqVO) {
        return merchantDetailsMapper.selectList(exportReqVO);
    }

    private void encryptInputs(MerchantDetailsDO target, MerchantDetailsWriteVO input) {
        if (!PaymentCredentialCryptoService.blank(input.getApiV3Key()) && input.getApiV3Key().getBytes(java.nio.charset.StandardCharsets.UTF_8).length != 32)
            throw PaymentCredentialCryptoService.failure("API v3 key must contain 32 bytes");
        target.setApiV3Key(credentialCrypto.encrypt(target.getDetailsId(), "apiV3Key", input.getApiV3Key()));
        target.setKeyPrivate(credentialCrypto.encrypt(target.getDetailsId(), "keyPrivate", input.getKeyPrivate()));
        target.setKeyCertPwd(credentialCrypto.encrypt(target.getDetailsId(), "keyCertPwd", input.getKeyCertPwd()));
        target.setKeyCert(credentialCrypto.encrypt(target.getDetailsId(), "keyCert", input.getKeyCert()));
        // Secret columns use explicit NOT_NULL update strategy: blank inputs never clear storage.
    }
}
