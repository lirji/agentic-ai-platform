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

### S6：研究与只读助手执行（DONE）

- needs：S5D/S5B；Owner：backend/runtime；契约：[R02/A01](WORK_TASK_CONTRACTS.md)。
- 范围：Tasks 公共受理/列表/输入、V2 索引；Python 受控工具适配、Deep Agents 有界阶段与 root JSON 恢复；既有 worker 顺序领取新 graph。无新组件。
- 验收：契约受理/隔离/分页真实 MySQL 与 HTTP；工具白名单/预算/次数、真实 PG 恢复/取消/接管、最终引用撤权；真实模型研究和三种助手模式/空证据，旧 QA 回归。
- 验收证据：[S6/S7 TEST_RESULT](../../qa/research-readonly-20261021/TEST_RESULT.md)；292项Python、78项Java、真实模型五案例与重启零新增调用均PASS。
- 变更集：async-task-service、ai-runtime tasks/qa/agents、必要 deploy 脚本、测试与文档。独立受理/执行闭环先验证，再进入 S7；不并行 Agent 写入。

### S7：研究与只读助手界面（DONE）

- needs：S6；Owner：frontend；沿 [现有视觉与路由](FRONTEND_ARCHITECTURE.md) 与 R02/A01。
- 范围：`/research`、`/research/:taskId`、`/assistants`、`/assistants/tasks/:taskId`；复用身份/查询/错误/引用，真实请求/阶段/结果。
- 验收：范围选择、未知提交同键恢复、列表分页、阶段事件、刷新、取消/失败恢复、报告事实/推断、工具记录、草稿提示与引用；1440/1024/414px 实际截图和浏览器操作；共享问答/知识入口回归。
- 验收证据：前端 `docs/qa/s7/TEST_RESULT.md`；38项单元、15组浏览器及样式修正后7组复验PASS，三屏宽报告/草稿/引用及窄屏未知提交截图已实际核对。没有新runtime或第二组件体系。

### S5E：知识问答界面（DONE，Git 交付见当前进度）

- 依赖：S5C 已交付的 Q01/Q03/T01/K04；S5D 的 Q04 格式、K05 及恢复/事件消费者兼容检查 PASS。S5E 当时先完成无模型 UI 验收，后续 S5D 已补齐真实模型门禁。没有多 Agent 并行。
- 可观察结果：创建/分页会话、受理原问题、选择已发布知识范围、读取同一 taskId 的真实交接/排队/生成/终态，取消、允许的恢复、引用重新判权与详情返回。最终答案只使用 SUCCEEDED 的 Q04 result。
- 路径/Owner：frontend 为独立 web 仓库的 chat feature、ChatPage、必要导航/文档入口、共享引用与错误目录、测试；runtime 仅扩展既有 loopback 开发桥的精确 Q/T 路由和 scopes；docs 同步原权威文档。无新依赖、表或后端业务接口。
- 范围：每会话200轮上限，原问题分页读取后仅渲染当前20轮；任务读取随可见卡片，运行中的流使用有界批次/游标。默认关联已确认的同范围最近成功问题；前序状态无法确认时保留错误，可显式选择独立提问。
- 幂等：创建与提问保留提交编号/原命令；结果未知不自动另建命令。sessionStorage 只保存作用域、请求摘要、幂等键与原 expectedRevision，不保存问题/答案或凭据；确认后清理对应意图。更换输入遇到未知命令先要求核对历史，不能默默丢弃。
- S5E-AC1：strict 构建、ESLint/Prettier、类型/契约/状态与流边界测试、Code Hygiene；不放宽已有规范、不新增第二组件体系。
- S5E-AC2：真实 Gateway/Conversation/Tasks 创建→提问→交接/排队→取消→刷新保留原问题；合成测试任务及时取消，未启用真实 worker，不额外消耗模型预算。
- S5E-AC3：受控契约流验证运行/断流游标/重复事件/新代重置、最终答案/无证据、引用/权限失败、取消竞争、恢复条件与409刷新、未知写响应原键/原版本重放；明确这是模拟响应验收。
- S5E-AC4：复用浅色靛蓝 Ant Design。1440/1024/414px 主页面、创建表单、引用侧栏截图实际查看；键盘、焦点、长内容/错误、范围切换旧响应与引用详情返回有行为证据。当时未包含真实模型；后续 S5D 已补充两轮真实回答与引用 UI 验收。
- 验收：独立前端 `docs/qa/s5e/TEST_RESULT.md` PASS。28项单元/组件、10组真实/受控浏览器、构建/格式/静态与 Code Hygiene COMPLETE；身份/草稿调整后相关2组复验及截图再核对通过。真实任务 CANCELLED/attemptCount=0，没有新增模型调用。S5D 现已另行完成真实模型门禁。

### S5D：知识问答执行与引用（DONE）

- 依赖 S5C DONE；[Q 执行契约](QA_EXECUTION_CONTRACTS.md) 固定 K05 身份/读取、答案与引用、事件、费用和 worker 边界。2026-10-21 用户已明确批准 deepseek-flash，并更新 API key；独立 LiteLLM/真实问答验收已通过。
- 允许路径：Knowledge 内部只读入口/独立验签与相关测试；ai-runtime 问答图/LlamaIndex 检索适配、聊天适配器/预算、中央 events/fail 与最终结果回调；deploy 私密配置/独立 LiteLLM/worker 与验证；必要契约和文档。不得修改已应用迁移、直连其他业务库或新增外部写工具。
- 顺序：先 K05 和有界客户端，再图/预算/事件及受控模型验证；型号确认后落实独立模型出口与真实调用。无多 Agent 并行。
- 验收：[S5D TEST_RESULT](../../qa/knowledge-qa-execution-20261021/TEST_RESULT.md) PASS；原受控恢复与 K05 验证、真实代理边界、deepseek-flash 回答/追问/无证据、浏览器引用、重启重放保持额度均通过。Embedding 0.20 元/20 批，聊天 0.092600 元/4 次保守预留，非账单。S5E 已交付；研究助手按下一切片推进。

### S5C：知识会话与可靠任务交接（DONE）

- 依赖 S5B DONE；依据 [CONVERSATION_CONTRACTS](CONVERSATION_CONTRACTS.md) 的冻结 Q01/Q03 与 T01 兼容读取扩展，Q02 继续使用中央事件。当前唯一执行片，无并行 Agent。
- 可观察结果：API 客户端创建会话、可靠提交问题、读取原问题及 taskId，并将中央已成功的前序回答关联到下一轮；重启/未知返回不丢失或重复创建任务。未接通模型的任务如实显示等待状态。
- 允许路径：新 conversation-service；共享协议中的任务状态映射、platform-security audience、Gateway 路由；Tasks 增加受委托状态读取；deploy 项目专用资源/进程、相关测试及文档。原 Knowledge/Tasks/PG 已应用迁移不改。
- 一致性检查：Java 会话拥有原始问题/上下文关系，中央拥有结果和执行状态；本地账本与任务创建按稳定 ID/幂等键恢复，远程调用不在 DB 事务中；不引入另一套任务执行图或答案写权威。
- 验收：CONVERSATION_CONTRACTS 末节全部场景，必要 Java/共享协议与 Python 状态映射回归、Code Hygiene、文档和 Git。当前不含 UI 和模型质量，不以合成终态证明真实回答。
- Runtime：本项目 MySQL 两个逻辑库、28088/29088 会话进程、已有网关与中央任务；聊天模型替代仍待用户答复，费用为零。
- 验收通过：[S5C TEST_RESULT](../../qa/knowledge-conversations-20261021/TEST_RESULT.md)，45 项 Java、40 项真实 HTTP、2 项重启、21 项 Python 客户端与 11 项真实恢复回归；Code Hygiene 限制已列明。

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

## 历史：首期后续依赖路线

下表保留首期建设时的顺序，完成情况由上方 S0–S7 及历史验收记录说明。2026-10-21 后的新工作统一使用下方 S8–S18；本表不再作为另一份待办清单，也不能由表中阶段推定多 worker 或生产能力已完成。

| 次序 | 后续结果 | 前置条件与首次运行依赖 |
| --- | --- | --- |
| 1 | 完成第一条知识路径的源代码依赖盘点、数据/权限与完整 K01–K04 契约 | Java 模块及迁移所有权明确；不照搬源项目所有模块 |
| 2 | 建立独立 Java/网关基础并交付文档登记、入库状态及授权读路径 | K 契约冻结；首次接入本项目 MySQL/S3 时由 runtime-and-deploy 同步 dev_infra 配置 |
| 3 | 真实 Embedding、Qdrant/ES 发布与可引用检索 | 单独核对模型账号/地域/预算；独立集合与版本，不混用 hash 数据；真实集成及失败恢复验证 |
| 4 | 创建独立 React 仓库，交付知识列表、上传和任务详情代表路径 | 已实现的 K 契约与可访问测试环境；截图查看和实际操作验收 |
| 5 | 冻结 Q/T 契约，接通 LangGraph/LlamaIndex 问答及 React 流式引用 | 三框架真实依赖安装/导入、权限、会话与任务所有权、PG 检查点和 MySQL 终态对账验证 |
| 6 | 研究分析及业务助手 | Deep Agents 工具边界、累计预算、多 worker 恢复/取消、报告制品与 UI；写工具另定契约 |
| 7 | 知识转换完善及 AgentScope 参考业务扩展 | 前述知识闭环验收，逐项业务需求和迁入边界明确 |

S1/S2/S3 已验收；处理与发布的历史准备见 [PROCESSING_READINESS](PROCESSING_READINESS.md)。Embedding 沿用原百炼配置，上限 1 元；聊天沿用原配置，另限 1 元。S4、S5A、S5B、S5C 与 S5E 界面已验收；S5D deepseek-flash 真实问答与引用已验收。S6/S7研究与只读助手已按 [WORK_TASK_CONTRACTS](WORK_TASK_CONTRACTS.md) 实现并验收；[RESEARCH_READINESS](RESEARCH_READINESS.md)保留实施前核对。可并行集合：无；未授权多个 Agent 并行写入。

<a id="capability-depth-slices"></a>

## 能力深化任务（S8–S18）

2026-10-21 重新规划，依据 [20 项差距实施方案](CAPABILITY_IMPLEMENTATION_PLAN.md)。本节是新增任务 ID、依赖与状态的唯一清单；S0–S7 的历史完成证据保持不变。用户已于本轮要求“开始实施”，按以下依赖连续推进，当前状态见 PROGRESS_STATE。

共 **51 项任务**；状态随逐项验收更新，当前以索引和 PROGRESS_STATE 为准。这里包括设计、实现、工程和专项验证任务，不将大阶段伪装成一次实现 pass。每个 IMPLEMENT 任务必须先完成所列 C-工作包的必要正式契约；C-工作包本身不是批准的 JSON，详见 [契约清单](CAPABILITY_CONTRACT_BACKLOG.md)。尚缺业务/环境/费用条件时，只能完成不依赖该条件的准备，不把协议替身当真实验收。

本轮不安排并行 Agent。各任务 `parallel=no`；未来允许并行时仍须证明迁移、共享契约、权限、制品与运行资源不冲突。Dependencies 是先后约束，ID 数字不是执行排序；例如 S10A 设计须先于 S9D 的处理版本实现。

### Owner 与路径说明


| Owner | 影响路径与归属 |
| --- | --- |
| K | backend / knowledge-service：api、application、domain、infrastructure 与 resources/mapper、db/migration；拟新增业务包按能力组织 |
| A | AI / ai-runtime/src/agentic_ai：processing、qa、rag、agents 与 adapters；沿现有错误/模型/预算适配 |
| T | backend / async-task-service：任务 API/应用/Mapper；拟新增 usage 能力模块仍使用本服务自有数据库 |
| Q | backend / conversation-service：会话/交接；条件项拟新增 memory 治理能力包 |
| G | backend / edge-gateway、platform-security、platform-contracts；已选 IAM 的契约适配 |
| W | frontend / 独立 agentic-ai-platform-web：src/pages、src/features、src/app；新增页面/feature 为计划路径，复用现有 UI 与 API 机制 |
| R | runtime / deploy、各服务构建与私密配置引用；共享基础设施优先 dev_infra |
| C | engineering / 两仓 .github/workflows（拟新增）、现有 Maven/Python/React 检查入口、docs/qa |

所有带 W 的任务共用 UI 验收：真实 API、成功/空/错误/权限/冲突/未知提交与恢复；沿既有方向检查 1440/1024/414px 适用布局和实际截图。不为无可见变化的任务套 UI 测试。迁移编号由实施前现场确认后串行追加，不在计划中预占所有 V 号。

### 新任务索引

| ID | 任务 | Needs | Pass | 状态 |
| --- | --- | --- | --- | --- |
| S8A | 冻结评测口径、样本和执行边界 | S7 | DESIGN | DONE |
| S8B | 评测集创建、修订与标注界面 | S8A | IMPLEMENT | DONE |
| S8C | 持久评测执行与逐题结果 | S8B | IMPLEMENT | DONE |
| S8D | 基线选择、逐题对照与退化门禁 | S8C | IMPLEMENT | DONE |
| S8E | 运行当前两路链路的可追溯基线 | S8D | VALIDATE | DONE |
| S8F | 最小跨语言与前端 CI | S8A | ENGINEERING | DONE |
| S9A | 冻结结构化解析与准备包 v2 | S8A | DESIGN | DONE |
| S9B | PDF 入库与页级引用 | S9A, S8E | IMPLEMENT | DONE |
| S9C | DOCX 段落和表格入库 | S9B | IMPLEMENT | DONE |
| S9D | LlamaIndex 结构分块及处理版本对照 | S9C, S8D, S10A | IMPLEMENT | DONE |
| S9E | 扫描资料 OCR | S9C, S9D | IMPLEMENT | CONDITIONAL |
| S9F | 表格资料按需支持 | S9C, S9D | IMPLEMENT | CONDITIONAL |
| S10A | 文档/发布/索引版本与删除语义 | S8A, S9A | DESIGN | DONE |
| S10B | 文档更新与原子发布切换 | S10A, S9D | IMPLEMENT | DONE |
| S10C | 撤下、逻辑删除与引用失效 | S10B | IMPLEMENT | DONE |
| S10D | 版本化重索引和处理 profile 切换 | S10B, S10C, S9D | IMPLEMENT | DONE |
| S10E | 安全内容查重与重复上传恢复 | S10C | IMPLEMENT | DONE |
| S10F | 处理诊断与有界批量管理 | S10D, S10E | IMPLEMENT | DONE |
| S11A | 冻结授权候选、融合与重排契约 | S8D, S10A | DESIGN | DONE |
| S11B | Java 授权候选及最终引用原语 | S11A, S10D | IMPLEMENT | DONE |
| S11C | LlamaIndex 接管两路编排与融合 | S11B, S8E | IMPLEMENT | DONE |
| S11D | 独立模型 Reranker 与预算/失败策略 | S11C, S8D | IMPLEMENT | DONE（协议；U04真实条件待满足） |
| S11E | 搜索、问答和研究共用检索策略 | S11D, S10F | IMPLEMENT | IN_PROGRESS |
| S12A | 追问指代与受控上下文 | S11E | IMPLEMENT | PLANNED |
| S12B | 答案支撑、覆盖与拒答评测 | S12A, S8D | IMPLEMENT | PLANNED |
| S12C | 研究计划、证据覆盖和缺口结果 | S12B | IMPLEMENT | PLANNED |
| S12D | 研究根图分阶段恢复 | S12C | IMPLEMENT | PLANNED |
| S13A | 正式登录与可信身份生命周期 | S8A | IMPLEMENT | PLANNED |
| S13B | 个人/团队资料共享与撤权 | S13A, S10B | IMPLEMENT | PLANNED |
| S13C | 撤权贯穿检索、在途与历史结果 | S13B, S11E, S12D | IMPLEMENT | PLANNED |
| S14A | 问题反馈与运营收件箱 | S8B, S7 | IMPLEMENT | PLANNED |
| S14B | 候选知识编辑与内容绑定评估 | S14A, S8D, S10B, S13A | IMPLEMENT | PLANNED |
| S14C | 审核与真实入库发布 | S14B, S13B | IMPLEMENT | PLANNED |
| S14D | 发布后探测与补偿版本 | S14C, S8D, S10C | IMPLEMENT | PLANNED |
| S15A | 统一模型用量与预算语义 | S8A, S11A | DESIGN | PLANNED |
| S15B | 共享额度预留、结算与调用归因 | S15A, S13A | IMPLEMENT | PLANNED |
| S15C | 跨语言诊断和业务审计 | S8F, S11E | IMPLEMENT | PLANNED |
| S16A | 多 worker 竞争和有界恢复 | S15B, S12D, S13C | IMPLEMENT | PLANNED |
| S16B | 租户公平与后台资源隔离 | S16A, S15C | IMPLEMENT | PLANNED |
| S16C | 代表负载与故障容量报告 | S16B, S10F, S11E | VALIDATE | PLANNED |
| S16D | 可操作告警与运行手册 | S15C, S16C | IMPLEMENT | PLANNED |
| S17A | 完整应用制品与本地部署入口 | S8F, S9C, S11E, S15B | IMPLEMENT | PLANNED |
| S17B | 升级兼容与回退演练 | S17A, S10D, S12D | VALIDATE | PLANNED |
| S17C | 跨存储还原与删除传播演练 | S17B, S13C, S15B | VALIDATE | PLANNED |
| S17D | 制品与依赖供应链门禁 | S8F, S17A | ENGINEERING | PLANNED |
| S18A | 一个真实知识来源的增量同步 | S10D, S10E, S13B, S16B | IMPLEMENT | CONDITIONAL |
| S18B | 用户记忆治理与持久化 | S13A, S15C | IMPLEMENT | CONDITIONAL |
| S18C | 受控记忆提议与上下文注入 | S18B, S12A, S15B | IMPLEMENT | CONDITIONAL |
| S18D | 一个具体业务的只读工具 | S13C, S15B, S12D | IMPLEMENT | CONDITIONAL |
| S18E | 受控业务写入与人工批准 | S18D, S16A | IMPLEMENT | CONDITIONAL |
| S18F | 一种渠道或协议接入 | S18D, S13C | IMPLEMENT | CONDITIONAL |

### S8A · 冻结评测口径、样本和执行边界

- **结果：** 后续开发者可按同一口径判断召回、引用、答案和拒答是否正确。
- **Needs：** S7。
- **Owner / 影响路径：** K、A、T、W（路径见本节 Owner 表）；范围：只写质量专业契约、题目/证据标注规范及测试计划；无迁移。
- **契约 / 迁移归属：** C-EVAL；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 区分机制集与业务集；空命中计分与执行失败分开；定义/资料/权限/策略版本可追溯；明确 task 与报告唯一归属。
- **Pass / Runtime：** DESIGN；无新增 Runtime。
- **状态 / 并行 / 对应缺口：** DONE；parallel=no；G01 G19。

### S8B · 评测集创建、修订与标注界面

- **结果：** 用户能保存问题、正确证据和拒答类型，修改后得到新修订。
- **Needs：** S8A。
- **Owner / 影响路径：** K、W（路径见本节 Owner 表）；范围：Knowledge 新增评测定义/修订迁移；前端质量入口与编辑页。
- **契约 / 迁移归属：** C-EVAL；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 真实 MySQL 幂等/版本冲突/租户隔离；UI 保存失败保留编辑、返回恢复筛选；旧基线不能沿用修改后的定义。
- **Pass / Runtime：** IMPLEMENT；现有 MySQL；无模型调用。
- **状态 / 并行 / 对应缺口：** DONE / 验收 PASS（见 quality-datasets-20261021/TEST_RESULT）；parallel=no；G01 G18。

### S8C · 持久评测执行与逐题结果

- **结果：** 用户发起一次评测，能查看进度、失败题并取消或按规则恢复。
- **Needs：** S8B。
- **Owner / 影响路径：** K、A、T、W（路径见本节 Owner 表）；范围：Knowledge 运行/逐题结果；Tasks 仅增加执行类型适配；AI evaluation 逻辑模块/根图。
- **契约 / 迁移归属：** C-EVAL；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 同键重复只受理一次；旧 epoch 不改结果；有答案题空召回计未命中，无答案题按拒答口径计分；外部失败不得计成功；当前权限改变时中止或标失效；报告回写可对账。
- **Pass / Runtime：** IMPLEMENT；复用现有 Tasks/MySQL、PG 与 worker；受控模型/索引按运行配置启用。
- **状态 / 并行 / 对应缺口：** DONE / 验收PASS；parallel=no；G01 G13 G18。

### S8D · 基线选择、逐题对照与退化门禁

- **结果：** 用户能知道哪些问题变好/变差及是否触发退化规则。
- **Needs：** S8C。
- **Owner / 影响路径：** K、A、W（路径见本节 Owner 表）；范围：质量比较/基线操作及对照页面，复用已定数据模型。
- **契约 / 迁移归属：** C-EVAL；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 禁止跨定义/权限/资料快照的伪比较；总体改善不能掩盖关键题退化；检索/答案/延迟/成本分列；不合格运行不能设基线。
- **Pass / Runtime：** IMPLEMENT；无新组件；模型裁判默认不自动开启。
- **状态 / 并行 / 对应缺口：** DONE；parallel=no；G01 G08 G18。

### S8E · 运行当前两路链路的可追溯基线

- **结果：** 交付现有 RRF/当前问答的首份逐题结果与已知失败分布。
- **Needs：** S8D。
- **Owner / 影响路径：** K、A、W（路径见本节 Owner 表）；范围：仅本次验证资料/标注和 QA 证据；演示资料经接口入库。
- **契约 / 迁移归属：** C-EVAL；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 真实业务资料不足时明确仅机制基线；费用前核账；固定模型/处理/权限/数据版本；无权/无答案/错误但合法引用分别记录；不以样本数虚构质量达标。
- **Pass / Runtime：** VALIDATE；仅已有获授权环境及累计额度；额度不足不运行付费部分。
- **状态 / 并行 / 对应缺口：** DONE / 验收PASS，真实8题及已知M04支撑失败见 quality-mechanism-20261024/TEST_RESULT；parallel=no；G01 G08。

### S8F · 最小跨语言与前端 CI

- **结果：** 一次提交能自动构建并验证受影响协议、代码和文档。
- **Needs：** S8A。
- **Owner / 影响路径：** C、A、K、W（路径见本节 Owner 表）；范围：两仓工作流及既有检查命令适配；不升级全仓依赖。
- **契约 / 迁移归属：** C-RELEASE 的最小 CI 部分已冻结于 [CI_CONTRACTS](CI_CONTRACTS.md)；现有构建与契约为检查依据，不等待 S17 的完整发布契约；无业务迁移。
- **可观察验收：** 干净环境按锁文件构建；Java/Python DTO 与契约不一致会失败；UI 类型/单元/构建运行；密钥不进日志；真实模型默认不在无预算 CI 调用。
- **Pass / Runtime：** ENGINEERING；GitHub Actions；必要数据库测试使用隔离资源/显式条件。
- **状态 / 并行 / 对应缺口：** DONE；本地与两仓真实Actions必需检查PASS，见 [S8F验收](../../qa/ci-contracts-20261024/TEST_RESULT.md)；parallel=no；G16 G19。

### S9A · 冻结结构化解析与准备包 v2

- **结果：** 确定原文件/提取物/来源结构如何安全送入 LlamaIndex。
- **Needs：** S8A。
- **Owner / 影响路径：** K、A、W、R（路径见本节 Owner 表）；范围：解析方案比较、样本验证计划与新 P 契约；无业务迁移。
- **契约 / 迁移归属：** C-PARSE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 比较 Java 隔离解析与 Python reader，记录依赖/许可证/资源成本；选择唯一解析者；hash/页段/表格语义、新旧格式与在途任务兼容明确。
- **Pass / Runtime：** DESIGN；仅方案；解析依赖在本项确定后才引入。
- **状态 / 并行 / 对应缺口：** DONE；设计及同样本双候选探针PASS，见 [S9A验收](../../qa/structured-parsing-20261024/TEST_RESULT.md)；产品链路由S9B/C实现；parallel=no；G02 G03。

### S9B · PDF 入库与页级引用

- **结果：** 用户上传原生 PDF，看到处理状态、页号与真实提取文本。
- **Needs：** S9A, S8E。
- **Owner / 影响路径：** K、A、W、R（路径见本节 Owner 表）；范围：Knowledge 格式受理与独立解析子进程；Python 结构输入；来源存储/索引字段；引用 UI。
- **契约 / 迁移归属：** C-PARSE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 真实多页/空页/旋转/损坏/加密样本；卡死/输出/内存预算；失败不发布；来源/摘要一致；TXT/MD 兼容；UI 不伪造原文 PDF 渲染。
- **Pass / Runtime：** IMPLEMENT；Java 解析进程与锁定依赖，复用 S3/MySQL/双索引。
- **状态 / 并行 / 对应缺口：** DONE；本地/两仓真实CI与正常main交付PASS，见[S9B验收](../../qa/pdf-ingestion-20261024/TEST_RESULT.md)；按用户要求在此暂停；parallel=no；G02 G03 G18。

### S9C · DOCX 段落和表格入库

- **结果：** 用户可核对标题、段落、表格及缺失信息提示。
- **Needs：** S9B。
- **Owner / 影响路径：** K、A、W（路径见本节 Owner 表）；范围：同解析协议新增 DOCX 适配；共用来源与告警 UI。
- **契约 / 迁移归属：** C-PARSE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 真实中文标题/空段/表格/合并单元格样本；不执行宏/外链；表头不丢；异常资源有界；同一文件重试来源稳定。
- **Pass / Runtime：** IMPLEMENT；沿 S9B 子进程，不新增解析服务。
- **状态 / 并行 / 对应缺口：** DONE；2026-10-25本地验收PASS，证据docs/qa/docx-ingestion-20261025/TEST_RESULT.md；Git/CI交付状态见PROGRESS_STATE；parallel=no；G02 G03 G18。

### S9D · LlamaIndex 结构分块及处理版本对照

实施与本地验证PASS，证据见[TEST_RESULT](../../qa/structured-chunking-20261025/TEST_RESULT.md)；Git/CI状态由PROGRESS_STATE和交付收据记录。

- **结果：** 长章节、表格和代码仍可找到上下文与原文来源。
- **Needs：** S9C, S8D, S10A。
- **Owner / 影响路径：** A、K、W（路径见本节 Owner 表）；范围：结构 Node 转换/分块 profile；准备结果校验与切分度量；K06前端已注册profile读取兼容与真实多来源验收。
- **契约 / 迁移归属：** C-PARSE、C-LIFECYCLE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 片段 ID 唯一、原文/增强分离；结构可跨段但来源不伪造；上限拒绝不截断；同集对照且旧 profile 可读；不直接启用全部 splitter。
- **Pass / Runtime：** IMPLEMENT；现有 Embedding 适配与预算；无额外向量存储。
- **状态 / 并行 / 对应缺口：** DONE；parallel=no；G03 G07。

### S9E · 扫描资料 OCR

- **结果：** 用户能辨认扫描页提取结果与不可靠区域。
- **Needs：** S9C, S9D。
- **Owner / 影响路径：** K、A、W、R（路径见本节 Owner 表）；范围：有界 OCR 引擎、语言包、来源/质量告警与镜像适配。
- **契约 / 迁移归属：** C-PARSE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 中英/低清/混合原生扫描页样本；页数/像素/进程/耗时上限；低质量有警告；OCR 失败不得伪成功；报告准确度与资源观测。
- **Pass / Runtime：** IMPLEMENT；本地 OCR 进程/应用镜像，不自动启用云解析。
- **状态 / 并行 / 对应缺口：** CONDITIONAL；parallel=no；G02 G03 G18。
- **触发条件：** U02 确认实际扫描件；OCR 样本及接受标准明确。

### S9F · 表格资料按需支持

- **结果：** 表格问题能够引用工作表、表头和行列范围。
- **Needs：** S9C, S9D。
- **Owner / 影响路径：** K、A、W（路径见本节 Owner 表）；范围：仅明确需要的 XLSX/CSV 读取与行列来源适配。
- **契约 / 迁移归属：** C-PARSE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 前导零/日期/引号/空行/多工作表；公式仅明确的缓存值策略；外链/宏不执行；宽行/合并单元格有边界及告警。
- **Pass / Runtime：** IMPLEMENT；沿已有隔离解析进程。
- **状态 / 并行 / 对应缺口：** CONDITIONAL；parallel=no；G02 G03 G18。
- **触发条件：** U02 确认真实表格需求；先冻结要支持的格式，不全量复制旧格式表。

### S10A · 文档/发布/索引版本与删除语义

- **结果：** 能明确区分改正文、重建索引、撤下和物理清理。
- **Needs：** S8A, S9A。
- **Owner / 影响路径：** K、A、W（路径见本节 Owner 表）；范围：生命周期契约与状态图；迁移扩展/回退及引用规则。
- **契约 / 迁移归属：** C-LIFECYCLE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 当前 v1 固定版本、全局 processor filter 的兼容影响列清；旧任务重试不覆盖新发布版；需要业务确定的保留规则单列。
- **Pass / Runtime：** DESIGN；无新增 Runtime。
- **状态 / 并行 / 对应缺口：** DONE；parallel=no；G04 G19。设计/文档验证见[TEST_RESULT](../../qa/knowledge-lifecycle-design-20261025/TEST_RESULT.md)，产品验收仍按LC矩阵待实施。

### S10B · 文档更新与原子发布切换

- **结果：** 用户可更新已有文档，处理失败时仍能使用旧发布版。
- **Needs：** S10A, S9D。
- **Owner / 影响路径：** K、A、T、Q、G、W（路径见本节 Owner 表）；范围：Knowledge 版本迁移、P05代次封装、更新受理/处理/发布、Tasks知识结果/事件读取适配与Conversation上下文、K08受限身份及React版本详情；来源见S10A生命周期契约，保证更新失效不只保护点击引用。
- **契约 / 迁移归属：** C-LIFECYCLE、C-PARSE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 真实 DB 并发更新/重复提交；准备或单 sink 失败不切换；发布指针 CAS；历史引用/历史答案及事件正文按契约失效；恢复不会混合准备结果；适用LC-01–11、15–16、20–21、24。
- **Pass / Runtime：** IMPLEMENT；既有 Knowledge worker、MySQL/S3/Qdrant/ES。
- **状态 / 并行 / 对应缺口：** DONE；[验收](../../qa/document-update-20261026/TEST_RESULT.md)；parallel=no；G04 G18。

### S10C · 撤下、逻辑删除与引用失效

- **结果：** 用户能撤下无效资料，并理解旧引用为何不可用。
- **Needs：** S10B。
- **Owner / 影响路径：** K、A、T、Q、G、W（路径见本节 Owner 表）；范围：可见性状态、撤下/逻辑删除命令，复用S10B历史结果判权并覆盖全部任务/事件/上下文/评测读取。
- **契约 / 迁移归属：** C-LIFECYCLE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 搜索/问答/研究/详情及历史引用统一拒绝；在途任务不得重新发布；并发更新/撤下合法；不隐式物理清空原文或备份；适用LC-12–16、21–24。
- **Pass / Runtime：** IMPLEMENT；无新组件；只用隔离测试数据验证删除行为。
- **状态 / 并行 / 对应缺口：** DONE；[验收](../../qa/document-visibility-20261026/TEST_RESULT.md)；parallel=no；G04 G18。

### S10D · 版本化重索引和处理 profile 切换

- **结果：** 用户可重建索引并看到进度，失败可继续且旧可用数据不丢。
- **Needs：** S10B, S10C, S9D。
- **Owner / 影响路径：** K、A、W、R（路径见本节 Owner 表）；范围：独立重建作业/索引代次、对账与切换；处理版本读取兼容。
- **契约 / 迁移归属：** C-LIFECYCLE、C-PARSE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 固定输入快照；新旧 profile 共存；重试不重算已绑定准备包；源权限/撤下变化复核；切换前质量/数量/摘要核对；清理资格单独列出。
- **Pass / Runtime：** IMPLEMENT；复用既有存储；新集合仅在实际 schema/model 不兼容时引入。
- **状态 / 并行 / 对应缺口：** DONE；parallel=no；G04 G13 G18。

### S10E · 安全内容查重与重复上传恢复

- **结果：** 同内容不同名字/新请求能指向可见资料或本人未完成任务。
- **Needs：** S10C。
- **Owner / 影响路径：** K、W（路径见本节 Owner 表）；范围：内容摘要索引/有界查重与冲突响应；上传 UI。
- **契约 / 迁移归属：** C-LIFECYCLE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 真实 MySQL 并发去重；无权资料不泄露；旧版/删除不误挡新上传；未知响应保留原幂等身份；共享启用时再跑新授权用例。
- **Pass / Runtime：** IMPLEMENT；现有 MySQL/S3；无模型查重。
- **状态 / 并行 / 对应缺口：** DONE；parallel=no；G05 G18。

### S10F · 处理诊断与有界批量管理

- **结果：** 用户能定位失败阶段、区分重试/修正文档并观察部分成功。
- **Needs：** S10D, S10E。
- **Owner / 影响路径：** K、W（路径见本节 Owner 表）；范围：任务筛选/来源告警/处理历史与有界管理命令；状态界面。
- **契约 / 迁移归属：** C-LIFECYCLE、C-PARSE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 无权限或不可重试状态无操作；批量每项独立结果/上限；未知结果可查询；不把重新提交当恢复；窄屏仍可查看来源和错误。
- **Pass / Runtime：** IMPLEMENT；现有知识任务，不新建批处理引擎。
- **状态 / 并行 / 对应缺口：** DONE；parallel=no；G05 G13 G18。

### S11A · 冻结授权候选、融合与重排契约

- **结果：** 调用者能够区分两路候选、RRF、精排分数与最终来源。
- **Needs：** S8D, S10A。
- **Owner / 影响路径：** K、A、G、W（路径见本节 Owner 表）；范围：检索边界与策略版本设计；明确新旧入口及模型失败规则。
- **契约 / 迁移归属：** C-RETRIEVAL、C-RERANK；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 合法正文外发前授权；稳定 chunk/batch 身份；一基排名/RRF60/同分规则；候选上限、deadline、重排回退与诊断权限；禁止循环调用。
- **Pass / Runtime：** DESIGN；无新增 Runtime。
- **状态 / 并行 / 对应缺口：** DONE；parallel=no；G07 G10。

### S11B · Java 授权候选及最终引用原语

- **结果：** 运行时可获得足量合法候选，普通客户端不能借接口导出他人资料。
- **Needs：** S11A, S10D。
- **Owner / 影响路径：** K、A、G（路径见本节 Owner 表）；范围：内部向量/词法候选与权威解析 API；调用身份/模型维度验证。
- **契约 / 迁移归属：** C-RETRIEVAL；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 索引伪造元数据/过期批次/隐藏文档均被过滤；原文出服务前复核；授权后保留内部原始名次、不压缩编号；数量/字节限制；原语不调用 Python RAG；旧 K 检索保持兼容。
- **Pass / Runtime：** IMPLEMENT；既有 MySQL/Qdrant/ES；仅内部服务身份扩展。
- **状态 / 并行 / 对应缺口：** DONE；parallel=no；G07 G10；[验收](../../qa/authorized-retrieval-20261028/TEST_RESULT.md)。

### S11C · LlamaIndex 接管两路编排与融合

- **结果：** 新路径在未启用精排前能重现原两路 RRF 行为。
- **Needs：** S11B, S8E。
- **Owner / 影响路径：** A、K（路径见本节 Owner 表）；范围：受控 Retriever 组合/异步扇出/融合适配；旧路径对照。
- **契约 / 迁移归属：** C-RETRIEVAL；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 固定候选 golden 对照，含无效候选造成的名次空位；同文不同 doc/版本不误并；RRF 一基排名；没有隐藏模型或全局 Settings；超时/单路失败明确；同一请求只融合一次。
- **Pass / Runtime：** IMPLEMENT；已有 HTTP/Embedding 适配；不新增召回源。
- **状态 / 并行 / 对应缺口：** DONE；parallel=no；G07 G13；[验收](../../qa/llama-retrieval-20261028/TEST_RESULT.md)。

### S11D · 独立模型 Reranker 与预算/失败策略

- **结果：** 合法候选由相关性模型重新排序，用户可识别是否实际精排。
- **Needs：** S11C, S8D。
- **Owner / 影响路径：** A、K、R（路径见本节 Owner 表）；范围：一个受控重排提供方、LlamaIndex 后处理适配、先行累计预算与结果校验。
- **契约 / 迁移归属：** C-RERANK、C-RETRIEVAL；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 逐题消融；候选不先裁成最终 K；重复/越界索引及非法分数拒绝；超时/额度不足结果明确；未知计费不盲重试；费用/延迟可追溯。
- **Pass / Runtime：** IMPLEMENT；优先验证百炼 qwen3-rerank；U04 未满足时只验证协议，真实调用待条件满足。
- **状态 / 并行 / 对应缺口：** DONE（协议/预算/失败机制；U04真实模型与消融UNVERIFIED）；parallel=no；G07 G15；[验收](../../qa/rerank-budget-20261028/TEST_RESULT.md)。

### S11E · 搜索、问答和研究共用检索策略

- **结果：** 同一身份/问题/策略在检索、问答和研究中采用一致的精排链路。
- **Needs：** S11D, S10F。
- **Owner / 影响路径：** A、K、G、W（路径见本节 Owner 表）；范围：三个入口共用 RAG 用例、版本化路由与可见诊断。
- **契约 / 迁移归属：** C-RETRIEVAL 及现行 Q/work 协议的兼容适配；本项不提前改变答案或研究 schema，新增语义留到后续任务。迁移仍归相应服务 Owner。
- **可观察验收：** 三入口对照与引用重验；旧任务绑定旧策略可恢复；禁双重融合/重复模型；切换失败可回原版本；前端显示实际策略/降级，不暴露无权候选。
- **Pass / Runtime：** IMPLEMENT；复用现有 AI 运行时；无新独立检索服务。
- **状态 / 并行 / 对应缺口：** IN_PROGRESS（完整证据/QA与研究及评测新图/会话策略基础已实现并验证；公共入口、前端及真实联合验收待完成）；parallel=no；G07 G08 G12 G18。

### S12A · 追问指代与受控上下文

- **结果：** 用户能基于指定前文追问，仍知道答案来自本次哪些证据。
- **Needs：** S11E。
- **Owner / 影响路径：** Q、A、W（路径见本节 Owner 表）；范围：合法历史上下文解析、受控查询改写、原问题/检索问题分离。
- **契约 / 迁移归属：** C-QA2；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 指代/跨文档/改变知识范围/撤权样本；旧答案不当原文；上下文与模型调用有界；失败回退不改变原问题；追问改善和退化分别记录。
- **Pass / Runtime：** IMPLEMENT；现有聊天出口与累计预算；新费用不得隐式增加。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G08 G18。

### S12B · 答案支撑、覆盖与拒答评测

- **结果：** 用户与评测者可区分有效引用、受支持结论和资料不足。
- **Needs：** S12A, S8D。
- **Owner / 影响路径：** A、K、W（路径见本节 Owner 表）；范围：支撑度/完整度/拒答评审协议与结果；必要时受控裁判适配。
- **契约 / 迁移归属：** C-QA2、C-EVAL；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 合法引用但错答、冲突资料、注入、无答案/部分答案用例；人工标注校准；裁判失败不算通过；默认策略有界且成本可测；不宣称绝对事实保证。
- **Pass / Runtime：** IMPLEMENT；复用现有图/模型适配；在线裁判仅在预算和收益验证后启用。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G01 G08 G18。

### S12C · 研究计划、证据覆盖和缺口结果

- **结果：** 研究报告说明回答了哪些子问题、使用哪些证据、还有哪些缺口。
- **Needs：** S12B。
- **Owner / 影响路径：** A、T、W（路径见本节 Owner 表）；范围：Deep Agents 有界计划/证据工具输出与报告结构；任务展示。
- **契约 / 迁移归属：** C-WORK2；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 未支持结论不能伪造引用；总模型/工具/搜索预算覆盖整个任务；计划不升级工具权限；空证据和目标无法完成有明确结果。
- **Pass / Runtime：** IMPLEMENT；现有 Deep Agents；不默认加子 Agent 或 shell。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G12 G18。

### S12D · 研究根图分阶段恢复

- **结果：** 中断研究可以从已确认阶段继续，界面能解释恢复位置。
- **Needs：** S12C。
- **Owner / 影响路径：** A、T、W（路径见本节 Owner 表）；范围：LangGraph 根图阶段和 graphVersion；安全快照/任务事件适配。
- **契约 / 迁移归属：** C-WORK2、现行 T01/CHECKPOINT 恢复协议；多 worker 容量契约由后续 S16 冻结，本项复用已有 fencing。迁移仍归 Tasks Owner。
- **可观察验收：** 真实 PG/中央失租/取消/阶段提交未知结果；旧 work-v1 按旧语义恢复；只读工具与正文权限复核；重复模型费用可见；不打开未验证内部 saver。
- **Pass / Runtime：** IMPLEMENT；现有 PG/Tasks；无需第二个调度器。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G12 G13 G18。

### S13A · 正式登录与可信身份生命周期

- **结果：** 真实用户无需开发固定身份即可进入被授权工作区。
- **Needs：** S8A。
- **Owner / 影响路径：** G、W、R（路径见本节 Owner 表）；范围：IAM 接入/令牌交换及前端登录退出/过期恢复；现有代码渐进适配。
- **契约 / 迁移归属：** C-IDENTITY；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** U03 实际配置核对；登录/过期/退出/错误来源；内部JWT可信边界；跨租户/伪造头拒绝；本地身份桥不自动回退开放。
- **Pass / Runtime：** IMPLEMENT；既定 Casdoor/auth-platform 接入；不新造 IAM。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G10 G18。

### S13B · 个人/团队资料共享与撤权

- **结果：** 资料 owner 可按明确角色共享和撤权。
- **Needs：** S13A, S10B。
- **Owner / 影响路径：** K、G、W（路径见本节 Owner 表）；范围：授权模型/命令与共享界面；迁移独占 Knowledge 归属。
- **契约 / 迁移归属：** C-SHARING；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 先冻结角色矩阵及 ACL/ReBAC 承载；跨用户/租户、并发授权/撤权、无权存在性保护；UI 使用真实后端允许动作；不能仅隐藏按钮。
- **Pass / Runtime：** IMPLEMENT；复用既定身份设施；是否新增授权存储由本项契约确认，不默认复制关闭的 ReBAC。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G10 G18。

### S13C · 撤权贯穿检索、在途与历史结果

- **结果：** 撤权后的候选、精排输入、答案/报告引用和恢复都受同一边界。
- **Needs：** S13B, S11E, S12D。
- **Owner / 影响路径：** K、A、Q、T、W（路径见本节 Owner 表）；范围：各消费路径的撤权/失效处理与联合验收。
- **契约 / 迁移归属：** C-SHARING、C-RETRIEVAL、C-QA2、C-WORK2；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 撤权发生在召回后/外发前/生成中/恢复前的竞态；历史报告/评测不泄露原文；相关索引状态不绕权威；拒绝原因可安全理解。
- **Pass / Runtime：** IMPLEMENT；现有组件；隔离身份和受控并发场景。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G10 G08 G18。

### S14A · 问题反馈与运营收件箱

- **结果：** 用户能提交问题和证据反馈，并查看自己的处理关联。
- **Needs：** S8B, S7。
- **Owner / 影响路径：** K、W（路径见本节 Owner 表）；范围：反馈持久化、按权限的收件箱和回答/引用反馈操作。
- **契约 / 迁移归属：** C-OPERATIONS；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 幂等、真实任务/文档引用校验、租户隔离；普通用户不能看运营全量；未知提交可查询；反馈不直接改知识正文。
- **Pass / Runtime：** IMPLEMENT；现有 Knowledge MySQL；无模型调用。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G09 G18。

### S14B · 候选知识编辑与内容绑定评估

- **结果：** 运营者能从反馈形成候选并看到当前内容的评估结果。
- **Needs：** S14A, S8D, S10B, S13A。
- **Owner / 影响路径：** K、W、A（路径见本节 Owner 表）；范围：候选修订/反馈关联/评估引用和编辑页面。
- **契约 / 迁移归属：** C-OPERATIONS、C-EVAL；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 编辑后旧评估/审批失效；并发冲突保留草稿；requiredText 仅局部检查；语义与检索评估走统一质量能力；无权限不可编辑。
- **Pass / Runtime：** IMPLEMENT；现有知识库/质量执行能力。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G09 G01 G18。

### S14C · 审核与真实入库发布

- **结果：** 获授权者审批后发布，只有真实发布成功才显示已生效。
- **Needs：** S14B, S13B。
- **Owner / 影响路径：** K、W（路径见本节 Owner 表）；范围：审批/发布状态机、发布槽/幂等和持久任务状态同步。
- **契约 / 迁移归属：** C-OPERATIONS、C-LIFECYCLE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 并发审批/发布、内容变更、未知提交、worker失败重试；固定内容摘要；角色/权限复核；不把图 interrupt 或模型文本当批准。
- **Pass / Runtime：** IMPLEMENT；复用 Knowledge 入库作业；不增加通用工作流引擎。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G09 G10 G18。

### S14D · 发布后探测与补偿版本

- **结果：** 运营者能确认修正是否可检索，并安全处理错误发布。
- **Needs：** S14C, S8D, S10C。
- **Owner / 影响路径：** K、A、W（路径见本节 Owner 表）；范围：精确新版本探测、反馈历史与补偿草稿操作。
- **契约 / 迁移归属：** C-OPERATIONS、C-EVAL、C-LIFECYCLE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 探测必须命中新发布版本；旧命中不能冒充生效；失败不改成成功；补偿生成新版本或明确撤下，保留审计；权限变化仍生效。
- **Pass / Runtime：** IMPLEMENT；现有检索/评测链路和额度。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G09 G01 G04 G18。

### S15A · 统一模型用量与预算语义

- **结果：** 能够解释一次调用由谁支付、预留多少、结果未知如何处理。
- **Needs：** S8A, S11A。
- **Owner / 影响路径：** T、A、K、Q（路径见本节 Owner 表）；范围：预算域设计、预留/结算/未知结果协议和旧账本迁移规则。
- **契约 / 迁移归属：** C-USAGE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 区分币种/单位/估算/真实usage/账单；operation/attempt身份稳定；恢复不清零；选择 Tasks usage 模块与未来抽取边界。
- **Pass / Runtime：** DESIGN；仅设计；不增加计量服务或 Redis 权威。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G15 G19。

### S15B · 共享额度预留、结算与调用归因

- **结果：** 多实例调用共享额度，重复结算不重复扣减，可按任务/模型核对。
- **Needs：** S15A, S13A。
- **Owner / 影响路径：** T、A、K、Q、W（路径见本节 Owner 表）；范围：Tasks 自有 usage 迁移/API；模型出口及知识准备接入；安全用量视图。
- **契约 / 迁移归属：** C-USAGE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 真实 DB 并发超额阻止、重复预留/结算、取消/超时未知结果；Embedding/聊天/重排/裁判都受约束；旧账本迁移核对保守余额。
- **Pass / Runtime：** IMPLEMENT；既有 MySQL；无新分布式锁/缓存权威。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G15 G18。

### S15C · 跨语言诊断和业务审计

- **结果：** 可由任务/请求追到检索、模型、索引或队列失败。
- **Needs：** S8F, S11E。
- **Owner / 影响路径：** G、K、T、Q、A、W、R（路径见本节 Owner 表）；范围：现有 Micrometer/OTel 与 Python 关联、关键阶段指标及受控诊断入口。
- **契约 / 迁移归属：** C-OBS；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** W3C上下文真实传播、线程/异步不串号；脱敏和指标基数；采集失败不拖业务；授权/发布/预算等业务审计可检索。
- **Pass / Runtime：** IMPLEMENT；复用现有采集设施，不默认引入外部 SaaS。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G14 G18。

### S16A · 多 worker 竞争和有界恢复

- **结果：** 多个 worker 可争用任务而不让旧执行者提交有效结果。
- **Needs：** S15B, S12D, S13C。
- **Owner / 影响路径：** T、A、K、R（路径见本节 Owner 表）；范围：worker角色/领取与连接并发预算，联合恢复场景。
- **契约 / 迁移归属：** C-CAPACITY、C-WORK2；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 抢占/失租/延迟心跳/取消/重启/终态未知窗口；PG与中央一致；任务/工具预算跨attempt；新旧图版本按规则兼容。
- **Pass / Runtime：** IMPLEMENT；现有服务增加受控 worker 实例；先隔离验证再调整长期运行。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G13。

### S16B · 租户公平与后台资源隔离

- **结果：** 单租户批量入库或慢模型不会耗尽其他用户全部额度。
- **Needs：** S16A, S15C。
- **Owner / 影响路径：** K、T、A、R（路径见本节 Owner 表）；范围：租户准入/队列/线程与下游资源限制；独立业务执行器及上下文。
- **契约 / 迁移归属：** C-CAPACITY；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 饥饿/饱和/拒绝/下游慢；任务不静默丢失；Java TTL/Trace和Python上下文验证；跨实例总预算；优雅停机可续办。
- **Pass / Runtime：** IMPLEMENT；既有 DB/HTTP/worker资源；不为了公平预设 MQ。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G13 G14。

### S16C · 代表负载与故障容量报告

- **结果：** 获得混合上传/检索/问答/研究下可解释的容量边界。
- **Needs：** S16B, S10F, S11E。
- **Owner / 影响路径：** K、T、A、W、R（路径见本节 Owner 表）；范围：隔离负载/故障场景和 QA 报告；必要有界修复单独记录。
- **契约 / 迁移归属：** C-CAPACITY；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** U06 目标未定仅报观测不宣称 SLA；记录排队/尾延迟/费用/资源；数据库慢/索引不可用/模型超时/worker退出；恢复后无非法业务状态。
- **Pass / Runtime：** VALIDATE；只在明确隔离环境按预算运行。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G13 G14。

### S16D · 可操作告警与运行手册

- **结果：** 运营者能识别积压/费用/依赖故障并找到处理入口。
- **Needs：** S15C, S16C。
- **Owner / 影响路径：** R、C、W（路径见本节 Owner 表）；范围：Prometheus/日志告警、处置手册与必要运维查询。
- **契约 / 迁移归属：** C-OBS、C-CAPACITY；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 阈值源于实际目标或标试验值；触发/恢复/去重可验证；告警不含正文/密钥；处置可执行，不能只写指标名。
- **Pass / Runtime：** IMPLEMENT；复用监控设施；无人授权不发送真实外部消息。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G14。

### S17A · 完整应用制品与本地部署入口

- **结果：** 全新验证环境可按固定制品启动独立平台。
- **Needs：** S8F, S9C, S11E, S15B。
- **Owner / 影响路径：** R、C（路径见本节 Owner 表）；范围：Java/Python/Web/模型出口制品、Compose、env.example和启停说明。
- **契约 / 迁移归属：** C-RELEASE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** dev_infra 连接/账号/命名空间隔离；健康/ready分开；私密凭据外置；无旧业务服务隐式依赖；重启不重置预算。
- **Pass / Runtime：** IMPLEMENT；完整应用容器；共享组件优先 dev_infra；非生产目标。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G17。

### S17B · 升级兼容与回退演练

- **结果：** 升级时旧任务/引用仍可解释，代码回退有明确适用范围。
- **Needs：** S17A, S10D, S12D。
- **Owner / 影响路径：** R、K、T、Q、A、C（路径见本节 Owner 表）；范围：迁移/旧新版本矩阵、升级脚本和回退验收。
- **契约 / 迁移归属：** C-RELEASE、C-LIFECYCLE、C-WORK2；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 真实隔离DB先扩后迁；应用无DDL权限；旧图/处理profile可读；停机中断后恢复；已发生业务效果不能用镜像回退假装撤销。
- **Pass / Runtime：** VALIDATE；隔离验证环境与固定旧/新制品。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G17 G16。

### S17C · 跨存储还原与删除传播演练

- **结果：** 原文、权威库、检查点、索引关系和预算能在隔离目标恢复。
- **Needs：** S17B, S13C, S15B。
- **Owner / 影响路径：** R、K、T、Q、A（路径见本节 Owner 表）；范围：备份/还原计划、隔离脚本和恢复验收。
- **契约 / 迁移归属：** C-RECOVERY；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** U07 保留/恢复要求明确；不覆盖共享/生产库；恢复后任务/引用/授权/撤下资料/预算连续；实测恢复时间与数据点；重建索引需有证据。
- **Pass / Runtime：** VALIDATE；独立恢复目标；没有生产部署或历史数据清理授权。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G17 G04 G15。

### S17D · 制品与依赖供应链门禁

- **结果：** 交付版本的依赖与制品来源可追溯，必要失败可见。
- **Needs：** S8F, S17A。
- **Owner / 影响路径：** C、R（路径见本节 Owner 表）；范围：SBOM、许可证/漏洞与镜像来源校验，复用既有检查。
- **契约 / 迁移归属：** C-RELEASE；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 检查绑定提交/制品；跳过不算通过；不擅自全仓升级或绕过保护；真实失败阻断相应交付并记录整改范围。
- **Pass / Runtime：** ENGINEERING；现有 CI/制品工具；无自动生产发布。
- **状态 / 并行 / 对应缺口：** PLANNED；parallel=no；G16 G17。

### S18A · 一个真实知识来源的增量同步

- **结果：** 资料变化/删除按来源身份进入知识生命周期，可暂停/恢复。
- **Needs：** S10D, S10E, S13B, S16B。
- **Owner / 影响路径：** K、W、R（路径见本节 Owner 表）；范围：单一来源适配、游标/映射/运行记录及运营页面。
- **契约 / 迁移归属：** C-SYNC；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 重复/乱序/游标回退/失败重放；来源URL和身份服务端绑定；删除不能绕版本/权限；任务真实完成才推进游标。
- **Pass / Runtime：** IMPLEMENT；需要周期业务触发时按约定接 XXL-JOB，优先复用现有实例。
- **状态 / 并行 / 对应缺口：** CONDITIONAL；parallel=no；G06 G18。
- **触发条件：** U05 确认具体来源、负责人和变更/删除协议。

### S18B · 用户记忆治理与持久化

- **结果：** 用户可知道系统保存了什么并控制是否继续使用。
- **Needs：** S13A, S15C。
- **Owner / 影响路径：** Q、W（路径见本节 Owner 表）；范围：Conversation 自有记忆/审计迁移与确认/修改/遗忘/过期界面。
- **契约 / 迁移归属：** C-MEMORY；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 真实DB并发/代次/版本；未确认/过期不生效；遗忘不泄露审计正文；跨用户/租户隔离；无模型抽取也能治理。
- **Pass / Runtime：** IMPLEMENT；既有 Conversation MySQL；不引入第二个向量记忆权威。
- **状态 / 并行 / 对应缺口：** CONDITIONAL；parallel=no；G11 G18。
- **触发条件：** U05 明确跨会话收益、记忆种类和保留规则。

### S18C · 受控记忆提议与上下文注入

- **结果：** 助手只使用当前可用且被确认的记忆，用户可追踪来源。
- **Needs：** S18B, S12A, S15B。
- **Owner / 影响路径：** Q、A、W（路径见本节 Owner 表）；范围：提议抽取/代次校验和只读记忆适配；拒绝/失效反馈。
- **契约 / 迁移归属：** C-MEMORY、C-QA2；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 清空期间旧抽取任务不得回填；停用/过期/权限变化即时生效；不以 Deep Agents Store 直写绕过Java；费用与错误可见。
- **Pass / Runtime：** IMPLEMENT；现有模型出口和预算；框架记忆仅作受控接口消费者。
- **状态 / 并行 / 对应缺口：** CONDITIONAL；parallel=no；G11 G08 G18。
- **触发条件：** S18B 的业务触发持续成立，并验证抽取/注入收益。

### S18D · 一个具体业务的只读工具

- **结果：** 用户完成一个超出知识资料的真实只读业务任务。
- **Needs：** S13C, S15B, S12D。
- **Owner / 影响路径：** A、T、G、W（路径见本节 Owner 表）；范围：选定领域的受控工具适配与任务结果；必要领域代码单独界定。
- **契约 / 迁移归属：** C-TOOLS；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 工具allowlist/身份/输入上限；结果来自实际领域API；跨租户/超时/重复查询；模型不能自选URL/凭据；UI不把自然语言当系统执行证明。
- **Pass / Runtime：** IMPLEMENT；依赖选定业务服务；不自动迁入旧平台所有模块。
- **状态 / 并行 / 对应缺口：** CONDITIONAL；parallel=no；G12 G20 G18。
- **触发条件：** U05 选定一个业务场景、数据所有者和真实成功条件。

### S18E · 受控业务写入与人工批准

- **结果：** 用户批准具体参数后执行一次可查询的业务动作。
- **Needs：** S18D, S16A。
- **Owner / 影响路径：** A、T、G、W（路径见本节 Owner 表）；范围：一个写工具的审批grant、LangGraph interrupt/resume和领域幂等结果查询。
- **契约 / 迁移归属：** C-TOOLS；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 批准人/参数摘要/有效期/权限均核验；resume重入不重复写；超时先查结果；取消不冒充业务回滚；领域补偿规则明确。
- **Pass / Runtime：** IMPLEMENT；复用根图和领域API；按场景选用既有业务流程，不默认新引擎。
- **状态 / 并行 / 对应缺口：** CONDITIONAL；parallel=no；G12 G20 G18。
- **触发条件：** 明确授权该写业务及确认/补偿规则，不能由只读工具授权推导。

### S18F · 一种渠道或协议接入

- **结果：** 外部用户能按同一权限提交并查询真实任务结果。
- **Needs：** S18D, S13C。
- **Owner / 影响路径：** G、A、T、W、R（路径见本节 Owner 表）；范围：仅一种已选渠道或MCP/A2A适配；不同时铺多种能力。
- **契约 / 迁移归属：** C-CHANNEL；有新迁移时由相应服务 Owner 串行追加并写表/字段注释；没有迁移不新建占位 SQL。
- **可观察验收：** 认证/身份映射、重复/乱序/重放、断线查询；外部回调不提升工具权限；授权的数据边界明确。
- **Pass / Runtime：** IMPLEMENT；选定渠道凭据及回调环境；不因有协议库就宣称业务可用。
- **状态 / 并行 / 对应缺口：** CONDITIONAL；parallel=no；G20 G18。
- **触发条件：** U05 明确首种渠道或协议与实际使用者；语音视觉另做具体场景设计。

### 下一任务、受阻条件及 Runtime 介入

- 恢复实施后的第一项是 S8A（设计任务），其后 S8B/S8C/S8D/S8E 形成质量闭环；S8F 可在 S8A 后按实际队列安排，不能无 CI 长期积累后续改动。S8A 设计验收后才能把 S8B 调整为 READY；不得跳过契约。
- 优先任务链、条件业务与 G01–G20 覆盖见主方案。S14A 反馈可在质量定义建立后提前；S13A/B 在多人使用前提前，不能机械排到最后。
- U01–U07 的条件只阻止相关行为；欠缺真实模型额度不会阻止契约/协议测试，但不能把未运行的真实效果标 PASS。
- Runtime 首次新增工作：S9B 解析子进程/依赖，S9E 条件 OCR，S11D 重排模型私密配置与预算，S13A 正式身份配置，S15C 观测接入，S16A worker 配额，S17A 完整制品。S18A 周期业务同步首次需要时才接既定 XXL-JOB。
- 逻辑上不共享资源的文档/CI可分别排期，但没有授权多个 Agent 并行写；同一 Knowledge 迁移序列、公共身份/协议或生产运行资源必须串行。
- 每个任务完成后更新本节状态及原 PROGRESS_STATE；报告必须给出对应契约、改变的文件、测试证据、未验证项、Git 结果和下一未完成项。不得把一次阶段验收当作整个 20 项完成。
