# 自动化测试体系升级审计

本次基于 PR13 的 `92e5a1d18d53479c75bd825c28dd5c6a7791915c` 创建独立分支 `test/phase6q-automation-upgrade`。PR12、PR13、develop/master 未修改或合并；未部署，未开始 Phase 6D。历史报告保留原身份，不能为新源码背书。

审计输入包括全部 17 个 `docs/quality/` 文件、Java/Vue/UniApp 依赖与生产代码、完整测试清单、统一运行器及 GitHub Actions。输入文件哈希保存在本地私有 `audit-inputs.json`；旧文档出现 READY 与 NO 并存，当前以原始请求质量门禁 NO 为准。

| 层级 | 原有能力与不足 | 本次处理 | 仍需验证的范围 |
|---|---|---|---|
| Java | JUnit5/Spring Boot Test/Mockito、Surefire 精确调用清单成熟 | 保留；独立库存、跨店商品、总券量断言及 PaymentEffects 通知分支 | 所有关键类85%行/75%分支仍需逐类确认 |
| 真实依赖 | Python 自有 Docker/MySQL8/Redis7.4/TLS；有归属与清理证明 | 复用两 JVM 隔离业务环境；低于临时端口范围分配后端端口 | 数据库故障与 Redis 故障未全部延伸到页面 |
| Vue 组件 | Vitest/VTU/V8，实际优惠券 Form；全源码行覆盖率0.17% | 保留，不将浏览器成功换算为V8覆盖率 | 商品/SKU/更多经营组件尚缺 |
| Vue 页面 | 历史人工 GUI 不能持续重复 | Playwright Test1.64.0，实际 Vue 路由/权限/表单；5项合成API页面测试，2项真实后端页面测试 | 正常验证码登录、产品创建/库存编辑、401/500/离线全面场景 |
| 小程序逻辑 | Node 原生测试、UniApp工具V8测量成熟 | 保留 | 页面与全源码覆盖率分别统计 |
| 小程序页面 | HBuilderX编译、历史GUI仅绑定当时源码 | 官方miniprogram-automator0.12.1；当前源码镜像重新编译、10项计划断言 | 具体启动/页面兼容性以本次receipt为准；真机与微信身份未验收 |
| 跨端 | HTTP/SQL不变量已有，但缺少浏览器持续链路 | 实际Vue→Spring→MySQL优惠券修改/停用、订单可见与员工跨店隔离；可选官方小程序下单接入 | 优惠券完整页面领取/合成支付生命周期、跨端故障矩阵 |
| 并发/性能 | 末库存、券限量、幂等DB竞争、两进程认证已有 | 复用；新增双JVM 12并发、240请求吞吐/P95/P99/错误率/锁等待差值 | 大规模负载、长稳、崩溃恢复，不以静态代码断言锁性能缺陷 |
| 变异 | 28项业务定向变异+2项前端变异，3项高风险存活 | 保留全部；新增独立拒绝原因/可领取状态断言区分防线 | 以最新变异执行结果判断，不改标签、不删算子 |
| 证据 | 原运行器已拒绝旧XML/零测试/遗漏/skip/清理失败 | 新页面精确计划、runId/SHA/完整源码摘要、六状态与负例 | 普通CI成功不代表专用小程序/真机/发布门禁成功 |

Testcontainers 评估：当前真实依赖已由有归属的 Docker 启动器提供，暂不新增重复容器生命周期；未来新增独立 Java 集成套件再引入。PIT 评估：可补充通用算子，但不能替代业务算子，本次没有无用途依赖。k6 评估：本次先提供适合当前规模的受控双进程读负载基准；生产SLO、较大负载未认证。uni-automator/Jest 评估：项目使用HBuilderX Vue3编译，优先一个官方编译产物页面执行器，不叠加多个主力页面框架。

兼容性依据：[Playwright安装](https://playwright.dev/docs/intro)、[浏览器管理](https://playwright.dev/docs/browsers)、[UniApp自动化](https://uniapp.dcloud.net.cn/worktile/auto/quick-start.html)、[Testcontainers](https://java.testcontainers.org/)、[PIT Maven](https://pitest.org/quickstart/maven/)、[k6测试类型](https://grafana.com/docs/k6/latest/testing-guides/test-types/)。CI浏览器使用Node22；本机Node20.20.2实际结果单独记录。Java17/Maven3.9.16、pnpm8.15.9与现有锁文件保持兼容。

Superpowers6.4.2用于系统定位、测试先行、执行后验证与独立代码审查。Playwright MCP0.0.83用于探索真实页面和定位；本会话没有注册的Playwright MCP原生工具，因此通过用户安装的官方stdio服务完成实际JSON-RPC调用，保留诊断记录。MCP探索不计入可重复CI断言；Playwright Test是正式页面门禁。

测试替身明确：普通浏览器Smoke使用合成API响应；跨端页面代理实际自有后端响应。身份通过自有测试环境API准备，不能声称正常GUI验证码登录已通过。小程序冷启动钩子仅注入合成身份并屏蔽微信登录/金融入口，不替换生产页面或本地业务API成功响应；响应丢失故障发生在后端实际提交之后。所有订单保持未支付，微信支付和对账开关均为false。
