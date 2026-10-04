package co.yixiang.yshop.module.pay.convert.merchantdetails;

import java.util.*;

import co.yixiang.yshop.framework.common.pojo.PageResult;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import co.yixiang.yshop.module.pay.controller.admin.merchantdetails.vo.*;
import co.yixiang.yshop.module.pay.dal.dataobject.merchantdetails.MerchantDetailsDO;

/**
 * 支付服务商配置 Convert
 *
 * @author yshop
 */
@Mapper
public interface MerchantDetailsConvert {

    MerchantDetailsConvert INSTANCE = Mappers.getMapper(MerchantDetailsConvert.class);

    @Mapping(target = "apiV3Key", ignore = true)
    @Mapping(target = "keyPrivate", ignore = true)
    @Mapping(target = "keyCert", ignore = true)
    @Mapping(target = "keyCertPwd", ignore = true)
    MerchantDetailsDO convert(MerchantDetailsCreateReqVO bean);

    @Mapping(target = "apiV3Key", ignore = true)
    @Mapping(target = "keyPrivate", ignore = true)
    @Mapping(target = "keyCert", ignore = true)
    @Mapping(target = "keyCertPwd", ignore = true)
    MerchantDetailsDO convert(MerchantDetailsUpdateReqVO bean);

    @Mapping(target = "apiV3KeyConfigured", expression = "java(configured(bean.getApiV3Key()))")
    @Mapping(target = "privateKeyConfigured", expression = "java(configured(bean.getKeyPrivate()))")
    @Mapping(target = "certificatePasswordConfigured", expression = "java(configured(bean.getKeyCertPwd()))")
    @Mapping(target = "keyCertificateConfigured", expression = "java(configured(bean.getKeyCert()))")
    MerchantDetailsRespVO convert(MerchantDetailsDO bean);

    List<MerchantDetailsRespVO> convertList(List<MerchantDetailsDO> list);

    PageResult<MerchantDetailsRespVO> convertPage(PageResult<MerchantDetailsDO> page);

    @Mapping(target = "apiV3KeyConfigured", expression = "java(configured(bean.getApiV3Key()))")
    @Mapping(target = "privateKeyConfigured", expression = "java(configured(bean.getKeyPrivate()))")
    @Mapping(target = "certificatePasswordConfigured", expression = "java(configured(bean.getKeyCertPwd()))")
    @Mapping(target = "keyCertificateConfigured", expression = "java(configured(bean.getKeyCert()))")
    MerchantDetailsExcelVO convertExcel(MerchantDetailsDO bean);

    List<MerchantDetailsExcelVO> convertList02(List<MerchantDetailsDO> list);

    default boolean configured(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
