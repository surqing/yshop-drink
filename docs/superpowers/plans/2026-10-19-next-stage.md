# 下一阶段功能与缺陷工程实现计划

> 执行技能：superpowers:subagent-driven-development。按任务执行、独立审查，原始失败不得改写。

Goal: 完成 T0–T5，修复六项缺陷、兑换安全重试、打印集成及当前源码证据。
Spec: /Users/sur/Documents/Codex/iPeony/开发报告/2026-10-19-下一阶段功能与缺陷开发计划.md
Authorization: 用户于 2026-10-19 明确要求 IMPLEMENT THIS PLAN；完整工程计划见本聊天同日批准文本。
Architecture: 沿用现有 Spring/MyBatis/MySQL 事务、会员账户流水、Vue/UniApp 及统一质量框架。

## Global Constraints
- 四业务 PR、独立 worktree；保留旧分支；未授权合并、部署、真实设备或厂商调用。
- 仅 owned/synthetic；真实支付、退款、充值、微信/短信及真实打印厂商调用冻结。
- 不复制积分账户，不自动修改历史积分/订单/流水，不弱化原业务断言。
- 精确 SHA/digest/runId/manifest/artifact/cleanup；NOT_RUN/BLOCKED/FAILED 不计 PASS。
- QUALITY_GATE_READY 与 PHASE_6D_ALLOWED 独立判断；F1–F5 与积分等级/券不在当前实现范围。
- 使用 /Users/sur/Documents/Codex/iPeony/.local-dev/tools.sh；Python 可复用 integration-merged-19/.quality/venv/bin/python。

### Task 1: T0 基线与修复状态合同
- [ ] legacy_baseline.py 增加必填 --base；冻结源码及其迁移，不修改基线生产源码。
- [ ] 每个版本的六项缺陷状态绑定源码；55e379f 建精确历史合同，未知缺合同拒绝。
- [ ] compare_defects 按 base/candidate 各自状态验收；批准 RED→GREEN，已修项不得豁免。
- [ ] CI 使用真实 base；六项全修候选必须 exit=0；部分修复保留原 FAILED。
- [ ] 保留断言指纹与业务状态；拒绝缺项、重复、变更正确预期、未知失败、环境失败和不实清理。
- [ ] 验证部分/全修基线、回退、预期篡改、缺清理等负向场景；更新 quick 精确清单。

### Task 2: T1 兑换正确性
- [ ] 先真实观察原 D002/D003/D005/D006/D007 RED；补数量边界、总成本不足、销量/流水无副作用和故障窗口。
- [ ] 保持 submit(Long uid, AppScoreOrderParam param) 和 CommonResult<Boolean>；num 传输兼容，严格正整数，拒绝空/0/负/小数/非数字/溢出。
- [ ] 数据库单价 × 数量，订单单价/总积分、账户与流水一致；BigDecimal 比较，不转 int。
- [ ] 商品行→会员行，检查删除/上架/库存/余额；复用 lockById/changeIntegral/expendExact，检查所有写入结果。
- [ ] 移除 Redis tryLock 跳过扣库存路径；订单、库存/销量、积分、流水同事务，任一失败回滚。
- [ ] ScoreOrderDatabaseTest 在 score 模块；跨收货奖励在 server 调用两类真实服务路径；H2 + 受控 MySQL 精确注册。
- [ ] 最后一件两用户、同用户积分只够一次、与收货奖励并发，已知业务失败与环境异常严格区分。
- [ ] PR-A 关闭五项，D008 原 FAILED 保留，D001/D004 安全回归通过。

### Task 3: T3 自提联系方式
- [ ] 原 D008 RED；新增改填号码、空/非法号码、同键改号、旧摘要、配送归属、跨店、取消一次。
- [ ] contactMobile 首次默认会员号码，v-model，页面回显不覆盖编辑；请求包含 mobile。
- [ ] takein trim+项目校验，落 user_phone；takeout 从本人地址取电话；不修改会员 mobile。
- [ ] 新摘要带版本和号码；旧摘要只在原请求与已存号码匹配时重放；缺号/不符提示看已有订单，不自动补号或重建。
- [ ] 未确认结果保留原键/快照，不因改号/旧缓存自动换键；真实 Mini→后台→SQL 验证。
- [ ] 更新有效测试夹具但保留业务断言；关闭 D008，完整 HTTP 全绿；PR-B。

### Task 4: T2 兑换幂等与页面反馈
- [ ] 新增 idempotencyKey，规则 [A-Za-z0-9_-]{16,64}；原成功响应保持 true。
- [ ] yshop_score_submission: uid/key/hash/orderId/createTime，唯一(uid,key)、唯一orderId；InnoDB 增量迁移，重复执行和不兼容拒绝，不历史回填/自动过期。
- [ ] 请求记录→商品→会员，同事务；规范商品/数量/地址摘要；同键同摘要重放、异摘要冲突；已提交结果优先于当前业务资源变化。
- [ ] 两次点击、响应丢失、进程重启、冲突、跨用户同键、故障重试，订单/支出/库存/销量均一次。
- [ ] 前端待提交/提交中/未知/明确失败/成功，会员隔离持久键和快照；单飞，未知保留原键，成功清理，未知不静默新意图。
- [ ] 文案为本次消耗 N 积分，去币种符号，页面固定1件；缺键提示更新，禁止服务端随机兜底。
- [ ] 同一发布单元，新增场景/精确清单/迁移验收，PR-C。

### Task 5: T4 打印集成
- [ ] 新隔离候选合入 feature/printer-management 历史，保留优惠券码+打印密钥脱敏。
- [ ] 门店删除同时保留总部权限、门店锁、绑定清理事务；测试 Bean 和无权/并发/回滚场景。
- [ ] 迁移在 DDL/菜单改变前校验旧表/父菜单/身份/完整结构；部分结构/缺约束拒绝，不普通角色扩权。
- [ ] mysql-acceptance.py 使用 owned helper、动态端口、锁定工具链、精确清单，清理后方可 PASS。
- [ ] Java/Node/浏览器/MySQL 注册统一框架；权限、旧路由、缓存、乱序、历史、重连、小池并发与 UNKNOWN 只读。
- [ ] 复用 PrinterProvider/Session；UNKNOWN 不重发，ACCEPTED 不等于出纸；真实厂商关闭；不自动订单打印，PR-D。

### Task 6: T5 最终验收与交付
- [ ] 每包先定向；冻结候选 BUILD/QUICK/JAVA/INTEGRATION/FRONTEND/BROWSER/CROSS-END/完整 HTTP。
- [ ] 新变异：数量乘法、行数、上架、余额、幂等、号码摘要、查询只读；旧矩阵不冒称新代码覆盖。
- [ ] 新场景注册兑换成功/拒绝/重试、自提改号、打印 UNKNOWN；两个独立完整 local + 一个实际 Linux ci，Linux Mini NOT_RUN。
- [ ] 每份证据 SHA/digest/runId/精确清单/产物/清理；SDK/JDBC 失败先采现场，根因未证实保持 UNCONFIRMED。
- [ ] 独立 task + 最终审查，安全摘要、未关闭问题、迁移/回退、开发报告索引；各质量状态分别报告。
