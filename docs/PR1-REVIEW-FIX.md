# PR #1 第二轮 review-fix

修复起点：`d9933123d72349f073e85a64d14444583f4e8dbd`。继续使用 `chore/baseline-hardening`，目标 develop，不合并、不改写已有四笔提交或 baseline tag。最终提交与 PR 状态另记本地交付报告，避免文档自引用。

## 修复范围

1. **统一 API 日志脱敏。** `SensitiveDataSanitizer` 被访问 Filter 与全局异常处理器共同调用。递归处理对象和数组，字段匹配不区分大小写，并归一化 `_`、`-`；包括 keyPrivate/privateKey/apiV3Key/apiKey/keyCertPwd/certificatePassword 和认证凭据。注解额外字段规则同样不区分大小写。空、标量、表单或损坏 JSON 不记录原文，解析失败不影响业务请求。保留 id/shopId 等普通业务字段，不修改原始请求 Map。身份接口仍省略 payload。异常审计、默认异常日志保留异常类型、cause 类别和位置，省略任意异常消息；访问审计保留结果码，去掉可能插入凭据的结果文案；启用响应审计时保留脱敏 data 与 code，不记录 msg。支付配置写入路径在聚焦测试中验证，不向真实服务写入商户凭据，不调用支付。
2. **修复 clean PR 扫描。** 默认基线为 `baseline-runtime-validated-2026-10-03`，从它与 HEAD 的 merge-base 扫 committed diff/受影响文件，同时扫 staged/unstaged/untracked。支持 `--base <ref>`；无有效 base、无保护值或指定 runtime 路径缺失均失败。提交内容不会被未提交的安全替换掩盖。只输出 PASS/FAIL、位置与覆盖文件数。此工具是精确值回归检查，CI 必须安全提供保护值，不能声称覆盖所有未知 Secret。
3. **收窄 authError。** 仅保留微信 session、手机号/身份交换。短信发送/登录走独立安全错误归一化；按仓库已有错误码白名单显示验证码错误、过期、已使用、发送频繁、每日限额或格式问题。未知发送失败显示“验证码发送失败”，未知登录失败显示“短信登录失败”，网络错误显示网络提示；不透传服务器 msg。请求层不重复弹 toast，页面只弹一次，日志只记录类别与数值码。不修改成功登录、存 token 或协议状态。
4. **修正 query 脱敏。** 移除旧的 isNotEmpty 提前返回错误，统一规则对非空 query 正常序列化；id/shopId 保留，token/code/password 及支付私钥字段去除，不修改调用方 Map。
5. **恢复安全 SQL 可观测性。** SafeSqlLog 保留 MyBatis 传入、经标识符校验的 mapper statement 名称，各类事件均可定位具体 mapper。SQL 原文可能含字面量，因此继续禁止 SQL 文本、参数、结果和原始异常消息；本轮不添加可关闭安全过滤的开关。可配置模板摘要/受控开发模板日志作为后续技术债。

## 测试与回归

- Web 聚焦测试 10 项：统一敏感字段/大小写/嵌套数组、普通 query/不变输入、损坏/标量/表单 JSON、identity payload、省略异常消息保留 cause、支付 create/update 访问审计、异常审计入口与显式响应审计；损坏 JSON 解析路径不调用会输出原文的通用解析器。
- SQL 日志测试 3 项；session TTL 测试 2 项。
- JS 6 项：原认证分类/日志防泄漏 2 项，短信白名单文案、请求层单次归一化、成功/微信路径隔离、网络及未知消息安全 4 项。
- Python 扫描测试 3 项：临时仓库 clean committed 泄漏必失败、已提交泄漏不能被 working copy 掩盖、staged/unstaged/untracked、显式 base、安全内容和无效 ref。
- 后端 JDK17 全量 clean install package，55/55 SUCCESS；聚焦单测另行执行。
- 后台 ts:check 零错误、build:local SUCCESS；无后台源码、锁文件或依赖升级。
- UniApp 重新编译，真实 wx.login → session 和现有真实会员登录态、get-info、商品/规格/购物车/未支付订单与详情 smoke。
- 提交前和 clean/committed 状态分别扫描 baseline→HEAD；真实身份、session、日志和 UniApp 产物扫描。

最终运行证据放在原有 Git 忽略 `.uniapp-dev/logs/review-fix-*` 与 baseline-smoke.json，报告仅包含安全状态与计数。真实 SMS 发信/付费通道没有调用；短信错误为请求层聚焦测试，不能记成运营商端到端验收。

## 支付与范围边界

Real payment was not invoked. No /order/pay request was made. No wx.requestPayment call was made. No funds were transferred.

仅允许必要未支付测试订单；旧单已超时逻辑删除时使用既有显式替代选项，经只读核对后创建至多一个必要订单，保留历史检查点，不更改数据库来适配测试。

本轮不扩大后台表单修改。商品新增/编辑、分类新增/编辑、门店编辑、订单详情/发货、角色菜单/数据权限、支付配置页面的定向 UI smoke 列为下一阶段任务；全项目 type/build 通过不替代这些页面的实际操作验收。真实支付、认证刷新体系重构、phoneCode 迁移及此前警告仍未处理。
