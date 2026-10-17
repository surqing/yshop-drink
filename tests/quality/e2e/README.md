# 三端核心 E2E

统一入口是 `tests/quality/run.py E2E`。Python 管理源码、构建、所属环境和清理；Playwright Test 串联后台页面、HTTP 与官方小程序驱动；只读 SQL oracle 独立核验最终状态。

## 首次运行

在本工作树根目录执行。需要 Git、Python 3.11+、JDK 17、Maven、Node、pnpm 8.15.9、Docker 和锁定版本 Chromium。已有 iPeony 工具链可先加载 `/Users/sur/Documents/Codex/iPeony/.local-dev/tools.sh`。

```sh
python3 -m venv .quality/venv
.quality/venv/bin/python -m pip install -r tests/quality/requirements.txt
pnpm --dir yshop-drink-vue3 install --frozen-lockfile
pnpm --dir yshop-drink-vue3 exec playwright install chromium
npm ci --prefix tests/mini --ignore-scripts --no-audit --no-fund

# 不创建环境、不启动程序、不写业务数据
.quality/venv/bin/python tests/quality/run.py E2E --profile local --list

# 本机完整三端；输出目录必须私有，且位于源码目录外或被忽略的 .quality 下
.quality/venv/bin/python tests/quality/run.py E2E --profile local --output /private/tmp/yshop-e2e-local

# Linux CI / 本机后台与后端变体
.quality/venv/bin/python tests/quality/run.py E2E --profile ci --output /private/tmp/yshop-e2e-ci

# 单场景复现，不能据此认证完整集合
.quality/venv/bin/python tests/quality/run.py E2E --profile local --scenario order-retry --output /private/tmp/yshop-e2e-retry
```

local 还需要 macOS、HBuilderX、已登录且启用服务端口的微信开发者工具，以及工作空间私有 AppID 配置。默认读取工作空间 `.uniapp-dev/.env` 中的 `WECHAT_APP_ID`；可用 `YSHOP_MINI_APP_CONFIG` 指定另一个私有文件。不要把它提交到仓库。能力缺失返回 BLOCKED，不自动降级。`YSHOP_TEST_WORKSPACE` 可以显式指定含 `.local-dev` 的工作空间；不会读取 `selected-repo` 选择被测源码。官方工具路径可用 `YSHOP_HBUILDER_CLI`、`YSHOP_WECHAT_CLI` 指定。

`order-cancel` 和 `coupon-release` 还需要可工作的原生确认控件接口。已观察到工具 `2.02.2608080` / SDK `3.17.4` 的 `Native.confirmModal()` 静默返回但不点击确认框；本框架通过真实对话框回调确认是否完成，缺失则报告 `MINI_NATIVE_CONFIRMATION_UNAVAILABLE` / BLOCKED。不会替换 `showModal` 的真实响应或直接调用取消业务方法。可用 `YSHOP_WECHAT_CLI` 指向兼容工具；macOS 可显式用 `YSHOP_E2E_NATIVE_ADAPTER=applescript` 启用 AppleScript/JXA 辅助功能适配器（默认关闭）。需要用户事先允许系统脚本及辅助功能权限。它只在官方接口未产生真实确认回调时，对唯一 `E2E-<runId>` 窗口核对顶部 WebArea 的完整私有项目路径、runId、取消文案和唯一确认链接后执行 AXPress；不使用全局键盘、坐标或业务回调替身。报告记录实际适配器使用；真实取消 API、UI 和 SQL 验证仍为必选。若 `osascript` 报“不允许辅助访问”，请由用户在系统设置中授权，不会自动修改系统权限。

运行时不要修改工作树。源码 SHA、内容摘要和产物哈希必须匹配；改变源码会使正在运行的结果无效。构建凭证不匹配时重新构建，不能使用旧 JAR 或旧 Surefire 报告。小程序从当前源码重新生成私有副本并核验编译哈希。

## 场景和边界

七组必需场景为 `auth-session`、`catalog-order`、`store-isolation`、`order-retry`、`order-cancel`、`coupon-release`、`coupon-complete`。local 标为 `mini-admin`；ci 标为 `api-admin`，小程序明确 NOT_RUN。

认证从空会话开始。自动化只替换微信平台凭据、手机号授权事件和短信发送渠道；会员绑定、验证码生命周期、token、业务服务、事务和数据库真实运行。未知微信公众号或支付 SDK 操作直接失败。合成完成通过私有子进程命令桥调用真实完成服务，不经供应商，不直接写 SQL 改已支付状态。真实微信身份、真机、真实短信、支付通知协议和真实支付均不在验收范围内。

取消遵循现有产品合同：逻辑删除、从活动列表消失、详情不可见、库存与券只恢复一次；不认证不存在的取消历史页。券核销同时核对 `redeemed_at`、`redeemed_order_id` 和操作账，不仅看状态码。

## 报告与失败定位

每轮使用新的 runId 和 owner。结果位于输出目录的 `<runId>/`，含 `report.json`、`junit.xml`、`report.html`、`summary.md`。JSON 记录计划/实际检查、执行端、源码、产物、替身和清理结果。

PASSED 要求精确清单中的检查全部真实执行、无重试、证据完整、源码未变且清理成功。FAILED 表示业务断言失败，BLOCKED 表示缺少能力，INCONCLUSIVE 表示中断、清理或证据问题；它们均不等于通过。子集通过、后台/API通过、完整三端通过和全项目质量门禁是不同结论。

先看 `failedStage`、场景 `stage` 和第一个失败 check。私有的 `playwright-private.log`、`case-*.json`、`mini-*/lifecycle-private.json`、导航诊断、截图、trace 和所属后端日志用于定位；不得整目录公开。修复后使用新 runId，保留首次失败。不使用自动测试重试、skip 或扩大等待来隐藏业务缺陷。

GitHub Actions 的 `core-e2e` 检出 PR head 源码，自动运行真实 ci profile。其他作业可能检出测试 merge commit，必须按实际 SHA 分别解释。两个来源只在 SHA 与内容摘要都相同时才能汇总。小程序、会员、商品、订单、券或框架变更还需要同源码的完整 local 证据；最终稳定性验收需要两轮新数据完整 local 运行。

最终汇总复用精确检查合同，要求两个不同 owner/runId 的完整 local 报告和一个实际 Linux ci 报告。它重新核验源码、产物、平台、编译证据和每项检查；本机 ci profile 不能替代 Linux。先将下载的 CI 安全摘要放在私有目录，再在冻结工作树执行：

```sh
PYTHONPATH=tests/quality .quality/venv/bin/python -m e2e.acceptance \
  --local /private/tmp/local-a/RUN_A/report.json \
  --local /private/tmp/local-b/RUN_B/report.json \
  --ci /private/tmp/linux-ci/report.json \
  --output /private/tmp/e2e-core-acceptance.json
```

CI 上传前运行 `publish.py`，只复制检查过的四个摘要；不会复制 context、日志、截图、trace 或发送收件箱。发布检查不把失败结果变成成功。

```sh
python3 tests/quality/e2e/publish.py --runs /private/tmp/yshop-e2e-ci --output /private/tmp/yshop-e2e-public

# 确定的业务断言失败探针：应非零退出、保留失败，并完成所属资源清理
YSHOP_E2E_INJECT_FAILURE=order-retry/single-order-and-stock-sql \
  .quality/venv/bin/python tests/quality/run.py E2E --profile ci --scenario order-retry --output /private/tmp/yshop-e2e-negative
```

## 中断与清理

优先向 runner 发送 SIGINT/SIGTERM，保留最长 120 秒清理窗口；不要强杀或手动删除其他开发容器。报告中的 cleanup 必须 PASSED 才能认证本轮。即使之后人工清理成功，原来的失败或中断报告仍保留。

异常强杀后，使用该运行 `report.json` 的 owner、`owned/process.json` 的 pid/port、私有编译项目路径检查残留。进程应同时匹配所属 owner 和该 run 的 support 目录，再终止该进程。微信 CLI 只关闭此运行的 `mini/project/unpackage/dist/dev/mp-weixin` 项目。

依赖容器清理复用 `owned_resources.remove_owned`：只构造本轮 owner 对应的两个确定名称；该函数在删除前复核标签并核验卷。不使用 `docker prune` 或按模糊前缀批量删除。

```python
# 在本工作树根目录执行；run_dir 必须是要恢复的那一轮私有目录。
import json, re, subprocess, sys
from pathlib import Path
sys.path.insert(0, 'tests/quality')
from owned_resources import remove_owned
run_dir = Path('/private/tmp/yshop-e2e-local/REPLACE_WITH_RUN_ID')
report = json.loads((run_dir / 'report.json').read_text())
owner = report['owner']
assert re.fullmatch('[a-f0-9]{32}', owner) and report['runId'] == run_dir.name
def docker(*args):
    result = subprocess.run(['docker', *args], text=True, capture_output=True, timeout=120)
    if result.returncode: raise RuntimeError('OWNED_DOCKER_OPERATION_FAILED')
    return result.stdout.strip()
names = ['yshop-quality-business-mysql-' + owner, 'yshop-quality-business-redis-' + owner]
print(remove_owned(docker, names, owner))
```

## 增加场景

在 `registry.py` 登记场景和每个必需 check ID，在 `core.spec.ts` 实现真实业务步骤；通用页面能力放 driver，必要的只读状态放 `oracle.py` 登记查询。不复制容器、编译、生命周期、日志或报告代码。先添加能失败的断言，再实现修复；框架合同或检查数量变化要更新精确 manifest 并运行 QUICK。业务 SQL 参数不得接受任意查询。

新增测试支持只放测试 classpath。正式 JAR 不得含测试支持类或测试 HTTP 端点；不得替换被验的订单、身份、库存、优惠券、完成服务或事务。

## 旧入口映射

| 旧入口 | 当前用途 / 对应 E2E |
|---|---|
| `run.py QUICK` | 框架和轻量业务回归，继续独立保留。 |
| `run.py CROSS-END` | 旧后台真实页面与 SQL 范围，保留兼容；新核心集合用 `E2E`。 |
| `run.py MINI-PAGES` / `tests/mini/pages.cjs` | 原有页面范围，旧会话夹具不认证新登录；新登录与核心闭环用 local E2E。 |
| `business-backend.py` | 旧 smoke、双进程和 GUI 兼容包装；环境生命周期由 OwnedEnvironment 共用。 |
| `tests/defects/coupon_lifecycle.py` | 原专项验收保留；新核心券流程用 coupon-release / coupon-complete。 |
| `run.py BROWSER` | 明确 API 夹具的浏览器页面范围，不替代真实后端 E2E。 |
| Java / frontend / mutation / coverage / FULL | 各自维持原范围；FULL 不隐含完整三端，独立执行 E2E。 |

本轮不自动合并前置分支、不部署，也不自动放行 QUALITY_GATE_READY 或 PHASE_6D_ALLOWED。
