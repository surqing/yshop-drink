package co.yixiang.yshop.module.pay.dal.mysql.merchantdetails;

import java.util.*;

import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.framework.mybatis.core.query.LambdaQueryWrapperX;
import co.yixiang.yshop.framework.mybatis.core.mapper.BaseMapperX;
import co.yixiang.yshop.module.pay.dal.dataobject.merchantdetails.MerchantDetailsDO;
import org.apache.ibatis.annotations.Mapper;
import co.yixiang.yshop.module.pay.controller.admin.merchantdetails.vo.*;

/**
 * 支付服务商配置 Mapper
 *
 * @author yshop
 */
@Mapper
public interface MerchantDetailsMapper extends BaseMapperX<MerchantDetailsDO> {

    @org.apache.ibatis.annotations.Update(
            "UPDATE merchant_details SET"
                + " deleted=0,appid=#{appid},mch_id=#{mchId},wechat_api_version=#{wechatApiVersion},merchant_certificate_serial=#{merchantCertificateSerial},platform_public_key_id=#{platformPublicKeyId},key_public=#{keyPublic},key_private=#{keyPrivate},api_v3_key=#{apiV3Key},notify_url=#{notifyUrl},is_test=#{isTest},sign_type=#{signType},input_charset=#{inputCharset},cert_store_type=#{certStoreType},update_time=CURRENT_TIMESTAMP"
                + " WHERE details_id=#{detailsId} AND details_id IN"
                + " ('wx_miniapp','wx_wechat','wx_h5') AND pay_type='wxPay' AND deleted=1 AND appid"
                + " LIKE 'local-unconfigured%' AND COALESCE(mch_id,'')='' AND"
                + " COALESCE(key_private,'')='' AND COALESCE(api_v3_key,'')='' AND"
                + " COALESCE(key_cert,'')='' AND COALESCE(key_cert_pwd,'')=''")
    int provisionEmptyPlaceholder(MerchantDetailsDO value);

    default PageResult<MerchantDetailsDO> selectPage(MerchantDetailsPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MerchantDetailsDO>()
                .eqIfPresent(MerchantDetailsDO::getPayType, reqVO.getPayType())
                .eqIfPresent(MerchantDetailsDO::getAppid, reqVO.getAppid())
                .eqIfPresent(MerchantDetailsDO::getMchId, reqVO.getMchId())
                .eqIfPresent(MerchantDetailsDO::getCertStoreType, reqVO.getCertStoreType())
                .eqIfPresent(MerchantDetailsDO::getKeyPublic, reqVO.getKeyPublic())
                .eqIfPresent(MerchantDetailsDO::getNotifyUrl, reqVO.getNotifyUrl())
                .eqIfPresent(MerchantDetailsDO::getReturnUrl, reqVO.getReturnUrl())
                .eqIfPresent(MerchantDetailsDO::getSignType, reqVO.getSignType())
                .eqIfPresent(MerchantDetailsDO::getSeller, reqVO.getSeller())
                .eqIfPresent(MerchantDetailsDO::getSubAppId, reqVO.getSubAppId())
                .eqIfPresent(MerchantDetailsDO::getSubMchId, reqVO.getSubMchId())
                .eqIfPresent(MerchantDetailsDO::getInputCharset, reqVO.getInputCharset())
                .eqIfPresent(MerchantDetailsDO::getIsTest, reqVO.getIsTest())
                .orderByDesc(MerchantDetailsDO::getDetailsId));
    }

    default List<MerchantDetailsDO> selectList(MerchantDetailsExportReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<MerchantDetailsDO>()
                .eqIfPresent(MerchantDetailsDO::getPayType, reqVO.getPayType())
                .eqIfPresent(MerchantDetailsDO::getAppid, reqVO.getAppid())
                .eqIfPresent(MerchantDetailsDO::getMchId, reqVO.getMchId())
                .eqIfPresent(MerchantDetailsDO::getCertStoreType, reqVO.getCertStoreType())
                .eqIfPresent(MerchantDetailsDO::getKeyPublic, reqVO.getKeyPublic())
                .eqIfPresent(MerchantDetailsDO::getNotifyUrl, reqVO.getNotifyUrl())
                .eqIfPresent(MerchantDetailsDO::getReturnUrl, reqVO.getReturnUrl())
                .eqIfPresent(MerchantDetailsDO::getSignType, reqVO.getSignType())
                .eqIfPresent(MerchantDetailsDO::getSeller, reqVO.getSeller())
                .eqIfPresent(MerchantDetailsDO::getSubAppId, reqVO.getSubAppId())
                .eqIfPresent(MerchantDetailsDO::getSubMchId, reqVO.getSubMchId())
                .eqIfPresent(MerchantDetailsDO::getInputCharset, reqVO.getInputCharset())
                .eqIfPresent(MerchantDetailsDO::getIsTest, reqVO.getIsTest())
                .orderByDesc(MerchantDetailsDO::getDetailsId));
    }

}
