# CI 实际证据与边界

PR14 基线 HEAD `c4a180833acbfa943dc99348d304dff8bd4fed83` 的 run `38039991551` 中，quality、controlled-dependencies、browser-smoke、high-risk-mutations 四项实际 SUCCESS。

新增测试已在本地最终快照 `b3646bff8e3fad01db36abdf9d549a1db46b0292` 执行，结果见 `delivery-evidence.json`。已知失败集合为独立执行入口，未混入现有稳定 CI，也未标成 PASS。

本文件提交时新草稿 PR 尚未发布，远程当前 HEAD 的 CI 状态为 NOT_RUN（等待推送触发）。创建 PR 后以该 PR 的实际检查状态为准，最终回复记录当时状态。基线 SUCCESS 与本地 PASSED 均不能代替新 HEAD 的远程 CI。无需为文档更新时间重复触发同一组测试。
