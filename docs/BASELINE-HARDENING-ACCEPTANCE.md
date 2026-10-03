# 第四阶段验收记录

截至 2026-10-03，代码修复、构建、真实登录和未支付订单回归已完成。协议默认未接受已验证；用户随后亲自完成勾选及手机号授权。后端 auth-miniapp-login 成功，页面回到首页，当前用户与真实 Redis 登录态有效。授权取消类别有单元测试覆盖；本轮最终人工实测选择了授权成功，未声称人工点击取消。

## Git

- 起点已核实：clean `develop`，HEAD `b8800d4071dd601b50f8be75b1b2811ba0558d30`，origin 为 `surqing/yshop-drink`。
- Annotated tag `baseline-runtime-validated-2026-10-03` 已创建、推送，指向上述 commit；未移动或改写。
- 修复分支：`chore/baseline-hardening`；逻辑提交与最终 PR/HEAD 记录见本地交付报告及 PR。
- `develop` 与 `master` 未被修改；未 merge、force push 或 rebase 公共历史。
- 全部提交文件逐个列于 [文件清单](BASELINE-HARDENING-FILES.md)。最终 clean 状态在提交与推送后另行检查。

## 问题、修复与证据

| 项目 | 根因与修复 | 验证 |
|---|---|---|
| Promise 错误处理 | App 与登录页原来的 wx.login.then(async ...) 没有完整捕获身份交换失败；统一共享进行中的真实交换，由调用者区分取消、SDK、后端和网络失败，显示可理解提示和安全类别日志 | 2 项分类/防泄漏测试通过；真实 code 首次交换成功，再用同一已消费 code 返回 1004004002，错误被捕获、未捕获异常 0；停止后端的真实网络失败被正确归类，未捕获异常 0；取消/拒绝与 SDK 失败类别由单元测试覆盖；最终人工手机号授权成功，不将其记为取消流程实测 |
| 敏感日志 | cookie、请求 payload、完整会员/支付响应，以及后台 SDK、HTTP 参数与 MyBatis DEBUG 参数可能包含身份凭据；改为状态、类别、业务 ID/数量，认证审计不记录 payload，SQL 日志省略原文、参数和结果值 | 3 项访问日志测试、2 项 SQL 日志安全测试通过；真实凭据与运行日志/产物扫描通过；10 份旧私有日志原地脱敏，未删除文件，运行日志权限限制为 600 |
| 协议勾选 | 原 radio 未将 checked 绑定到业务 isChecked，原生展示和业务布尔值可能不同步；改为同一 ref 控制 UI 和登录 guard | 默认 checked=false 已实测；用户亲自勾选及授权后 auth-miniapp-login 成功，业务 guard 通过，登录页正常返回首页；自动化未代为同意 |
| 微信 session | Redis set 原来无 TTL；现经 Spring 配置绑定到有限 Duration，默认 30m，拒绝空/零/负值；登录页加载时更新 session，过期后获取新 session 并要求重新授权，禁止旧加密数据自动重放 | 2 项配置/Redis 调用测试通过；真实 TTL=1800；临时 3 秒配置实测 3 → 到期不存在(-2) → 正常新 wx.login 恢复 3；仍识别同一会员；已恢复 30m |
| Member token | 默认 OAuth2 client SQL seed 为 18,000,000 秒，约 208.3 天，后台与会员共用；refresh 为 43,200 秒，服务器有 refresh API，小程序没有完整刷新周期 | 未修改 TTL。修改共享策略会影响现有登录，需要后续分离客户端策略、完成刷新与重新认证，未做大规模认证重构 |
| TS2688 | typeRoots 限制安装声明子路径解析，qrcode 声明名误写为 @types/qrcode；修复配置后暴露历史类型错误 | 按你追加授权修复必要历史类型；最终 ts:check 零错误、build:local 成功；检查严格度、Node、依赖大版本、锁文件保持原状 |

## 构建与运行

| 验收 | 结果 |
|---|---|
| JDK 17 / 后端完整构建 | 55/55 模块 SUCCESS，使用项目已验证的 clean install package / skip-tests 流程和私有 Maven 缓存 |
| 后端启动 | 48081 健康检查 UP；数据库、Redis 正常；最近稳定运行日志 ERROR=0 |
| 聚焦后端单测 | MiniRedisDAO 2、ApiAccessLogFilter 3、SafeSqlLog 2 均通过，另行执行而非声称 skip-tests 构建运行了单测 |
| 后台 Vue | ts:check SUCCESS，build:local SUCCESS；5173 登录页与滑块验证码加载正常，浏览器 console errors=0；未代用户完成 CAPTCHA |
| UniApp | HBuilderX 5.26.2026091802 编译 SUCCESS；微信开发者工具 Stable 2.02.2608080 正常运行；官方 CLI 与 miniprogram-automator 可用 |
| 本地基础服务 | 原 MySQL 与 Redis 容器 healthy；本地工具、凭据和数据库数据保留 |

## 真实 Smoke Test

| 检查 | 结果 |
|---|---|
| 真实微信 code → session/openid | 成功，仅输出存在状态，未输出身份标识或密钥 |
| 本地会员识别 | 成功，绑定会员数为 1，无伪造身份 |
| 后端重启 / 小程序冷启动后的真实登录态 | 成功，已存 token 有效，get-info code=0 |
| 首页 | PASS |
| 种子门店 2 | PASS |
| 商品列表 | PASS，10 个商品 |
| 商品详情/规格弹窗 | PASS |
| 页面加入购物车 / 购物车页面 | PASS |
| 创建未支付订单 | PASS，仅新建 1 单；复跑使用持久检查点复用该单 |
| 订单列表与详情 | PASS |
| paid=0 | HTTP 详情与数据库均确认 |
| 支付接口调用数 | 0，检查本轮审计记录及应用请求事件 |
| 未捕获运行异常 | 0 |
| 一键启动、编译、打开工具、验收、退出清理 | run.sh 完整通过，加入工具端口与 App 的有限就绪等待 |

订单创建通过真实 create API 使用页面同等 payload，避免原页面提交按钮继续进入支付页。该验收不声称测完支付关联按钮或真实支付。未 mock 登录、token、openid 或门店商品；未改数据库来适配测试。

## 安全与限制

真实支付：NO。

Real payment was not invoked. No /order/pay request was made. No wx.requestPayment call was made. No funds were transferred.

AppSecret 仍仅在服务端私有配置；源码、构建产物、报告与计划提交均不包含它。扫描精确匹配私有配置及真实运行身份值，结果仅报告 PASS/FAIL 和位置。安全扫描不能替代全量安全审计。旧数据库中既有审计记录未被清除，本次修复保护后续记录；运行日志已对可识别的既有敏感诊断脱敏。

仍存在：208 天 token / 12 小时 refresh 策略技术债、小程序未集成 refresh；旧 encryptedData/iv 手机号接口；Node 20 EOL、Vite CJS、Browserslist、::v-deep、wx.getSystemInfoSync、HTTP 图片、样式选择器和旧依赖警告。开发工具热重载期间曾出现 SDK 路由错误与图片中断/Broken pipe，脚本通过关闭旧项目再启动和有限就绪等待消除测试启动竞争，未修改业务来掩盖这些现象。自动化端口测试期间临时开启，结束应回归普通开发模式。

协议人工授权已完成并通过后端与用户态验证。本分支按认证/隐私、后台类型、自动化/文档拆分 conventional commits，推送后创建指向 develop 的 PR；不合并 PR。最终 Git 状态、提交哈希和 PR 地址在本地交付报告中记录，避免提交文档自引用其自身提交哈希。
