# 第五版离线验收（2026-11-10）

状态：实现及离线验证PASS，真实质量UNVERIFIED，业务交付HOLD。第五版尚未登记新增资金、未执行模型调用；第四版55/60及全部失败保留。

## 改动与验证

| 范围 | 结果与证据 |
| --- | --- |
| AC1/AC2 条件句覆盖 | 提示把同一业务对象的后续确认与当前阶段范围分开表达；一次生成、768上限、资料隔离和引用重验保持。真实覆盖/支撑效果待验证。 |
| AC3 固定资金路由 | 质量20题绑定旧v4，control-1十题绑定旧v3，其余30题绑定拟新增v5；逐池预算与累计343次上限共同检查。 |
| 账户身份 | 原账户按固定ID查询，同类型账户不能覆盖/替代；不同返回顺序与原账户缺失均有回归。实际SQL核对历史金额一致。 |
| 恢复与兼容 | 新候选前核对v4原任务成功恢复及前12候选未变；旧恢复测试绑定历史提示，实际入口继续拒绝提示漂移；旧CLI及6份报告验证通过。 |
| 定向测试 | 122 passed；覆盖次数/资金拒绝、全部分池不足、既完成组不重复预留、历史状态不改写和QA生成边界。 |
| 全量回归 | 921 passed / 102 skipped / 34 warnings；跳过的是需显式真实环境开关的集成测试，不能视为运行通过。 |
| 工程检查 | Ruff格式175文件、静态、mypy100源、编译、16契约/16共享fixture、Java计量边界均PASS。 |
| 历史证据 | 原始、v1、v2、v3、v4中断快照、v4恢复后六份verify PASS；6份题目/资料/rubric与原基线逐字节一致。 |

源码及日志摘要见[implementation-evidence.json](implementation-evidence.json)。Code Hygiene为IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：扫描器未识别子目录Ruff（实际规范命令PASS）；次数283的提示为advisory，该值已经是命名常量。初次历史verify误用了v1/v2参数，已按原有draft/final参数重验通过；失败日志保留。

## 精确复测提案

仅申请一次冻结60题，新增CHAT调用60次，整改上限223→283，含原基线上限283→343；新增独立CHAT资金最多1.5元，累计授权13→14.5元。尚未获准，不能执行下列命令。

| 资金池 | 固定组 | CHAT费用上界 | 当前/拟新增可用额 |
| --- | --- | --- | --- |
| 旧v4 | quality 20题 | 0.940696元 | 0.990384元 |
| 旧v3 | control-1 10题 | 0.470412元 | 0.846136元 |
| 拟新增v5 | attack-1、control-2、attack-2共30题 | 1.412612元 | 1.5元（未登记） |
| 原Embedding | 全部60题 | 0.60元 | 1.980932元 |

当前占额及内部结算8.965156元，本轮保守总上界3.423720元，累计预计上界12.388876元；各资金池不可相互挪用，因此仍需独立新增CHAT授权。提供方最终账单未知，旧未知占额保持。提示摘要、固定路由、预算及旧manifest兼容核对见[funding-proposal.json](funding-proposal.json)和[prompt-proposal.json](prompt-proposal.json)。

获得明确授权后，先保存授权记录，再依次执行quality、control-1、attack-1、control-2、attack-2：

```sh
ai-runtime/.venv/bin/python deploy/quality_remediation_runtime.py run --revision v5 --approved-additional-calls 283 --approved-extra-chat-cny 1.5 --label quality
```

每组保持同一提示/数据/模型/上限；运行中断先查询状态，禁止自动重发未知操作或抽样成功题。逐题评分、核账与完整报告后再决定业务验收；新增真实报告存在后才把v5 verify加入CI，不保存空结果占用不可变报告路径。无新增基线选择、无部署。
