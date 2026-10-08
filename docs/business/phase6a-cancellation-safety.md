# PR #10 review-fix：取消与支付尝试互斥

审查基线：`b39bdc692e596ff2333225fa7f03aa4f8394e004`。本修复仅处理顾客取消、旧订单取消及新版未付超时与现有支付生命周期的互斥；不进入 Phase 6B，不改 provider、finalization、钱包或 reconciliation 状态机，不新增远端操作。

## 根因与审计

`OrderPlacementService.cancel()` 原来锁定订单后仅判断 paid/status/refundStatus；没有读取 PaymentAttempt。`expireBatch()` 和 `UnpaidOrderExpiry` 使用该方法，所以同样可能释放仍可付款或结果不确定订单的库存/优惠券。旧取消入口也缺同类门禁。

审计确认：PaymentAttemptService 创建、预支付声明/结果记录、终止和 remote proof 提交均先锁订单，再锁 attempt。PaymentAttemptMapper 的 active slot 只覆盖 CREATED/PREPAY_CREATED，不能单独证明全部历史尝试均已安全终止；只看最新 attempt 也会遗漏旧未知记录。PaymentProcessor 的锁顺序是 payment event → order → attempt；取消不能改成 order → event。远端终止证明由既有 lease/fencing 机制写入 remote_terminal_state/remote_confirmed_at，本地过期不是该证明。

## 安全判定

新增只读 `PaymentCancellationGuard.assertSafeAfterOrderLock()`，调用方必须已锁订单。锁定该订单全部 attempt 历史，禁止只查 active slot 或最新记录。

| 证据 | 顾客取消 / 自动超时 |
|---|---|
| 无 attempt，且没有支付收件/冲突证据，订单仍未付且可取消 | 允许；保留原事务内库存/券恢复 |
| CREATED，即使还未声明 provider 请求 | 拒绝/延期；调用方须先通过已有 terminateCreated 显式终止，再重试 |
| prepay_requested_at 已写入、预支付超时/未知、PREPAY_CREATED、恢复 lease | 拒绝/延期；不重发、不清 marker、不自动查询/关单 |
| FAILED/EXPIRED/CANCELED，完全没有请求、预支付、交易、收件、支付时间或恢复证据 | 允许显式本地终止后的取消 |
| 已请求的终态，没有有效 remote proof | 拒绝；本地 EXPIRED/CANCELED 不足以证明远端关闭 |
| WECHAT + CANCELED/CLOSED 或 REVOKED；FAILED/PAYERROR，且 remote_confirmed_at 存在 | 仅在无成功收件/冲突/交易等其他证据时允许 |
| PAID、未知 provider/status、交易/payment_event/paid_at、残留恢复 lease | 拒绝/延期 |
| 任何支付收件或 claimed-order conflict，包括 RECEIVED、FAILED_RETRYABLE、PAYMENT_CONFLICT、RECONCILIATION_REQUIRED | 拒绝/延期，不删除或覆盖证据 |
| 可信关闭后迟到 success | 仍走既有 reconciliation，不自动履约；若证据已落库则取消也延期 |

没有自动终止 attempt、清理金融证据、修改 paid 或修改账本。无法证明安全时 fail closed；Schema 不完整也不能继续释放库存。

“无 attempt/收件”是本地可观测证据，不声称能证明旧版未被记录的 provider 链接已关闭。历史外部链接仍遵循已有只读风险审计和人工处置要求；本阶段真实支付继续冻结。

## 锁与事务

新旧取消统一使用 READ COMMITTED，并在 order 行锁之后检查 attempt。顾客、Redis 旧监听入口和数据库超时入口共用防护；旧取消的门禁放在 markCanceled 之前。库存、券、订单软删除、取消记录仍在同一个事务。

支付收件/冲突使用该事务的 READ COMMITTED 非锁定读取，避免复用更早的 REPEATABLE READ 快照，同时不倒置 event → order 的锁。没有额外 REQUIRES_NEW 连接，因此不会因所有连接都在等订单锁而耗尽连接池。若调用方已开启不兼容的 REPEATABLE READ 外层事务，则明确拒绝、回滚，不默默降级判定。

订单锁保证创建/声明/确认 proof/支付处理与取消有先后关系：取消先提交则后续 createOrGet 拒绝 deleted 订单；attempt 先提交则取消被阻止。确认关闭与迟到成功沿用原状态机：取消之后才到达的成功证据仍保留为 reconciliation，而非重复履约。

## 验证方式

`PaymentCancellationDatabaseTest` 运行现有真实 PaymentAttemptService、生产 MyBatis mapper、PaymentInbox/PaymentProcessor/finalization 及新取消服务。只模拟外部依赖，不调用 provider。108 个用例在 H2 和隔离 MySQL 8/InnoDB 执行，覆盖：

- 活跃/未知预支付、显式未请求终止、可信 CLOSED/REVOKED/PAYERROR、未证明终止的历史记录及多代 attempt。
- 已付、独立冲突、已收件未处理、关闭后迟到成功、缺 schema、外层错误隔离级别、安全终止后的取消重试。
- 顾客取消 vs attempt 创建、超时 vs attempt 创建、callback success vs 取消、关闭后 late success vs 取消：每组 20 并发，分别重复 20 轮。
- prepay claim vs 取消、remote proof vs 取消；强制 event 已锁且等待 order 的竞争，验证取消不锁 event、不死锁；先读再提交收件，验证取消看见最新已提交证据。
- 被阻止的顾客取消与超时均比较完整前后快照：商品/SKU 库存与销量、优惠券、订单、库存预占、attempt、payment/conflict、用户、bill、钱包 ledger 和充值表完全不变。

历史异常终态使用隔离库合成 INSERT 夹具表达，没有放宽不可变 UPDATE 约束。另有缺 schema 测试故意删除该临时库表并验证取消拒绝；不在开发库执行此类 DDL。MySQL 测试库/账号自动清理，不使用开发库资金。既有 80 项业务测试继续执行真实 guard（其金融表为空），新增 108 项覆盖完整金融 schema 和实际 mapper。

复现：先完成 reactor install，然后运行普通 focused；真实库执行 `python3 tests/payment/mysql-acceptance.py --cancellation` 和 `python3 tests/business/mysql-acceptance.py`。前者包含原金融回归与新 108 项。最终普通 focused 为 629 PASS（原有 521 + 新增 108）；MySQL 金融/取消安全 429 PASS，业务 80 PASS，合计 509，均为 0 failure/error/skip；后端 55/55 SUCCESS。严格源码、压缩产物和运行日志扫描通过，实际后端的小程序未付下单/取消 smoke 12/12，paymentRequests=0。完整记录见 PR 描述及本机 review-fix 交付报告。

## Financial Safety Statement

未使用真实商户凭据；未调用真实微信 API、预支付、订单查询、关单或 callback；未调用 wx.requestPayment；未执行真实退款/充值或资金交易；未修改开发库余额、积分、金融账本、支付记录或 marker。支付/reconciliation 配置仍关闭，所有成功事件和终止证明仅在自动清理的隔离测试库合成。
