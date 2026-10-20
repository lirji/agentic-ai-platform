# 平台技术与组件选型

日期：2026-10-20。适用范围：新项目 `agentic-ai-platform` 的整体选型，参考既有 Java/Python 平台资产。目标架构见 [BACKEND_ARCHITECTURE](BACKEND_ARCHITECTURE.md)。

**选型主线：Python 3.12 + LangGraph + Deep Agents + LlamaIndex，复用 Java 领域服务、LiteLLM、MySQL、Qdrant、Elasticsearch、S3 与既有 IAM；为图检查点复用 PostgreSQL 实例中的独立数据库。**

用户已选定三个主框架，并指定文档迁入新项目。本文件重点决定组合方式、持久化、基础组件及迁移边界。表中“选择”是设计建议；“已有/当前/沿用”指参考项目及拟复用资产，不表示新项目已安装或接入这些组件。当前版本依据为 Java `468cec4`、Python `7738cb7`；官方资料核对日期同上。

## 1. 主框架与运行方式

| ID / 决策 | 当前/约束 | 候选与选择 | 理由与取舍 | 引入阶段 |
| --- | --- | --- | --- | --- |
| T01 AI 执行 | 用户指定 LangGraph；当前 AgentScope | **LangGraph**；保留自研调度/AgentScope 仅作迁移兼容 | 统一图状态、分支、检查点与恢复；需要对接中央任务和 fencing，不能直接复用旧 DAG 序列化 | P0–P2 |
| T02 复杂 Agent | 用户指定 Deep Agents | **Deep Agents**；纯 StateGraph 用于确定性短流程 | 复用子 Agent、上下文和工作文件能力；限制内置工具，显式启用规划，控制额外模型开销 | P1 |
| T03 RAG | 用户指定 LlamaIndex | **LlamaIndex Core + 按需集成**；不再新建 LangChain RAG 主线 | 接管知识转换与检索编排；初期用受授权 Java Retriever，后续移交查询/准备算法 | P1/P3 |
| T04 Python 服务 | 参考项目 FastAPI/Pydantic/HTTPX/uv | **新项目采用 Python 3.12 + FastAPI/Pydantic/HTTPX/uv**；借鉴现有服务模式 | 独立依赖与运行版本；不与 AgentScope 全套依赖混装；新图状态、数据库连接和 async 生命周期需验证 | P0 |
| T05 模型出口 | 现有 LiteLLM | **继续统一 LiteLLM**；直接厂商 SDK、增加第二网关均暂缓 | 复用路由、凭据与归因；OpenAI-compatible 并不保证全部模型参数、工具和流式语义兼容 | 已存在/P0 |
| T06 AI 部署 | 现有 Python inline / api-worker 与中央任务 | **自托管库 + 现有 API/worker 模式**；托管 Agent 平台备选 | 控制内部身份、数据和 Java 契约；承担运行适配、恢复、运维与升级成本 | P0/P2 |
| T07 旧 AI 技术 | AgentScope 和 LangChain4j 已在使用 | **迁移兼容与回退基线** | 新 AI 用例优先三框架；按能力移交普通 Chat/RAG 后减少旧依赖，避免长期双主线 | P1–P4 |

Deep Agents 构建在 LangChain Agent 基础之上，使用 LangGraph 执行；LangChain 是必要依赖，不需要再设计一个同级“LangChain 服务”。LlamaIndex 的 Agent/Workflow 不作为顶层执行器，以免叠加调度、恢复和预算。[Deep Agents 官方架构说明](https://docs.langchain.com/oss/python/deepagents/overview)

对于固定步骤和单次检索，直接使用 LangGraph 节点和 LlamaIndex 即可；复杂研究、资料汇总、分析规划才使用 Deep Agents。框架选择不要求每个 HTTP 请求都经过多 Agent 循环。

## 2. 候选版本与已做验证

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

**没有安装依赖、修改现有锁文件、import 运行框架、连接数据库或调用模型。** 不等于与当前 AgentScope 全套依赖可以混装，不等于流式输出、工具执行或恢复兼容，也未验证 Linux ARM64/本机 ARM64。P0 应在独立运行环境生成带哈希的正式锁文件，验证许可证、漏洞、目标 CPU/镜像和真实运行，再冻结部署制品。

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
| T09 中央任务 | 现有 MySQL 任务服务 / 新调度平台 | **复用 async-task-service** | 保留任务、取消、租约、持久事件和 webhook；图状态另归 T08，必须验证跨库完成报告和 fencing | 已存在/P2 |
| T10 Agent 工作文件 | StateBackend / StoreBackend / 宿主文件系统 | **StateBackend 优先，必要时 CompositeBackend 接受控适配器** | 大结果存 S3 引用，限制 checkpoint 大小；不默认安装共享可写目录或额外用户记忆库 | P1 |
| T11 用户长期记忆 | 既有治理 API / 新 PostgresStore 画像 | **沿用受治理画像 API** | 避免双写权威；跨线程 Agent 工作资料将来可单独用 StoreBackend，不冒充业务画像 | 已存在/P1 |
| T12 Java 业务数据库 | 当前 MySQL 8.4 / 全迁 PostgreSQL | **继续 MySQL 8.4** | AI 图状态使用 PG 不构成业务表迁库理由；现有 SQL、Flowable、数据迁移保持兼容 | 已存在 |
| T13 Java 持久化 | 已有 MyBatis-Plus/XML/Flyway | **沿用** | 自定义 SQL 放 XML；新表和字段有注释，迁移不改历史。Python Checkpointer 使用自身适配，不强行套 Java ORM | 已存在/P0 |
| T14 向量检索 | 当前 Qdrant / pgvector / ES 向量 | **保留 Qdrant** | 降低现有集合/过滤语义迁移面；pgvector/单 ES 可在明确缩减运维成本时做对照，不同时更换框架与检索引擎 | 已存在/P3 |
| T15 关键词检索 | ES 8.15.3 + smartcn / 数据库全文 / 只保留向量 | **保留 Elasticsearch BM25** | 复用既有中文精确词检索与混排证据；LlamaIndex 接管编排不自动保证数据库全文或向量检索等价 | 已存在/P3 |
| T16 原文/派生对象 | 已有 S3-compatible / 本地磁盘 | **复用现有对象存储** | 原文不可变；Agent 附件和派生结果独立前缀/权限；不公开 bucket，不让框架直接持有全桶权限 | 已存在/P0 |
| T17 缓存与 Registry | 当前 Redis 7 / 新 L1+L2 / 无缓存 | **沿用既有 Redis 职责** | Registry 不是可随意丢弃的普通缓存；新 RAG 响应缓存需绑定权限、文档版本、模型版本，暂不增加多级缓存 | 已存在 |
| T18 可靠消息 | 现有 Kafka/Outbox / RabbitMQ / 新工作队列 | **按已有事件链复用 Kafka 和 Outbox** | LangGraph 节点和子 Agent 不全部发 MQ；不新增 RabbitMQ/Celery 来重复中央任务与图调度 | 已存在 |
| T19 业务审批 | 现有 Flowable 7.1.0 / 图 interrupt | **Flowable 保持业务流程权威** | LangGraph interrupt 只是 AI 执行暂停，不能替代业务审批记录和事务；不增加 Temporal/Camunda | 已存在 |
| T20 文档解析/OCR | 已有 Tika/Tesseract / LlamaIndex readers / LlamaParse | **先复用已验证解析器，LlamaIndex 管转换** | Reader 按格式补入；复杂版面质量不足才单独评估云解析、成本与数据出境边界 | 已存在/P3 |

T08 采用官方数据库 Checkpointer 能减少自研存储协议工作，但框架不负责本平台中央租约 fencing。[官方数据库示例](https://docs.langchain.com/oss/python/langgraph/add-memory)

T14 的候选均具备技术可行性：Qdrant 有[过滤能力](https://qdrant.tech/documentation/search/filtering/)，pgvector 提供[PostgreSQL 向量检索](https://github.com/pgvector/pgvector)，Elasticsearch 提供[混合检索](https://www.elastic.co/docs/solutions/search/hybrid-search)。**保留当前双检索引擎是基于已有集成与迁移成本的工程判断，不是性能排名。** 未开展三者基准测试；具体 ES 功能还须核对所选版本和许可，不能把当前官网能力全部套到 8.15.3。

`llama-index-vector-stores-qdrant 0.10.4` 已读取发布元数据，但不包含在上述 113 包解析集合中，也不在 P1 开启直接索引写入。P3 默认仍走 Knowledge 提交边界；确有直接读连接需求再单独验证过滤、数据格式和授权能力。

## 4. 网关、治理、前端与工程组件

| ID / 能力 | 选择 | 对比、边界与引入条件 |
| --- | --- | --- |
| T21 业务入口 | 沿用 Spring Cloud Gateway | 与 LiteLLM 分工：一个处理业务入口/身份，一个处理模型出口；不让 Python 内重复实现完整业务网关 |
| T22 认证授权 | 沿用 Casdoor + auth-platform + 内部 JWT；文档权限按目标启用 ReBAC | 不更换 Keycloak 或再建 SSO。当前 edge 使用自定义 GlobalFilter + Spring Security JOSE，不能写成完整 Resource Server 已接入；后者若迁移需单独验证 CORS/401/旧认证行为 |
| T23 配置/发现 | 当前环境配置 + 可选 Config Server + 部署 DNS | Nacos Config/Discovery 分别按动态配置、多实例发现需求评估；既有寻址可用时不再叠加注册中心，不形成同键双配置权威 |
| T24 调用保护 | 保留 HTTPX/Java 现有超时、连接池及隔离；按缺口选择 Resilience4j | 不全链统一加多层重试；先定义总 deadline、幂等和依赖预算；Python 不套 Java 组件 |
| T25 业务定时任务 | 有新业务定时需求时采用既定 XXL-JOB | 本次没有新业务周期任务，不新增调度器；框架心跳/租约维护和检查点机制不能机械搬到 XXL-JOB |
| T26 状态机 | 现有状态模型；新简单流程用稳定 code/迁移表，复杂生命周期按需评估 COLA | LangGraph 图状态、Flowable 流程状态、领域状态各归其主；COLA 不取代图运行时或数据库条件更新 |
| T27 可观测/评测 | OTel + 现有 Prometheus/采集端；复用固定 RAG/Agent 评测资产 | LangSmith 可选作独立诊断工具，须评估数据与费用，不作为三框架运行硬依赖；不同时新建多套观测平台 |
| T28 API 与协议 | HTTP/JSON/SSE；沿用 MCP/A2A 边界 | 不引入 gRPC/GraphQL 作为此次框架迁移前置；SDK 事件只在内部使用，对外发布语言中立版本契约 |
| T29 前端 | 首期可复用现有 Vue 门户/API 客户端；新建独立管理后台默认 React + TypeScript + Vite + Ant Design | 复用与新建分别处理；React Router、TanStack Query、Axios、Ant Design Form/主题、CSS Modules、pnpm 为默认配套，Zustand 按需；具体版本与前端交付范围待单独设计，不迁改旧 Vue 项目 |
| T30 部署 | 本地 Compose + dev_infra；按部署需求参考既有 Helm 能力 | 新项目尚无部署文件；共享实例落实库/账号/前缀与资源隔离，当前命名隔离不等于生产 ACL；不将选型解释为立即上 K8s |
| T31 工程验证 | Python pytest/Ruff/mypy + Java 现有测试 + 契约一致性 | 增加与真实风险对应的检查点、恢复、租户和版本共存测试；不改现有 Coding Agent 工具或触发真实模型 benchmark |

基础设施与版本事实来自 [dev_infra 说明](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/deploy/dev-infra/README.md)、[Compose](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/deploy/docker-compose.yml)、[edge POM](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/edge-gateway/pom.xml)、[共享观测模块](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/platform-observability/pom.xml)、[前端依赖](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/capability-showcase-frontend/package.json)。现有配置不等于本次已验证服务运行版本。

## 5. 模型与检索策略选择

框架主线已经确定，模型按角色选择，复用 LiteLLM 的逻辑模型名与凭据机制。[LiteLLM 官方入口](https://docs.litellm.ai/docs/proxy/quick_start)

| 角色 | 本次建议 | 冻结具体模型前的证据 |
| --- | --- | --- |
| 主 Agent/规划 | 先用现有可用且支持工具调用的主模型作为迁移对照 | 工具选择、参数 schema、长任务成功率、循环/幻觉、模型接口兼容 |
| 摘要/轻任务 | 在语义相同前提下单独配置低成本模型 | 摘要事实保真、长上下文压缩和总调用成本；不能只比较单 token 单价 |
| Embedding | 迁移框架时先保持同模型、维度和规范化 | 中文/业务语料 Recall@k、过滤后召回、向量维度、旧索引共存 |
| Rerank | 先保留当前模型与排序策略，再单变量对照 | nDCG/MRR、引用质量、P95、超时回退；避免 Java 与 LlamaIndex 重复 rerank |
| 视觉/语音 | 现有领域适配继续提供能力 | 本次不同时切换 ASR/TTS/视觉供应商，后续按真实缺口评估 |

真实业务模型型号、数据使用条件、峰值额度和预算仍待确认。本次不凭公开榜单承诺某型号更好，也不运行计费模型。Hash embedding 只能用于管道/契约测试，不能作为语义检索质量证据。

## 6. 复杂度预算与获取方式

| 能力 | 获取方式 | 为什么需要/不采用更简单方案 | 新增成本与退出方式 |
| --- | --- | --- | --- |
| LangGraph/Deep Agents/LlamaIndex | INTEGRATE | 用户已确定 AI 主框架；继续自研会保留原调度/上下文维护负担 | 适配与升级、113 包候选依赖面；框架类型限制在内部，公开协议独立 |
| 图持久化 | REUSE_EXISTING 基础设施 + INTEGRATE Checkpointer | 仅内存不能支持多进程恢复；自研 MySQL Saver 增加存储语义维护 | 新 PG 逻辑库、备份、迁移、连接预算；保留版本绑定，替换需显式状态迁移 |
| 中央任务和 Java 工具 | REUSE_EXISTING + BUILD 跨运行时适配 | 已有任务/权限/副作用能力；新框架不具备本平台业务约束 | fencing、终态对账、事件映射；不建立第二任务后台 |
| RAG 连接与准备协议 | BUILD 小范围领域适配 | 通用 vector store 插件不能表达现有内容去重、发布版本与外部授权 | Retriever/准备 DTO/提交协议与回归；存储仍由 Knowledge 管理 |
| 检索、对象存储、身份、流程、观测 | REUSE_EXISTING | 已有明确职责和接入证据，无本次替换收益 | 保留原运维成本；新增流量先计入共享容量 |
| LlamaParse/LlamaCloud、托管 Agent 平台 | DEFER | 当前无必须采购或向外发送文档的需求 | 有真实格式/运维缺口后评估价格、数据边界、许可和退出能力 |
| 新 MQ、图数据库、专用记忆平台、第二配置中心 | DEFER | 当前要求未证明必须新增 | 写清触发问题后再选，不为架构图完整而引入 |

新框架不能替代业务知识与数据质量建设。优先自研领域契约、授权与可恢复交接，通用推理/检索组件采用集成。

## 7. 现有版本与维护策略

| 项目 | 本次判断 | 后续动作 |
| --- | --- | --- |
| Java 21 + Boot 3.3.5 / Cloud 2023.0.3 | 当前兼容基线；不是新生产维护承诺 | 单独建立 Java 依赖维护切片，不与三框架迁移一次性混改 |
| 个人偏好的 Boot 3.5 / Cloud 2025.0 | 版本配套成立，但不能直接当作当前受支持长期目标 | 本次 Spring Cloud 官网已将 2025.0 标记结束支持；若保留该路线需确定实际补丁来源，否则评估受支持 Boot 4/Cloud 2025.1 组合及全套依赖兼容 |
| LangChain4j 1.13.1 | 旧链路兼容依赖 | 仅为安全/兼容需要升级，不为替换中的旧主线继续扩建 agentic |
| 基础 Compose Qdrant `latest` | 构建不可复现风险 | 固定经核验版本/digest；共享 dev_infra 的实际 digest 另核对，不推断已运行 `latest` |
| ES 8.15.3、Kafka 3.8、Redis 7、LiteLLM 既有镜像 | 记录现状，未做完整生命周期/漏洞认证 | 根据实际镜像/SBOM/客户端与备份恢复证据制定维护窗口，不按名称直接判兼容 |
| 新三框架依赖 | 候选精确版本已解析，运行与供应链检查未完成 | P0 验证后生成正式锁与镜像；任何升级重跑受影响的契约/恢复/安全检查 |

Boot/Cloud 配套与支持状态采用 [Spring Cloud 当前项目页](https://spring.io/projects/spring-cloud/)；其较早更新的 [GitHub 兼容表](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions)仍有不同支持标记，因此不使用历史表格推导今天仍受支持。生产补丁来源需在实施时确认。

Spring AI 不再作为本项目主线替代候选；它的 1.1/2.x 分别绑定 Boot 3.5/4.x，也不能直接混入当前 Boot 3.3.5。保留该核对仅解释此前讨论的兼容限制。[Spring AI 官方版本关系](https://github.com/spring-projects/spring-ai/blob/main/README.md)

## 8. 设计后的验证清单

按架构文档 P0→P4 推进，先解决两个关键接缝：**中央租约与 LangGraph 检查点；Java 文档治理与 LlamaIndex Node/准备结果**。

| 验证 | 通过依据 | 当前状态 |
| --- | --- | --- |
| Python 3.12/Linux 候选依赖解析 | 固定直接依赖可解析，无源码构建 | 已通过，113 包；仅元数据层 |
| 框架与模型接口 | import、假模型、结构化工具、流式、取消、预算；再做获授权真实模型对照 | 未执行 |
| LangGraph 持久化 | 真实 PG 进程退出、重复 resume、丢租约旧 worker、终态写回未知结果 | 未执行，上线前置 |
| RAG 迁移 | 同模型/语料对照；租户/文档授权、撤权、引用、去重、处理版本、索引回滚 | 未执行 |
| 内置工具与记忆 | 子 Agent、filesystem、Skills、checkpoint、跨线程读写和审批绕过用例 | 未执行 |
| 性能与成本 | 先确认峰值/SLA/预算，再测总调用链、共享 DB 连接与下游额度 | 目标值待确认 |
| 发布与回退 | 新旧任务按运行版本分流；旧任务排空；新状态不交给旧引擎解释 | 未执行 |

本次完成的是选型方案、源码/官方依据核对和候选依赖可解性检查；没有实现三框架集成，也没有给出生产可用结论。
