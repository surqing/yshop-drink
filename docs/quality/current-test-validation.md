# 当前源码测试验证

任务：PHASE6Q-DEFECT-DISCOVERY。检测日期：2026-10-10。按用户最新要求，本轮交付重点是模块用例结构、实际执行和未通过清单，不继续原因分析或业务修复。所有生产源码保持 PR14 内容；本轮仅添加诊断测试、证据和文档，并对小程序复用入口作必要参数扩展。

基线 SHA：`c4a180833acbfa943dc99348d304dff8bd4fed83`；工作树内容 SHA256：`b3192ff7645d4f02062b94c764fed54cd43e2b3074da50112de37bc220308717`。全部基线报告已核对完整执行、源码未改变、精确用例和独立资源清理。新增交付集合的执行快照另列，不能将旧摘要改写成新 HEAD。

PR12 → develop、PR13 → PR12、PR14 → PR13 均保持未合并。PR14 是 draft，本轮独立工作树与分支 `audit/phase6q-defect-discovery`，新 draft PR 目标 `test/phase6q-automation-upgrade`。原始日志、截图、Trace、身份、Token 和数据库配置在仓库外私有目录；公开文件仅含脱敏计数、状态和原始文件哈希。

环境：macOS arm64；Java 17.0.20.1、Maven 3.9.16、Node 20.20.2、pnpm 8.15.9、Python 3.9；专属 Docker MySQL 8.0/Redis 7.4；真实 Chromium；HBuilderX 当前源码编译与微信官方 miniprogram-automator。模拟器、真实身份和物理设备分别记录。

## 当前实际执行结果

| 范围 | 状态 | 当前执行与限制 | 运行 ID |
|---|---|---|---|
| BUILD | PASSED | 55 个后端模块 + Vue 构建/类型全部通过，测试独立执行。 | `a91165144941477a862c34f948d2e280` |
| JAVA | PASSED | 77 个普通套件，1721 次调用；参数变体和重复轮数单列。 | `ddd94ba4cf6a4475879835b3e5ebdc16` |
| FRONTEND | PASSED | QUICK 184（Node 52 + Python 132）+ 实际 Coupon Form VTU 10 + V8 覆盖。组件测试使用 HTTP 和厂商组件替身。 | `9e06cc584fff4e4eb5921973a380ea6f` |
| BROWSER | PASSED | 实际 Chromium 页 5，显式合成 API；不证明真实验证码登录。 | `a7b5f42ee94d4158a61d7995f492fa1f` |
| MINI-PAGES | PASSED | 现有官方模拟器页面 10 + 真实跨端浏览器 2 + SQL 5 + HTTP 45，资源已清理。 | `79ee2f67c53d4064b639df5dae37265f` |
| PERFORMANCE | PASSED | 复用真实 CROSS-END：浏览器 2、SQL 5、HTTP 45；另双 JVM 240 请求/2 场景，不能认定生产 SLO。 | `05ac8dc8dfb144619573e2471dbb44f1` |
| INTEGRATION | PASSED | 专属 MySQL 业务 494、财务 497、认证 72；真实 Redis 66、合成 TLS 回调入口 10，全部通过并清理。 | `74a4bb845ba4416abea73f8543be22aa` |
| MUTATION | PASSED | 原有后端 28 + 前端 2，共 30 KILLED；原始基线通过、变异副本清理、无生产变异残留。 | `ce99980b5c1f4205953dcd4f5bc6b13d` |
| SECURITY | PASSED | QUICK + 指定 Java 安全边界与离线凭据差异守卫通过；不声称全项目安全无漏洞。 | `c248c6e16b514a2399dc402c5efc66fe` |

CROSS-END 已在 PERFORMANCE 与 MINI-PAGES 的实际模式中执行，不重复累加同一检查。QUICK 在 FRONTEND/SECURITY 重复通过也只计一次。BUILD 初次因不可写缓存失败、QUICK 初次因沙箱端口限制失败；详见 ENV001/ENV002，原始失败未删除。

独立检查口径：**1487** 项（{'PASSED': 1478, 'FAILED': 9}），包括 Java 参数变体 1214、QUICK 184、VTU 10、浏览器 5、去重 HTTP 29、跨端页面 2/SQL 5、既有小程序 10、新优惠券页面 12/SQL 5、性能场景 2、RED 9。Java 对应 1822 次 distinct 调用，重复测试轮次不冒充新场景；H2 与 MySQL 重跑同名用例去重。辅助框架测试和业务检查不能当作相同数量的完整用户旅程。30 变异和 240 负载请求单列。

完整业务矩阵见 `current-test-matrix.md/.json`，包含实际边界、状态、用例名称和证据引用。矩阵行可能共用同一个证据，不能相加为测试数。

## 未通过结果

HTTP D001–D008：每个初次环境两次，再新建独立环境一次，24 次正确预期断言均 FAILED；数据库最终状态和 API 返回一致，独立环境均清理通过。D009：两个独立 Chromium 上下文均在错误目标写入断言处 FAILED；先前脚本定位器/提示假设失败未当作产品 Bug。共 9 个稳定失败业务用例（已有等级 P1 4、P2 5），按最新要求只记录结果，后续逐项处理。环境、脚本和不完整执行另外列在 `discovery-execution-issues.json`。

优惠券完整页面链路：新领券 → 选择减免 → 服务提交成功但响应丢失 → 同键重试 → SQL 确认预留 → 可信合成核销 → 重复事件幂等 → 页面显示已核销 → 再进入仍已核销，12 页面 + 5 SQL 检查 PASSED。Java bridge 复用真实 Mapper/事务/finalization，通知、统计、购物车适配器为既有测试替身；真实支付签名入口另由合成 TLS 测试负责。不等同完整真实商户支付。

金融测试均使用隔离数据库和合成事件，支付与对账开关 false。HTTP/浏览器/小程序守卫的 provider=0 限定在这些测试路径；没有全球网络测量，因此不夸大为全机零请求证明。

## 当前覆盖率

JaCoCo 仅使用本轮 JAVA 的新鲜执行文件，校验 SHA、摘要、报告 UUID、完成状态及字节码匹配。整个编译的 src/main 含 DTO 与基础设施，不能以总比例替代业务准入。

全 Java 行 **31.17%**（8120/26052），分支 **6.94%**（3117/44914）。核心目标仍为行 85% / 分支 75%，未全部满足。HTTP 未合并进本次 JaCoCo 度量，不用覆盖率推断页面通过。

| 核心类 | 行 % | 分支 % | 85/75 目标 |
|---|---|---|---|
| OAuth2TokenApiImpl | 100.0 | 无分支 | MET |
| CatalogOptions | 98.51 | 69.57 | GAP |
| PaymentAttemptService | 96.89 | 77.11 | MET |
| PaymentCancellationGuard | 97.44 | 73.08 | GAP |
| AdminAuthServiceImpl | 93.18 | 73.53 | GAP |
| StoreAccessService | 94.29 | 87.5 | MET |
| UserServiceImpl | 0.0 | 0.0 | GAP |
| OAuth2TokenServiceImpl | 94.55 | 72.86 | GAP |
| PermissionServiceImpl | 88.68 | 70.83 | GAP |
| OrderPlacementService | 91.53 | 70.0 | GAP |
| PaymentEffects | 100.0 | 91.67 | MET |
| PaymentProcessor | 85.56 | 80.41 | MET |
| MemberAuthServiceImpl | 55.88 | 53.85 | GAP |
| CouponCodeGuard | 86.96 | 67.86 | GAP |
| CouponMarketingService | 98.09 | 69.44 | GAP |
| CouponLifecycle | 90.2 | 64.58 | GAP |

V8：Vue 全源码行 0.17% / 分支 8.76%；实际 Form 组件行 100% / 分支 91.66%。这些为组件运行范围，不是端到端范围。UniApp 第一方 utils 行 30.29% / 分支 87.24%，未执行模块留在分母；不包含页面或真实 API。不同工具分支口径不可相加。

## CI 与验收边界

基线 PR14 实际 run `38039991551`，HEAD 为 `c4a180833acbfa943dc99348d304dff8bd4fed83`：quality、controlled-dependencies、browser-smoke、high-risk-mutations 均 SUCCESS。本轮草稿 PR 的最终 HEAD CI 结果单列于 `discovery-ci.md`；基线 SUCCESS 不代替新 PR 实际状态。

TEST_EXECUTION_COMPLETE / DEFECT_DISCOVERY_COMPLETE 仅针对本轮可执行的测试与结果收集。覆盖缺口和阻塞不被改写为 PASS。本轮完成判定见 `discovery-delivery.md`。已确认 RED 未修复且关键覆盖未达标，QUALITY_GATE_READY=NO、PHASE_6D_ALLOWED=NO。
