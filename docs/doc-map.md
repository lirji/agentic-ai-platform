# 文档来源映射

2026-11-01 Docker正式身份增量：`deploy/docker/`对应[运行手册](../deploy/docker/README.md)及[当前部署记录](deployment/docker-identity-20261101/PROGRESS_STATE.md)。候选使用两仓固定提交；OIDC回调、代理、worker租户与镜像模式保持一致，历史工作区快照说明仅适用于旧部署。

2026-10-27 本机 Docker 增量：`deploy/docker/` 对应 [运行手册](../deploy/docker/README.md) 与 [部署记录](deployment/docker-20261027/PROGRESS_STATE.md)。源码来源为用户明确指定的双仓当前工作树快照，包含未提交 S10E 后端；仅部署文件进入本次 Git 交付，业务实施进度不因此完成。

同步日期：2026-10-21。

本项目已有 Python AI 运行时与独立 Java Gateway/Knowledge/Tasks/Conversations 服务。知识资料和中央任务按参考规则独立实现，旧数据和旧部署未迁入；以下原项目映射仍是固定取证证据。

本轮同步基线 `865aaa4ba1e0f50c91e2b206385fd78fdc027ee1` → S5D 工作树：Knowledge 独立只读身份、问答图、引用重验、流式预算、中央事件/失败、完成前回调及 worker 同步至契约/运行说明。[S5D 证据](qa/knowledge-qa-execution-20261021/TEST_RESULT.md)绑定当前源码，新增 LiteLLM 锁定制品、实际 deepseek-flash、三条业务路径、浏览器引用与无新增费用重启证据；S2–S5C 历史证据保留，当前状态见唯一进度。

本轮 S6/S7 同步基线：后端 `cb2f688`、前端 `f68e660`。Tasks 公共 work 请求/分页、V2 索引、`agents/` 受控工具与根图、共享 worker 和网关 SSE 映射到 [R02/A01](design/knowledge-first-20261020/WORK_TASK_CONTRACTS.md)；两路页面与共享意图/事件消费映射到独立前端 S7。证据与实际源码摘要见 [S6/S7 验收](qa/research-readonly-20261021/TEST_RESULT.md)，旧 QA 记录为历史验证，不替代当前回归。


S15A（2026-11-02，基线d9b0c79）：三类Python本地预算、实际模型出口与Tasks租约/数据库取证，冻结[用量设计](design/knowledge-first-20261020/USAGE_DESIGN.md)、[usage-v1](design/knowledge-first-20261020/USAGE_CONTRACTS.md)和[验收矩阵](design/knowledge-first-20261020/USAGE_ACCEPTANCE.md)。选择Tasks usage模块，原架构/选型原位同步；同库fence事务耦合阻止直接抽取。仅设计，无产品/迁移/额度变更；源码摘要与设计核验见[QA](qa/unified-usage-design-20261102/TEST_RESULT.md)。

S15B已实现并验收：Tasks usage、V6/V7、受控CLI/冻结、模型出口与Task/Knowledge归因、React本人视图对应[运行说明](design/knowledge-first-20261020/USAGE_RUNTIME.md)和[UA验收](qa/shared-usage-budget-20261102/TEST_RESULT.md)。仅隔离测试库迁移，原付费账本未变；Git/CI交付状态见权威进度。当前检查点与中间证据统一见[进度](design/knowledge-first-20261020/PROGRESS_STATE.md)，不改写S15A历史源码快照。
## 当前能力实施（2026-10-21）

用户已要求“开始实施”，并选择以项目架构/操作文档建立机制评测集。[主方案](design/knowledge-first-20261020/CAPABILITY_IMPLEMENTATION_PLAN.md)与 [51 项稳定任务](design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md#capability-depth-slices)继续有效；不能把规划能力当已实现。

S8A 已冻结 E01 与指标，S8B 基于后端 `39af9f6`、前端 `d2b4dc6` 实现评测定义、不可变修订、原提交恢复、授权原文标注和 React 界面。涉及 Knowledge evaluation 包、V4、共享发布 SQL、精确本地代理路由及质量页面；来源和验证见 [实现映射](qa/quality-datasets-20261021/IMPLEMENTATION_EVIDENCE.md)及 [TEST_RESULT](qa/quality-datasets-20261021/TEST_RESULT.md)。E01 用途筛选补充为数据库过滤并绑定游标。当前状态与 Git 交付仍由唯一 PROGRESS_STATE 维护。

S8C 基于后端 `c810e959`、前端 `427bcb9` 实现持久运行、Tasks失败凭据、独立角色、LangGraph逐题恢复及React运行详情。Knowledge V5 / Tasks V3仅追加，既有QA路径和预算复用；[E02](design/knowledge-first-20261020/EVALUATION_RUN_CONTRACTS.md)、[实现映射](qa/quality-runs-20261021/IMPLEMENTATION_EVIDENCE.md)、[验收](qa/quality-runs-20261021/TEST_RESULT.md)记录边界。S8D在后端44409bf/前端717e8c2上补齐不可变评分/基线、V6和React对照；[E03](design/knowledge-first-20261020/EVALUATION_COMPARISON_CONTRACTS.md)、[实现映射](qa/quality-baselines-20261021/IMPLEMENTATION_EVIDENCE.md)及[验收](qa/quality-baselines-20261021/TEST_RESULT.md)记录范围。S8E在后端aa355c5上完成6份固定来源真实入库、8题运行和逐题评分，[验收](qa/quality-mechanism-20261024/TEST_RESULT.md)保留1项非关键支撑失败；源码未改。

[框架证据](design/knowledge-first-20261020/FRAMEWORK_EVIDENCE.json)与原比较基线保持历史身份。S8F基于后端9a0e0b4/前端5960531新增两仓Actions、E02共享报文金标和真实DTO测试，P01沿用原共享金标；[CI契约](design/knowledge-first-20261020/CI_CONTRACTS.md)与[验收](qa/ci-contracts-20261024/TEST_RESULT.md)记录实际检查及限制。S8F无模型调用和依赖升级。

S9A在后端82eb31c上核对UploadRules、PreparedDocument、IngestionRunner、DualSearchIndex、KnowledgeSearch/EvaluationRow及Python CitationSource/处理DTO；[解析设计](design/knowledge-first-20261020/PARSING_DESIGN.md)和[P-v2](design/knowledge-first-20261020/STRUCTURED_PROCESSING_CONTRACTS.md)对应这些真实变化点。候选制品与自建格式样本见[S9A验收](qa/structured-parsing-20261024/TEST_RESULT.md)，不从探针推导已实现在线PDF/DOCX或生产隔离。 S9B的[实现证据](qa/pdf-ingestion-20261024/IMPLEMENTATION_EVIDENCE.md)覆盖实际解析模块、P04、V7兼容存储、双索引、K06和独立前端；[验收](qa/pdf-ingestion-20261024/TEST_RESULT.md)区分真实模型、隔离故障和浏览器重放，不扩展到DOCX/OCR。

S9C基线backend `ebcb94b` / frontend `185d3fa`：POI/XWPF、三种profile与闭合段/格来源、V8 MIME扩展、独立DOCX受理开关、Python结构校验和现有上传/引用UI同步到运行手册、架构/选型增量及[S9C验收](qa/docx-ingestion-20261025/TEST_RESULT.md)。新旧资料并存、故障恢复、实际来源权限和浏览器已核对；Git/CI结果由同目录交付收据记录。没有新模型预算、OCR、服务或数据库。

S10A基线backend `88dcf6c` / frontend `cdf7e47`：KnowledgeService/ProcessingMapper/旧唯一约束、profile注册、Tasks结果读取与评测绑定的真实事实，对应[原架构S10A增量](design/platform-selection-20261020/BACKEND_ARCHITECTURE.md)、T33及[生命周期契约](design/knowledge-first-20261020/LIFECYCLE_CONTRACTS.md)。生命周期状态与内容修订/索引代次分开，历史正文需服务端判权，保留期限未知不自动清理；本片仅设计，源码与运行文件未改。

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

S9D基线backend `79368c0` / frontend `cdf7e47`：[实现证据](qa/structured-chunking-20261025/IMPLEMENTATION_EVIDENCE.md)对应真实LlamaIndex Document/Node、Java/Python独立来源流校验、两个结构profile、V9与K06闭合消费者；[验收](qa/structured-chunking-20261025/TEST_RESULT.md)绑定旧新金标、真实恢复/权限、同源模型对照和界面截图。引用不含Embedding增强metadata，预算按实际账本，未实现生命周期更新。

## S10B 同步（2026-10-26）

基线backend `49d1f47` / frontend `b176130`。Knowledge生命周期/P05/版本投影、Tasks证据与历史读取、Python恢复校验、K08独立身份、Vite v2代理和React更新/版本历史映射到既有生命周期契约、运行说明和[S10B验收](qa/document-update-20261026/TEST_RESULT.md)。不改旧金标/历史报告，新增10个代次金标；源码与验证摘要见[validation.json](qa/document-update-20261026/validation.json)。未部署生产，撤下/删除/重索引与清理维持后续范围。

## S10C 同步（2026-10-27）

基线backend `7ba26c4` / frontend `83d24db`。K07撤下/逻辑删除、文档与作业同事务隔离、默认关闭双开关、本地身份有限路由及React原键恢复映射到既有生命周期契约、运行说明和[S10C验收](qa/document-visibility-20261026/TEST_RESULT.md)。复用S10B的Tasks/K08/QA/工作/Conversation当前授权，新增真实墓碑下的历史/PG/评测验证。无新数据库迁移或基础设施，未物理清理；S10D/E仍按计划实施。源码摘要见[validation.json](qa/document-visibility-20261026/validation.json)。

## S10D 同步（2026-10-27）

基线backend `f2243d4` / frontend `dbc77b2`。K07重索引/不可变发布源、同内容独立代次、REINDEX恢复、双sink全量ID/摘要回读、20/60/100候选及总期限、React原参数恢复映射到既有生命周期契约和[S10D验收](qa/document-reindex-20261027/TEST_RESULT.md)。Python仅补同文件跨profile样本校验；旧金标和业务守卫不改。无新依赖/迁移，非生产部署，物理清理未开启。

2026-10-28 S10F：Knowledge Lifecycle诊断读模型/Mapper/GET和前端ProcessingTasks/原CAS批次恢复对应[LIFECYCLE第9节](design/knowledge-first-20261020/LIFECYCLE_CONTRACTS.md#9-处理诊断与有界恢复s10f)与[验收](qa/processing-diagnostics-20261028/TEST_RESULT.md)；不增加DDL/写端点/批处理引擎。

2026-10-28 S11A：KnowledgeSearch/DualSearchIndex/发布SQL与Python KnowledgeClient/AuthorizedRetriever、锁定框架源码映射到[检索契约](design/knowledge-first-20261020/RETRIEVAL_CONTRACTS.md)；边界决策D-RT01–05追加到既有架构/选型，16项RT验收对应S11B–E，无产品/数据库/运行变化。

2026-10-28 S11B：RuntimeRetrievalController/AuthorizedRetrieval/RankedSearchIndex/RetrievalMapper对应[检索契约](design/knowledge-first-20261020/RETRIEVAL_CONTRACTS.md)第2–4/6节与[验收](qa/authorized-retrieval-20261028/TEST_RESULT.md)。发布SQL与查询容量共享，旧接口保持；全Java与固定源码容器证据同QA目录。

2026-10-28 S11C：ai-runtime/retrieval的严格模型、客户端、期限/容量、原生Retriever与pipeline对应[检索契约](design/knowledge-first-20261020/RETRIEVAL_CONTRACTS.md)第3–6节；RetrievalFusionGoldenTest与test_retrieval共同执行retrieval-golden-v1.json。49项离线行为、1项实际只读链路、固定源码容器与未切换消费者边界见[验收](qa/llama-retrieval-20261028/TEST_RESULT.md)。

2026-10-28 S11D：retrieval/rerank_*的私密配置、独立账本、DashScope适配、原生后处理与精排管线对应[检索契约](design/knowledge-first-20261020/RETRIEVAL_CONTRACTS.md)第5–6节；test_rerank与test_rerank_integration对应RT-10–13。52项离线行为、2项实际知识链路和U04条件边界见[验收](qa/rerank-budget-20261028/TEST_RESULT.md)。

2026-10-28 S11E接入核对：QaInput/WorkInput/EvaluationInput、TaskProtocol、TurnRow、EvaluationRunRow、CitationProtocol与Tasks V4约束对应[接入前核对](design/knowledge-first-20261020/RETRIEVAL_CONSUMER_READINESS.md)；20项源码摘要见[取证](qa/retrieval-consumers-20261028/preflight-evidence.json)。本阶段只读，不代表公共契约或消费者实现完成。

2026-10-30 S12A后端增量：Conversation V3/query_policy与独立受理、Tasks v3输入/完成/历史绑定、Python contextual_models/query_rewrite/contextual_pipeline/worker对应[C-QA2](design/knowledge-first-20261020/CONTEXTUAL_QA_CONTRACTS.md)。共享contextual-qa-golden夹具、真实MySQL/HTTP、PG接管及回归摘要见[后端证据](qa/contextual-qa-20261030/backend-evidence.json)。当时仅后端验收；当前完整状态见以下记录，运行容器未切换。

2026-11-01 S12A交付同步：前端Composer/intent/query/TurnCard、网关受众/路由、本地身份桥/容器代理与真实联合测试对应C-QA2，当前两仓源码摘要、浏览器/容器/预算证据见[完整验收](qa/contextual-qa-20261030/TEST_RESULT.md)和[validation](qa/contextual-qa-20261030/validation.json)。同步范围：后端b097420/前端31315b8之后本片提交与工作树；真实小样本已完成，部署未执行；当时下一片S12B尚未实现。

2026-11-01 S12B：前端ReviewOutcome/review-outcome与decision-messages对应既有E03四维度；后端AnswerQualityCalibrationIT及answer-quality-calibration.json验证五项已确认口径的真实评分、引用与比较。产品协议/schema未变，[验收与摘要](qa/answer-quality-20261101/TEST_RESULT.md)。

2026-11-01 S12C：Tasks ResearchCall/ResearchKnowledgeResults与任务行锁额度、Python research_models/research_pipeline、前端research/ResearchReport/CreateWork/WorkPage对应[C-WORK2](design/knowledge-first-20261020/RESEARCH_COVERAGE_CONTRACTS.md)。完整代次引用、三端18组负例、真实MySQL/PG/双索引/HTTP/浏览器及默认关闭的路由验证见[验收](qa/research-coverage-20261101/TEST_RESULT.md)；金额预算不重置，分阶段恢复留待S12D。

2026-11-01 S12D：StagedResearchPipeline、Tasks v4受理/ResearchResumeStage、前端recovery/Progress及公开路由对应[恢复契约](design/knowledge-first-20261020/RESEARCH_RECOVERY_CONTRACTS.md)。真正PG确认阶段、未确认模型重跑累计次数和浏览器恢复位置见[验收](qa/research-recovery-20261101/TEST_RESULT.md)；不启用内部子图检查点或新调度器。

- S13A身份前置事实：[IDENTITY_READINESS](design/knowledge-first-20261020/IDENTITY_READINESS.md)，对应源摘要[evidence](design/knowledge-first-20261020/evidence/identity-readiness-20261101.json)；仅核实当前网关/React与Auth参考和公开discovery，不表示正式配置或成员授权已完成。

- S13A正式身份：[IDENTITY_CONTRACTS](design/knowledge-first-20261020/IDENTITY_CONTRACTS.md) → Gateway BrowserSecurity/BrowserSessionGuard/PersonalMemberPolicy、React identity/session与HTTP；[Runtime](design/knowledge-first-20261020/IDENTITY_RUNTIME.md) → deploy/identity_local.py和identity_runtime.py；[验收](qa/identity-20261101/TEST_RESULT.md) → 网关专项与真实Casdoor/Knowledge浏览器证据。

- S13B/C：SharingService/SharingMapper/V13、CasdoorSharingMembers、发布权威SQL与双索引过滤对应[C-SHARING](design/knowledge-first-20261020/SHARING_CONTRACTS.md)；React共享设置/共享给我/只读详情/统一消费摘要对应[验收](qa/sharing-20261102/TEST_RESULT.md)。Tasks与Python沿既有最终/恢复证据校验；本片以真实共享ACL补联合验证，没有新增模型或生产部署。

- S14A：FeedbackService/FeedbackMapper/V14、TaskFeedbackProof和FeedbackProofVerifier对应[反馈契约](design/knowledge-first-20261020/OPERATIONS_CONTRACTS.md)；React引用反馈、原键恢复与收件箱对应同契约F01–F06；[Runtime](design/knowledge-first-20261020/OPERATIONS_RUNTIME.md)说明默认开关和隔离验收。
- S14B：CandidateService/CandidateQuality/CandidateMapper/V16、检索目的过滤与同事务副本归属对应 C-OPERATIONS C01–C04；React CandidatePage/operations 与既有质量编辑器对应 C05；candidate_smoke.py 的真实 Tasks/Knowledge/字节流浏览器验证对应 C06。局部文字检查仅提供字面状态，不替代现有质量报告或人工评分。

- S14D：CandidateRecoveryService/RecoveryMapper/V18与React恢复面板对应[发布后恢复契约](design/knowledge-first-20261020/OPERATIONS_CONTRACTS.md)的operations-recovery-v1；[验收](qa/publication-recovery-20261102/TEST_RESULT.md)记录精确探测、历史完整源补偿与明确撤下。

S15C：由Java既有Micrometer/OTel、Python真实HTTP/worker及原共享/预算流水映射到[C-OBS](design/knowledge-first-20261020/OBSERVABILITY_CONTRACTS.md)、[运行说明](design/knowledge-first-20261020/OBSERVABILITY_RUNTIME.md)和[验收](qa/cross-language-diagnostics-20261102/TEST_RESULT.md)。源码摘要固定在验收目录，未新增审计库或生产采集服务。

S16A：WorkerConfig/CLI、中央claim绑定与既有MySQL租约/PG检查点映射到[容量契约](design/knowledge-first-20261020/CAPACITY_CONTRACTS.md)、[运行说明](design/knowledge-first-20261020/CAPACITY_RUNTIME.md)及[验收](qa/multi-worker-recovery-20261102/TEST_RESULT.md)。真实双进程竞争与19项恢复复验，无新数据库迁移；全局公平仍属S16B。

S16B：Tasks V8/Knowledge V19及各自容量Mapper、全部新增/恢复入口、查询许可、独立执行器与Python显式HTTP容量映射到同一[容量契约](design/knowledge-first-20261020/CAPACITY_CONTRACTS.md)、[运行说明](design/knowledge-first-20261020/CAPACITY_RUNTIME.md)和[租户隔离验收](qa/tenant-capacity-isolation-20261102/TEST_RESULT.md)。默认未启用，新模式需全writer升级与固定策略；生产混合容量/SLO仍属S16C。

2026-11-02 Docker更新：固定后端ea339ee/前端c2d12fe构建release6b6adccd4f4f997f，映射到[部署验收](deployment/docker-capacity-20261102/TEST_RESULT.md)和[实际制品收据](deployment/docker-capacity-20261102/deployment-result.json)。9健康、真实身份/只读与数据完整性通过，原模式保留，新容量策略未启用；旧部署证据未覆盖。

## S16C容量验证证据

[报告](qa/mixed-capacity-20261102/CAPACITY_REPORT.md)与[验收](qa/mixed-capacity-20261102/TEST_RESULT.md)由`deploy/capacity_validation.py`、`ai-runtime/tests/capacity_probe.py`和`test_capacity_integration.py`真实隔离运行生成；精确源码/日志摘要见[source-evidence](qa/mixed-capacity-20261102/source-evidence.json)。原产品契约/运行默认值未变，当前进度仍以唯一PROGRESS_STATE为准。

2026-11-02 S16C后Docker更新：固定后端7863ae3/前端c2d12fe，release6b7bf063f040da7a，见[部署验收](deployment/docker-latest-20261102/TEST_RESULT.md)和[实际制品收据](deployment/docker-latest-20261102/deployment-result.json)。9健康、45业务表2716行及预算不变；前端相同源码制品复用及文件层核对见[复用证据](deployment/docker-latest-20261102/frontend-reuse.json)。

S17A：`deploy/docker`制品门禁/隔离入口、Knowledge ProcessingProperties命名空间、本地身份桥origin及可读资料分页对应[C-RELEASE](design/knowledge-first-20261020/RELEASE_CONTRACTS.md)与[验收](qa/isolated-release-validation-20261102/TEST_RESULT.md)。只读/合成隔离验证通过，原九应用恢复；未生产部署。2026-11-09从原`.local/s17a-delivery.json`归档[两仓交付收据](qa/isolated-release-validation-20261102/delivery-result.json)，并复核远程main及四次CI运行；进度同步未改产品源码或验证制品。

- S9E OCR：`platform-contracts/.../OcrProtocol.java`、`knowledge-parser/.../OcrPdfExtractor.java`、`TesseractProcess.java`、Knowledge V20及profile、Python structured → [增量契约](design/knowledge-first-20261020/OCR_CONTRACTS.md)；`deploy/docker/Ocr.Dockerfile`与runtime/compose → [运行与复验](qa/scanned-pdf-20261109/RUNTIME.md)。前端扫描选项/告警与浏览器证据在前端仓同日QA目录。
