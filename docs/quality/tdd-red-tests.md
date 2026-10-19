# TDD RED 回归集合

本轮只建立正确预期的失败测试，**没有修改生产业务逻辑**。D001–D008 使用真实本地 HTTP 和独立 MySQL；D009 使用真实 Chromium、Vue、Element Plus 和显式合成 API。已有稳定门禁未排除任何历史测试。新增 RED 集合由显式入口运行，不在常规门禁里伪装成 PASS、skip 或 xfail。

## 执行条件

需要 Java 17、Maven、Node 20、pnpm 8、Python 3.9+、Docker、已锁定的 Vue 依赖和 Chromium。依赖安装及工具位置沿用 `upgrade-execution.md`。Maven 缓存必须可写；macOS 受限执行环境需要获准使用本机 Docker/socket。不得指向常驻业务库或真实商户配置。

在仓库根目录设置安全开关和私有输出根目录；路径应在源码目录之外：

```sh
export YSHOP_PAY_WECHAT_V3_ENABLED=false
export YSHOP_PAY_WECHAT_V3_RECONCILIATION_ENABLED=false
export YSHOP_TEST_WORKSPACE=/absolute/path/to/workspace
export YSHOP_MAVEN_REPOSITORY=/absolute/path/to/writable/maven-cache
export PLAYWRIGHT_BROWSERS_PATH=/absolute/path/to/playwright-cache
umask 077
export DISCOVERY_RESULTS="$(mktemp -d /tmp/yshop-discovery.XXXXXX)"
python3 tests/quality/run.py BUILD --output "$DISCOVERY_RESULTS/build"
```

HTTP RED 要求 `BUILD` 生成的 `.quality/backend-build.json` 与当前 SHA、整个工作树内容摘要和 JAR 哈希一致。修改任何被摘要包含的源码/文档后必须重新构建，不能复用旧证书。执行期间保持源码不变。

```sh
python3 tests/defects/http_red.py --output "$DISCOVERY_RESULTS/http-red" --repeat 2
python3 tests/defects/browser_red.py --output "$DISCOVERY_RESULTS/browser-red"
```

**当前两条命令均应以非零状态结束。** 其 `report.json` 保留 FAILED。不要用 `|| true` 充当成功结果。基础设施异常单列 INCONCLUSIVE，不作为稳定 RED。修复后，正确行为应让对应断言自然 PASSED，不能改预期、删用例或调整结果标签。

| Bug | 正确预期 / RED 断言 | 依赖与隔离 |
|---|---|---|
| D001 | 普通资料编辑不能改变已验证手机号或破坏另一个账号的唯一身份 | 两个独立合成会员 |
| D002 | 数量 2 × 100 积分 = 200；剩余 800 | 每次恢复库存/积分夹具 |
| D003 | 库存 1 兑换 2 必须拒绝；无订单或积分副作用 | 真实条件库存 SQL |
| D004 | 使用非本人地址必须在写入前拒绝 | 两个合成地址，校验订单详情不泄露 |
| D005 | 下架商品兑换被拒绝且状态不变 | is_switch=0 |
| D006 | 数量 0 被拒绝且状态不变 | 输入边界 |
| D007 | 支出流水剩余余额等于扣减后的会员积分 | 真实流水表 |
| D008 | 自取订单保留提交的联系电话 | 用例结束取消未付订单 |
| D009 | 加载 B 失败后，后续操作不能向 A 发出更新请求 | 每次新浏览器上下文、失败响应固定 |

`http_red.py` 仅使用所属启动器新建、带所有者标签的容器与数据库，并校验 `/actuator/info` 所有者。只发送 loopback 请求，路径守卫禁止支付、退款、充值等金融入口。测试数据和状态复位只针对本次独立库。finally 通知启动器回收 JVM、容器、临时配置；清理失败或源码变化将结果降为 INCONCLUSIVE。原始日志保持私有。

浏览器入口等待 Playwright 关闭其新建上下文与服务器；原始 JSON、截图和 Trace 仅保存在指定私有目录。**禁止将这些文件直接作为公开 PR 附件。**

## 优惠券页面链路（正常回归，不是 RED）

新增 `coupon_lifecycle.py` 复用现有隔离启动器与 HBuilderX 编译/官方自动化。先执行普通 Java 集合，传入其带 UUID 的报告目录：

```sh
python3 tests/quality/run.py JAVA --output "$DISCOVERY_RESULTS/java"
python3 tests/defects/coupon_lifecycle.py \
  --java-report "$DISCOVERY_RESULTS/java/<run-id>" \
  --output "$DISCOVERY_RESULTS/coupon-pages"
```

需要 HBuilderX、微信开发者工具、现有私有 AppID 配置和已锁定 `tests/mini` 依赖。不是物理手机测试，也不认证真实微信身份。页面领取和下单使用实际隔离 HTTP；成功事件通过测试专用 Java bridge 进入实际 `PaymentFinalizationService`/事务/Mapper，通知、异步统计和购物车适配器使用既有测试替身，会员余额写入委托实际 Mapper。该桥接验证核心核销事务，不等于完整生产 Spring 容器的所有支付后置作用。它不经过真实商户，也不宣称验证支付平台签名接入。既有合成 TLS/回调测试单独负责接入边界。

桥接同时核对一笔权益、一笔订单、预留状态、金额、一次核销、一次收款记录和一次业务流水，重复成功事件必须幂等。生产代码不添加测试后门。所有者、库名、loopback 和 financial=false 校验均须保留。

## 后续 GREEN / REFACTOR 验收

按 `failed-tests.md` 每次选一项，在下一轮进行。每次先保存独立 RED，再最小化修复、原测试转绿、运行相关异常/并发/权限集合，最后执行稳定门禁和独立 RED 集合。恢复生产质量门禁还需要清除剩余关键覆盖缺口；本轮稳定 CI 成功不能覆盖已知产品 RED。
