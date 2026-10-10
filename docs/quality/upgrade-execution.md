# 可复现命令、证据与遗留范围

实现基线：PR13 `92e5a1d18d53479c75bd825c28dd5c6a7791915c`，新分支 `test/phase6q-automation-upgrade`。历史报告未重标；执行时源码SHA和内容摘要必须同时匹配。本文写入时已通过开发阶段页面/跨端试跑，最终冻结源码的回归、覆盖率、变异与GitHub CI结果由交付的机器报告和PR检查提供，本文不预判这些结果。

环境：JDK17、Maven3.9、Python3.11（本机3.9也实际运行）、Docker；Node22用于CI浏览器，本机20.20.2实际验证；pnpm8.15.9。微信试点需要macOS、HBuilderX5.26 Vue3编译器、官方微信开发者工具、用户拥有的合法AppID和已登录开发者会话。节点依赖锁定：Playwright Test1.64.0、官方miniprogram-automator0.12.1。MCP0.0.83使用用户已安装实例，仅作诊断，不作为CI报告来源。

在仓库根目录执行，输出位于仓库外或被忽略的`.quality/`；不要把Token/私有配置/截图/Trace提交或公开上传。

```sh
python3 -m venv .quality/venv
. .quality/venv/bin/activate
python3 -m pip install -r tests/quality/requirements.txt
pnpm --dir yshop-drink-vue3 install --frozen-lockfile
pnpm --dir yshop-drink-vue3 exec playwright install chromium
# Linux: playwright install --with-deps chromium
npm ci --prefix tests/mini --ignore-scripts --no-audit --no-fund

python3 tests/quality/run.py BUILD --output /tmp/quality-build
python3 tests/quality/run.py QUICK --output /tmp/quality-quick
python3 tests/quality/run.py JAVA --output /tmp/quality-java
python3 tests/quality/run.py FRONTEND --output /tmp/quality-frontend
python3 tests/quality/run.py BROWSER --output /tmp/quality-browser
python3 tests/quality/termination_probe.py --output /tmp/quality-termination
python3 tests/quality/termination_probe.py --phase cleanup --output /tmp/quality-cleanup-cancel
python3 tests/quality/run.py INTEGRATION --output /tmp/quality-integration
python3 tests/quality/run.py CROSS-END --output /tmp/quality-cross
python3 tests/quality/run.py MINI-PAGES --output /tmp/quality-mini
python3 tests/quality/run.py PERFORMANCE --output /tmp/quality-performance
python3 tests/quality/run.py MUTATION --output /tmp/quality-mutation
python3 tests/quality/run.py SECURITY --output /tmp/quality-security
```

Maven缓存、浏览器安装目录和工作空间通过`YSHOP_MAVEN_REPOSITORY`、`PLAYWRIGHT_BROWSERS_PATH`、`YSHOP_TEST_WORKSPACE`可选设置，不硬编码用户目录。小程序设置`YSHOP_MINI_APP_CONFIG`指向私有文本（唯一必需字段`WECHAT_APP_ID`）；官方CLI可由`YSHOP_HBUILDER_CLI`、`YSHOP_WECHAT_CLI`指定。缺失工具/AppID/锁定依赖为BLOCKED，编译成功不能认证页面成功。CROSS-END/MINI-PAGES/PERFORMANCE需要BUILD生成的当前源码后端凭证；修改源码或提交改变SHA后必须重新BUILD。

Java覆盖率只使用对应JAVA运行目录中的新鲜JaCoCo数据，并校验当前源码/字节码。下载锁定CLI后执行：

```sh
mvn dependency:get -Dartifact=org.jacoco:org.jacoco.cli:0.8.12:jar:nodeps -Dtransitive=false
python3 tests/quality/coverage.py --run /tmp/quality-java/<runId> --cli "$HOME/.m2/repository/org/jacoco/org.jacoco.cli/0.8.12/org.jacoco.cli-0.8.12-nodeps.jar" --output /tmp/quality-coverage
# 只聚合明确提供的当前源码、非空且已通过的报告；重复范围不计为新场景
python3 tests/quality/run.py REPORT --run-report /tmp/quality-browser/<runId>/report.json --run-report /tmp/quality-cross/<runId>/report.json
```

当前注册计划为77个普通Java套件/1721次调用、3个条件套件；QUICK183次（原147+新增36）。页面5项合成API Smoke与2项实际后端，官方Mini10项检查。次数不是独特场景数量，MySQL重新运行同套件也不累加为独特测试。

开发阶段验证（保留原SHA、内容摘要与失败历史，不作为最终HEAD证明）：

| 范围 | 实际结果 | 证据runId/说明 |
|---|---|---|
| Vue页面 | PASSED，5项 | `19eabc040782414b82bcff1d20929378` |
| PaymentEffects | PASSED，7次 | `f8a7b6eb4ace4a08ac82989a56cf8ccb` |
| 完整普通Java | PASSED，77套件1721次 | `a7c0fdd9af8d4144b6cf316a735d769b` |
| Vue→Spring→MySQL | PASSED，2页面+5独立SQL断言，45既有HTTP检查，cleanup PASS | `151363b8900646f18ef94bf50fd3d098` |
| 双JVM读基准 | PASSED，240请求，错误率0 | 上述run；商品P95/P99 95.143/231.134ms，鉴权64.224/65.817ms，锁等待119次/3624ms；不是生产SLO或因果缺陷结论 |
| 官方Mini→Spring/MySQL→Vue | PASSED，10页面检查+2商家页面+5SQL断言；cleanup PASS；金融计数0 | `0294dc52167745aa8eeae1d76606390e` |
| 全构建/类型 | PASSED，55模块、Vue构建/类型 | `86f1359a418a403f8c0494668562d49e` |
| 新门禁负例 | PASSED，36项 | 实际先复现旧构建/外来run/零浏览器误接收，再修复；缺环境完整入口BLOCKED与超时等待清理均已测试 |

QUICK清单为183次：52 Node+95原Python+36新增页面/来源/状态检查。上表以清单和报告为准，不从文档估计数量。

已复现并修复的测试基础设施问题：初始缺PyYAML；连续后端端口被Docker随机映射抢占；登录缓存封装与Vue历史路由错误；过宽金融路径匹配误阻断通知源码模块；自定义Dialog缺少预期无障碍名称；开关的隐藏input与订单单元格文字导致定位错误；Mini跨执行上下文读取wx属性失败（改为私有Storage计数）；审查发现旧JAR来源、测试结果归属、环境状态传播与SIGTERM清理缺口。失败报告/截图/Trace保留在私有证据目录，未改写成PASS。

遗留范围：GUI正常验证码登录、真实微信身份/物理设备、多浏览器兼容、大规模负载与崩溃恢复、页面级Redis/事务故障、全部优惠券页面领取→预留→合成支付→核销生命周期、完整商品/SKU经营页面。现有Java/HTTP合成支付和并发测试继续保留，不能替代这些跨端范围。关键类仍有85%/75%未达项，Vue全源码和组件覆盖率保持单独指标。

因此本文不宣布原Phase6Q质量准入通过：QUALITY_GATE_READY=NO，PHASE_6D_ALLOWED=NO。TEST_FRAMEWORK_UPGRADE_COMPLETE必须在冻结源码的执行与最新CI完成后独立判断。未进行真实支付、退款、充值、生产部署或PR合并。

执行中报告使用 `complete=false` 与 `NOT_READY/INCONCLUSIVE`；仅整轮结束写入 `complete=true`。汇总与覆盖率拒绝缺失完成标志或未完成的报告，写入采用同目录原子替换。新增负例先复现已完成前缀被误接收，再验证修复。
