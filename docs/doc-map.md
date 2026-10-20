# 文档来源映射

同步日期：2026-10-20。

本项目尚无应用代码。以下映射记录可行性讨论引用的原项目证据，不代表相关代码已经复制、迁移或部署到新项目。

## 权威文档

- 本轮分析：[AI 平台升级可行性分析](feasibility/AI平台升级可行性分析.md)。
- 文档入口：[文档索引](README.md)。
- 文档任务状态：[CODEX_PROGRESS.md](../CODEX_PROGRESS.md)。

## 固定取证基线

| 来源 | 取证提交 | 核对范围 |
| --- | --- | --- |
| `lirji/langchain4j-platform` | `468cec425dd160e80cfc3ab378d3440a8767e4d5` | 当前路由、知识检索与入库、记忆治理、业务服务及已有评估 |
| `lirji/platform-agentscope`，本地目录 `agentscope-platform` | `7738cb7bedd1a31fd4260aa66b74572ca48d9ade` | Agent 适配、DAG 调度与恢复、预算、工具治理及测试定义 |
| 三框架官方文档 | 2026-10-20 讨论及优势增补时查询的动态页面 | 框架职责、检查点、流式、子图、子 Agent、上下文、Skills、数据连接器、入库与检索等；链接位于分析正文 |

本次保存前核对两个参考仓库 HEAD 与以上提交一致、工作树干净。正文代码链接固定到上述提交；后续原仓库变化不自动改写本轮历史事实。

## 能力与来源对应

| 能力领域 | 原项目代码或资料区域 | 分析正文位置 |
| --- | --- | --- |
| 默认 Agent 路由 | Java `deploy/docker-compose.yml`；Python `infrastructure/agentscope/runner.py` | 第 3 节 |
| 框架职责、优势与选择理由 | LangGraph、Deep Agents、LlamaIndex 官方文档；结合原项目能力作设计判断 | 第 4 节，含 4.1–4.3 |
| 业务用途与组合价值 | 以原项目资产为基础提出的候选场景，尚非新项目已实现功能 | 第 4.4–4.5 节 |
| 收益与衡量方法 | 框架能力结合项目场景的工程推断；指标为建议口径，无实测收益数值 | 第 8 节，含 8.1–8.2 |
| 知识检索、去重与发布 | Java `knowledge-service` | 第 5 节、第 7 节 R3 |
| 用户记忆治理 | Java `conversation-service/.../memory/profile` | 第 5–6 节、第 7 节 R4 |
| Agent 调度与恢复 | Python `application/dag_scheduler.py`、`application/dag_recovery.py` | 第 5–7 节 |
| 业务动作与工具权限 | Java `workflow-service`、`analytics-service`；Python `domain/tool.py`、`infrastructure/agentscope/governed_tools.py` | 第 5 节、第 7 节 R2/R5 |
| 既有风险与能力缺口 | 两个原项目的 `project-business-assessment` / 项目优势不足评估 | 第 7 节 R5 |
| 回归与故障验收 | Python `tests/test_dag_recovery.py`、`test_dag_budget.py`、`test_tool_confirmation.py` 等 | 第 9 节 |
| 尚未确定的范围与验证状态 | 本次用户要求、可行性讨论与任务检查结果 | 第 1、10、11 节 |

## 后续维护规则

1. 保存讨论不等于接受其中所有建议；后续正式决策需要明确状态与依据。
2. 若引用更新后的源码，先核对实际差异，再更新受影响结论与基线；不只刷新日期。
3. 官方网页会变化，正式引入依赖前复核 API、兼容版本、默认行为及相关产品边界。
4. 新项目实现后，新增实际架构、接口、数据和运行文档，并在本索引登记权威来源；保留本轮可行性记录的时间与状态。
5. 不写入真实密钥、访问令牌或账户密码；尚未建立的环境不补造端口、实例或登录方式。
