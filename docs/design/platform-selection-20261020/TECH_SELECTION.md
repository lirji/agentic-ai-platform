# 平台技术与组件选型

日期：2026-10-20。适用范围：新项目 `agentic-ai-platform` 的整体选型，参考既有 Java/Python 平台资产。目标架构见 [BACKEND_ARCHITECTURE](BACKEND_ARCHITECTURE.md)。

**已确认选型主线：全新 React 前端 + Python 3.12 + LangGraph + Deep Agents + LlamaIndex，按需迁入 Java 领域能力；沿用已选 LiteLLM、MySQL、Qdrant、Elasticsearch、S3 与 IAM 接入方式，为图检查点使用 PostgreSQL 独立数据库。**

用户已确认架构与组件，并明确本项目独立运行、React 前端重新建设、知识业务优先及参考资产来源，见[首期约定](../knowledge-first-20261020/BRIEF.md)。本文件所选方向作为实施基线；暂缓项和按需项仍按触发条件处理。“已有/当前/沿用”以及阶段列的“已存在”指参考资产，不表示新项目已安装、迁入或接入，也不要求旧业务部署持续运行。版本依据为 Java `468cec4`、Python `7738cb7`；保留原选型的官方核对记录，具体版本仍需 P0 运行验证。

## 1. 主框架与运行方式

| ID / 决策 | 当前/约束 | 候选与选择 | 理由与取舍 | 引入阶段 |
| --- | --- | --- | --- | --- |
| T01 AI 执行 | 用户指定 LangGraph；参考项目使用 AgentScope | **LangGraph**；自研调度/AgentScope 仅作参考与隔离对照 | 统一图状态、分支、检查点与恢复；需要对接本项目中央任务和 fencing，不能直接复用旧 DAG 序列化 | P0–P2 |
| T02 复杂 Agent | 用户指定 Deep Agents | **Deep Agents**；纯 StateGraph 用于确定性短流程 | 复用子 Agent、上下文和工作文件能力；限制内置工具，显式启用规划，控制额外模型开销 | P0 验证/P2 业务 |
| T03 RAG | 用户指定 LlamaIndex | **LlamaIndex Core + 按需集成**；不再新建 LangChain RAG 主线 | 接管知识转换与检索编排；初期用受授权 Java Retriever，后续移交查询/准备算法 | P1/P3 |
| T04 Python 服务 | 参考项目 FastAPI/Pydantic/HTTPX/uv | **新项目采用 Python 3.12 + FastAPI/Pydantic/HTTPX/uv**；借鉴现有服务模式 | 独立依赖与运行版本；不与 AgentScope 全套依赖混装；新图状态、数据库连接和 async 生命周期需验证 | P0 |
| T05 模型出口 | 参考项目 LiteLLM 与独立 Embedding 配置 | **聊天/Agent 继续统一 LiteLLM**；文本 Embedding 沿参考栈的受控 OpenAI 兼容客户端接百炼 | 参考路由与归因，新环境独立落实凭据和权限；不增加厂商专用 SDK 或第二网关，不假定 LiteLLM 已有 Embedding 路由 | 已存在/P0 |
| T06 AI 部署 | 现有 Python inline / api-worker 与中央任务 | **自托管库 + 现有 API/worker 模式**；托管 Agent 平台备选 | 控制内部身份、数据和 Java 契约；承担运行适配、恢复、运维与升级成本 | P0/P2 |
| T07 旧 AI 技术 | AgentScope 和 LangChain4j 在参考项目使用 | **业务/代码参考与隔离对照** | 新 AI 用例使用三框架；迁入功能若仍依赖局部 LangChain4j 适配，登记并逐步替换；AgentScope 不作为新项目必需运行时或默认回退目标 | P1–P4 |

Deep Agents 构建在 LangChain Agent 基础之上，使用 LangGraph 执行；LangChain 是必要依赖，不需要再设计一个同级“LangChain 服务”。LlamaIndex 的 Agent/Workflow 不作为顶层执行器，以免叠加调度、恢复和预算。[Deep Agents 官方架构说明](https://docs.langchain.com/oss/python/deepagents/overview)

对于固定步骤和单次检索，直接使用 LangGraph 节点和 LlamaIndex 即可；复杂研究、资料汇总、分析规划才使用 Deep Agents。框架选择不要求每个 HTTP 请求都经过多 Agent 循环。

## 2. 候选版本与已做验证

### S2 实际接入（2026-10-20）

正式 [uv.lock](../../../ai-runtime/uv.lock) 已锁定并在 Python 3.12/macOS ARM64 安装 `langgraph 1.2.16`、`deepagents 0.7.28`、`llama-index-core 0.14.25`、`langchain-openai 1.7.2`、`langgraph-checkpoint-postgres 3.2.0`；PyJWT 2.15.1（crypto）补充独立 Knowledge 服务 RS256 验签，不引入第二个身份服务。

本段保留 S2 当时的验证范围：LangGraph/LlamaIndex 用于 P01 准备图，Deep Agents 工具循环/流式通过隔离测试；真实北京百炼 `text-embedding-v4 / 1024` 准备/查询已验证，见 [S2 验收](../../qa/knowledge-processing-20261020/TEST_RESULT.md)。此后 S5B/S5D/S6 已验证 PG 联合恢复、真实问答和研究；Linux 完整应用交付、系统性质量与容量仍须另验，不能沿用 S2 的未实现描述作为当前状态。

本机 118 个运行/开发依赖已查 PyPI 发布元数据，许可证无缺项；NLTK 3.10.3 有未修复的模型文件路径沙箱绕过公告 `GHSA-8mgp-746c-j5xp`。本片显式分句，不调用该公告涉及的训练、模型导入/导出或用户控制路径，不以此宣称整个 NLTK 安全。后续启用文档 reader/模型加载前重新评估。OSV 直连 TLS 失败后使用 PyPI 发布公告，完整结果与例外见 S2 报告。

### 历史候选解析

以下是本次建议用于 **P0 兼容验证** 的固定版本，不是生产认证清单：

| 包 | 候选版本 | 用途与依据 |
| --- | --- | --- |
| `langgraph` | **1.2.16** | 主执行运行时；[PyPI 发布页](https://pypi.org/project/langgraph/1.2.16/) |
| `deepagents` | **0.7.28** | Agent 执行封装；要求 Python `>=3.11,<4`；[PyPI 发布页](https://pypi.org/project/deepagents/0.7.28/) |
| `llama-index-core` | **0.14.25** | 数据/RAG 核心；[PyPI 发布页](https://pypi.org/project/llama-index-core/0.14.25/) |
| `langgraph-checkpoint-postgres` | **3.2.0** | PostgreSQL Checkpointer/Store 适配；[PyPI 发布页](https://pypi.org/project/langgraph-checkpoint-postgres/3.2.0/) |
| `langchain-openai` | **1.7.2** | LangGraph/Deep Agents → LiteLLM；[包元数据](https://pypi.org/pypi/langchain-openai/1.7.2/json) |
| `llama-index-llms-openai-like` | **0.8.1** | LlamaIndex 模型适配；[包元数据](https://pypi.org/pypi/llama-index-llms-openai-like/0.8.1/json) |
| `llama-index-embeddings-openai-like` | **0.4.0** | P3 embedding 适配候选；[包元数据](https://pypi.org/pypi/llama-index-embeddings-openai-like/0.4.0/json) |

本次使用 `uv 0.11.3`、Python 3.12/Linux x86_64 目标、官方 PyPI 索引、禁止源码构建的依赖解析，以上包加 FastAPI、Pydantic、HTTPX、Psycopg 共解析出 **113 个包**。关键传递依赖解析为 `langchain 1.4.6`、`langchain-core 1.6.12`、`langgraph-checkpoint 4.3.0`、`llama-index-workflows 2.26.0`。这验证了依赖约束可共同满足。

**以上选型解析当时没有安装依赖、修改现有锁文件、import 运行框架、连接数据库或调用模型。** 不等于与当前 AgentScope 全套依赖可以混装，不等于流式输出、工具执行或恢复兼容，也未验证 Linux ARM64/本机 ARM64 的三框架组合。P0 应在独立运行环境生成带哈希的正式锁文件，验证许可证、漏洞、目标 CPU/镜像和真实运行，再冻结部署制品。

后续 S0 已为实际使用的服务基座生成 [uv.lock](../../../ai-runtime/uv.lock)，在 macOS ARM64 / Python 3.12.13 安装并验证 FastAPI 0.151.0、Pydantic 2.14.1、HTTPX 0.28.1、Uvicorn 0.54.0；开发检查使用 Ruff 0.17.1、mypy 1.20.2、pytest 9.1.1。该锁仅包含 S0 运行与开发依赖，不包含上述三框架候选组合。68 项测试、本地 HTTP、构建及当前平台 27 个运行/开发依赖的漏洞与许可证核对结果见 [S0 验证](../../qa/runtime-foundation-20261020/TEST_RESULT.md)；Linux、容器、真实模型和业务集成仍待后续验证。

复现输入和解析输出随文档迁入 [evidence](evidence/README.md)：[包元数据](evidence/package-metadata.json)、[候选输入](evidence/candidate-requirements.in)、[解析输出](evidence/candidate-requirements.txt)、[结果摘要](evidence/dependency-summary.json)、[解析日志](evidence/dependency-resolution.log)。它们是选型证据，不是应用安装清单或正式锁文件。以下命令在项目根目录运行，将新结果放入临时目录，保留本轮历史输出；重做时传递依赖可能变化，应比较完整结果。

```bash
selection_output_dir="$(mktemp -d)"
uv --no-config pip compile \
  docs/design/platform-selection-20261020/evidence/candidate-requirements.in \
  --python-version 3.12 --python-platform x86_64-unknown-linux-gnu \
  --no-build --default-index https://pypi.org/simple \
  --no-header --no-annotate \
  --output-file "$selection_output_dir/candidate-requirements.txt"
```

Deep Agents 0.7 的规划为显式能力，升级中也有 backend API 变化。以候选版本对应文档验证配置，不能粘贴其他版本示例后假定可运行。[规划说明](https://docs.langchain.com/oss/python/deepagents/overview)、[backend 迁移说明](https://docs.langchain.com/oss/python/deepagents/backends)

## 3. 状态与基础组件

| ID / 能力 | 当前/候选 | 建议选择及原因 | 代价、否决原因与兼容影响 | 阶段 |
| --- | --- | --- | --- | --- |
| T08 图检查点 | PostgreSQL 官方适配 / MySQL 自研适配 / Redis / SQLite | **PostgreSQL 16 共享实例、独立库 + AsyncPostgresSaver** | 新增逻辑库与备份、池预算；自研 MySQL Saver 维护成本高，SQLite 不作为多副本运行基线；Redis 不因已存在就替代关系型恢复存储 | P0 |
| T09 中央任务 | 现有 MySQL 任务服务 / 新调度平台 | **按需迁入 async-task-service 及依赖** | 保留任务、取消、租约、持久事件和 webhook；绑定本项目配置与数据，图状态另归 T08，验证跨库完成报告和 fencing | P0 验证/P2 完整接入 |
| T10 Agent 工作文件 | StateBackend / StoreBackend / 宿主文件系统 | **StateBackend 优先，必要时 CompositeBackend 接受控适配器** | 大结果存 S3 引用，限制 checkpoint 大小；不默认安装共享可写目录或额外用户记忆库 | P1 |
| T11 用户长期记忆 | 既有治理 API / 新 PostgresStore 画像 | **有需求时迁入受治理画像能力** | 避免双写权威；历史画像不默认导入，跨线程 Agent 工作资料不冒充业务画像 | 按知识会话需求 |
| T12 Java 业务数据库 | 当前 MySQL 8.4 / 全迁 PostgreSQL | **继续 MySQL 8.4** | AI 图状态使用 PG 不构成业务表迁库理由；现有 SQL、Flowable、数据迁移保持兼容 | 已存在 |
| T13 Java 持久化 | 已有 MyBatis-Plus/XML/Flyway | **沿用** | 自定义 SQL 放 XML；新表和字段有注释，迁移不改历史。Python Checkpointer 使用自身适配，不强行套 Java ORM | 已存在/P0 |
| T14 向量检索 | 当前 Qdrant / pgvector / ES 向量 | **保留 Qdrant** | 降低现有集合/过滤语义迁移面；pgvector/单 ES 可在明确缩减运维成本时做对照，不同时更换框架与检索引擎 | 已存在/P3 |
| T15 关键词检索 | 参考 ES 8.15.3 + smartcn / 数据库全文 / 只保留向量 | **保留 Elasticsearch BM25；新实例采用 8.19.23 + smartcn** | 复用中文精确词检索与混排语义；本项目安全补丁决策见下文，旧平台实例不变 | 已存在/P3 |
| T16 原文/派生对象 | 已有 S3-compatible / 本地磁盘 | **复用现有对象存储** | 原文不可变；Agent 附件和派生结果独立前缀/权限；不公开 bucket，不让框架直接持有全桶权限 | 已存在/P0 |
| T17 缓存与 Registry | 当前 Redis 7 / 新 L1+L2 / 无缓存 | **沿用既有 Redis 职责** | Registry 不是可随意丢弃的普通缓存；新 RAG 响应缓存需绑定权限、文档版本、模型版本，暂不增加多级缓存 | 已存在 |
| T18 可靠消息 | 现有 Kafka/Outbox / RabbitMQ / 新工作队列 | **按已有事件链复用 Kafka 和 Outbox** | LangGraph 节点和子 Agent 不全部发 MQ；不新增 RabbitMQ/Celery 来重复中央任务与图调度 | 已存在 |
| T19 业务审批 | 现有 Flowable 7.1.0 / 图 interrupt | **Flowable 保持业务流程权威** | LangGraph interrupt 只是 AI 执行暂停，不能替代业务审批记录和事务；不增加 Temporal/Camunda | 已存在 |
| T20 文档解析/OCR | 已有 Tika/Tesseract / LlamaIndex readers / LlamaParse | **先迁入所需解析能力，LlamaIndex 逐步接管转换** | P1 必须支持首期资料入库；Reader 按格式补入，复杂版面不足再评估云解析与数据边界 | P1 入库/P3 转换移交 |

T08 采用官方数据库 Checkpointer 能减少自研存储协议工作，但框架不负责本平台中央租约 fencing。[官方数据库示例](https://docs.langchain.com/oss/python/langgraph/add-memory)

T14 的候选均具备技术可行性：Qdrant 有[过滤能力](https://qdrant.tech/documentation/search/filtering/)，pgvector 提供[PostgreSQL 向量检索](https://github.com/pgvector/pgvector)，Elasticsearch 提供[混合检索](https://www.elastic.co/docs/solutions/search/hybrid-search)。**保留当前双检索引擎是基于已有集成与迁移成本的工程判断，不是性能排名。** 未开展三者基准测试；具体 ES 功能还须核对所选版本和许可，不能把当前官网能力全部套到 8.15.3。

S3 搜索运行版本决策（2026-10-20）：本项目独立实例采用 **Qdrant 1.19.2、Elasticsearch 8.19.23 + 同版本 analysis-smartcn**。组件与双索引契约不变；在首次业务验收前修正从 dev_infra 参考的旧镜像补丁，避免新实例继续锁定已有修复的版本。Qdrant 的 [GHSA-3gph-6c29-p29v](https://github.com/qdrant/qdrant/security/advisories/GHSA-3gph-6c29-p29v) 于 1.19.2 修复，本地单节点未启用受影响的内部认证模式；Elastic 的 [8.19.23 发布说明](https://www.elastic.co/guide/en/elasticsearch/reference/8.19/release-notes-8.19.23.html)包含安全校验和依赖更新，[smartcn 官方安装方式](https://www.elastic.co/guide/en/elasticsearch/plugins/8.19/analysis-smartcn.html)仍适用。锁定实际拉取的 digest，项目自行构建 smartcn 镜像，不修改旧 `lc4j-*` 实例。升级前保存项目资源快照，升级后必须重验 schema、Bulk 单项结果、查询和真实发布；备份存在不等于已验证恢复或承诺 RTO/RPO。

`llama-index-vector-stores-qdrant 0.10.4` 已读取发布元数据，但不包含在上述 113 包解析集合中，也不在 P1 开启直接索引写入。P3 默认仍走 Knowledge 提交边界；确有直接读连接需求再单独验证过滤、数据格式和授权能力。

## 4. 网关、治理、前端与工程组件

| ID / 能力 | 选择 | 对比、边界与引入条件 |
| --- | --- | --- |
| T21 业务入口 | 迁入本项目所需 Spring Cloud Gateway 能力 | 与 LiteLLM 分工：一个处理业务入口/身份，一个处理模型出口；独立配置路由，不默认转发到旧业务部署 |
| T22 认证授权 | 沿用 Casdoor + auth-platform + 内部 JWT；文档权限按目标启用 ReBAC | 不更换 Keycloak 或再建 SSO。当前 edge 使用自定义 GlobalFilter + Spring Security JOSE，不能写成完整 Resource Server 已接入；后者若迁移需单独验证 CORS/401/旧认证行为 |
| T23 配置/发现 | 当前环境配置 + 可选 Config Server + 部署 DNS | Nacos Config/Discovery 分别按动态配置、多实例发现需求评估；既有寻址可用时不再叠加注册中心，不形成同键双配置权威 |
| T24 调用保护 | 保留 HTTPX/Java 现有超时、连接池及隔离；按缺口选择 Resilience4j | 不全链统一加多层重试；先定义总 deadline、幂等和依赖预算；Python 不套 Java 组件 |
| T25 业务定时任务 | 有新业务定时需求时采用既定 XXL-JOB | 本次没有新业务周期任务，不新增调度器；框架心跳/租约维护和检查点机制不能机械搬到 XXL-JOB |
| T26 状态机 | 现有状态模型；新简单流程用稳定 code/迁移表，复杂生命周期按需评估 COLA | LangGraph 图状态、Flowable 流程状态、领域状态各归其主；COLA 不取代图运行时或数据库条件更新 |
| T27 可观测/评测 | OTel + 现有 Prometheus/采集端；复用固定 RAG/Agent 评测资产 | LangSmith 可选作独立诊断工具，须评估数据与费用，不作为三框架运行硬依赖；不同时新建多套观测平台 |
| T28 API 与协议 | HTTP/JSON/SSE；沿用 MCP/A2A 边界 | 不引入 gRPC/GraphQL 作为此次框架迁移前置；SDK 事件只在内部使用，对外发布语言中立版本契约 |
| T29 前端 | **全新 React + TypeScript + Vite + Ant Design 前端，独立 Git 仓库** | 用户明确不复用旧前端；React Router、TanStack Query、Axios、Ant Design Form/主题、CSS Modules、pnpm 为默认配套，Zustand 按需；围绕知识库、问答、研究和助手完成页面/状态设计，S4 已锁定版本与独立仓库，详见 [前端选型](https://github.com/lirji/agentic-ai-platform-web/blob/main/docs/TECH_SELECTION.md) |
| T30 部署 | 本项目独立 Compose + dev_infra；按需求参考 Helm 能力 | 已有本地脚本及独立搜索/模型代理配置；完整应用固定制品、升级与还原由 S17 验收。共享实例继续使用项目库/账号/前缀，旧业务部署不作为必需依赖 |
| T31 工程验证 | Python pytest/Ruff/mypy + Java 现有测试 + 契约一致性 | 增加与真实风险对应的检查点、恢复、租户和版本共存测试；不改现有 Coding Agent 工具或触发真实模型 benchmark |

基础设施与版本事实来自 [dev_infra 说明](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/deploy/dev-infra/README.md)、[Compose](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/deploy/docker-compose.yml)、[edge POM](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/edge-gateway/pom.xml)、[共享观测模块](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/platform-observability/pom.xml)、[前端依赖](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/capability-showcase-frontend/package.json)。现有配置不等于本次已验证服务运行版本。

## 5. 模型与检索策略选择

框架主线已经确定，模型按角色配置。用户已明确模型及数据可借鉴 langchain 项目；已定位路由配置、知识数据模型与评测资产，见[首期约定第 5 节](../knowledge-first-20261020/BRIEF.md#5-已核对的参考资产)。参考 LiteLLM 的逻辑模型名与凭据机制，在新项目环境独立配置实际权限和额度。[LiteLLM 官方入口](https://docs.litellm.ai/docs/proxy/quick_start)

| 角色 | 本次建议 | 冻结具体模型前的证据 |
| --- | --- | --- |
| 主 Agent/规划 | 先用现有可用且支持工具调用的主模型作为迁移对照 | 工具选择、参数 schema、长任务成功率、循环/幻觉、模型接口兼容 |
| 摘要/轻任务 | 在语义相同前提下单独配置低成本模型 | 摘要事实保真、长上下文压缩和总调用成本；不能只比较单 token 单价 |
| Embedding | **默认百炼 `text-embedding-v4`，1024 维，OpenAI 兼容接口**；来自参考项目标准 Compose；不使用 HashEmbedding | 真实调用、中文/业务 Recall@k、过滤后召回、入库与查询模型一致性；模型/维度变化创建新索引并重建 |
| Rerank | 当前只有 Java 两路 RRF60，尚无独立精排模型；S11 优先验证百炼 qwen3-rerank 适配 | 先融合等价、再做 nDCG/MRR/答案质量/延迟/费用消融；实际账号/地域/端点/预算另核对，避免 Java 与 LlamaIndex 重复排序 |
| 视觉/语音 | 现有领域适配继续提供能力 | 本次不同时切换 ASR/TTS/视觉供应商，后续按真实缺口评估 |

参考配置中 `chat-default` 声明为 `deepseek/deepseek-chat`，这是来源事实，未验证本项目账号可用性或运行效果。初始 RAG 资产含 200 条合成问题与 20 条语料清单记录，需重绑定新项目文档/权限并补充业务样本；旧评测结果不作为新项目验收结果。

用户明确不采用默认 HashEmbedding。本项目统一使用上述真实语义默认，包括本地业务运行、演示、Compose 与 RAG 验收；迁入时检查应用和各覆盖配置，移除 hash 业务默认及回退。参考模型的 1024 维和 OpenAI 兼容接口已核对[官方规格](https://www.alibabacloud.com/help/en/model-studio/embedding)与[同步接口](https://www.alibabacloud.com/help/en/model-studio/text-embedding-synchronous-api)。接入参数、地域、账号、额度与预算仍需在实际环境验证；未配置或调用失败时明确报错。Ollama `nomic-embed-text` 作为来源已有备选，按显式配置和独立索引验证启用，禁止自动混用向量。

## 6. 复杂度预算与获取方式

| 能力 | 获取方式 | 为什么需要/不采用更简单方案 | 新增成本与退出方式 |
| --- | --- | --- | --- |
| LangGraph/Deep Agents/LlamaIndex | INTEGRATE | 用户已确定 AI 主框架；继续自研会保留原调度/上下文维护负担 | 适配与升级、113 包候选依赖面；框架类型限制在内部，公开协议独立 |
| 图持久化 | REUSE_EXISTING 基础设施 + INTEGRATE Checkpointer | 仅内存不能支持多进程恢复；自研 MySQL Saver 增加存储语义维护 | 新 PG 逻辑库、备份、迁移、连接预算；保留版本绑定，替换需显式状态迁移 |
| 中央任务和 Java 工具 | MIGRATE 所需参考代码 + BUILD 跨运行时适配 | 已有任务/权限/副作用能力可借鉴；新框架不具备本平台业务约束 | 迁入依赖闭包、独立数据、fencing、终态对账和事件映射；不建立第二任务后台 |
| RAG 连接与准备协议 | BUILD 小范围领域适配 | 通用 vector store 插件不能表达现有内容去重、发布版本与外部授权 | Retriever/准备 DTO/提交协议与回归；存储仍由 Knowledge 管理 |
| 检索、对象存储、身份、流程、观测 | REUSE 基础设施 + MIGRATE 所需接入代码 | 参考项目已有明确职责和接入证据，按当前知识业务需要接入 | 项目级权限与命名空间、共享容量预算；业务服务不能隐式依赖旧部署 |
| LlamaParse/LlamaCloud、托管 Agent 平台 | DEFER | 当前无必须采购或向外发送文档的需求 | 有真实格式/运维缺口后评估价格、数据边界、许可和退出能力 |
| 新 MQ、图数据库、专用记忆平台、第二配置中心 | DEFER | 当前要求未证明必须新增 | 写清触发问题后再选，不为架构图完整而引入 |

新框架不能替代业务知识与数据质量建设。优先自研领域契约、授权与可恢复交接，通用推理/检索组件采用集成。

## 7. 现有版本与维护策略

### 新项目 S1 实施版本（2026-10-20）

源项目旧 Java 版本仅是取证基线。新项目按用户偏好使用 JDK 21、Spring Boot **3.5.16** / Cloud **2025.0.3**；实际 Gateway 4.3.5、Security 6.5.11、MyBatis-Plus 3.5.17、AWS SDK 2.57.0。当前 Java 验证平台为 Microsoft JDK 21.0.11 / Maven 3.9.12 / macOS ARM64。版本从 [Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[MyBatis-Plus 安装说明](https://baomidou.com/getting-started/install/)和 [Maven Central](https://repo.maven.apache.org/maven2/)核对，并以根 POM 与解析依赖树为实际声明。

初始 BOM 的 178 个运行坐标中，OSV 查询命中 17 个包。依据公告补丁范围，在不改变已选组件主路线的前提下显式覆盖：Jackson 2.21.8、Netty 4.1.139.Final、Tomcat 10.1.61、OpenTelemetry 1.62.0、HttpClient 5.6.5 / HttpCore 5.4.4、Log4j 2.25.5、Bouncy Castle 1.85.2。覆盖版本需由本片完整构建、身份/原文/事务/重启验证及二次依赖查询确认，不把版本数字当作兼容证据。

Spring Framework 6.2.19 的三个现存公告仍须保留：WebFlux 功能端点配合 DispatcherServlet、片段视图 SSE、XsltView 隐式视图路径。当前 S1 是独立 Netty Gateway 与 JSON/MVC Knowledge，未配置这些路径，按调用条件评估当前不受影响；这个结论不能自动延伸到未来流式/视图能力。Spring 官方给出 OSS 7.0.9 或商业 6.2.20 修复；生产维护路线仍需确定，不能直接把 Spring 7 塞入 Boot 3.5，也不因首片本地验收改变已选架构。[官方公告 47892](https://spring.io/security/cve-2026-47892/)、[47890](https://spring.io/security/cve-2026-47890/)、[47884](https://spring.io/security/cve-2026-47884/)。

Flyway 由 Boot 管理为 11.7.2，实际 MySQL 8.4 的 V1 创建、重复启动校验和事务测试已运行；其自带版本识别仍提示只验证至 MySQL 8.1，保留兼容告警，不能推导全部后续迁移兼容。当前不以消除告警为由引入 Flyway 13 大版本。版本/告警和供应链最终结论进入 S1 验证报告。

| 项目 | 本次判断 | 后续动作 |
| --- | --- | --- |
| 参考项目 Java 21 + Boot 3.3.5 / Cloud 2023.0.3 | 历史取证基线；新项目实现采用本节上方的 S1 版本 | 不把参考项目版本与新项目制品混写 |
| 个人偏好的 Boot 3.5 / Cloud 2025.0 | 版本配套成立，但不能直接当作当前受支持长期目标 | 本次 Spring Cloud 官网已将 2025.0 标记结束支持；若保留该路线需确定实际补丁来源，否则评估受支持 Boot 4/Cloud 2025.1 组合及全套依赖兼容 |
| LangChain4j 1.13.1 | 旧链路兼容依赖 | 仅为安全/兼容需要升级，不为替换中的旧主线继续扩建 agentic |
| 基础 Compose Qdrant `latest` | 构建不可复现风险 | 固定经核验版本/digest；共享 dev_infra 的实际 digest 另核对，不推断已运行 `latest` |
| ES 8.15.3、Kafka 3.8、Redis 7、LiteLLM 既有镜像 | 记录现状，未做完整生命周期/漏洞认证 | 根据实际镜像/SBOM/客户端与备份恢复证据制定维护窗口，不按名称直接判兼容 |
| 新三框架依赖 | 候选精确版本已解析，运行与供应链检查未完成 | P0 验证后生成正式锁与镜像；任何升级重跑受影响的契约/恢复/安全检查 |

Boot/Cloud 配套与支持状态采用 [Spring Cloud 当前项目页](https://spring.io/projects/spring-cloud/)；其较早更新的 [GitHub 兼容表](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions)仍有不同支持标记，因此不使用历史表格推导今天仍受支持。生产补丁来源需在实施时确认。

Spring AI 不再作为本项目主线替代候选；它的 1.1/2.x 分别绑定 Boot 3.5/4.x，也不能直接混入当前 Boot 3.3.5。保留该核对仅解释此前讨论的兼容限制。[Spring AI 官方版本关系](https://github.com/spring-projects/spring-ai/blob/main/README.md)

## 8. 设计后的验证清单

按已更新的架构文档 P0→P4 推进，先验证独立运行基线和两个关键接缝：**中央租约与 LangGraph 检查点；Java 文档治理与 LlamaIndex Node/准备结果**。知识入库与 React 问答闭环在 P1 落地，LlamaIndex 转换算法的进一步移交在 P3 完成。

| 验证 | 通过依据 | 当前状态 |
| --- | --- | --- |
| Python 3.12/Linux 候选依赖解析 | 固定直接依赖可解析，无源码构建 | 已通过，113 包；仅元数据层 |
| 真实文本 Embedding | text-embedding-v4 返回 1024 维；入库/查询一致；缺配置和调用失败不回退 hash；批量限制及模型变更索引隔离 | 设计默认已定，运行未验证 |
| 框架与模型接口 | import、假模型、结构化工具、流式、取消、预算；再做获授权真实模型对照 | S0 Embedding 协议与取消已验证；三框架及真实模型待执行 |
| LangGraph 持久化 | 真实 PG 进程退出、重复 resume、丢租约旧 worker、终态写回未知结果 | 未执行，上线前置 |
| RAG 迁移 | 同模型/语料对照；租户/文档授权、撤权、引用、去重、处理版本、索引回滚 | S1 受理/去重/OWNER 授权已验证；处理/检索/发布仍未执行 |
| 内置工具与记忆 | 子 Agent、filesystem、Skills、checkpoint、跨线程读写和审批绕过用例 | 未执行 |
| 性能与成本 | 先确认峰值/SLA/预算，再测总调用链、共享 DB 连接与下游额度 | 目标值待确认 |
| 独立运行与发布回退 | 不访问旧业务部署；本项目任务按运行版本处理，回退制品兼容检查点；旧项目不是自动回退目标 | S0/S1 本地独立运行已验证；部署/版本回退未验证 |

选型及本轮独立项目范围已确认；S0/S1 的实际证据见 [S1 验收](../../qa/knowledge-ingestion-20261020/TEST_RESULT.md)及其中引用，不能扩大为三框架集成、生产运维或完整知识库验收。历史候选依赖解析证据保持原时间与范围。

## S5A 实施核对

中央任务沿既定 JDK 21 / Boot 3.5.16、MyBatis-Plus/XML、MySQL 8.4 实现；新模块运行依赖与此前已审查制品逐 jar SHA256 一致，没有新增外部依赖版本。详见 [依赖复用证据](../../qa/durable-ai-tasks-20261020/dependency-reuse.json)。

原聊天路由 `chat-default → deepseek/deepseek-chat` 已核对。2026-10-20 原账号 GET /models 返回 200，仅列出 deepseek-flash / deepseek-v4-pro；官方声明 deepseek-chat 于 2026-07-24 停用。[官方公告](https://api-docs.deepseek.com/news/news260424/)。已询问是否改用 deepseek-flash，在用户答复前不调用聊天模型；已批准的聊天 1 元与 Embedding 1 元分别累计，跨自然日不重置本轮额度。LiteLLM 与 PG 尚未因 S5A 创建。

## S5B 实施核对

2026-10-21 复用 dev-infra PostgreSQL 16.15，创建本项目两个逻辑库和分权账号；保留原生 Checkpointer 结构，追加不可变触发器与中文注释。Python 的中央凭据/执行代次适配通过真实 LangGraph + PG + MySQL + HTTP 的接管、响应丢失与终态恢复验收；不是将原生 saver 当作中央租约保证。详见 [S5B 契约](../knowledge-first-20261020/CHECKPOINT_CONTRACTS.md)与[验收](../../qa/ai-checkpoint-recovery-20261021/TEST_RESULT.md)。

宿主缺少 libpq，为原已锁定的 psycopg 3.3.6 补充官方 binary/pool extra，只新增 psycopg-binary 3.3.6；未升级框架或共享 PG。官方 wheel 与 lock 摘要一致，许可证 LGPL-3.0-only、PyPI 版本公告为空，本机 libpq 18.6 / OpenSSL 3.5.8；依赖增量和既有风险保留在验收记录，不宣称全部依赖零漏洞。无新 LiteLLM 实例、常驻图 worker 或聊天调用。

## S5C 会话实施增量（2026-10-21）

会话服务沿用 JDK 21、Boot 3.5.16、MyBatis-Plus/XML、MySQL 8.4 与既有观测组件；新建自己的逻辑库/账号，不引入 MQ。TaskStatus 共享至 platform-contracts，后者只新增对已解析 jackson-annotations 的直接声明，实际 jar 与原 Knowledge 制品内同一包完全一致；没有新外部版本。凭据引用、端口和资源界限见 [运行说明](../../../deploy/README.md)，依赖差异与验收见 [S5C](../../qa/knowledge-conversations-20261021/TEST_RESULT.md)。


## S5D 实际聊天出口（2026-10-21）

用户批准将原停用型号替换为 `deepseek-flash` 并更新凭据。独立 `deploy/model-proxy/` 环境固定 LiteLLM **1.106.0**，不混入 AI 运行时依赖；补充官方可选依赖 Prisma **0.15.0** 解决未认证请求异常分类，不配置代理数据库。wheel、锁摘要与依赖公告扫描见 [制品记录](../../qa/knowledge-qa-execution-20261021/litellm-artifact.json)。123 个注册依赖在本次 OSV 扫描无命中，这不是未来或所有部署形态的安全保证。

本机单路由 `chat-default → deepseek/deepseek-flash`，显式 `thinking.type=disabled`，保持现有纯文本问答契约；不启用默认思考输出、工具、重试、回退、缓存或外部追踪。提供方 key 仅进入代理，worker 使用独立本机密钥。真实代理转换/失败边界与真实问答分别验证，详见 [S5D 验收](../../qa/knowledge-qa-execution-20261021/TEST_RESULT.md)。

按 [官方价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/) 的峰值未缓存输入 2 元/百万、输出 8 元/百万配置保守预算；客户端另加字节上界和 2 倍余量。官方模型默认思考开启，实际请求显式关闭，参数依据 [官方接口](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/)。聊天与 Embedding 的原 1 元累计上限不变。历史 S5A/S5B 的“未接入”仅描述当时状态，当前以本节及权威进度为准。

## 能力深化的增量选型（2026-10-21，方案阶段）

本轮不升级框架/依赖、不新增运行组件。沿当前锁定的 `LangGraph 1.2.16 / Deep Agents 0.7.28 / llama-index-core 0.14.25`；[本地接口证据](../knowledge-first-20261020/FRAMEWORK_EVIDENCE.json)记录已安装版本及源码摘要。官方在线文档说明能力方向，具体调用必须对照锁定实现，不凭最新版示例改造。

| 对应既有决策 | 本轮方案及适配范围 | 冻结/验证任务 |
| --- | --- | --- |
| T03/T27 评测 | Java 管定义/标注/报告；LlamaIndex 指标经项目适配，LangGraph 调用实际 RAG。无答案、空召回、失败分别计量；默认人工标注，不默认新增付费裁判 | S8A–E、S12B |
| T20 解析 | 优先 Java 有界子进程解析，LlamaIndex 处理规范结构；用真实样本比较 Tika/PDFBox/POI 与 Python Reader 后锁定依赖。OCR、XLSX/CSV 按需启用 | S9；不提前采购 LlamaParse 或增加第二套解析 |
| T03/T14/T15 检索 | 保持 Qdrant + ES；Java 授权候选，LlamaIndex 显式融合适配保持一基排名与稳定身份。默认查询生成、全局模型和零基排名不能直接沿用 | S11A–C；先固定输入等价，再真实链路对照 |
| T05 独立精排 | 首选待验证候选为百炼 qwen3-rerank 受控 HTTP + LlamaIndex 后处理；聊天与 rerank 不共用未经验证的协议。仅在数据/部署要求驱动时评估本地 CrossEncoder | S11D；账号、地域、接口、实际费用与独立累计额度落实后才能真实调用 |
| T01/T02 执行深度 | LangGraph 根图分阶段；Deep Agents 有界规划/证据工具；保持文件工具、通用子 Agent、shell 和内部 saver 对任务关闭；记忆经治理 API | S12、条件 S18；不新增同级 Workflow/调度器 |
| T09/T12/T13 费用 | 建议 Tasks 内独立 usage 模块/自有 MySQL 账本，内部 API 预留/结算；不让多实例各守本地额度，不在本轮引入独立计费服务 | S15A 冻结归属及精度/未知结果语义；S15B 对账原账本，不清零 |
| T22/T29 身份与 UI | 沿 Casdoor/auth-platform 方向和现有 React 栈；先定组织/角色/共享规则，再按真实能力加入评测、运营及共享页面 | S13 及各带 UI 任务 |
| T27/T30/T31 工程 | 最小 CI 前置，埋点随功能；固定制品、升级、隔离还原及供应链分项验收。公共中间件优先 dev_infra，不新造缓存/MQ/配置中心 | S8F、S15C、S16、S17 |

百炼官方当前给出独立重排协议；这只支持候选方案，不证明本项目账号已开通或效果改善。[重排序文档](https://help.aliyun.com/zh/model-studio/rerank) 新格式、Reader、模型加载或依赖变化需重新检查对应许可证、安全公告及实际运行路径，既有 S2/S1 依赖结论不是永久豁免。

完整顺序、条件项及回退规则见[实施方案](../knowledge-first-20261020/CAPABILITY_IMPLEMENTATION_PLAN.md)；本轮没有新增模型费用额度或生产部署授权。

## T32 结构化解析选型（S9A，2026-10-24）

选择Knowledge持有原文件和解析作业、一次性Java子进程集成 **PDFBox3.0.8 / POI6.0.0（XWPF）**；Java21兼容已用实际制品编译/运行受控样本。PDFBox/POI为Apache-2.0。pypdf6.21.2/python-docx1.2.0也通过同组样本，观测成本更低，但本期保持原架构的Java解析归属；不把二进制再次送给AI运行时解析。Tika通用格式包本期不引入。

选择依据、其它候选、直接许可证、17个解析JAR及约23.87MB成本、一次进程时间/RSS和新POI主版本风险见 [解析设计](../knowledge-first-20261020/PARSING_DESIGN.md)与[实际制品证据](../../qa/structured-parsing-20261024/research-evidence.json)。资源数字仅小样本观察，JVM堆限制不是总RSS硬隔离。完整制品/日志桥/传递依赖在S9B/C进入产品前锁定并验证，不扩用POI的渲染、宏或PDFBox附件提取。

引入阶段：S9B启用PDF，S9C启用DOCX；S9A只用隔离探针，项目POM/uv.lock和正在运行的服务未变。LlamaIndex0.14.25、Embedding v4/1024及原预算沿用；新[P-v2](../knowledge-first-20261020/STRUCTURED_PROCESSING_CONTRACTS.md)明确旧任务和新profile共存，不自动重算TXT/MD。

2026-10-25产品实施：S9B/C已将PDFBox3.0.8、POI6.0.0纳入 `knowledge-parser`，POI日志经根POM锁定的 `log4j-to-slf4j` 进入原stderr日志链；不引入Log4j Core。XMLBeans5.4.1、Commons Compress1.28.0/IO2.22.0/Collections4.6.0及仅parser覆盖的Lang3.21.0随固定制品核验，实际依赖、许可证与JAR摘要见[S9C制品证据](../../qa/docx-ingestion-20261025/artifacts.json)。V8与DOCX来源消费者是开启受理的前置，默认开关false；无新Python包、模型、数据库或预算。

S9C传递依赖补丁：Boot3.5.16默认Commons Lang3.17.0覆盖了Compress所需3.18.0，处于CVE-2025-48924影响范围；仅knowledge-parser覆盖到已核验3.21.0，避免新引入链路携带已知问题。官方公告/版本来源及最终制品见[S9C研究记录](../../qa/docx-ingestion-20261025/research-evidence.json)，原服务依赖不随此模块属性改变。
