# Phase 6B 验收记录

## Git 与范围

批准的 PR #10 head `4868858e23b62ad2b18901cb070db72444af8125` 经 OPEN/MERGEABLE/base develop/clean 核验后用 Merge Commit 合并。
Merge/develop：`1ed6061e62a754f281dc321269b58f0f562484c9`。
Annotated tag：`baseline-phase6a-multistore-ordering-2026-10-08`；tag object `28de2f7bf3fb00082873e9caafce5d65fd2ab41e`，peeled commit 同 merge。
Phase 6B 分支 `feature/phase6b-product-catalog`；不合并本阶段 PR，不修改 master，不开始 Phase 6C。

## 自动验收（2026-10-08，Mac mini / JDK 17）

| 验收 | 结果 | 证据 |
|---|---|---|
| 普通 backend focused | 725 PASS / 0 failure/error/skip | 20 个 suite 的逐项结果，包含原 629 + Catalog 96；不能将 Maven 各模块汇总重复计数 |
| 原 Phase 6A ordering | 80 PASS | OrderingDatabaseTest，H2 和 MySQL 均保留原断言 |
| PaymentAttempt 取消防护 | 108 PASS | PaymentCancellationDatabaseTest，含原 order → attempt 竞争 |
| MySQL 业务 | 201 PASS | Ordering 80 + Catalog 96 + 原商品保存路径 CatalogEditingMysqlAcceptance 25 |
| MySQL 金融/取消 | 429 PASS | wallet/attempt/payment/v3/readiness/preflight/cancellation；fake transport/synthetic keys |
| MySQL 引擎 | 8.0.46 / 19/19 InnoDB | business runner 验证 schema metadata |
| Backend full build | 55/55 SUCCESS | clean install package，跳过测试另由上述 focused 执行 |
| 客户端逻辑 | 28 PASS | cart-context 17 + catalog-options 11，整数分/条件/默认/失效上下文 |
| Vue | type check + production build | 不跳过类型错误，修复构建声明扫描污染 |
| UniApp | 官方 HBuilderX 5.26 编译成功 | 原 Vue3 UniApp 工程的忽略副本，379 原文件校验未被编译流程篡改 |
| 实际微信开发者工具 | 18/18 + 原基线 12/12 | 已授权本机 CLI/automator、本人既有登录态；uncaughtExceptions=0 |
| 支付请求 | 0 | smoke 同时拦截、计数 pay/prepay/requestPayment，未伪造 paid |
| Strict secret scan | PASS | 原严格 scanner 未修改；源码/diff/未跟踪/日志/压缩产物/当前 simulator 身份 RAM 比对 |

MySQL runner 每次创建随机专用 schema/account，只授予隔离 schema 权限，finally 清理；不使用开发库执行合成金融测试。运行日志/报告为 Git 外的 `.local-dev/logs` 与 `.local-dev/acceptance` 私有文件。H2 不替代 MySQL 并发结果。

## 20 并发 × 20 轮

四组关键竞争在真实 MySQL/InnoDB 执行，每组 20 个并发 worker，重复 20 轮：

1. 抢最后一个库存：恰好一个成功，订单 paid=0，库存不负。
2. SKU 改价与订单创建：订单或按锁前已确认版本成交、或拒绝过期版本；无新旧价混算、客户端伪价无效。
3. 可售库存调整与订单创建：expectedStock 冲突明确拒绝，无漏扣/覆盖；库存汇总一致。
4. 原商品 SKU 结构编辑与预占：旧 SKU ID 和已有预占不丢失，退休停售；新 SKU 独立库存；取消返回原 SKU 恰好一次。

额外验证 A/B 同名差异价/库存、跨店 SKU/加料/分类攻击、普通员工越权、总部范围、必选/单选/多选/重复份数、条件冰量、负价/三位小数、float/string/overflow 数量、配置版本、审计失败后全部回滚、商品创建失败无孤儿、复制幂等、批量全回滚、优惠券兼容、订单重放与历史快照不变。原支付和取消竞争用例全部保留。

## 实际 Vue 页面

只使用本机新增的合成 A/B 门店和新商品，未修改存量经营商品。

- 登录由用户完成滑块，无验证码绕过或新增授权。
- 选择 A 门店后列表与分类树仅显示 A 店。
- 图片选择/本地上传、分类创建、排序编辑；实际新建多杯型饮品，商品31、两 SKU、价格15/18、库存10/12，总22。
- 定制表单实际将珍珠差价改为2.50、保存，再增加可选风味分组；温度/甜度/条件冰量/重复加料由结构化表单回显。
- 可售库存20→22，原因与操作者1、时间正确展示；停售/恢复单SKU不清库存。
- 实际批量下架后进入待上架列表，再批量恢复销售；批量分类更新新商品到本店类别成功；跨店复制产生商品32，B店独立SKU、下架/零库存，B店旧合成商品价格20不变。
- 编辑新饮品保留门店、图片、SKU与价格，已有库存只读；修复原多规格空表头回显缺陷。
- 对窄窗口适配经营弹窗；未保存定制阻止会刷新数据的经营动作，关闭要求放弃确认。

页面截图保留 Git 外 `.local-dev/logs/phase6b-admin.png`，不包含凭据。

## 实际小程序闭环

A 店 → 明确中杯 → 热默认不显示冰量 → 冰饮激活冰量 → 切热移除冰量 → 珍珠2份×2.50 + 基价15 =20/杯 → 数量2=40 → 购物车 → 注入一次网络失败 → 幂等 key/购物车保留 → 重试创建一笔未付订单 → 服务端快照确认 base15/extra5/unit20/line40、预占2 → 查看待支付详情 → 页面取消 → 恰好一次返还库存。

同时切 B 店清旧购物车/券/提交 key，原首页/会员/商品/购物车/待付/取消12项保持通过。所有订单正常 paid=0，没有调用真实付款或修改 paid。

## 迁移及安全审查

Additive migration `2026-10-08-product-catalog.sql` 重跑不改已有数据；没有真实金融数据回填。详情读成交快照，历史订单兼容原格式。商品旧SKU保留，reserved引用不重建。价格和库存操作与审计同事务，数据库为库存真值。

开发库 before/after 指纹核对用户余额/积分、wallet/recharge/payment/conflict/attempt 表、既有订单支付字段完全不变。支付及 reconciliation 开关始终 false。原 48081/5173 服务及其他开发 worktree 未切换/删除。

既有 warning：Vite CJS API、Browserslist 数据、::v-deep、UnoCSS experimental，以及 UniApp 既有 defineProps/span 提示。未为消除 warning 关闭检查。本阶段不声称原料库存、批量价/库存、完整营销、会员积分、接单平台已经实现。

根 MIT 与部分源码 All rights reserved 头、既有 UEditor/uv-ui/图片字体商用授权仍需权利人核实，详见设计文档。该授权待核实事项不是被隐藏的工程 PASS。

## Financial Safety Statement

未使用真实商户凭据；未调用真实微信预支付/查询/关单/API；未处理真实 callback；未调用 wx.requestPayment；未执行真实退款、充值或资金交易；未修改开发库用户余额、积分、金融账本、支付记录或 marker。合成金融测试仅在自动清理的隔离数据库中执行。

## 复现入口

在 JDK17/Maven3.9、Node20/pnpm8 环境，先按现有本机开发环境准备隔离 MySQL，再运行：

```sh
# yshop-drink-boot3
mvn clean install package -Dmaven.test.skip=true
mvn -pl yshop-framework/yshop-spring-boot-starter-web,yshop-framework/yshop-spring-boot-starter-mybatis,yshop-module-pay/yshop-module-pay-biz,yshop-module-mall/yshop-module-order-biz test '-Dtest=GlobalExceptionHandlerTest,SensitiveDataSanitizerTest,ApiAccessLogFilterTest,SafeSqlLogTest,PaymentCredentialDatabaseTest,PaymentCredentialCryptoServiceTest,MerchantDetailsControllerSecurityTest,LivePaymentMysqlVersionTest,LivePaymentPreflightSecurityTest,LivePaymentPreflightTest,PaymentCancellationDatabaseTest,PaymentAttemptDatabaseTest,WalletDatabaseTest,PaymentLiveReadinessDatabaseTest,WechatV3DatabaseTest,PaymentDatabaseTest,LiveMerchantPreflightDatabaseTest,OrderingDatabaseTest,CatalogDatabaseTest,CallbackVerificationTest' -Dsurefire.failIfNoSpecifiedTests=false
# repository root; existing Git-ignored local-dev database helper required
python3 tests/business/mysql-acceptance.py --catalog
python3 tests/payment/mysql-acceptance.py --cancellation
node --test tests/business/cart-context-test.mjs tests/business/catalog-options-test.mjs
# yshop-drink-vue3
pnpm ts:check
pnpm build:prod
```

本机官方编译副本/本人登录态和服务端私有配置沿用 Git 忽略环境；`YSHOP_API_PORT=48083` 运行 `tests/business/catalog-mini-program.cjs`、`tests/business/mini-program.cjs`。配置和本人身份不在测试源码内。严格扫描通过私有 wrapper 把 simulator 身份在内存比对，并扫描原严格规则及所有构建产物；不将该私有身份文件提交。

另补真实 MySQL 多规格详情回读再保存测试：15/18 售价、3/4 成本、16/19 原价必须正确回显，重新保存不能清零或覆盖可售库存。实际编辑页验证两 SKU 库存10/12只读、原价格回显。本问题源于原 Spring BeanUtils 不转换 BigDecimal→Double，现已显式转换。

额外扩大到框架全部历史 `*Test` 时，既有 DesensitizeTest 的样例名称与断言不一致（期望芋前缀，实际 yshop），该文件本轮未改动；已记录，未为了商品验收修改无关脱敏测试或宣称全仓库测试零错误。725 项为列明的受影响业务与金融 focused 集合。
