# 原任务部分恢复工具验证

AC3部分恢复工具已实现并完成离线验证，真实恢复UNVERIFIED；等待额外1次调用授权，无新增资金。本版原有回答覆盖FAIL保持，不以恢复工具通过替代业务通过。

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 定向回归 | PASS，79项 | resume-targeted-final.log；包含14项新增边界检查 |
| 全量Python | PASS，898通过/102跳过/34警告 | resume-python-all.log |
| 格式/静态/类型/编译 | PASS | Ruff172文件、mypy100源、py_compile；CI纳入新工具 |
| 原始及v1–v4报告重建 | PASS | 五份verify，旧结果未改 |
| 未授权入口 | PASS | 错误CLI参数在构造器前拒绝；无新恢复状态、服务或模型调用 |
| 前缀/顺序/总次数 | PASS（离线） | 禁止Q01–12、重复/乱序、额外第9次、累计达到283后的请求 |
| 未知恢复响应 | PASS（离线） | 发送前保存resumeStarted；未知时不自动重发或重新执行worker |
| 原任务与候选保留 | PASS（离线） | 相同runId/taskId；新标签/私密状态；发布后逐项核对原12候选不变 |
| 真实资金/资料只读预检 | PASS | 原60题提示/fixture一致，275次历史计数；当前池余量足以覆盖保守20题上界0.924936元，Embedding0.20元；实际只允许8题 |
| Code Hygiene | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS | 子目录Ruff未被扫描器发现，但规范命令实际通过；无其他finding |
| 原检查点真实恢复 | UNVERIFIED | 尚无额外1次授权；不能宣称运行故障已修复 |

来源及摘要见[resume-implementation-evidence.json](resume-implementation-evidence.json)，明确提案见[resume-proposal.json](resume-proposal.json)。当前仍为275/282累计CHAT、53/60本轮调用；批准1次后可用剩余7＋新增1，从原12题检查点续发8题，总上限283（整改223）。资金上限仍13元，不登记新资金、不清除Q13未知占额，不重复前12题。

实际恢复命令为`ai-runtime/.venv/bin/python deploy/quality_partial_resume.py --approved-extra-calls 1`，**只在明确授权后运行**。原v4 state保留，新私密状态coverage-remediation-v4-quality-resume-state.json；新结果runs/quality-resume*.json与transcripts/quality-resume-*；旧quality失败快照不覆盖。若再次失败或响应未知，先核对服务/任务/计量再决定下一步，不重跑该命令。

成功后逐题评分须保留前12题诊断结论并绑定新正式报告摘要；通过原review API保存20份assessment。审计、合并统计使用新文件，原53次usage-audit及summary保持为恢复前快照。生产提示、模型、输出上限、固定题集与金标均未改。

初始验证中的测试路径笔误、未安装pytest-asyncio标记、只读probe构造遗漏documents均已修正；没有安装新依赖或发真实请求。失败日志保留，最终以79定向与898全量通过为准。底层存储故障根因仍未知，工具仅提供有界恢复和证据保留。
