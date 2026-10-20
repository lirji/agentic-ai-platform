# 独立知识平台：首期业务与工程约定

确认日期：2026-10-20。来源：用户在完成架构与组件选型后，对独立项目、前端、代码规范、业务优先级及参考资产作出的明确补充。

本文件记录当前需求基线。[后端架构](../platform-selection-20261020/BACKEND_ARCHITECTURE.md)与[组件选型](../platform-selection-20261020/TECH_SELECTION.md)继续作为唯一技术设计来源；[当前进度](PROGRESS_STATE.md)只记录实施与验证状态。

## 1. 已确认的决定

| ID | 用户决定 | 对后续工作的约束 |
| --- | --- | --- |
| B01 | `agentic-ai-platform` 是独立项目 | 独立构建、配置、数据命名空间与运行；所需业务能力纳入本项目，不能把旧项目的业务进程默认当作运行前提 |
| B02 | 需要的能力可以从 langchain 项目迁移 | 以 `langchain4j-platform` 为代码、领域规则、模型接入及数据资产来源，按当前功能需要迁入依赖闭包；保留来源提交和必要测试 |
| B03 | 前端使用 React，重新建设 | 遵循个人已确认的 React 配套和前后端分仓；旧 Vue 前端不作为代码基线，不迁入其页面、路由或构建工程 |
| B04 | 架构与组件已经选定 | 沿用 LangGraph、Deep Agents、LlamaIndex 及已有组件决策；版本可解性、运行兼容和生产维护状态分别验证 |
| B05 | 优先建设知识库系统 | 优先覆盖知识入库、知识问答、研究分析和业务助手；先完成真实业务闭环，不能只交付框架示例或空后台 |
| B06 | 后期业务参照 AgentScope | 从 `agentscope-platform` 提取业务用例、规则和验收场景，按新平台架构实现；不把 AgentScope 运行时恢复为目标主引擎 |
| B07 | 模型与数据借鉴原 langchain 项目 | 参考模型逻辑名、路由、参数、知识数据模型、样例和评测集；实际账号、权限、预算与数据导入另按新项目环境落实 |
| B08 | 代码结构按 SKILL 实现 | 按职责使用前后端架构、实现、切片、运行和验证技能；用户规范及已批准契约优先，不复制旧项目整个目录作为新结构 |
| B09 | 不使用默认 HashEmbedding，可采用 langchain 已有的其他 Embedding 模型 | 新平台默认真实语义 Embedding；配置缺失或模型失败时明确报错，不回退到 hash |

以上决定已确认。下文的闭环细化、工程落地顺序与首轮业务助手范围属于基于这些决定的工程建议；不将未给出的权限策略、预算数值或业务写操作规则标记为用户已批准。

## 2. 优先业务与可观察结果

| 能力 | 核心用户任务 | 首期验收方向 | 需要补齐的契约 |
| --- | --- | --- | --- |
| 知识入库 | 在知识库内上传、更新资料并查看处理结果 | 原文登记、解析、分块、索引与发布形成可查询版本；能区分上传成功、处理中、发布成功和失败；重复请求与重试不产生重复发布 | 知识库/文档/版本标识、上传与处理任务、权限、重复文件、状态与重试 |
| 知识问答 | 选择获授权知识范围，提问并核对引用 | 使用已发布且可访问的资料；支持连续提问、流式回答和来源定位；资料不足时如实反馈，越权文档不能进入上下文 | 会话、问题、检索范围、答案与引用、SSE、错误及取消 |
| 研究分析 | 基于多份资料提出研究任务并取得报告 | 展示真实任务状态、阶段结果与证据；能处理失败、取消及恢复；报告区分来源事实与生成分析 | 任务输入、进度事件、结果制品、来源版本、恢复与预算 |
| 业务助手 | 基于知识和受控工具完成业务查询、解释及任务辅助 | 建议首轮从资料查询、解释和草稿辅助开始；工具结果与引用可追溯，权限由服务端裁决 | 工具清单、输入输出、执行权限、结果语义；写操作另补批准、防重与未知结果处理 |

工程建议顺序为：知识入库与问答形成第一条端到端路径，再扩展研究分析和业务助手。四类能力均属于优先业务；先后顺序用于控制依赖，不把研究分析或业务助手移出目标范围。第一轮 UI 随对应 API 纵向交付，不留到所有后端完成之后。

后续 AgentScope 业务扩展按具体需求逐项登记。订单、退款、NL2SQL、多渠道、多模态及复杂审批等参考能力，不因旧平台存在就自动进入首期。

## 3. 独立性与资产迁入

独立运行的验收方向：在不启动、不访问两个旧项目业务服务的环境中，使用本项目交付物与明确登记的外部依赖，完成知识入库、问答和当前阶段的任务路径。首次验证无需停掉用户正在运行的旧服务，可通过隔离环境和依赖地址检查证明。

- **代码**：迁入所需领域服务、共享模块、迁移和测试，剔除与本阶段无关的功能依赖；AI 执行、RAG 编排和前端分别按本项目已选架构实现。
- **业务调用**：Python 通过版本化 HTTP 契约调用本项目拥有的 Java 领域服务；迁入来源不改变其在新项目中的数据所有权。
- **基础设施**：继续优先使用 `dev_infra`。复用实例时配置本项目的库、账号、对象前缀、检索集合/索引与权限；共享实例不等于共用旧业务数据。
- **身份与模型出口**：沿已选 IAM/Casdoor/LiteLLM 接入方式建设，逐项登记外部依赖、客户端配置与凭据引用；不能靠旧业务网关或旧服务进程隐式转发。
- **数据**：优先迁入结构、规则、经过核对的样例及评测资产。历史真实文档、会话、画像和在途任务暂未确认需要搬迁；不默认整库复制。
- **回退**：新项目回退到自己的已验证版本。旧项目可作隔离对照，只有明确建立并验证兼容关系后才可能成为迁移回退目标。

## 4. 代码组织与技能规则

| 范围 | 落地约定 | 对应技能 |
| --- | --- | --- |
| 后端仓库 | 当前仓库承载后端及共享契约；Java 按业务能力组织 Maven 多模块，服务独立构建；Python AI 运行时保持独立依赖与入口，API/worker 共用同一代码版本 | `backend-architecture-design`、`backend-implementation` |
| 服务内部 | API 接入、应用用例、领域规则、基础设施适配职责分离；领域层不依赖 HTTP、数据库或框架实现；逻辑模块不机械拆成独立进程 | `backend-architecture-design`、`backend-implementation/ddd-sdd.md` |
| AI 代码 | 沿用 `orchestration`、`agents`、`rag`、`adapters`、`api/worker` 的职责划分；框架 State/Node/Document 类型留在边界内，公开契约保持语言中立 | 已批准后端架构、`backend-implementation` |
| React 前端 | 独立 Git 仓库；React + TypeScript + Vite + Ant Design；路由、查询状态、HTTP、表单与样式沿个人已确认配套，Zustand 按需 | `frontend-architecture-design`、`frontend-implementation` |
| 前端模块 | 壳层、业务功能、共享 UI 和类型化 API 客户端分开；按知识库、问答、研究任务、业务助手组织，URL、服务端状态、表单和本地 UI 状态分别管理 | `frontend-architecture-design` |
| 持久化与数据 | Java 使用 MyBatis-Plus + Mapper XML；建表及字段写注释，迁移可版本化；演示数据写入数据库，页面从接口读取 | 用户规范、`backend-implementation` |
| 通用代码规则 | 中文原因注释、稳定状态 code、统一错误码及提示、配置化参数；按实际路径落实权限、幂等、取消与恢复 | 用户规范、前后端实现技能 |
| 交付 | 正式契约 → 有界纵向切片 → 实现 → 独立验证 → 进度 → Git；运行组件在首次需要时接入 | `implementation-slicing`、`implementation-validation`、`runtime-and-deploy`、`task-git-delivery` |

技能定义职责和依赖方向，具体目录见 [前端架构](FRONTEND_ARCHITECTURE.md)与[实施切片](IMPLEMENTATION_SLICES.md)。`ai-runtime/` 已实现基础 `rag`、`adapters`、`runtime`、`api` 和 CLI；S1 已建立 `platform-contracts`、`platform-security`、`knowledge-service`、`edge-gateway`。前端独立仓库尚未创建，不为无当前用例的能力建立空模块。

## 5. 已核对的参考资产

Java 来源固定为 `lirji/langchain4j-platform@468cec425dd160e80cfc3ab378d3440a8767e4d5`。下表保留前期只读资产核对结果；S1 已按[迁入清单](KNOWLEDGE_MIGRATION.md)独立实现所需规则并建立本项目存储资源。没有执行旧数据导入、复用旧业务服务或调用真实模型。

| 资产 | 已核对事实 | 迁入用途及限制 |
| --- | --- | --- |
| [LiteLLM 配置](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/deploy/litellm/config.yaml)、[dev_infra 配置](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/deploy/litellm/config.dev-infra.yaml) | 配置声明 `chat-default` 对应 `deepseek/deepseek-chat`，回退逻辑名为 `chat-default-fallback` | 可参考逻辑模型名与路由；这是仓库声明，不代表新环境可用或最终型号已冻结；密钥单独配置 |
| [Knowledge 配置](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/resources/application.yml)、[标准 Compose](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/deploy/docker-compose.yml) | 应用层默认为 hash，但标准 Compose 覆盖为 `openai` 协议的百炼 `text-embedding-v4`、1024 维；另有 Ollama 接入 | 新项目采用标准栈的真实语义路线，所有目标启动配置保持一致，不继承应用层和部分 split 配置的 hash 默认 |
| [知识领域代码](https://github.com/lirji/langchain4j-platform/tree/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service)、[数据迁移](https://github.com/lirji/langchain4j-platform/tree/468cec425dd160e80cfc3ab378d3440a8767e4d5/database-migrations/src/main/resources/db/migration/knowledge-ingestion) | 已有知识入库、版本、去重、检索与质量评测的代码和持久化定义 | 按新项目用例迁入所需数据模型、服务依赖和测试；发布/授权不变量保留，旧数据不自动导入 |
| [知识种子脚本](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/deploy/seed-kb.sh) | 存在知识数据初始化入口 | 迁入前核对新 API、租户和目标库；业务记录持久化，重跑和清理限定本次种子数据 |
| [RAG 问题集](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/docs/qa/resume-metrics-0727-1539/datasets/rag-golden-200.json)、[语料清单](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/docs/qa/resume-metrics-0727-1539/datasets/rag-corpus-manifest.json) | 200 条不同问题、20 条语料清单记录；字段包含问题和相关文档 ID | 作为初始回归资产；重新绑定新项目文档/版本/权限，不能直接套用旧结果或旧通过阈值 |
| [数据集生成方式](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/docs/qa/resume-metrics-0727-1539/scripts/prepare-datasets.mjs)、[原评测说明](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/docs/qa/resume-metrics-0727-1539/QA_REPORT.md) | 问题集基于项目文档生成并校验来源，属于合成评测资产 | 补充本项目真实业务问题、无答案及越权用例；本轮不运行原生成脚本或计费评测 |
| [通用冒烟基线](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/eval-service/src/main/resources/eval/baselines/platform-smoke.json)、[Agent 任务集](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/docs/qa/resume-metrics-0727-1539/datasets/agent-goals-20.json) | 分别为 1 条冒烟 case 和 20 条 Agent 目标 | 冒烟与业务质量分别验收；Agent 目标按知识业务相关性筛选，不因资产数量扩大首期范围 |

AgentScope 后续业务来源固定为 [platform-agentscope@7738cb7](https://github.com/lirji/platform-agentscope/tree/7738cb7bedd1a31fd4260aa66b74572ca48d9ade)。本轮确认参考方向，不把其全部场景登记为首期必须实现。

### Embedding 落地基线

用户指定使用真实模型、允许参考旧项目中的其他 Embedding；据其标准 Compose，选取**百炼 `text-embedding-v4`，1024 维，OpenAI 兼容接口**作为本项目默认。`provider=openai` 表示接口协议，不表示改用 OpenAI 厂商模型。该型号支持指定 1024 维，官方同步接口批量上限为 10 条；运行参数仍按实际账号、地域和接口核验。[官方模型规格](https://www.alibabacloud.com/help/en/model-studio/embedding)、[官方同步接口](https://www.alibabacloud.com/help/en/model-studio/text-embedding-synchronous-api)

- 参考配置键：`RAG_EMBEDDING_PROVIDER=openai`、`RAG_EMBEDDING_MODEL=text-embedding-v4`、`RAG_EMBEDDING_DIMENSIONS=1024`；单次请求分批不超过提供方限制。
- 参考旧栈的受控 OpenAI 兼容客户端接入百炼；基础地址和 API Key 按新环境独立配置。聊天与 Agent 的 LiteLLM 出口保持既定分工，不假定旧 LiteLLM 已配置 Embedding 路由。
- 本地业务运行、演示、Compose 和真实 RAG 验收均使用真实语义模型；不迁入 hash 作为业务提供方，不将缺凭据或调用失败处理为 hash 回退。纯单元测试可用明确的测试替身，不能据此声称真实检索质量通过。
- 入库与查询绑定相同模型、维度、预处理及索引版本。切换模型或维度时创建对应索引并重新向量化，通过对照后切换，不混用旧 hash 向量或不同模型的向量。
- 原项目还支持 Ollama `nomic-embed-text`，保留为明确配置的备选；启用前验证模型可用性、实际维度和检索质量，不自动切换。
- 当前已实现并验证真实协议客户端及配置拒绝，尚未配置真实凭据、请求模型或建索引；账号接入及质量验证仍纳入 P0/P1。

## 6. 后续准备顺序与剩余事项

1. 明确知识库、文档/版本、会话、研究任务和工具调用的业务规则；对照来源代码形成最小迁入依赖清单。
2. 新 React 前端的信息架构、首条任务路径、目录提案及页面状态已形成设计；前端仓库名称/远程位置在创建时落实。
3. R01/H01 已冻结；继续冻结第一条知识入库与问答路径 K/Q/T 的字段、状态、错误、鉴权、幂等、引用和 SSE。迁入旧 schema 时保留单一权威来源，明确兼容与变更项。
4. 已按 `implementation-slicing` 形成 S0；完整 P0 仍须覆盖三框架依赖、真实 Embedding、真实存储、恢复与权限，首个 UI 切片使用真实接口。
5. 依次完成知识闭环、研究分析与业务助手，再扩展 AgentScope 参考业务。阶段里程碑见后端架构第 9 节；它们不是已验收切片。

仍需按首个具体用例细化：文件格式/体积与语料规模、知识可见范围与角色、业务助手工具及写操作规则、实际模型额度与费用上限、质量/延迟目标、数据保留策略及目标运行环境。上述事项不改变本轮已确认的独立项目、React、知识优先和技术主线；先在设计中给出可审查的具体方案，再按所需业务信息补齐。
