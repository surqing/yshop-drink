# Phase 6A 验收记录

基线：develop `ca16926b36d7080bbf4018bc93471dd93eb93e95` / `baseline-production-prepayment-readiness-2026-10-05`。交付分支 `feature/phase6a-multistore-ordering`，仅提交 PR，等待人工审查，不合并。

## 结果与边界

Phase 6A 的多门店归属、服务端计价、数据库库存事务、未付订单幂等、顾客归属与员工门店授权通过。完整功能矩阵、默认规则和迁移/回滚见 [foundation](phase6a-foundation.md)，后续工作见 [roadmap](phase6-roadmap.md)。没有将完整商品经营、营销、积分或门店履约宣称为已完成。

| 验收 | 实测结果 |
|---|---|
| Phase 6A 普通集成 | 80 PASS，0 failure/error/skip |
| 保留的支付/钱包/attempt/credential/V3/readiness/preflight/日志安全回归 | 441 PASS；与业务测试合计 521 PASS |
| Phase 6A 真实 MySQL | MySQL 8.0.46 / 13 张 InnoDB 表，80 PASS，隔离库和账号自动清理 |
| 保留的金融 MySQL acceptance | 331 PASS，隔离合成库，realPaymentRequests=0 |
| Backend | 55/55 SUCCESS；构建与测试分开执行，没有将跳过测试的 package 当作测试证据 |
| Vue | `ts:check`、`build:prod` PASS |
| UniApp | 官方 HBuilderX 微信小程序编译 PASS |
| Node 前端业务/登录兼容 | 23 PASS（17 项门店/购物车/券规则 + 6 项既有登录/SMS） |
| 工具/secret scanner 自身回归 | 23 + 4 PASS |
| 实际开发者工具页面 smoke | 12/12 PASS，paymentRequests=0，uncaughtExceptions=0 |
| Strict secret scan | 原扫描器未修改；基线 diff、新文件、私有配置精确值、当前会员身份/会话、压缩 JAR、Vue/Uni 产物及本地运行/验收日志均 PASS，未输出原值 |
| 金融数据只读指纹 | 余额、积分、钱包账本、充值记录、payment/conflict/attempt 和既有订单支付字段，测试前后完全一致 |

并发验收使用同一生产服务代码，普通集成与 MySQL 都覆盖：同 key 20 并发只有一个订单；20 位顾客争抢最后库存并重复 20 轮，每轮仅一单成功；优惠券 20 并发仅一单预占；同单 20 并发取消只释放一次；顾客取消与超时 20 并发只释放一次。真实数据库锁超时拒绝并回滚；没有使用 Redis/JVM 锁替代数据库正确性。

权限证据包括真实 A/B 测试数据、顾客查询实际生成的归属 SQL、门店/商品/分类资源校验、后台真实 Controller 在调用业务逻辑前拒绝跨店操作、列表/导出服务端范围，以及多门店店长、总部显式角色、未授权账号和授权撤销。未在开发库新建或扩大员工角色权限。

## 小程序闭环与本机环境

通过开发者工具元素操作完成：首页 → 选 A → SKU/加购物车 → 切 B 清空 → 回 A 加购 → 购物车 → 结算 → 一次合成网络失败 → 保留 key 重试 → 待支付详情 → 取消 → 数据库检查预占全部释放。数据库确认 paid 始终为 0、只有一条取消记录、同一 submission 只对应一单。

实际本地目录使用已有门店 2（商品）/3（空目录）验证切店；A/B 商品隔离、售罄、下架、停业、SKU 替换、伪造价格、超时与并发均在隔离合成库验证，没有修改开发目录来模拟异常。整个测试期间创建的 6 笔本地订单均保持 unpaid，已通过正常顾客取消接口取消，不删除历史支付或清 marker。

现有 `.local-dev/selected-repo` 指向另一个使用中的工作树，因此保留它及原 48081/5173 服务。本轮独立后端在 48082，私有 UniApp 镜像仅本机覆盖至此端口；仓库没有写死 48082。当前本地 DDL 已重复应用并验证金融指纹不变。运行支付与 reconciliation 开关均强制 false；Quartz 未自动运行历史任务，新版未付过期扫描照常运行。提交后的干净源码构建嵌入当前 Git revision，避免混用其他工作树产物。

## 尚存 warning 与后续限制

- 现有编译的 unchecked/deprecated、Vue CSS deep/Browserslist/UnoCSS experimental、HBuilderX defineProps/style 和微信基础 API/http-local 调试警告仍存在；没有通过降低安全检查来消除它们。最终 smoke 没有未捕获异常。
- 本地 http 使用第二阶段已获授权的开发者工具域名调试配置；不是生产 HTTPS/发布配置。
- 普通下单目前明确支持自取/外卖；堂食、零元订单不自动支付。外卖保留原有开关/起送价/地址归属/配送费规则，实际地理配送范围、运营状态机在 6E 完善。
- 老订单 ordering_version=0 保持原兼容逻辑；不猜测历史库存或 status=1 优惠券是否被占用。回滚前须处理新版未付预占，不能直接回旧取消逻辑。
- 购物车仍是本机存储；单店创建锁店铺行，正确性优先，吞吐优化应先测量。总部汇总仍复用原全局统计，普通员工不能读取该汇总。
- 已有非本轮安全 focused 的历史测试问题不等同于本轮新增失败；这里明确给出实际执行的测试集合，不声称整个仓库所有历史测试均已覆盖。

## 修改清单

- `docs/business/phase6-roadmap.md`
- `docs/business/phase6a-foundation.md`
- `docs/business/phase6a-verification.md`
- `tests/business/cart-context-test.mjs`
- `tests/business/mini-program.cjs`
- `tests/business/mysql-acceptance.py`
- `tests/business/run-smoke.sh`
- `tests/business/server-check.py`
- `tests/smoke/mini-program.cjs`
- `tests/smoke/server-check.py`
- `yshop-drink-boot3/sql/migrations/2026-10-08-multistore-ordering.sql`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz/src/main/java/co/yixiang/yshop/module/order/controller/admin/storeorder/StoreOrderController.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz/src/main/java/co/yixiang/yshop/module/order/controller/app/order/param/AppOrderParam.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz/src/main/java/co/yixiang/yshop/module/order/dal/mysql/storeorder/StoreOrderMapper.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz/src/main/java/co/yixiang/yshop/module/order/service/ordering/OrderPlacementService.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz/src/main/java/co/yixiang/yshop/module/order/service/ordering/UnpaidOrderExpiry.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz/src/main/java/co/yixiang/yshop/module/order/service/storeorder/AppStoreOrderServiceImpl.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz/src/main/java/co/yixiang/yshop/module/order/service/storeorder/StoreOrderServiceImpl.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz/src/test/java/co/yixiang/yshop/module/order/ordering/OrderingDatabaseTest.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz/src/test/java/co/yixiang/yshop/module/order/payment/PaymentDatabaseTest.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz/src/test/resources/ordering-h2.sql`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-product-biz/src/main/java/co/yixiang/yshop/module/product/controller/admin/category/ProductCategoryController.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-product-biz/src/main/java/co/yixiang/yshop/module/product/controller/admin/storeproduct/StoreProductController.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-product-biz/src/main/java/co/yixiang/yshop/module/product/controller/app/product/AppStoreProductController.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-product-biz/src/main/java/co/yixiang/yshop/module/product/controller/app/product/vo/AppStoreProductRespVo.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-product-biz/src/main/java/co/yixiang/yshop/module/product/dal/mysql/category/ProductCategoryMapper.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-product-biz/src/main/java/co/yixiang/yshop/module/product/dal/mysql/storeproduct/StoreProductMapper.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-product-biz/src/main/java/co/yixiang/yshop/module/product/service/category/ProductCategoryServiceImpl.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-product-biz/src/main/java/co/yixiang/yshop/module/product/service/storeproduct/AppStoreProductServiceImpl.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-product-biz/src/main/java/co/yixiang/yshop/module/product/service/storeproduct/StoreProductServiceImpl.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-store-biz/src/main/java/co/yixiang/yshop/module/store/dal/mysql/storeshop/StoreShopMapper.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-store-biz/src/main/java/co/yixiang/yshop/module/store/service/storeshop/StoreAccessService.java`
- `yshop-drink-boot3/yshop-module-mall/yshop-module-store-biz/src/main/java/co/yixiang/yshop/module/store/service/storeshop/StoreShopServiceImpl.java`
- `yshop-drink-boot3/yshop-module-marketing/yshop-module-coupon-biz/src/main/java/co/yixiang/yshop/module/coupon/controller/app/coupon/AppCouponController.java`
- `yshop-drink-boot3/yshop-module-marketing/yshop-module-coupon-biz/src/main/java/co/yixiang/yshop/module/coupon/controller/app/coupon/vo/AppMyCouponVO.java`
- `yshop-drink-boot3/yshop-module-marketing/yshop-module-coupon-biz/src/main/java/co/yixiang/yshop/module/coupon/dal/dataobject/couponuser/CouponUserDO.java`
- `yshop-drink-boot3/yshop-module-marketing/yshop-module-coupon-biz/src/main/java/co/yixiang/yshop/module/coupon/service/couponuser/AppCouponUserServiceImpl.java`
- `yshop-drink-boot3/yshop-module-system/yshop-module-system-biz/src/main/java/co/yixiang/yshop/module/system/service/oauth2/OAuth2TokenServiceImpl.java`
- `yshop-drink-uniapp-vue3/api/order.js`
- `yshop-drink-uniapp-vue3/pages-checkout/cart/cart.vue`
- `yshop-drink-uniapp-vue3/pages-checkout/packages/index.vue`
- `yshop-drink-uniapp-vue3/pages-checkout/pay/pay.vue`
- `yshop-drink-uniapp-vue3/pages-order/orders/detail.vue`
- `yshop-drink-uniapp-vue3/pages-user/coupons/coupons.vue`
- `yshop-drink-uniapp-vue3/pages/index/index.vue`
- `yshop-drink-uniapp-vue3/pages/menu/menu.vue`
- `yshop-drink-uniapp-vue3/store/store.js`
- `yshop-drink-uniapp-vue3/utils/ordering-context.js`
- `yshop-drink-vue3/src/views/mall/order/storeOrder/StoreOrderForm.vue`
- `yshop-drink-vue3/src/views/mall/order/storeOrder/index.vue`

## Financial Safety Statement

未使用真实商户凭据；未调用真实微信支付 API、预支付、查询、关单或 callback；未调用 wx.requestPayment；未退款、充值、修改真实余额/积分/金融账本或清理支付标记；无资金交易。合成金融回归仅在自动清理的隔离测试库执行。

PHASE 6A BUSINESS FOUNDATION READY: YES
