# 研究分析与只读助手实施前核对

2026-10-21 更新：S6/S7 已按 [正式契约](WORK_TASK_CONTRACTS.md) 实现并验证，当前证据见 [TEST_RESULT](../../qa/research-readonly-20261021/TEST_RESULT.md)。Deep Agents 采用关闭内部 checkpoint 的原子 analyze 阶段，外层根图继续 JSON 恢复；实际工具仅搜索和读证据。两类任务共用原预算，助手模式为解释、清单、草稿。

下文保留 S5D 结束时的实施前核对，描述当时的缺口，不作为当前未实现清单。

## 当前可复用与不能直接复用的部分

| 部分 | 当前事实 | 对后续实施的影响 |
| --- | --- | --- |
| Java 领域与任务 | S5C 会话可靠交接已交付；中央 `TaskProtocol.KIND` 和 Python Task 模型只接收 `KNOWLEDGE_QA` | 研究任务需单独冻结 kind、输入、报告与提交所有权，不能把研究输入伪装成问答 |
| LangGraph 恢复 | S5B `FencedPostgresSaver._config` 仅接受根图空 namespace；所有恢复依据中央凭据 | Deep Agents 子图/子 Agent 不能直接宣称已被当前恢复协议覆盖，先决定并验证对应有界执行形态 |
| LlamaIndex 与 Knowledge | K05 只读身份和有界检索/引用已实现并有真实 HTTP 证据 | 可复用授权读取；每次搜索仍消耗独立 Embedding 预算，不能因工具只读就免计费 |
| 聊天适配器 | S5D `ChatMessage` 仅 system/user，文本流拒绝 tool_calls、只允许正常文本终止 | 不能直接拿该适配器驱动 Deep Agents 工具循环；需新增受治理工具调用协议适配，保留既有问答边界 |
| Deep Agents 0.7.28 | 实际锁定安装；既有兼容测试使用真实 Agent 循环、确定性模型替身和 InMemorySaver | 证明依赖/工具循环可运行，不证明真实模型、业务权限或持久恢复 |
| 默认工具和委派 | 安装包 `graph.py` 默认包含文件工具；即使 `subagents=[]`，仍可能按 profile 补入 general-purpose 子 Agent | 仅传空数组不足以建立工具白名单。必须对最终模型 profile、所有工具和子 Agent 做显式限制及实际枚举验证 |
| 身份与工作文件 | 已批准架构要求线程内 StateBackend、不挂载宿主项目目录，不对外写业务系统 | 后续报告工作文件要有 tenant/user/task 归属、大小/数量和生命周期边界；不启用本地 shell、浏览器、外部写工具 |

源码依据：[中央协议](../../../async-task-service/src/main/java/com/lrj/agentic/task/domain/TaskProtocol.java)、[Python中央模型](../../../ai-runtime/src/agentic_ai/tasks/models.py)、[恢复器](../../../ai-runtime/src/agentic_ai/tasks/checkpointer.py)、[安全序列化](../../../ai-runtime/src/agentic_ai/tasks/serialization.py)、[文本聊天适配](../../../ai-runtime/src/agentic_ai/qa/chat.py)、[既有三框架兼容测试](../../../ai-runtime/tests/test_framework_compatibility.py)。本轮只读核对安装的 `deepagents/graph.py` 的入口参数、默认工具和 general-purpose 组装分支，没有调用新模型或运行研究任务。

官方说明也将规划列为可选能力，并说明虚拟文件、工具和子 Agent 需配置；实际实现仍以已锁定 0.7.28 制品及其验收为准，不能直接照抄滚动更新示例。[Deep Agents 官方说明](https://docs.langchain.com/oss/python/deepagents/overview)

## 下一步的具体顺序

1. 已完成 S5D 实际出口：用户批准 deepseek-flash 及更新凭据，LiteLLM 1.106.0 已固定安装；真实问答/追问/无证据/引用与重启保持累计额度通过。聊天预留 0.092600 / 1 元，Embedding 0.20 / 1 元，后续研究继续共用原预算，不另起账本。详见 [S5D 验收](../../qa/knowledge-qa-execution-20261021/TEST_RESULT.md)。
2. 按原架构 P1→P2 的退出条件，问答真实闭环通过后冻结研究与只读助手的输入/报告/任务/错误契约和独立 S-ID。只读助手沿 BRIEF 的资料解释、清单、草稿辅助；不扩展到执行外部业务写操作。
3. 在新切片中分别验证受控工具调用、调用次数/整体时限、预算累计和未知响应；显式限制默认文件/委派工具，确定消息/工作文件与现有 checkpoint 的兼容范围。未通过前不放宽根图/序列化限制，也不通过删除恢复记录重跑。
4. 实现后再接研究/助手路由，真实报告需要可追溯证据、取消/接管/完成重放与权限变化验证；本轮 S5E 的问答 UI 通过不能代替这些验收。

阶段依赖来自原已批准的[阶段顺序](../platform-selection-20261020/BACKEND_ARCHITECTURE.md#9-独立知识平台的阶段顺序与退出条件)和实际模型授权边界。没有重开已完成设计、换语言/主框架、增加中间件、启动多 Agent 或扩展外部写权限。
