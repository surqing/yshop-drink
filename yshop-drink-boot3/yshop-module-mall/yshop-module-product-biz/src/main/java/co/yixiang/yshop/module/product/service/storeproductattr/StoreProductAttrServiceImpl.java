package co.yixiang.yshop.module.product.service.storeproductattr;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import co.yixiang.yshop.framework.common.util.string.StrUtils;
import co.yixiang.yshop.module.product.dal.dataobject.storeproductattr.StoreProductAttrDO;
import co.yixiang.yshop.module.product.dal.dataobject.storeproductattrvalue.StoreProductAttrValueDO;
import co.yixiang.yshop.module.product.dal.mysql.storeproductattr.StoreProductAttrMapper;
import co.yixiang.yshop.module.product.dal.mysql.storeproductattrvalue.StoreProductAttrValueMapper;
import co.yixiang.yshop.module.product.service.storeproduct.dto.FromatDetailDto;
import co.yixiang.yshop.module.product.service.storeproduct.dto.ProductFormatDto;
import co.yixiang.yshop.module.product.service.storeproductattrresult.StoreProductAttrResultService;
import co.yixiang.yshop.module.product.service.storeproductattrvalue.StoreProductAttrValueService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;

import static co.yixiang.yshop.framework.common.exception.util.ServiceExceptionUtil.exception;
import static co.yixiang.yshop.module.product.enums.ErrorCodeConstants.*;

/**
 * 商品属性 Service 实现类
 *
 * @author yshop
 */
@Service
@Validated
public class StoreProductAttrServiceImpl extends ServiceImpl<StoreProductAttrMapper,StoreProductAttrDO> implements StoreProductAttrService {

    @Resource
    private StoreProductAttrMapper storeProductAttrMapper;
    @Resource
    private StoreProductAttrValueService storeProductAttrValueService;
    @Resource
    private StoreProductAttrValueMapper storeProductAttrValueMapper;
    @Resource
    private StoreProductAttrResultService storeProductAttrResultService;
    @Resource
    private org.springframework.jdbc.core.JdbcTemplate catalogJdbc;


    @Override
    public void deleteStoreProductAttr(Long id) {
        // 校验存在
        validateStoreProductAttrExists(id);
        // 删除
        storeProductAttrMapper.deleteById(id);
    }

    private void validateStoreProductAttrExists(Long id) {
        if (storeProductAttrMapper.selectById(id) == null) {
            throw exception(STORE_PRODUCT_ATTR_NOT_EXISTS);
        }
    }

    @Override
    public StoreProductAttrDO getStoreProductAttr(Long id) {
        return storeProductAttrMapper.selectById(id);
    }

    @Override
    public List<StoreProductAttrDO> getStoreProductAttrList(Collection<Long> ids) {
        return storeProductAttrMapper.selectBatchIds(ids);
    }


    /**
     * 新增商品属性
     * @param items attr
     * @param attrs value
     * @param productId 商品id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void insertYxStoreProductAttr(List<FromatDetailDto> items, List<ProductFormatDto> attrs,
                                         Long productId)
    {
        // All catalog writers serialize with ordering/cancellation on the product row.
        catalogJdbc.queryForObject("SELECT id FROM yshop_store_product WHERE id=? AND deleted=0 FOR UPDATE",Long.class,productId);
        List<StoreProductAttrDO> attrGroup = new ArrayList<>();
        for (FromatDetailDto fromatDetailDto : items) {
            StoreProductAttrDO  storeProductAttr = StoreProductAttrDO.builder()
                    .productId(productId)
                    .attrName(fromatDetailDto.getValue())
                    .attrValues(StrUtil.join(",",fromatDetailDto.getDetail()))
                    .build();

            attrGroup.add(storeProductAttr);
        }

        /*int count = storeProductAttrValueService.count(Wrappers.<YxStoreProductAttrValue>lambdaQuery().eq(YxStoreProductAttrValue::getProductId, productId));
        if (count > 0 ) {
            throw new BadRequestException("该产品已被添加到其他活动,禁止操作!");
        }*/

        List<StoreProductAttrValueDO> valueGroup = new ArrayList<>();
        List<StoreProductAttrValueDO> newValues = new ArrayList<>();
        for (ProductFormatDto productFormatDto : attrs) {

//            if(productFormatDto.getPinkStock()>productFormatDto.getStock() || productFormatDto.getSeckillStock()>productFormatDto.getStock()){
//                throw new BadRequestException("活动商品库存不能大于原有商品库存");
//            }
            List<String> stringList = new ArrayList<>(productFormatDto.getDetail().values());
            stringList =  StrUtils.compareTo(stringList);
            StoreProductAttrValueDO oldAttrValue = storeProductAttrValueService.getOne(new LambdaQueryWrapper<StoreProductAttrValueDO>()
                    .eq(StoreProductAttrValueDO::getSku, StrUtil.join(",",stringList))
                    .eq(StoreProductAttrValueDO::getProductId, productId));

            String unique = IdUtil.simpleUUID();
            if (Objects.nonNull(oldAttrValue)) {
                unique = oldAttrValue.getUnique();
                if(oldAttrValue.getPrice().compareTo(BigDecimal.valueOf(productFormatDto.getPrice()))!=0) {
                    catalogJdbc.update("INSERT INTO yshop_product_operation(operation_id,actor_id,request_key,request_hash,product_id,sku_id,kind,before_value,after_value,reason) VALUES(?,?,?,?,?,?,'PRICE',?,?,'商品编辑调整售价')",
                        java.util.UUID.randomUUID().toString().replace("-",""),co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId(),java.util.UUID.randomUUID().toString(),"product-edit",productId,oldAttrValue.getId(),oldAttrValue.getPrice(),BigDecimal.valueOf(productFormatDto.getPrice()));
                }
            }

            StoreProductAttrValueDO yxStoreProductAttrValue = StoreProductAttrValueDO.builder()
                    .id(Objects.isNull(oldAttrValue) ? null : oldAttrValue.getId())
                    .productId(productId)
                    .sku(StrUtil.join(",",stringList))
                    .price(BigDecimal.valueOf(productFormatDto.getPrice()))
                    .cost(BigDecimal.valueOf(productFormatDto.getCost()))
                    .otPrice(BigDecimal.valueOf(productFormatDto.getOtPrice()))
                    .unique(unique)
                    .image(productFormatDto.getPic())
                    .barCode(productFormatDto.getBarCode())
                    .weight(BigDecimal.valueOf(productFormatDto.getWeight()))
                    .volume(BigDecimal.valueOf(productFormatDto.getVolume()))
                    .brokerage(BigDecimal.valueOf(productFormatDto.getBrokerage()))
                    .brokerageTwo(BigDecimal.valueOf(productFormatDto.getBrokerageTwo()))
                    .stock(oldAttrValue==null?productFormatDto.getStock():oldAttrValue.getStock())
                    .sales(oldAttrValue==null?0:oldAttrValue.getSales())
                    .isShow(oldAttrValue==null?1:oldAttrValue.getIsShow())
                    .integral(productFormatDto.getIntegral())
                    .pinkPrice(BigDecimal.valueOf(productFormatDto.getPinkPrice()==null?0:productFormatDto.getPinkPrice()))
                    .seckillPrice(BigDecimal.valueOf(productFormatDto.getSeckillPrice()==null?0:productFormatDto.getSeckillPrice()))
                    .pinkStock(productFormatDto.getPinkStock()==null?0:productFormatDto.getPinkStock())
                    .seckillStock(productFormatDto.getSeckillStock()==null?0:productFormatDto.getSeckillStock())
                    .build();

            valueGroup.add(yxStoreProductAttrValue);
            if(oldAttrValue==null) newValues.add(yxStoreProductAttrValue);
        }

        if(attrGroup.isEmpty() || valueGroup.isEmpty()){
            throw exception(STORE_PRODUCT_ATTR_NEED);
        }

        //清理属性
        this.clearProductAttr(productId);
        var retained=valueGroup.stream().map(StoreProductAttrValueDO::getSku).toList();
        for(var old:storeProductAttrValueService.list(Wrappers.<StoreProductAttrValueDO>lambdaQuery().eq(StoreProductAttrValueDO::getProductId,productId))) {
            if(!retained.contains(old.getSku())) catalogJdbc.update("UPDATE yshop_store_product_attr_value SET is_show=0 WHERE id=? AND product_id=?",old.getId(),productId);
        }

        //批量添加
        this.saveBatch(attrGroup);
        storeProductAttrValueService.saveOrUpdateBatch(valueGroup);
        for(var value:newValues) {
            if(value.getStock()>0) {
                catalogJdbc.update("INSERT INTO yshop_product_operation(operation_id,actor_id,request_key,request_hash,product_id,sku_id,kind,before_value,after_value,reason) VALUES(?,?,?,?,?,?,'STOCK',0,?,'新增规格初始库存')",
                    java.util.UUID.randomUUID().toString().replace("-",""),co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId(),java.util.UUID.randomUUID().toString(),"initial",productId,value.getId(),value.getStock());
            }
        }
        catalogJdbc.update("UPDATE yshop_store_product SET stock=(SELECT COALESCE(SUM(stock),0) FROM yshop_store_product_attr_value WHERE product_id=?) WHERE id=?",productId,productId);

        Map<String,Object> map = new LinkedHashMap<>();
        map.put("attr",items);
        map.put("value",attrs);

        storeProductAttrResultService.insertYxStoreProductAttrResult(map,productId);
    }

    /**
     * 删除YxStoreProductAttrValue表的属性
     * @param productId 商品id
     */
    private void clearProductAttr(Long productId) {
        if(ObjectUtil.isNull(productId)) {
            throw exception(STORE_PRODUCT_NOT_EXISTS);
        }

        storeProductAttrMapper.delete(Wrappers.<StoreProductAttrDO>lambdaQuery()
                .eq(StoreProductAttrDO::getProductId,productId));
        // Retire omitted SKUs; never delete IDs referenced by orders/reservations.
        // saveOrUpdateBatch below preserves previous inventory and sales for retained SKUs.
        // Explicit SKU sale operations control availability of retained rows.

    }


}
