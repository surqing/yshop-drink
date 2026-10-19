# Phase 6C 验收证据

## PR #12 兑换码 review-fix（2026-10-08）

本节为后续小范围复测，不将下文首次交付结果冒充本轮新结果。实现与上线注意事项见[兑换码安全补充](phase6c-code-security.md)。

- 普通focused **1097 PASS**，另真实Redis两客户端 **65 PASS**，共 **1162 PASS / 24 suites**；0 failure/error/skip。保留原优惠券、订单、商品、payment/wallet/attempt/cancellation回归；新增88项安全测试。
- 真实MySQL8.0.46/InnoDB业务 **486 PASS**，23/23 InnoDB；最终隐藏活动防枚举及领取重试契约再跑 **5项定向PASS**，均自动清理隔离库/账户。5项为重复定向覆盖，不与486相加作为独立场景。本轮金融状态机执行普通集成回归，没有将前轮497项金融MySQL结果当作本轮执行。
- 最新后端完整构建 **55/55 SUCCESS**；Vue ts:check/build:prod PASS。未修改UniApp源码，本次没有重编译或复制旧UniApp/smoke数字作为新结果。
- 真实Redis三组 **20并发×20轮**：不同短错误码、轮换requestKey、32位可预测错误码。分别只允许3、3、5个请求进入领取服务，其余429；另验共享网络20会员、小时限额、IP/短码日限额、TTL恢复。
- 当前微信模拟器已认证会话直接请求本机48083：20个不同32位错误码/key，**5个统一400 + 15个429**；券模板/权益/领取记录指纹不变，访问审计和服务日志原码匹配 **0**。隐藏CODE活动与不存在活动的id-only请求同错误，无领券或支付副作用。
- Redis故障fail-closed、异常日志不带请求或异常cause、既有AOP不打印参数、请求及一次性创建响应脱敏、新码192随机位、手工短/长弱码拒绝、历史短摘要/plaintext兼容、丢失领取响应重试不重发均已验证。
- 开发库金融指纹未变；真实支付和reconciliation仍false。Strict Secret扫描见交付报告的最终结果；没有改扫描器来放行。

私有证据：.local-dev/logs/phase6c-code-regression.log、phase6c-code-backend-build.log、phase6c-code-vue-types.log、phase6c-code-vue-build.log、phase6c-code-http-smoke.json、phase6c-code-enum.json、phase6c-code-mysql-business.log、phase6c-code-mysql-final.log。详细MySQL日志在.local-dev/acceptance，不提交凭据或原始身份。

原DesensitizeTest历史1 FAIL记录保留，本轮未扩大范围修复，不宣称全仓库历史测试零失败。未做schema migration、旧码重置或金融核心修改，不开始6D。

2026-10-08，分支feature/phase6c-coupon-marketing，基线develop/PR11 merge fa39ef2b854eb21655390866090f61b0c4d52a11。Merge前核对批准head ad7b96513b92c7090bbfff41382ffb3b7540ef8a、OPEN/MERGEABLE、develop及工作区clean；无新增CI失败。annotated baseline-phase6b-product-catalog-2026-10-08已push，tag object bc12caa4fe481a022ba46600d22d24c215736a47，peeled为merge。master b8800d4071dd601b50f8be75b1b2811ba0558d30未改动。

## 实际结果

| 集合 | 结果 | 范围 |
|---|---|---|
| 普通 focused | 1074 PASS，22 suites，0 failure/error/skip | 原725 + 新Coupon281 + CouponPayment68 |
| MySQL 8.0.46业务 | 482 PASS，0 failure/error | Ordering80 + Catalog96 + CatalogEditing25 + Coupon281；23/23 InnoDB |
| MySQL8金融/取消 | 497 PASS，0 failure/error | 原429 + CouponPayment68；独立合成库 |
| Backend | 55/55 SUCCESS | clean install package，skip tests，测试由上列执行 |
| Vue | ts:check / build:prod PASS | 结构化表单/记录/经营审计 |
| Node上下文 | 41 PASS | 原购物车/商品 + 新券门槛/上限/重试/旧响应防护 |
| UniApp | HBuilderX官方编译成功 | 源码同步至Git忽略开发副本；local API48083 |
| 微信优惠券业务 | 27/27 PASS | 真页面领券、失败重试、公共码、选券40减5、待付预占、取消返还 |
| 微信补充状态 | 6/6 PASS | 模板快照、作废、新人资格、限领、A/B店及global |
| 微信商品基线 | 18/18 PASS | SKU/加料/定价/切店/重试/待付及取消 |
| 微信原业务基线 | 12/12 PASS | 原多门店基础点单 |
| Vue Admin GUI | PASS | 真实创建单店/全店公共码/新人券、编辑/停用/记录/作废/统计 |
| Strict Secret Scan | PASS | 原scanner未修改；源码diff/untracked、产物/压缩、日志及当前私有身份 |
| 开发库金融指纹 | unchanged | 余额/积分、wallet/recharge、payment/conflict/attempt、既存订单付款字段 |
| 历史券原字段指纹 | unchanged | 2旧模板、3旧实例均保持 |

所有数值来自本轮执行，不复制6B结果。普通focused与MySQL是不同引擎执行，不能把同一套测试叠加宣传独立场景数。成功付款测试仅在自动清理隔离库，通过已有synthetic trusted event完成，未新增开发支付成功入口。

## 并发证据

新增CouponDatabaseTest包含12组RepeatedTest(20)，每组20并发×20轮：最后1张、同会员多key限1、限N、相同key重试、领取/停用、领取/改额度、新人单活动、公共码重试、同券多订单、取消/超时、新人跨活动、到期/下单。CouponPaymentDatabaseTest另3组20并发×20轮：重复可信事件、成功/取消、可信远端终态后取消及迟到成功重放。加上原108项取消/attempt竞态与金融状态机回归，未移除原断言。无超发/超限/重复核销/重复释放或死锁。

订单库存失败及审计注入故障全回滚；返券不改变使用期，超期返回EXPIRED。金融冲突/未知attempt拒绝取消时，不写券操作证据、不返库存、不清marker。新核销审计故障会回滚paid、付款SUCCESS、会员效果和券核销；解除故障后可重放成功。

## 页面验收与复现

后台本机5175→48083，保留48081/5173及用户打印机工作树。使用当前授权管理员登录，不绕过验证码。页面新建“Phase6C合成”命名活动，仅选择已有合成A/B店或HQ通用范围；未修改旧活动。模板face5改6并停用后，记录页和实际小程序仍显示原领券face5。显式作废仅操作本轮新领合成券，原因与操作记录保留。公共码不回显、列表无code，访问日志匹配合成公共码次数为0。

实际Mini从登录态：首页→A店商品/中杯/珍珠加料→中心领取→模拟网络失败同key重试→我的券/公共码→购物车→选券→小计40优惠5应付35→提交失败保留cart/key/选券→重试唯一待付订单→数据库核对预占→安全取消→库存及券释放一次。全程paid0、paymentRequests0，无未捕获异常。补充页面切店B只保留global，A券不显示；旧会员无法领取新人权益，达到限额按钮显示原因。

自动化脚本 tests/business/coupon-mini-program.cjs 接收YSHOP_COUPON_TITLE选择新建合成活动；首次应空领券且限额可领。复跑创建新命名合成活动，不能删除旧领取证据重置资格。catalog-mini-program.cjs与mini-program.cjs为旧基线；coupon-state-mini-program.cjs验证本轮GUI合成活动状态，不伪造登录/paid或业务页面数据。所有支付调用仍被原冻结业务及测试监测保护。

私有证据在工作区外的 .local-dev/logs/phase6c-*、.local-dev/acceptance及.uniapp-dev/logs/phase6c-*；后台截图phase6c-admin.png。运行mysql-acceptance.py --coupon分别位于tests/business与tests/payment，自动创建/清理隔离库/账户。权限migration单独验证重跑无重复，system_role_menu/system_user_role未改动。

## 已知限制及警告

单独复跑既有DesensitizeTest仍1 FAIL：样例yshop与期望“芋***”不一致，原文件未改，独立记录phase6c-historical-desensitize.log。1074是列明focused集合，不宣称全仓库历史测试零失败。

Vite CJS API、旧::v-deep、wx.getSystemInfoSync废弃提示与本机HTTP图片HTTPS警告仍存在；不削弱安全机制消除warning。实际Mini验证无未捕获异常，图片商用授权及HTTPS生产素材部署留待上线前处理。根MIT/部分源码及素材版权待人工核实，保留6B风险清单。

历史只读审计发现1个歧义status1实例、1个发行计数不一致模板；不重置、不补发，详见phase6c-legacy-coupon-audit.md。积分券不支持实际领取、一次性码/付费券/退款返券不在范围，人工任意发券入口关闭。无新实际业务/金融blocker，不开始6D。

## Financial Safety Statement

未使用真实商户凭据；未调用真实微信预支付、查询、关单或其他支付API；未处理真实callback；未调用wx.requestPayment；未执行真实退款/充值或资金交易；未修改开发库余额、积分、金融台账、付款记录、PaymentAttempt或marker。yshop.pay.wechat-v3.enabled=false，reconciliation-enabled=false。金融成功用例只使用自动清理的隔离合成数据库，页面只新增并安全取消本轮合成待付订单。支付请求0，真实金融操作0。
