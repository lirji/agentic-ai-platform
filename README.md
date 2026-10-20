# Agentic AI Platform

独立建设的知识与 Agent 平台，优先实现**知识入库、知识问答、研究分析和业务助手**。架构与组件已确认：**全新 React 前端 + LangGraph、Deep Agents、LlamaIndex AI 核心 + 按需迁入的 Java 领域能力**。

**已实现独立资料受理、持久处理、双索引发布与可引用检索，以及 Python 的鉴权知识准备接口。** Java 服务与 AI 启动入口见 [本地运行](deploy/README.md)，三框架及真实 Embedding 说明见 [AI 运行时](ai-runtime/README.md)。Python 准备结果已用真实百炼验证，Java 双索引处理与恢复已通过真实组件集成测试，S3 完整真实链路、重启恢复和搜索补丁升级验收通过。[独立 React 工作台](https://github.com/lirji/agentic-ai-platform-web)已接通资料、状态、检索和引用，本机入口 http://127.0.0.1:28086；中央任务与 PG 检查点联合恢复、原问题与可靠交接已通过 S5A–S5C 验收，问答执行、研究和助手尚未接通；验收与交付状态见[当前进度](docs/design/knowledge-first-20261020/PROGRESS_STATE.md)。

## 阅读入口

- [首期业务与工程约定](docs/design/knowledge-first-20261020/BRIEF.md)：独立运行、业务优先级、React 与技能代码规范，以及模型、数据和代码参考资产。
- [目标架构](docs/design/platform-selection-20261020/BACKEND_ARCHITECTURE.md)：三框架职责、模块与数据所有权、任务恢复、安全边界及 P0–P4 演进建议。
- [技术与组件选型](docs/design/platform-selection-20261020/TECH_SELECTION.md)：31 项决策、候选版本、基础组件、取舍与验证条件；附可复现的依赖解析证据。
- [React 前端设计](docs/design/knowledge-first-20261020/FRONTEND_ARCHITECTURE.md)、[契约](docs/design/knowledge-first-20261020/CONTRACTS.md)及[实施切片](docs/design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md)：首片范围与后续业务依赖。
- [中央 AI 任务](docs/design/knowledge-first-20261020/TASK_CONTRACTS.md)：执行租约、持久事件、取消/恢复与检查点提交凭据；[S5A 验收](docs/qa/durable-ai-tasks-20261020/TEST_RESULT.md)。
- [图检查点恢复](docs/design/knowledge-first-20261020/CHECKPOINT_CONTRACTS.md)：不可变快照、执行代次隔离、中央凭据与安全解码；[S5B 验收](docs/qa/ai-checkpoint-recovery-20261021/TEST_RESULT.md)。
- [知识会话与交接](docs/design/knowledge-first-20261020/CONVERSATION_CONTRACTS.md)：原问题、顺序版本、有界上下文、可靠任务交接与查询；[S5C 验收](docs/qa/knowledge-conversations-20261021/TEST_RESULT.md)。
- [知识 API](docs/design/knowledge-first-20261020/KNOWLEDGE_CONTRACTS.md)与[迁入清单](docs/design/knowledge-first-20261020/KNOWLEDGE_MIGRATION.md)：身份、幂等、原文、分页、持久任务及参考规则边界。
- [AI 平台升级可行性分析](docs/feasibility/AI平台升级可行性分析.md)：前期讨论记录；第 4 节说明框架优势、选择理由与业务用途，第 8 节说明收益及衡量方法。
- [文档索引](docs/README.md)：文档职责与状态。
- [文档来源映射](docs/doc-map.md)：参考仓库基线、源码领域与后续同步规则。
- [任务进度与恢复入口](CODEX_PROGRESS.md)：实施、验证与交付状态。

## 当前范围

需要的能力按依赖从 `langchain4j-platform` 迁入本项目；模型接入、知识数据模型和评测资产也参考该项目。新平台按独立构建与运行目标建设，所需业务服务随本项目交付；共享基础设施仍优先接入 `dev_infra`，使用项目级数据与权限隔离。

前端使用独立新建的 React 工程和既定配套；后续业务参考 AgentScope 的用例与规则。默认文本 Embedding 采用参考项目的百炼 `text-embedding-v4`、1024 维和 OpenAI 兼容接口；不采用 HashEmbedding 或自动回退到 hash。

准备基线、React 架构、R01/H01、P01/P02、知识 K、中央 T 与会话 Q 契约已落盘。Java 使用 JDK 21、Boot 3.5、MyBatis-Plus/XML，Gateway 验签后换发内部 JWT；Knowledge、Tasks、Conversations 各自拥有独立数据库。Python 3.12 已锁定三框架：LangGraph/LlamaIndex 用于准备图，Deep Agents 完成隔离工具/流式兼容验证。真实北京百炼账号的 1024 维准备/查询和 PG 检查点联合恢复已验证；完整问答、研究助手、检索质量与生产运行仍待后续验收。
