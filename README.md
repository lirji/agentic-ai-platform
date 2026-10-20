# Agentic AI Platform

参考 `langchain4j-platform` 与 `agentscope-platform` 建设的新一代 AI 平台项目。用户已确定以 **LangGraph、Deep Agents、LlamaIndex** 为主；当前已形成架构与组件选型方案，并完成候选依赖的元数据解析。

**当前产物为项目文档，尚无应用代码或可运行服务。**

## 阅读入口

- [目标架构](docs/design/platform-selection-20261020/BACKEND_ARCHITECTURE.md)：三框架职责、模块与数据所有权、任务恢复、安全边界及 P0–P4 演进建议。
- [技术与组件选型](docs/design/platform-selection-20261020/TECH_SELECTION.md)：31 项决策、候选版本、基础组件、取舍与验证条件；附可复现的依赖解析证据。
- [AI 平台升级可行性分析](docs/feasibility/AI平台升级可行性分析.md)：前期讨论记录；第 4 节说明框架优势、选择理由与业务用途，第 8 节说明收益及衡量方法。
- [文档索引](docs/README.md)：文档职责与状态。
- [文档来源映射](docs/doc-map.md)：参考仓库基线、源码领域与后续同步规则。
- [任务进度与恢复入口](CODEX_PROGRESS.md)：本次文档任务状态及后续恢复边界。

## 当前范围

用户确认在独立新项目中开展升级，并将架构与选型文档迁入 `agentic-ai-platform`。建议用 LangGraph 统一执行和恢复，Deep Agents 处理复杂任务，LlamaIndex 负责知识处理与 RAG；首期通过契约复用 Java 领域服务。

三框架的主线地位已确认；Java 复用范围、完整独立运行范围、正式契约与容量预算仍待确定。候选版本仅通过联合依赖解析，尚未安装依赖、实现服务、迁移数据或执行部署。下一阶段从 P0 兼容与契约验证开始。
