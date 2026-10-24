# 项目文档

当前任务：在[20 项差距](../.engineering/exploration/CAPABILITY_GAPS.md)基础上交付[完整任务拆分和实施方案](design/knowledge-first-20261020/CAPABILITY_IMPLEMENTATION_PLAN.md)，共 11 批、51 项任务。方案已交付，用户随后要求开始实施，目前从 S8 质量闭环推进；当前状态见唯一 [PROGRESS_STATE](design/knowledge-first-20261020/PROGRESS_STATE.md)。

当前阶段：资料与独立 React 工作台、中央任务/PG 恢复、原问题和可靠交接已交付。S5D 问答图、累计预算及 worker 已通过受控恢复与真实 deepseek-flash 问答、追问、无资料和引用验证；S5E 问答 UI 通过28项单元/组件和10组真实/受控浏览器验收。S6/S7 研究分析、资料解释、核对清单和草稿建议已接通真实 Deep Agents、任务恢复、界面与引用；本轮结果见 S6/S7 验收。最终 Git 交付以当前进度为准。记录日期：2026-10-21。

上一实施批次：[R02/A01 契约](design/knowledge-first-20261020/WORK_TASK_CONTRACTS.md)、[S6/S7 验收](qa/research-readonly-20261021/TEST_RESULT.md)、[实现映射](qa/research-readonly-20261021/IMPLEMENTATION_EVIDENCE.md)。

## 文档索引

| 文档 | 职责 | 状态 |
| --- | --- | --- |
| [能力实施方案](design/knowledge-first-20261020/CAPABILITY_IMPLEMENTATION_PLAN.md) | 框架分工、批次与 G01–G20 映射、三条关键路径、兼容及验收 | 完整计划；43 项主线、8 项条件任务，S8 开始实施 |
| [实施切片 S8–S18](design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md#capability-depth-slices) | 51 项稳定任务、逐项依赖、Owner/路径、契约、Runtime 与可观察验收 | 新任务唯一清单；保留 S0–S7 |
| [契约工作包](design/knowledge-first-20261020/CAPABILITY_CONTRACT_BACKLOG.md)、[框架证据](design/knowledge-first-20261020/FRAMEWORK_EVIDENCE.json) | 新协议待冻结范围、锁定接口及适配风险 | 不是新线上 schema；未改依赖或运行配置 |
| [质量契约](design/knowledge-first-20261020/QUALITY_CONTRACTS.md)、[样本验收](design/knowledge-first-20261020/QUALITY_ACCEPTANCE.md) | E01 评测集/修订/标注、质量指标、运行归属与首批机制资料 | S8A 冻结，S8B 实施依据 |
| [评测定义验收](qa/quality-datasets-20261021/TEST_RESULT.md)、[实现映射](qa/quality-datasets-20261021/IMPLEMENTATION_EVIDENCE.md) | S8B 真实保存/修订、权限/发布绑定、原文标注与失败恢复 | 结果与限制见验收；执行/基线由 S8C/D 接续 |
| [评测执行契约](design/knowledge-first-20261020/EVALUATION_RUN_CONTRACTS.md)、[S8C验收](qa/quality-runs-20261021/TEST_RESULT.md) | 原任务恢复、中央凭据确认、逐题指标与运行界面 | 当前执行与恢复能力 |
| [评审与比较契约](design/knowledge-first-20261020/EVALUATION_COMPARISON_CONTRACTS.md)、[S8D验收](qa/quality-baselines-20261021/TEST_RESULT.md) | 人工评分、冻结基线、逐题退化与未知结果恢复 | 必需验收通过；真实机制质量见S8E |
| [真实机制基线](qa/quality-mechanism-20261024/TEST_RESULT.md) | S8E 固定来源、真实RRF/问答、逐题评分与核账 | 8题执行成功；保留M04支撑失败，仅机制集 |
| [计划文档验证](qa/capability-plan-20261021/TEST_RESULT.md) | 任务覆盖/依赖、文档结构/引用、接口摘要及改动边界 | 仅文档 PASS，不代表新能力实现或运行通过 |
| [能力欠缺主报告](../.engineering/exploration/CAPABILITY_GAPS.md) | 对照业务、实现、UI、验证和运行能力；逐项影响与验收方向 | 历史差距基线；当前沿新计划实施 |
| [能力地图](../.engineering/exploration/CAPABILITY_MAP.md)、[候选](../.engineering/exploration/OPPORTUNITIES.md)、[路线](../.engineering/exploration/EVOLUTION_ROADMAP.md) | 九维成熟度、触发条件、依赖及暂不建设项 | 建议，未冻结新契约或新技术选型 |
| [比较证据](../.engineering/exploration/EVIDENCE_INDEX.md) | 26 组证据、147 处固定提交文件引用、配置与验证边界 | 本轮只读取证，未重跑模型或应用测试 |
| [首期业务与工程约定](design/knowledge-first-20261020/BRIEF.md) | 独立性、业务范围、React、技能结构规范、参考资产与后续准备 | 用户决定已记录；闭环细化和顺序区分工程建议 |
| [目标架构](design/platform-selection-20261020/BACKEND_ARCHITECTURE.md) | 三框架组合、模块与状态边界、任务恢复、安全、契约交接及 P0–P4 阶段 | 已确认架构基线；S0–S7 当前实现及剩余边界见进度/能力报告 |
| [技术与组件选型](design/platform-selection-20261020/TECH_SELECTION.md) | 31 项决策、候选版本、基础组件、真实 Embedding 默认与引入条件 | 组件方向已确认；锁定组合及限定业务验收见 S0–S7，系统质量/容量仍待验证 |
| [选型证据](design/platform-selection-20261020/evidence/README.md) | 候选依赖输入/输出、包元数据与原项目事实核对 | 随文档迁入；不是应用锁文件 |
| [React 架构](design/knowledge-first-20261020/FRONTEND_ARCHITECTURE.md) | 全新前端的任务、路由、模块、状态与视觉验收方向 | S4–S7 已实现资料、问答、研究和助手；独立前端验收见进度 |
| [契约基线](design/knowledge-first-20261020/CONTRACTS.md) | 内部 Embedding、存活端点及 K/Q/T 契约入口 | R/P/K/T/Q 及 work 契约分阶段冻结；当前业务执行见 S5D/S6 契约与验收 |
| [知识资料契约](design/knowledge-first-20261020/KNOWLEDGE_CONTRACTS.md) | 身份、受理、资料/任务查询与后续发布检索语义 | S1 的权威 API；当前不把 RECEIVED 当作 READY |
| [知识处理契约](design/knowledge-first-20261020/PROCESSING_CONTRACTS.md) | 服务身份、准备结果、查询向量与跨语言摘要 | S2 P01/P02 与 S3 领取/准备/发布/检索契约已冻结；S3 验收通过 |
| [S2 验证结果](qa/knowledge-processing-20261020/TEST_RESULT.md) | 三框架、准备/查询、授权与取消、真实模型及依赖适用性 | 范围与实际证据见报告 |
| [中央任务契约](design/knowledge-first-20261020/TASK_CONTRACTS.md) | T01 身份、持久任务、租约、事件、检查点凭据与终态对账 | T01 已验证；后续 PG 联合恢复另见 S5B/S6 证据 |
| [S5A 验证结果](qa/durable-ai-tasks-20261020/TEST_RESULT.md) | 29 项 Java、38 项 HTTP、重启与故障验收及证据边界 | 历史 S5A 验证范围；当时未接问答，后续实现见 S5D |
| [PG 检查点契约](design/knowledge-first-20261020/CHECKPOINT_CONTRACTS.md) | 不可变快照、代次隔离、中央凭据和安全解码 | S5B 已冻结并验证 |
| [S5B 验证结果](qa/ai-checkpoint-recovery-20261021/TEST_RESULT.md) | 150 项 Python、真实 PG/MySQL/HTTP 恢复、资源和身份界限 | 历史 S5B 验收通过；该批未启动常驻 worker/聊天，后续见 S5D |
| [会话与交接契约](design/knowledge-first-20261020/CONVERSATION_CONTRACTS.md) | Q01–Q03 原问题、上下文、交接、查询；T01 owner 委托读取扩展 | S5C 冻结并验证；模型问答由后续 S5D 验收 |
| [S5C 验证结果](qa/knowledge-conversations-20261021/TEST_RESULT.md) | 45 项 Java、40 项真实 HTTP、重启/依赖故障和 Python 恢复回归 | 历史 S5C 会话/交接验收通过，该批无付费调用 |
| [问答执行契约](design/knowledge-first-20261020/QA_EXECUTION_CONTRACTS.md) | K05、原生问答图、引用/事件、累计预算与 worker | S5D 冻结；deepseek-flash 已批准并实测 |
| [S5D 验证结果](qa/knowledge-qa-execution-20261021/TEST_RESULT.md) | 263 项 Python、真实知识读取与 PG/Tasks/合成模型恢复 | PASS；真实模型、问答/引用、预算重启已验证 |
| S5E 前端 `docs/qa/s5e/TEST_RESULT.md` | 会话/提问/任务/流式/引用、真实Q/T、无付费浏览器与视觉验收 | 独立前端仓库验收通过；不替代真实模型效果 |
| [研究与只读助手准备](design/knowledge-first-20261020/RESEARCH_READINESS.md) | Deep Agents 默认工具、文本适配与工具循环、根图恢复和研究契约缺口 | 历史准备分析；R02/A01 与 S6/S7 已另行冻结并验收 |
| [知识迁入清单](design/knowledge-first-20261020/KNOWLEDGE_MIGRATION.md) | 参考规则、依赖闭包及未迁入能力 | 源仓库只读；按新契约实现所需能力 |
| [Java 本地运行](../deploy/README.md) | dev-infra 独立资源、分权账号、启动/停止、测试身份和故障验收 | 本机开发验证入口；无生产部署声明 |
| [实施切片](design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md) | S0–S7 分阶段范围与依赖路线 | 已有切片记录；后续实施已暂停 |
| [运行时说明](../ai-runtime/README.md) | Python 3.12 安装、配置、运行与验证命令 | 已含准备、问答、研究/助手与恢复；范围以契约和 QA 为准 |
| [S0 验证结果](qa/runtime-foundation-20261020/TEST_RESULT.md) | 当前源码摘要、68 项测试、构建、质量检查与依赖审查 | S0 必需验收通过；工具发现限制和未验证集成单独列明 |
| [S1 验证结果](qa/knowledge-ingestion-20261020/TEST_RESULT.md) | 26 项 Java、45 项 HTTP、5 项故障验证、源码摘要与依赖适用性 | S1 必需验收通过；生产与工具限制明确保留 |
| [处理与发布准备](design/knowledge-first-20261020/PROCESSING_READINESS.md) | 模型、版本化准备、worker、索引、发布恢复与后续输入 | 历史准备文档；S2/S3 已验证，预算连续保留 |
| [独立知识平台准备进度](design/knowledge-first-20261020/PROGRESS_STATE.md) | 当前范围同步、参考核对、文档验证与 Git 交付 | 当前任务唯一状态来源 |
| [选型与迁移进度](design/platform-selection-20261020/PROGRESS_STATE.md) | 上一任务的文档迁移、检查结果与 Git 交付 | 历史记录，已完成；最新范围以首期约定为准 |
| [AI 平台升级可行性分析](feasibility/AI平台升级可行性分析.md) | 框架优势与选择理由、能力及业务用途（第 4 节）、收益与衡量方法（第 8 节），以及现有能力映射、架构、风险与验证路线 | 前期可行性建议；当前实现/验证以 S0–S7 和本轮能力对比为准 |
| [文档来源映射](doc-map.md) | 固定取证基线，维护源码领域与正文的对应关系 | 已建立 |
| [任务进度](../CODEX_PROGRESS.md) | 当前任务标识与恢复入口 | 指向唯一 PROGRESS_STATE |

## 状态约定

- **已确认要求**：用户明确提出的目标与交付范围。
- **仓库事实**：有固定提交、配置或源码依据的现有实现；不自动代表部署环境实际启用。
- **已确认基线**：用户已确认的架构、组件和项目边界；确认不等于已经实现或运行验收通过。
- **建议方案**：尚未确认的工程细化与次序，或明确保留的历史建议；不能覆盖后续用户决定。
- **验证通过**：必须标明验证对象与范围；文档检查通过不表示业务、性能或安全验收通过。
- **待验证/待明确**：保留未解决的问题，后续按实际证据更新。

当前需求以用户最新范围、首期约定及能力实施方案为准，技术基线以架构与选型正文为准；可行性分析保留前期背景。已有准备图、Java 知识/任务/会话服务、React 工作台、真实检索及 PG 恢复；问答、研究和只读助手均有有限业务验收。新增任务按切片逐项实施，未执行生产部署。
