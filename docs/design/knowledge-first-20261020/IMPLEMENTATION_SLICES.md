# 首批实施切片与后续依赖

依据：[BRIEF](BRIEF.md)、[架构](../platform-selection-20261020/BACKEND_ARCHITECTURE.md)、[前端设计](FRONTEND_ARCHITECTURE.md)、[CONTRACTS](CONTRACTS.md)。用户已授权完成准备后继续实施并开始 Coding。

本批目标：落实已确认准备基线，完成前端设计及契约分界，交付首个可验证代码切片，进入真实工程实现。知识平台的完整业务范围继续按 P1–P4 推进，不以首片完成宣称整个平台完成。

## 一致性审查

2026-10-20 人工检查结果：**PASS（仅 S0）**。独立项目、React 新建、知识优先、Java 数据所有权和真实 Embedding 方向一致；R01/H01 定义完整，可独立实现。S0 不修改业务 schema、不访问旧业务服务、不建设第二个数据权威，不需要前端仓库或模型凭据才能验证其协议与配置行为。

S1 已追加知识受理/查询的一致性审查与冻结契约，见下文。处理/发布与 Q/T 的后续细节仍需冻结，不能由实现自行猜测。公共 Runtime 因全局技能漂移未启动，人工审查不冒充机器 Gate。

## 可执行切片

| ID | 可观察结果 | Needs | Owner/允许路径 | 契约/迁移归属 | 并行 | 验收 | Runtime | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| S0 | 开发者可启动 AI 运行时存活端点、验证真实模型配置；内部 Embedding 客户端正确分批并拒绝错误响应及 hash 配置 | D0 文档基线已交付；R01/H01 已冻结 | backend：`ai-runtime/`，根 `.gitignore`；必要 CI 与文档由对应 Owner 同步 | R01/H01；无数据库迁移 | no；首次定义共享运行入口与错误目录 | 下列 S0-AC 全部满足 | Python 3.12、HTTPX、FastAPI；本片不启动数据库/模型服务 | DONE |
| S1 | API 客户端通过独立网关鉴权提交资料，原文与受理状态真实持久化，并能分页查询自己的资料与任务 | S0；KNOWLEDGE_CONTRACTS K01/K02/K03 查询与身份边界 | backend：根 Maven、platform-contracts/security、knowledge-service、edge-gateway；runtime：deploy；doc：当前设计/验证/进度 | Knowledge 独占本项目数据库 V1、S3 桶；不导入旧数据 | no；首次共享安全/迁移与 Runtime | 下列 S1-AC | JDK 21、独立 IAM/内部密钥、dev-infra MySQL/S3 | DONE |
| S2 | Knowledge 客户端经独立服务身份调用 Python，取得有来源定位和真实向量的版本化准备结果及查询向量 | S0/S1；PROCESSING_CONTRACTS P01/P02；模型账号/地域/1 元授权已确认 | backend：ai-runtime；runtime：deploy 本地 AI 启动/私密配置；docs：既有设计/QA | Python 只计算准备结果，不写 Knowledge/检索存储；无业务迁移 | no；首次跨语言准备契约 | 下列 S2-AC | 已选三框架、PyJWT RSA 验签、真实百炼；服务密钥独立 | DONE |
| S3 | 上传资料经持久 worker、真实准备及双索引发布为 READY；所有者可检索、引用及按原输入重试 | S2；PROCESSING_CONTRACTS S3 与 K03/K04 | backend：knowledge-service、必要 API；ai-runtime 仅本机预算护栏；runtime：deploy 独立搜索与验证；docs：权威设计/QA | Knowledge V2/V3、准备对象、双索引；不修改已应用迁移 | no | 下列 S3-AC | 现有 MySQL/S3/AI；独立 Qdrant 1.19.2、ES 8.19.23 smartcn | DONE |

### S5B：PG 检查点与中央恢复（DONE）

- 依赖 S5A DONE；边界见 [CHECKPOINT_CONTRACTS](CHECKPOINT_CONTRACTS.md)，150 项 Python（含 11 项真实集成）和质量/构建检查通过，见 [TEST_RESULT](../../qa/ai-checkpoint-recovery-20261021/TEST_RESULT.md)。这是原 S5 问答路线中的恢复前置，后续会话/模型/UI 继续按各自正式契约推进。
- 允许路径：ai-runtime 受控任务客户端、Checkpointer、配置/测试/必要驱动 extra；deploy 项目 PG 初始化及平台 SQL；设计/QA。不改 S5A 已应用 V1，不迁移旧业务数据，不启用真实聊天。
- 人工一致性检查：中央仍为唯一租约/结果权威；PG 只保存图执行快照，孤立写不可见于恢复。官方 saver 原生结构保留，支持范围与未覆盖子图/DeltaChannel 显式拒绝。
- 验收：CHECKPOINT_CONTRACTS 末节全部真实跨库、并发、指纹、未知响应和权限项目；Python 锁/类型/格式/测试、Code Hygiene、文档/Git。
- Runtime：现有中央任务 + 独立项目 PG16 逻辑库，4 连接有界池；当前使用受控测试图，无付费模型、常驻新 worker 或前端改动。

### S5A：持久 AI 任务（DONE）

- 依赖 S4 DONE；[T01](TASK_CONTRACTS.md) 已完成中央任务部分的一致性审查。S5A 已通过真实验收，见 [TEST_RESULT](../../qa/durable-ai-tasks-20261020/TEST_RESULT.md)；PG Checkpointer 由 S5B 验收，问答继续后续切片。
- 允许路径：async-task-service、必要 platform-security/edge-gateway 路由、根 Maven；runtime 的 deploy/task_local.py、独立 MySQL dev/test；当前设计和 QA。保留知识服务已应用迁移与既有数据。
- API 由 T01 冻结；中央 MySQL 短事务 + Mapper XML，不引入第二执行图、内存权威、MQ 或未用的调度平台。
- 验收：T01 末节真实数据库/HTTP/重启项目，以及 Maven 构建、必要单元/集成测试、Code Hygiene、文档与 Git 交付。中央凭据只是联合 fencing 的一部分，完整 PG 恢复在 S5B 验证。
- 运行：JDK 21 / Boot 3.5.16；独立 28087 / 管理 29087；共享 MySQL 8.4 中本项目 task dev/test 数据库。S5A 不发模型请求。

### S4：React 知识工作台（DONE）

- 目标：用户在独立 React 工作台完成真实资料分页、上传、状态/失败重试、已发布内容检索和授权引用核对。依赖 S3 DONE；本片不冒充 Q 聊天回答。
- 契约：K01–K04 原样消费；仅有 cursor/limit 分页，不伪造名称/状态服务端筛选、总数、整篇原文下载或取消 API。详情显示任务真实阶段与摘要元数据，检索入口打开本资料的引用片段。
- 路径：独立 `/Users/liruijun/personal/LLM/agentic-ai-platform-web` 仓库，任务分支 `feat/knowledge-workbench-20261020`；后端只维护当前切片、前端架构/运行约定和交付记录，产品接口不变。
- Pass：前端实现（src/tests）；runtime（Vite loopback 开发身份桥与运行文档）；独立 QA（真实浏览器 + 契约故障状态）；文档/进度/Git。无并行 Agent、无新业务数据库。
- 本机身份：开发入口限定 127.0.0.1:28086，用服务端读取已有测试私钥换发短时 JWT 后代理 Gateway，浏览器不接收 JWT/私钥；固定 local-dev / workbench-user，与 S3 验收资料隔离。开发桥拒绝非同源写入、非本机 Host 和非知识路径；默认构建不启用桥，生产 IAM 仍需独立接入。页面明确显示本地联调身份。
- 数据：少量合成资料经正式上传 API 写入真实存储；固定幂等键，可重跑但不清理已提交数据，沿用累计 Embedding 1 元预算。
- S4-AC1：React strict 类型/生产构建、ESLint/Prettier、直接相关 Vitest/RTL、Code Hygiene 通过；版本锁定且依赖兼容/许可证/公告可追溯。
- S4-AC2：真实浏览器上传→处理→READY→检索→引用→详情/返回，所有数据来自正式 API；游标页和返回上下文成立，202 不显示发布完成。
- S4-AC3：校验/网络失败保留输入及幂等键，未知响应可原键重放；409 刷新任务再操作，FAILED 只有 retryable 且 retryCount<3 才允许重试；请求取消、查询范围与缓存隔离。
- S4-AC4：loading/empty/error/403/404、服务不可用、窄屏/长内容/模态键盘焦点经过操作；1440/1024/414px 当前截图已实际查看。合成故障夹具仅在测试，不作为生产列表。
- S4-AC5：开发桥拒绝非法来源/Host/路径，密钥、令牌及正文不进 URL/构建/Git；运行说明、验证证据和独立仓库交付完成。


### S3 有限验收

人工一致性检查：Java 继续拥有任务/索引/发布，Python 只准备；既有 K03/K04 补齐恢复语义。验证使用项目独立资源，新增组件限已批准搜索能力。

1. **S3-AC1**：真实 MySQL 并发领取、租约接管、旧 owner 拒绝、条件重试与双表发布原子性成立；V1 不变，V2 全部列/表有注释。
2. **S3-AC2**：跨语言准备逐项重算；原文或向量/摘要篡改拒绝。已持久准备包跨进程恢复复用，不重复消耗模型。
3. **S3-AC3**：真实 Qdrant/ES 写入和搜索；单 sink 故障不发布，恢复不重做已完成 sink，Bulk 部分失败和未知提交可重放。
4. **S3-AC4**：真实网关受理→处理→READY→检索→引用闭环；跨用户/租户/未发布/旧版本不泄露，依赖失败不是假空结果。
5. **S3-AC5**：并发/响应大小/期限/退出有界，重启可恢复；模型账本延续，超预算和损坏拒绝发送，累计不超过用户授权。
6. **S3-AC6**：Java/Python 构建和测试、真实链路/故障、Code Hygiene、依赖/文档检查通过，进度及 Git 交付可追溯。

### S2 有限验收

人工一致性检查：沿已批准 Python AI/Java 数据边界细化 P01/P02；复用 R01，原 H01 默认行为兼容。PyJWT 仅补充该接口 RSA 验签，不新增身份服务。框架版本沿候选主线，正式锁及本机运行、许可证/漏洞在本片验证。

1. **S2-AC1**：三框架在正式锁中共同安装/导入；LangGraph 固定处理图、LlamaIndex 实际分块、Deep Agents 工具/流式调用由独立测试验证。测试替身不进入产品配置；PG 恢复与真实聊天不计入本片。
2. **S2-AC2**：P01/P02 拒绝伪签名、过期/错误 issuer/audience/azp/scope、身份不匹配；未知字段/错误类型/错误哈希/超大正文被拒绝，错误及日志不含输入和秘密。
3. **S2-AC3**：中文/英文/重复段落/Unicode/CRLF 原文的分块位置、稳定 ID、摘要可复现；块数、向量数量/维度/有限值严格校验，失败不截断或 fallback。
4. **S2-AC4**：并发超限立即拒绝，总期限与取消生效，失败后容量归还；请求状态互不串用，框架不上传原文观测。
5. **S2-AC5**：在授权地域用少量合成文本完成真实 1024 维准备/查询验证；本轮费用预留与已知用量记录不超 1 元；真实 HTTP 认证端点验证成功和拒绝路径。
6. **S2-AC6**：Ruff/mypy/pytest/构建、依赖与代码卫生检查、源码摘要/文档同步通过；明确准备成功不等于索引发布完成。

验证：[S2 TEST_RESULT](../../qa/knowledge-processing-20261020/TEST_RESULT.md)。103 项测试、真实准备/查询与本地 HTTP 通过；NLTK 当前路径适用性、工具发现限制、PG/生产未验证范围明确保留。

### S0 有限验收

1. **S0-AC1**：Python 3.12 独立项目可安装、构建；src layout、领域端口/适配器分离，相关中文注释和稳定错误目录齐备。
2. **S0-AC2**：默认 text-embedding-v4 / 1024 维；hash/未知提供方、缺凭据、非法地址和越界参数明确拒绝，错误与摘要不含密钥/输入值。
3. **S0-AC3**：多于 10 条文本正确拆批；索引乱序时恢复输入顺序；维度、数量、重复索引、非有限值、JSON 和上游错误严格处理。
4. **S0-AC4**：整次调用有总 deadline；失败不返回部分成功、不自动 hash 回退、不自动重试；取消不会被吞掉或继续后续批次。
5. **S0-AC5**：配置 CLI 返回正确退出码和安全摘要；真实本地 HTTP 进程的 `/health/live` 符合 H01，不宣称依赖就绪。
6. **S0-AC6**：格式、静态检查、类型、行为测试、构建、代码卫生和差异检查通过；验证结果绑定当前源码摘要并记录协议替身的证据边界。

本片先完成可运行代码与窄验证，再由独立验证 pass 对照上述验收检查；不用纯 Mock 宣称真实模型、数据持久化、框架恢复或用户业务已通过。

本片依赖范围：运行时 FastAPI、HTTPX、Pydantic、Uvicorn；构建 Hatchling；开发检查 Ruff、mypy、pytest。初始工程没有已有格式/测试配置，采用这些工具并锁定运行与开发解析结果；不引入第二套检查体系或尚未使用的 AI 框架。

验证：[S0 TEST_RESULT](../../qa/runtime-foundation-20261020/TEST_RESULT.md)。68 项测试、格式/静态/类型、构建和质量检查通过；工具配置发现限制与真实集成未验证范围保持显式记录。

### S1 有限验收

人工一致性检查：本片遵循已选 Java 数据所有权、独立 Gateway、MyBatis-Plus/XML、真实关系库/S3；本地验收使用签名测试身份，生产 IAM 映射保持未验证。所需契约完整，允许进入实现。

1. **S1-AC1**：JDK 21 独立 Maven 多模块编译/测试/打包；网关无业务服务/数据库依赖，知识领域不调用旧服务；中文注释、统一错误与稳定枚举齐备。
2. **S1-AC2**：真实网关验签并交换独立内部令牌；伪造内部头、缺身份、错误签名/issuer/audience/过期被拒绝；读/写 scope 与租户/所有者过滤生效。
3. **S1-AC3**：TXT/Markdown 验证与大小限制生效；原文写入项目独立 S3，文档/任务同一 MySQL 事务建立。返回 RECEIVED/ACCEPTED，publishedVersion=null。
4. **S1-AC4**：真实 MySQL 验证同键重放、同键异参、所有者内容去重、并发争用与事务完整性；存储失败不受理；同内容跨租户/用户不互相泄露。
5. **S1-AC5**：资料稳定游标列表、详情及任务查询符合契约；启动/重启后数据仍可查询；真实 socket smoke，readiness 绑定当前依赖，停止不清空存储。
6. **S1-AC6**：DDL 全部表/列有中文注释；应用/迁移账号分离；示例无凭据，工具只处理本项目资源；构建、测试、质量 Gate 与文档检查结果绑定当前版本。

S1 不包含处理 worker、向量发布、问答或页面；完成后继续细化下一必要切片，不能将 RECEIVED 当作知识入库终态。运行时 Owner 在本片首次配置共享 MySQL/S3 的独立资源与本机启停。

验收：[S1 TEST_RESULT](../../qa/knowledge-ingestion-20261020/TEST_RESULT.md)。26 项 Java、45 项真实 HTTP、5 项真实依赖故障检查通过；当前 Spring 公告的适用性、生产维护和工具限制分别记录，不宣称依赖零风险。

## 后续依赖路线

下表是需要进一步展开的业务工作，不是已经 READY 的实施切片；形成正式 schema 后由 implementation-slicing 分配稳定 S-ID，避免用大型阶段冒充一次 pass。

| 次序 | 后续结果 | 前置条件与首次运行依赖 |
| --- | --- | --- |
| 1 | 完成第一条知识路径的源代码依赖盘点、数据/权限与完整 K01–K04 契约 | Java 模块及迁移所有权明确；不照搬源项目所有模块 |
| 2 | 建立独立 Java/网关基础并交付文档登记、入库状态及授权读路径 | K 契约冻结；首次接入本项目 MySQL/S3 时由 runtime-and-deploy 同步 dev_infra 配置 |
| 3 | 真实 Embedding、Qdrant/ES 发布与可引用检索 | 单独核对模型账号/地域/预算；独立集合与版本，不混用 hash 数据；真实集成及失败恢复验证 |
| 4 | 创建独立 React 仓库，交付知识列表、上传和任务详情代表路径 | 已实现的 K 契约与可访问测试环境；截图查看和实际操作验收 |
| 5 | 冻结 Q/T 契约，接通 LangGraph/LlamaIndex 问答及 React 流式引用 | 三框架真实依赖安装/导入、权限、会话与任务所有权、PG 检查点和 MySQL 终态对账验证 |
| 6 | 研究分析及业务助手 | Deep Agents 工具边界、累计预算、多 worker 恢复/取消、报告制品与 UI；写工具另定契约 |
| 7 | 知识转换完善及 AgentScope 参考业务扩展 | 前述知识闭环验收，逐项业务需求和迁入边界明确 |

S1/S2/S3 已验收；处理与发布的历史准备见 [PROCESSING_READINESS](PROCESSING_READINESS.md)。Embedding 沿用原百炼配置，上限 1 元；聊天沿用原配置，另限 1 元。S4、S5A 与 S5B 已验收；下一步为问答执行，Q 契约冻结后继续。可并行集合：无；未授权多个 Agent 并行写入。前端仓库创建和真实模型调用在对应阶段落实，不由本片验收替代。
