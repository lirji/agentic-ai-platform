# 项目文档

当前阶段：资料与独立 React 工作台、中央任务/PG 恢复、原问题和可靠交接已交付。S5D 问答图、累计预算及 worker 已通过受控恢复与真实 deepseek-flash 问答、追问、无资料和引用验证；S5E 问答 UI 通过28项单元/组件和10组真实/受控浏览器验收。S6/S7 研究分析、资料解释、核对清单和草稿建议已接通真实 Deep Agents、任务恢复、界面与引用；本轮结果见 S6/S7 验收。最终 Git 交付以当前进度为准。记录日期：2026-10-21。

当前增量：[R02/A01 契约](design/knowledge-first-20261020/WORK_TASK_CONTRACTS.md)、[S6/S7 验收](qa/research-readonly-20261021/TEST_RESULT.md)、[实现映射](qa/research-readonly-20261021/IMPLEMENTATION_EVIDENCE.md)。

## 文档索引

| 文档 | 职责 | 状态 |
| --- | --- | --- |
| [首期业务与工程约定](design/knowledge-first-20261020/BRIEF.md) | 独立性、业务范围、React、技能结构规范、参考资产与后续准备 | 用户决定已记录；闭环细化和顺序区分工程建议 |
| [目标架构](design/platform-selection-20261020/BACKEND_ARCHITECTURE.md) | 三框架组合、模块与状态边界、任务恢复、安全、契约交接及 P0–P4 阶段 | 已确认基线；S0 开始实施，三框架业务路径未接通 |
| [技术与组件选型](design/platform-selection-20261020/TECH_SELECTION.md) | 31 项决策、候选版本、基础组件、真实 Embedding 默认与引入条件 | 组件方向已确认；联合依赖解析通过，运行兼容与业务效果未验证 |
| [选型证据](design/platform-selection-20261020/evidence/README.md) | 候选依赖输入/输出、包元数据与原项目事实核对 | 随文档迁入；不是应用锁文件 |
| [React 架构](design/knowledge-first-20261020/FRONTEND_ARCHITECTURE.md) | 全新前端的任务、路由、模块、状态与视觉验收方向 | S4 已实现资料/上传/状态/检索/引用，独立前端仓库与验收见进度 |
| [契约基线](design/knowledge-first-20261020/CONTRACTS.md) | 内部 Embedding、存活端点及 K/Q/T 契约入口 | R01/H01、P01/P02、K01–K04、T01 和 PG 恢复已冻结；Q 业务执行待后续 |
| [知识资料契约](design/knowledge-first-20261020/KNOWLEDGE_CONTRACTS.md) | 身份、受理、资料/任务查询与后续发布检索语义 | S1 的权威 API；当前不把 RECEIVED 当作 READY |
| [知识处理契约](design/knowledge-first-20261020/PROCESSING_CONTRACTS.md) | 服务身份、准备结果、查询向量与跨语言摘要 | S2 P01/P02 与 S3 领取/准备/发布/检索契约已冻结；S3 验收通过 |
| [S2 验证结果](qa/knowledge-processing-20261020/TEST_RESULT.md) | 三框架、准备/查询、授权与取消、真实模型及依赖适用性 | 范围与实际证据见报告 |
| [中央任务契约](design/knowledge-first-20261020/TASK_CONTRACTS.md) | T01 身份、持久任务、租约、事件、检查点凭据与终态对账 | S5A 已验证；不等于 PG 联合恢复完成 |
| [S5A 验证结果](qa/durable-ai-tasks-20261020/TEST_RESULT.md) | 29 项 Java、38 项 HTTP、重启与故障验收及证据边界 | 中央任务验证通过，问答执行尚未接通 |
| [PG 检查点契约](design/knowledge-first-20261020/CHECKPOINT_CONTRACTS.md) | 不可变快照、代次隔离、中央凭据和安全解码 | S5B 已冻结并验证 |
| [S5B 验证结果](qa/ai-checkpoint-recovery-20261021/TEST_RESULT.md) | 150 项 Python、真实 PG/MySQL/HTTP 恢复、资源和身份界限 | 验收通过；无常驻图 worker、无聊天调用 |
| [会话与交接契约](design/knowledge-first-20261020/CONVERSATION_CONTRACTS.md) | Q01–Q03 原问题、上下文、交接、查询；T01 owner 委托读取扩展 | S5C 冻结并验证，未宣称模型问答可用 |
| [S5C 验证结果](qa/knowledge-conversations-20261021/TEST_RESULT.md) | 45 项 Java、40 项真实 HTTP、重启/依赖故障和 Python 恢复回归 | 会话与交接验收通过，无付费调用 |
| [问答执行契约](design/knowledge-first-20261020/QA_EXECUTION_CONTRACTS.md) | K05、原生问答图、引用/事件、累计预算与 worker | S5D 冻结；deepseek-flash 已批准并实测 |
| [S5D 验证结果](qa/knowledge-qa-execution-20261021/TEST_RESULT.md) | 263 项 Python、真实知识读取与 PG/Tasks/合成模型恢复 | PASS；真实模型、问答/引用、预算重启已验证 |
| S5E 前端 `docs/qa/s5e/TEST_RESULT.md` | 会话/提问/任务/流式/引用、真实Q/T、无付费浏览器与视觉验收 | 独立前端仓库验收通过；不替代真实模型效果 |
| [研究与只读助手准备](design/knowledge-first-20261020/RESEARCH_READINESS.md) | Deep Agents 默认工具、文本适配与工具循环、根图恢复和研究契约缺口 | 只读核对；P1真实模型闭环已通过，下一步冻结研究切片 |
| [知识迁入清单](design/knowledge-first-20261020/KNOWLEDGE_MIGRATION.md) | 参考规则、依赖闭包及未迁入能力 | 源仓库只读；按新契约实现所需能力 |
| [Java 本地运行](../deploy/README.md) | dev-infra 独立资源、分权账号、启动/停止、测试身份和故障验收 | 本机开发验证入口；无生产部署声明 |
| [实施切片](design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md) | S0 有界验收及后续依赖路线 | 唯一可执行切片清单 |
| [运行时说明](../ai-runtime/README.md) | Python 3.12 安装、配置、运行与验证命令 | 已实现存活服务与模型适配器；无业务就绪声明 |
| [S0 验证结果](qa/runtime-foundation-20261020/TEST_RESULT.md) | 当前源码摘要、68 项测试、构建、质量检查与依赖审查 | S0 必需验收通过；工具发现限制和未验证集成单独列明 |
| [S1 验证结果](qa/knowledge-ingestion-20261020/TEST_RESULT.md) | 26 项 Java、45 项 HTTP、5 项故障验证、源码摘要与依赖适用性 | S1 必需验收通过；生产与工具限制明确保留 |
| [处理与发布准备](design/knowledge-first-20261020/PROCESSING_READINESS.md) | 模型、版本化准备、worker、索引、发布恢复与后续输入 | 账号/地域/1 元预算已确认，S2 已验证；Java 发布继续推进 |
| [独立知识平台准备进度](design/knowledge-first-20261020/PROGRESS_STATE.md) | 当前范围同步、参考核对、文档验证与 Git 交付 | 当前任务唯一状态来源 |
| [选型与迁移进度](design/platform-selection-20261020/PROGRESS_STATE.md) | 上一任务的文档迁移、检查结果与 Git 交付 | 历史记录，已完成；最新范围以首期约定为准 |
| [AI 平台升级可行性分析](feasibility/AI平台升级可行性分析.md) | 框架优势与选择理由、能力及业务用途（第 4 节）、收益与衡量方法（第 8 节），以及现有能力映射、架构、风险与验证路线 | 已补充优势与收益；原型与运行效果未验证 |
| [文档来源映射](doc-map.md) | 固定取证基线，维护源码领域与正文的对应关系 | 已建立 |
| [任务进度](../CODEX_PROGRESS.md) | 当前任务标识与恢复入口 | 指向唯一 PROGRESS_STATE |

## 状态约定

- **已确认要求**：用户明确提出的目标与交付范围。
- **仓库事实**：有固定提交、配置或源码依据的现有实现；不自动代表部署环境实际启用。
- **已确认基线**：用户已确认的架构、组件和项目边界；确认不等于已经实现或运行验收通过。
- **建议方案**：尚未确认的工程细化与次序，或明确保留的历史建议；不能覆盖后续用户决定。
- **验证通过**：必须标明验证对象与范围；文档检查通过不表示业务、性能或安全验收通过。
- **待验证/待明确**：保留未解决的问题，后续按实际证据更新。

当前需求以首期约定为准，技术基线以架构与选型正文为准；可行性分析保留前期背景。已有准备图、Java 知识/任务/会话服务、React 知识与问答工作台、真实检索及 PG 恢复。真实问答闭环已验证，研究和助手继续实施；未执行生产部署。
