# 功能模块测试结构

本轮使用 PR14 已有 JUnit、VTU、Playwright、官方小程序自动化、独立 MySQL/Redis、覆盖率和变异工具。结构沿用统一入口 `tests/quality/run.py`，仅增加独立未通过集合和优惠券页面用例，不再建设另一套框架。

| 功能模块 | 服务 / 数据库用例 | HTTP / 页面用例 | 本轮未通过入口 |
|---|---|---|---|
| 多门店与权限 | OrderingDatabaseTest、CouponDatabaseTest、PermissionServiceTest、OAuth2LifecycleDatabaseTest | `business_http.py`、`e2e/admin.spec.ts`、跨端页面 | 未通过/未执行状态按矩阵列出 |
| 商品 SKU 库存 | CatalogDatabaseTest、CatalogEditingMysqlAcceptance、OrderingDatabaseTest | 本地真实 HTTP；官方商品/SKU/购物车页 | D003、D005 属积分商品；后台经营页未测项单列 |
| 购物车订单 | OrderingDatabaseTest；`tests/business/cart-context-test.mjs` | 官方 `tests/mini/pages.cjs`；真实 Vue 订单查询 | `http_red.py` D008 |
| 优惠券 | CouponDatabaseTest、CouponPaymentDatabaseTest、CouponCodeRedisAcceptanceTest、CouponCodeSecurityTest | `tests/coupon-form.test.ts`、`e2e/admin.spec.ts`、`coupon_lifecycle.py` | `browser_red.py` / `browser/dialog.spec.ts` D009 |
| 会员身份 | MemberAuthServiceTest、OAuth2LifecycleDatabaseTest、OAuth2TokenServiceImplTest | 两 JVM 真实登录/刷新/撤销 HTTP | `http_red.py` D001、D004 |
| 积分兑换与流水 | 本轮真实 HTTP + MySQL 独立数据 | `http_red.py` 逐项正确预期断言 | D002、D003、D005、D006、D007 |
| 合成支付账务 | PaymentDatabaseTest、PaymentAttemptDatabaseTest、PaymentCancellationDatabaseTest、WalletDatabaseTest、WechatV3DatabaseTest、CallbackIngressEndToEndTest 等 | 仅隔离库、合成事件/TLS；页面桥接核销 | 现有集合结果见报告，不使用真实资金接口 |
| Vue 后台 | Vue Test Utils / Vitest | Playwright 实际 Chromium、独立跨端页面 | D009；其余页面缺口见矩阵 |
| UniApp | `tests/business/*-test.mjs` 生产工具逻辑 | HBuilderX 当前编译 + 官方 automator 10 既有页检查和 12 优惠券链路检查 | 模拟器/物理设备分别标注 |
| 安全、性能、框架完整性 | `tests/quality/` 清单、报告/资源/凭据检查；30 定向变异 | 240 请求双 JVM 有界基准 | 框架与环境执行问题单独记录 |

## 用例结构

每项实际测试保留：模块、名称、源码 SHA 与内容摘要、预期、实际、状态、私有证据位置、清理结果。业务矩阵的机器文件 `current-test-matrix.json` 指向当前实际执行的用例名称。稳定 Java 清单 `tests/quality/java-manifest.json`（实际清单位置由 evidence.manifest 读取）、QUICK / Vue / browser / mini 清单保持精确名称和次数校验。

新增独立集合位于 `tests/defects/`：

```text
tests/defects/
  http_red.py                      # D001–D008，真实本地 API + 专属数据库
  browser_red.py                   # D009，原始 FAILED 报告
  browser/dialog.spec.ts           # 实际 Vue 页面测试
  browser/playwright.config.ts     # 独立执行与私有截图/Trace
  coupon_lifecycle.py              # 复用已有启动器和官方编译流程
  coupon-pages.cjs                 # 实际页面领取、预留、核销、重进
  SyntheticCouponCompletion.java   # 测试专用合成事件桥接
```

D001–D009 明确断言正确业务预期；当前源码下失败并以非零状态退出。独立执行集合不使用 skip / xfail，也不修改预期来取得绿色结果。稳定 CI 与独立失败集合的结果分别报告。

现有入口执行方式和各层边界见 `upgrade-execution.md`；新增集合的准备、命令与清理见 `tdd-red-tests.md`。未测功能保持 NOT_RUN，环境阻塞保持 BLOCKED，不完整记录保持 INCONCLUSIVE。

本轮交付到此停止：下一轮根据 `failed-tests.md/.json` 逐项分析并修复。
