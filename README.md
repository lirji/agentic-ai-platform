# Agentic AI Platform

独立建设的知识与 Agent 平台，优先实现**知识入库、知识问答、研究分析和业务助手**。架构与组件已确认：**全新 React 前端 + LangGraph、Deep Agents、LlamaIndex AI 核心 + 按需迁入的 Java 领域能力**。

**已进入 Coding：首个 AI 运行时切片已实现，包含本地存活服务、配置检查和真实 Embedding 客户端。** 启动方式见 [AI 运行时](ai-runtime/README.md)；知识业务与 React 页面尚未接通。

## 阅读入口

- [首期业务与工程约定](docs/design/knowledge-first-20261020/BRIEF.md)：独立运行、业务优先级、React 与技能代码规范，以及模型、数据和代码参考资产。
- [目标架构](docs/design/platform-selection-20261020/BACKEND_ARCHITECTURE.md)：三框架职责、模块与数据所有权、任务恢复、安全边界及 P0–P4 演进建议。
- [技术与组件选型](docs/design/platform-selection-20261020/TECH_SELECTION.md)：31 项决策、候选版本、基础组件、取舍与验证条件；附可复现的依赖解析证据。
- [React 前端设计](docs/design/knowledge-first-20261020/FRONTEND_ARCHITECTURE.md)、[契约](docs/design/knowledge-first-20261020/CONTRACTS.md)及[实施切片](docs/design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md)：首片范围与后续业务依赖。
- [AI 平台升级可行性分析](docs/feasibility/AI平台升级可行性分析.md)：前期讨论记录；第 4 节说明框架优势、选择理由与业务用途，第 8 节说明收益及衡量方法。
- [文档索引](docs/README.md)：文档职责与状态。
- [文档来源映射](docs/doc-map.md)：参考仓库基线、源码领域与后续同步规则。
- [任务进度与恢复入口](CODEX_PROGRESS.md)：实施、验证与交付状态。

## 当前范围

需要的能力按依赖从 `langchain4j-platform` 迁入本项目；模型接入、知识数据模型和评测资产也参考该项目。新平台按独立构建与运行目标建设，所需业务服务随本项目交付；共享基础设施仍优先接入 `dev_infra`，使用项目级数据与权限隔离。

前端使用独立新建的 React 工程和既定配套；后续业务参考 AgentScope 的用例与规则。默认文本 Embedding 采用参考项目的百炼 `text-embedding-v4`、1024 维和 OpenAI 兼容接口；不采用 HashEmbedding 或自动回退到 hash。

准备基线、React 架构与首片 R01/H01 契约已落盘。Python 3.12 基础运行依赖已锁定安装，客户端协议及本地启动已验证；三框架候选组合仍仅通过元数据解析。后续完成知识业务契约、Java 迁入、真实存储与模型接入，再交付 React 知识路径；模型账号、语义质量和费用尚未验证。
