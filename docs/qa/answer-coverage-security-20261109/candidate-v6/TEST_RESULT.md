# 第六版离线验收（2026-11-10）

实现和离线验证PASS，真实质量UNVERIFIED，业务交付HOLD。新资金尚未登记，新模型调用0次。第五版56/60及四项失败保持，未以新提示或离线测试宣称修复完成。

| 范围 | 结果 |
| --- | --- |
| AC1/AC2 条款表达 | 首句不添加推断前置；状态保持原所属对象，条件/时序优先保留原文关系；禁止操作时单列可行替代及适用场景。一次生成、768上限和安全/引用边界保持。 |
| AC3 有界运行 | v6独立状态/运行键，前版五组必须SUCCEEDED且收集；control-1使用旧v3、attack-1使用旧v5，其余40题使用拟新增v6。 |
| 额度和审计 | 五个CHAT池及Embedding分列，逐池与累计调用同时校验；旧manifest/价格保持；无新基线自动选择。 |
| 定向测试 | 140 passed；包括缺授权、无计划标签、旧版本兼容、状态不可变、失败前版拒绝、固定分池/恢复路由、各池不足及累计调用临界值。 |
| 全量回归 | 939 passed / 102 skipped / 34 warnings；102项需显式外部环境的集成测试跳过，未当作执行通过。 |
| 工程检查 | Ruff175文件、mypy100源、编译、16契约/16共享fixture、Java计量边界PASS。 |
| 历史证据 | 原始、v1、v2、v3、v4中断/恢复后、v5共七份verify PASS；6份固定fixture与原始基线逐字节一致。 |

源码和日志摘要见[implementation-evidence](implementation-evidence.json)。Code Hygiene为IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：扫描器未发现子目录Ruff，实际规范命令通过；调用上限343已是命名常量，仍产生advisory。未发生真实模型试跑，不对语义效果给出PASS。

## 固定60题复测提案（尚未授权）

申请新增60次CHAT调用：整改上限283→343，含基线总上限343→403；独立CHAT资金最多2元，总授权14.5→16.5元。当前占额及内部结算10.984026元，本轮CHAT保守上界2.840520元、Embedding0.60元，累计预计上界14.424546元。各池不可挪用，Embedding余额不能用于CHAT，因此需要新增独立CHAT池。

| 固定资金池 | 组与次数 | CHAT费用上界 | 当前/拟新增可用额 |
| --- | --- | --- | --- |
| 旧v3 | control-1，10次 | 0.473212元 | 0.547092元 |
| 旧v5 | attack-1，10次 | 0.473900元 | 0.592860元 |
| 拟新增v6 | quality/control-2/attack-2，共40次 | 1.893408元 | 2元（未登记） |
| 原Embedding | 全60题 | 0.60元 | 1.978826元 |

最大请求保守上界8434 bytes；提示摘要与路由见[prompt-proposal](prompt-proposal.json)、[funding-proposal](funding-proposal.json)。当前计数仍343、私密v6 state和资金manifest均不存在；v5五组终态及原状态未改已只读核对，旧两份外部账本摘要保持。

获明确授权后先记录授权，再依次运行quality、control-1、attack-1、control-2、attack-2。以下命令目前不可执行：

```sh
ai-runtime/.venv/bin/python deploy/quality_remediation_runtime.py run --revision v6 --approved-additional-calls 343 --approved-extra-chat-cny 2 --label quality
```

正式提示冻结后不修改题目/资料/rubric，不重抽成功答案；中断先核对结果，不自动重发未知操作。真实报告存在后才把v6 verify加入CI，不提前保存空报告占用不可变路径。已有验证和任务分支推送授权延续；业务未通过不合入main、不部署。

代码与离线证据已WIP提交b421b50并推送同名任务分支，四项远程CI全部通过；见[交付回执](DELIVERY_RESULT.json)和[CI结果](CI_RESULT.json)。这不代表真实语义验收通过，费用提案仍待明确授权。
