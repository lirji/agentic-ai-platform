# 文档来源映射

同步日期：2026-10-21。

本项目已有 Python AI 运行时与独立 Java Gateway/Knowledge/Tasks/Conversations 服务。知识资料和中央任务按参考规则独立实现，旧数据和旧部署未迁入；以下原项目映射仍是固定取证证据。

本轮同步基线 `865aaa4ba1e0f50c91e2b206385fd78fdc027ee1` → S5D 工作树：Knowledge 独立只读身份、问答图、引用重验、流式预算、中央事件/失败、完成前回调及 worker 同步至契约/运行说明。[S5D 证据](qa/knowledge-qa-execution-20261021/TEST_RESULT.md)绑定当前源码，新增 LiteLLM 锁定制品、实际 deepseek-flash、三条业务路径、浏览器引用与无新增费用重启证据；S2–S5C 历史证据保留，当前状态见唯一进度。

本轮 S6/S7 同步基线：后端 `cb2f688`、前端 `f68e660`。Tasks 公共 work 请求/分页、V2 索引、`agents/` 受控工具与根图、共享 worker 和网关 SSE 映射到 [R02/A01](design/knowledge-first-20261020/WORK_TASK_CONTRACTS.md)；两路页面与共享意图/事件消费映射到独立前端 S7。证据与实际源码摘要见 [S6/S7 验收](qa/research-readonly-20261021/TEST_RESULT.md)，旧 QA 记录为历史验证，不替代当前回归。

## 当前能力实施（2026-10-21）

用户已要求“开始实施”，并选择以项目架构/操作文档建立机制评测集。[主方案](design/knowledge-first-20261020/CAPABILITY_IMPLEMENTATION_PLAN.md)与 [51 项稳定任务](design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md#capability-depth-slices)继续有效；不能把规划能力当已实现。

S8A 已冻结 E01 与指标，S8B 基于后端 `39af9f6`、前端 `d2b4dc6` 实现评测定义、不可变修订、原提交恢复、授权原文标注和 React 界面。涉及 Knowledge evaluation 包、V4、共享发布 SQL、精确本地代理路由及质量页面；来源和验证见 [实现映射](qa/quality-datasets-20261021/IMPLEMENTATION_EVIDENCE.md)及 [TEST_RESULT](qa/quality-datasets-20261021/TEST_RESULT.md)。E01 用途筛选补充为数据库过滤并绑定游标。当前状态与 Git 交付仍由唯一 PROGRESS_STATE 维护。

S8C 基于后端 `c810e959`、前端 `427bcb9` 实现持久运行、Tasks失败凭据、独立角色、LangGraph逐题恢复及React运行详情。Knowledge V5 / Tasks V3仅追加，既有QA路径和预算复用；[E02](design/knowledge-first-20261020/EVALUATION_RUN_CONTRACTS.md)、[实现映射](qa/quality-runs-20261021/IMPLEMENTATION_EVIDENCE.md)、[验收](qa/quality-runs-20261021/TEST_RESULT.md)记录边界。S8D在后端44409bf/前端717e8c2上补齐不可变评分/基线、V6和React对照；[E03](design/knowledge-first-20261020/EVALUATION_COMPARISON_CONTRACTS.md)、[实现映射](qa/quality-baselines-20261021/IMPLEMENTATION_EVIDENCE.md)及[验收](qa/quality-baselines-20261021/TEST_RESULT.md)记录范围。S8E在后端aa355c5上完成6份固定来源真实入库、8题运行和逐题评分，[验收](qa/quality-mechanism-20261024/TEST_RESULT.md)保留1项非关键支撑失败；源码未改。

[框架证据](design/knowledge-first-20261020/FRAMEWORK_EVIDENCE.json)与原比较基线保持历史身份。S8F基于后端9a0e0b4/前端5960531新增两仓Actions、E02共享报文金标和真实DTO测试，P01沿用原共享金标；[CI契约](design/knowledge-first-20261020/CI_CONTRACTS.md)与[验收](qa/ci-contracts-20261024/TEST_RESULT.md)记录实际检查及限制。S8F无模型调用和依赖升级。

S9A在后端82eb31c上核对UploadRules、PreparedDocument、IngestionRunner、DualSearchIndex、KnowledgeSearch/EvaluationRow及Python CitationSource/处理DTO；[解析设计](design/knowledge-first-20261020/PARSING_DESIGN.md)和[P-v2](design/knowledge-first-20261020/STRUCTURED_PROCESSING_CONTRACTS.md)对应这些真实变化点。候选制品与自建格式样本见[S9A验收](qa/structured-parsing-20261024/TEST_RESULT.md)，不从探针推导已实现在线PDF/DOCX或生产隔离。 S9B的[实现证据](qa/pdf-ingestion-20261024/IMPLEMENTATION_EVIDENCE.md)覆盖实际解析模块、P04、V7兼容存储、双索引、K06和独立前端；[验收](qa/pdf-ingestion-20261024/TEST_RESULT.md)区分真实模型、隔离故障和浏览器重放，不扩展到DOCX/OCR。

## 已完成：能力比较（2026-10-21）

用户要求暂停知识深化实施，先比较业务与实现细度。本轮权威入口为[能力缺口](../.engineering/exploration/CAPABILITY_GAPS.md)，配套[地图](../.engineering/exploration/CAPABILITY_MAP.md)、[候选](../.engineering/exploration/OPPORTUNITIES.md)、[路线](../.engineering/exploration/EVOLUTION_ROADMAP.md)及[证据](../.engineering/exploration/EVIDENCE_INDEX.md)。当前后端基线 `d61536cbb9179db010f33d7b1cdbda737e22fc92`，React 基线 `d2b4dc6957cfbc81d29a8493dc9aa284aab8e584`，参考 Java 基线保持 `468cec425dd160e80cfc3ab378d3440a8767e4d5`。本轮未重新审计独立 AgentScope，也未执行模型/运行验收。

新增事实由固定提交、源码/配置/SQL、前端 API 调用和近期 QA 交叉支持，文件摘要见报告目录的 `evidence-manifest.json`；历史报告仅用作线索。新报告不替换现有契约，不自动恢复五步实施；当前任务状态仍在原 PROGRESS_STATE。

## 权威文档

- 当前需求：[首期业务与工程约定](design/knowledge-first-20261020/BRIEF.md)；当前进度：[准备与实施进度](design/knowledge-first-20261020/PROGRESS_STATE.md)。
- 当前设计：[目标架构](design/platform-selection-20261020/BACKEND_ARCHITECTURE.md)、[技术与组件选型](design/platform-selection-20261020/TECH_SELECTION.md)。
- 实施依据：[React 架构](design/knowledge-first-20261020/FRONTEND_ARCHITECTURE.md)、[契约](design/knowledge-first-20261020/CONTRACTS.md)、[知识契约](design/knowledge-first-20261020/KNOWLEDGE_CONTRACTS.md)、[切片](design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md)；已实现入口：[AI 运行时](../ai-runtime/README.md)、[Java 本地运行](../deploy/README.md)。
- 前期分析：[AI 平台升级可行性分析](feasibility/AI平台升级可行性分析.md)。
- 历史选型证据与状态：[证据目录](design/platform-selection-20261020/evidence/README.md)、[选型迁移进度](design/platform-selection-20261020/PROGRESS_STATE.md)。
- 文档入口：[文档索引](README.md)。
- 文档任务状态：[CODEX_PROGRESS.md](../CODEX_PROGRESS.md)。

## 固定取证基线

| 来源 | 取证提交 | 核对范围 |
| --- | --- | --- |
| `lirji/langchain4j-platform` | `468cec425dd160e80cfc3ab378d3440a8767e4d5` | 当前路由、知识检索与入库、记忆治理、业务服务及已有评估 |
| `lirji/platform-agentscope`，本地目录 `agentscope-platform` | `7738cb7bedd1a31fd4260aa66b74572ca48d9ade` | Agent 适配、DAG 调度与恢复、预算、工具治理及测试定义 |
| 三框架官方文档 | 2026-10-20 讨论及优势增补时查询的动态页面 | 框架职责、检查点、流式、子图、子 Agent、上下文、Skills、数据连接器、入库与检索等；链接位于分析正文 |
| PyPI 与基础组件官方资料 | 2026-10-20 选型时核对 | 8 个包元数据、7 个直接 AI 候选依赖与服务基座共 113 包解析；运行行为未验证 |
| 百炼官方模型规格及同步接口 | 2026-10-20 真实 Embedding 要求补充时核对 | text-embedding-v4 支持 1024 维和 OpenAI 兼容协议；没有调用实际账号或修改参考项目 |

可行性分析保存时，两个参考仓库 HEAD 与以上提交一致、工作树干净。后续选型使用相同取证提交，迁入时将原稿中的 Java 仓库相对链接改为固定提交链接；后续原仓库变化不自动改写本轮历史事实。

## 历史选型与文档迁移

任务 `platform-selection-20261020`：按用户要求，将三份设计/进度文档从 `langchain4j-platform/docs/design/platform-selection-20261020/` 迁入本项目同路径，并携带依赖解析与源码核对证据。原项目中本次新增的文档入口同步撤回，原业务文档和历史进度保留。

当时曾建议复用 Java 服务与现有门户；该部署/前端范围已由下述用户新决定替代。可行性分析的历史正文保留，顶部链接指向最新设计。

## 当前独立知识平台约定

任务 `knowledge-first-baseline-20261020`：用户确认独立项目，必要能力按需从 langchain4j-platform 迁入；全新 React 前端、前后端分仓，知识入库/问答/研究分析/业务助手优先，后期参考 AgentScope 业务。

用户进一步要求不使用默认 HashEmbedding。重新核对同一固定提交发现：应用配置和部分 split 配置默认 hash，而标准 Compose 覆盖为百炼 text-embedding-v4 / 1024 维。新项目选择后者作为真实语义默认，并要求所有启动配置一致、失败不回退 hash。具体配置和模型/样本引用集中在首期约定第 5 节；只核对声明与结构，未执行种子、模型或数据库操作。

以下表格是首期设计及 S0/S1 当时的记录，保留当时验证边界；其中“未实现/未创建/待落实”不代表当前状态，当前实现以 S6/S7 QA 和上方能力比较为准。

| 首期历史记录 | 权威位置 | 当时证据与限制 |
| --- | --- | --- |
| 三框架职责、状态权威、恢复与安全 | 目标架构第 3–8 节 | 原项目固定提交 + 框架官方资料；新项目尚未实现 |
| 框架与基础组件选型 | 技术选型 T01–T31 | 对照复用、替换与暂缓条件；不是新增全部组件的授权 |
| 候选版本与解析 | 技术选型第 2 节、evidence | Python 3.12 / Linux x86_64 元数据解析；未安装、未启动、未调用模型 |
| 独立性、React、知识优先与技能代码组织 | 首期约定第 1–4 节、React 架构 | 用户已确认；Python 基础运行时已创建，前端工程尚未创建 |
| S0 真实 Embedding 与本地存活服务 | CONTRACTS R01/H01、ai-runtime 源码与测试、S0 验证结果 | 68 项本地测试通过；HTTPX 模型协议替身不能证明真实语义效果 |
| S1 知识资料受理与查询 | KNOWLEDGE_CONTRACTS、KNOWLEDGE_MIGRATION、Java 四模块、deploy | 独立网关 JWT、MyBatis XML/Flyway、项目 MySQL/S3；当前状态和真实测试证据见进度 |
| 模型、数据模型和评测资产 | 首期约定第 5 节、选型第 5 节 | 固定提交只读核对：模型路由、知识迁移、200 条不同合成问题、20 条语料记录、20 条 Agent 目标及 1 条冒烟 case |
| 阶段顺序与剩余风险 | 目标架构第 9–10 节、实施切片与当前进度 | P0–P4 按知识优先推进；处理/发布已验证；Q/T schema、SLA 待落实，Embedding/聊天各 1 元已授权 |

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

2026-10-21 当前实现补充：S5E 前端证据在独立 `agentic-ai-platform-web/docs/qa/s5e/`，覆盖真实 Q/T、受控 Q04/SSE、知识引用共享消费者与视觉交互；实际 Git 交付仍以当前进度为准。[研究准备](design/knowledge-first-20261020/RESEARCH_READINESS.md)映射已锁定 Deep Agents 0.7.28 的入口/默认工具与本项目文本聊天、根图恢复边界，不是新研究契约或生产接入声明。

1. 用户最新决定优先；已确认基线与工程建议分别标记，不让历史未决项覆盖后续确认。
2. 若引用更新后的源码，先核对实际差异，再更新受影响结论与基线；不只刷新日期。
3. 官方网页会变化，正式引入依赖前复核 API、兼容版本、默认行为及相关产品边界。
4. 新项目实现后，新增实际架构、接口、数据和运行文档，并在本索引登记权威来源；保留本轮可行性记录的时间与状态。
5. 不写入真实密钥、访问令牌或账户密码；尚未建立的环境不补造端口、实例或登录方式。
