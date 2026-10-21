# 本地运行

该入口用于开发验证。使用独立 Gateway/Knowledge 进程与项目独占数据库、账号、原文桶；共享 `dev-infra` 的 MySQL 8.4 和 MinIO 实例。不会修改、停止旧业务服务，不执行生产部署。

## S2 Python 知识准备服务

先在 `ai-runtime/` 执行 `uv sync --frozen --python 3.12`。回到项目根目录：

```bash
python3 deploy/local.py init-ai --reference-env /已授权的来源项目/deploy/.env
python3 deploy/local.py start-ai
python3 deploy/local.py status
python3 deploy/local.py stop-ai
```

`init-ai` 仅在已有授权时从来源 `.env` 指定的百炼 CSV 读取 Embedding 密钥与接口，不执行来源脚本、不迁入其他配置、不调用模型。已存在本项目配置则不覆盖。凭据在 `.local/ai-runtime.json`，Knowledge 专用服务密钥在 `.local/knowledge-ai-{private,public}.pem`，权限 0600；Python 只需公钥。不要提交或展示这些文件。

AI 绑定 `127.0.0.1:28085`，PID 在 `.local/ai-runtime.pid`，日志在 `.logs/ai-runtime.log`。存活端点仅证明 HTTP 可用，内部接口和必要配置见 [P01/P02](../docs/design/knowledge-first-20261020/PROCESSING_CONTRACTS.md)。该进程目前不接收公开网关流量，也不直接写入知识数据库。

首次获授权的 `init-ai` 建立 `.local/embedding-budget-20261020.json`，上限 1 元；已有配置不重建缺失账本。未知结果仍计入预留，额度不足则拒绝继续。该文件属于本轮操作预算，不能删除后重跑来重置额度；不是供应商账单或正式租户计费系统。

S2 的 `processing_smoke.py` 可独立重算准备摘要，现仍保留调用级预留；与 S3 适配器批次预留叠加时会更保守。当前完整链路使用下文 `publication_smoke.py`，由适配器统一预留，不需为 S3 先重跑 S2 付费验收。

## S3 持久处理与双索引

先完成上述 MySQL/S3/AI 初始化和模型调用授权，再运行：

```bash
python3 deploy/local.py init-search
python3 deploy/local.py test
python3 deploy/local.py start
ai-runtime/.venv/bin/python deploy/publication_smoke.py
```

`init-search` 拉取固定 Qdrant 1.19.2 digest，构建固定官方 ES 8.19.23 基础镜像与同版本 smartcn 插件，启动本项目独立实例。Compose 为 `deploy/search/compose.yaml`，复用已有 `dev-infra` 网络；仅开放 loopback 26333/29200，512 MiB/1 GiB 内存与各 1 CPU。首次镜像拉取、插件下载需要网络；不访问旧 lc4j 搜索数据。`stop-search` 只停止本项目实例，不删除卷。准确镜像地址见 Compose 与 Dockerfile。

私密认证保存在 `.local/search.json`；ES 应用账号仅能访问自己 dev/test 索引，Qdrant 管理 key 仅用于本项目实例。ES 数据与快照各用独立命名卷，Qdrant 另有数据卷；本机 HTTP 无 TLS 不作为远程部署模板。重复初始化校验维度、距离和 mapping，不覆盖不兼容 schema。禁止把原 `.local/` 凭据删掉后重新初始化已有卷。

| 资源 | 开发 | 集成测试 |
| --- | --- | --- |
| Qdrant collection | agentic_knowledge_text_v1 | agentic_knowledge_text_v1_test |
| ES index | agentic-knowledge-text-v1 | agentic-knowledge-text-v1-test |
| ES user/role | agentic_knowledge_dev | agentic_knowledge_test |

本机脚本检测到搜索配置后开启处理，worker **仅领取 `local-dev` 租户**；测试环境关闭后台 worker，由集成测试显式领取。其他租户受理的数据保持等待，不把它们误标为已处理。一般部署配置默认关闭 worker，启用前须明确作用租户、凭据与模型预算。

每实例一个 worker，租约 60 秒、心跳 10 秒，原文与准备对象放本项目 S3。只有 Qdrant 与 ES 均确认后，MySQL 才原子发布文档与任务。失败通过正式 retry API、当前 revision 与有限退避恢复；已接受准备和成功 sink 复用。未接受准备就崩溃时，恢复可能再次消耗模型；每个实际 HTTP 模型批次由 AI 适配器预先锁定本轮账本额度，缺失/损坏/耗尽即拒绝，不能手工清零。

`publication_smoke.py` 使用少量合成资料，经真实网关上传、处理、搜索、引用；只暂停本项目 ES 制造单索引失败，再重启 Knowledge 并重试。恢复状态保存到 `.local/s3-smoke-state.json`，失败后重跑复用原任务，不生成另一批付费任务。实际费用以账单为准；Embedding 与后续聊天各自的 1 元预算不互相借用。

升级前已为初始测试索引创建 ES `agentic_local/s3-prepatch-20261020` 快照，保存在 `agentic-ai-search_elastic-snapshots` 卷；Qdrant 快照下载到 `.local/search-backups/20261020-prepatch/`。快照创建不代表恢复验收通过；降级不能直接将旧镜像挂到升级后的数据卷。搜索投影可由权威准备包重建，但本片尚无一键全量重建/清理工具。

## 前提与启动

- JDK 21、Maven、Python 3 标准库、OpenSSL、Docker。
- `dev-infra-mysql84-1`（127.0.0.1:43306）、`dev-infra-minio-1`（127.0.0.1:49000）正在运行，外部网络 `dev-infra` 存在。
- mc 镜像固定 `minio/mc:RELEASE.2025-08-13T08-35-41Z`；首次可由 Docker 获取，应用不会运行依赖安装。

```bash
python3 deploy/local.py init
python3 deploy/local.py init-tasks
mvn -B -ntp package
python3 deploy/local.py test
python3 deploy/local.py start
python3 deploy/local.py token
python3 deploy/smoke.py --restart
python3 deploy/local.py status
python3 deploy/local.py stop
```

`init` 首次检查数据库、账号及桶未被占用，再将随机凭据保存到 `.local/runtime.json`（0600）并创建资源。重复执行沿用原凭据，不重置账号密码。若有同名资源而无所有权记录，停止；初始化中断后保留记录并重跑，不删除资源。

| 资源 | 开发 | 集成测试 |
| --- | --- | --- |
| MySQL database | agentic_knowledge | agentic_knowledge_test |
| 应用账号（仅库内 DML） | agentic_app | agentic_test_app |
| 迁移账号（库内 DML + 必要 DDL，无 DROP） | agentic_migrate | agentic_test_migrate |
| S3 bucket | agentic-ai-knowledge | agentic-ai-knowledge-test |
| S3 account（仅自己桶 list/get/put） | agentic_app | agentic_test_app |

本地 MySQL/S3 连接限定 loopback；示例关闭 MySQL TLS 仅适用于此本机开发入口。生产应由环境提供 TLS、IAM/JWKS 策略、轮换、备份和部署配置，不复用测试密钥。

## 进程与验证入口

| 进程 | API | management（只绑定 127.0.0.1） | 就绪依赖 |
| --- | --- | --- | --- |
| edge-gateway | 127.0.0.1:28080 | 29080 | JWT 验签及内部签名材料有效、进程接受请求 |
| knowledge-service | 127.0.0.1:28084 | 29084 | 真实 DB/原文桶；处理启用时增加 AI 存活与双索引 schema |
| async-task-service | 127.0.0.1:28087 | 29087 | 独立任务 MySQL 与三套身份公钥 |
| conversation-service | 127.0.0.1:28088 | 29088 | 独立会话 MySQL；中央离线仍允许读取已保存原问题 |

管理端点：`/actuator/health/liveness`、`/actuator/health/readiness`、`/actuator/prometheus`。health 不显示依赖细节；gateway readiness 不代替知识服务 readiness。链路由 Micrometer/OTel 传播，默认不开远程导出；当前不新增观测集群。

应用日志在 `.logs/{knowledge,gateway,tasks,conversations}.log`；PID 与本地密钥在 `.local/`。`stop` 验证 PID 对应本仓库 jar 后发送 SIGTERM；不强杀，不清空数据。`start` 检查端口冲突并等待真实 readiness；进程未成功则保留日志供诊断。

本地测试 issuer 为 `urn:agentic-ai:local-test`，audience 为 `agentic-ai-api`；`token` 将短时令牌写到 `.local/test-token`，不输出令牌。测试 IAM 和内部签名使用不同 RSA 密钥，知识服务仅有内部公钥。**生产 IAM 接入尚未验证。**

```bash
curl --fail-with-body http://127.0.0.1:28080/api/v1/knowledge/documents \
  -H "Authorization: Bearer $(cat .local/test-token)"
curl --fail-with-body http://127.0.0.1:28080/api/v1/knowledge/ingestions \
  -H "Authorization: Bearer $(cat .local/test-token)" \
  -H "Idempotency-Key: local-first-upload-001" \
  -F "file=@/absolute/path/to/document.md"
```

切勿在带 shell tracing 的终端执行含令牌命令。首次仅支持 UTF-8 TXT/Markdown ≤1 MiB；受理返回 `RECEIVED/ACCEPTED`。处理启用且租户在 worker 范围内时，后续推进为 READY 或 FAILED；READY 才有 `publishedVersion`。AI 存活不证明模型账号、预算或生成质量可用。

## 持久化与故障边界

原文先写 S3，短 MySQL 事务再写文档/任务；没有跨存储原子事务。未知提交结果保留对象，不删除可能已被获胜任务引用的数据。同键重放依赖数据库唯一约束，可安全重新查询。暂未提供清理工具；非终态及仍需幂等的数据不删除。原文孤儿清理必须先核对引用、保留窗口及任务状态，在后续 worker 运维切片实现有界流程。

S1 的身份、时间、幂等键与 revision 记录受理；S3 通过 `knowledge_ingestion_event` 记录系统执行者、领取代次、动作和状态变化，不用原上传者冒充 worker。当前保留任务、准备包、引用块和事件；生产保留期限与清理传播需后续确定。

`local.py test` 将测试专用资源传给 Maven Failsafe；测试产生的记录保留在独立测试库，使用随机租户避免覆盖原数据。不提供 drop、truncate 或跨项目清理命令。

## 持久 AI 任务（S5A）

`python3 deploy/local.py init-tasks` 在 dev-infra MySQL 创建 `agentic_tasks` / `agentic_tasks_test`，分别使用 `agentic_task_app` / `agentic_task_test_app` 与对应 migrate 账号；应用只有库内 DML，迁移账号无 DROP。随机凭据及独立 task-submit/task-worker RSA 密钥保存在忽略的 `.local/`（0600）。重复初始化不改已有数据或轮换密码；无所有权记录时不接管同名资源。

`mvn -B -ntp package` 后使用 `python3 deploy/local.py start --service tasks`；停止仍走同一入口 `stop --service tasks`，只终止本项目进程。该模块不需要模型、PG、S3 或旧业务服务即可验收中央协议。T01 见 [任务契约](../docs/design/knowledge-first-20261020/TASK_CONTRACTS.md)。目前只接收内部会话服务的任务；S5D 的独立问答 worker 和 UI 真实验收见下文。

`ai-runtime/.venv/bin/python deploy/task_smoke.py` 验证真实网关、三类签名身份、输入拒绝、任务/事件/完成幂等；保存 `.local/s5a-http-state.json` 以便中断后先核对原任务，不能删除该文件后盲目重做未知结果。完成后重启本项目 tasks，再运行同脚本 `--restart-check`。`deploy/task_fault_smoke.py` 仅启动临时故障进程 28097/29097，连接不可达地址验证 503 与 readiness DOWN，不中断共享 DB；测试结束终止自己创建的子进程。

MySQL V1 已在两个任务库应用，后续必须新增迁移；任务及事件暂不自动清理，生产保留期和删除传播另行确认。SSE 使用持久事件有界批次，携带游标重连，断线不会新建任务或重置预算。PostgreSQL 准备写、中央凭据提交与真实 LangGraph 恢复在 S5B 接入并验证。

## PG 检查点恢复（S5B）

`python3 deploy/local.py init-checkpoints` 复用 `dev-infra-postgres16-1`（16.15 / 127.0.0.1:45432），仅创建本项目 `agentic_checkpoints` / `agentic_checkpoints_test` 与各自 app/migrate 角色。所有权和随机密码存 `.local/checkpoints.json`（0600），无记录不接管同名资源。原生迁移由迁移身份运行，平台 V1 补齐全部表列中文注释和不可变保护，摘要写入账本；已应用迁移不得改写。应用无 DDL/DELETE 权限，两个项目库各自撤销 PUBLIC CONNECT。

先运行 `uv sync --frozen` 安装匹配的 psycopg binary/pool，再启动 tasks。项目根目录执行：

```bash
AGENTIC_CHECKPOINT_IT=1 ai-runtime/.venv/bin/pytest ai-runtime/tests/test_task_checkpoints_integration.py -q
```

这 11 项真实测试每次创建专用合成租户/任务，记录 `.local/s5b-*.json`；不会触发模型或删除数据。其中租约故障只修改自己的合成任务行，不停共享 MySQL/PG。连接池、statement 时限、应用权限、重复迁移、并发接管、丢失响应、中央终态和解码拒绝均已验证。结果见 [S5B TEST_RESULT](../docs/qa/ai-checkpoint-recovery-20261021/TEST_RESULT.md)。

## 知识会话与可靠交接（S5C）

先完成 `init`、`init-tasks`，再运行 `python3 deploy/local.py init-conversations`，创建独立的 `agentic_conversations` / `agentic_conversations_test` 与各自 app/migrate 账号。所有权与随机凭据保存在 `.local/conversations.json`（0600）；重复初始化保留原凭据与问题。V1 已在两个库应用，后续新增迁移，不编辑旧 V1。

构建后用 `python3 deploy/local.py start --service conversations`；启停与默认全服务命令均已接入。Gateway 路由 `/api/v1/conversations`，读写 scope 为 `conversation:read` / `conversation:write`。会话服务仅拿到自身数据库/提交签名与 Gateway 公钥；不会注入 Tasks worker 私钥或 Knowledge 数据库配置。详见 [Q01–Q03 契约](../docs/design/knowledge-first-20261020/CONVERSATION_CONTRACTS.md)。

本地初始化默认启用 `local-dev` 单租户交接 worker；每次领取一个问题，HTTP 最多 2 在途、5 秒、256 KiB，15 秒数据库租约，5 次自动尝试和最多 3 次显式恢复。`SUBMITTED` 仅表示中央任务受理，最终回答仍由 Tasks API 返回；本机问答 worker 由 S5D 的显式命令单独启动。Tasks 暂时不可用时原问题仍可读；需要前序答案的提问返回明确依赖错误，不保存伪造上下文。Actuator 提供交接活跃/积压/结果/耗时、HTTP 在途和执行池指标。

`ai-runtime/.venv/bin/python deploy/conversation_smoke.py` 首次执行真实 Gateway/会话/Tasks 联合验收，恢复标识保存在 `.local/s5c-http-state.json`。此命令包含临时 28098/29098 隔离进程：使用专用合成租户、真实中央提交后丢弃响应、重启恢复、不可达 Tasks/数据库故障。完成后只终止自己创建的进程，不停止共享数据库；合成中央任务已完成或取消，不会被后续付费 worker 领取。重启会话后用 `--restart-check` 查询原记录；中途失败先核对私密状态与任务，不删除记录来重新跑未知命令。

Java `-Pintegration` 覆盖真实数据库的并发/幂等/上下文/租约/次数上限，HTTP 联调和重启证据见 [S5C 验收](../docs/qa/knowledge-conversations-20261021/TEST_RESULT.md)。原问题与上下文没有自动清理策略；生产保留期、IAM、容量和部署仍未验收。回退会话制品前先停止交接 worker，保留数据库与中央任务；代码回退不会撤销已受理问题或生成任务。

本机已显式启动 S5D 单在途问答 worker，固定 local-dev，使用用户批准的 deepseek-flash。PG 准备写可能留下孤立旧分支，但恢复只认中央凭据。生产保留期、备份恢复和容量另行落实；本轮保留数据与私密记录。

## 问答执行（S5D，真实聊天已验收）

`python3 deploy/local.py init-qa` 仅建立独立 `runtime-knowledge-{private,public}.pem`；重复执行核对配对、保留原密钥，Knowledge 只收到公钥。启用后提供 K05 内部只读搜索/引用，Gateway 不转发内部路由。原公开 Knowledge 身份与新 worker 身份不互认。

先正常停止需重建的 Java 服务，再构建对应 jar、启动服务；避免覆盖运行中的 jar 使关闭时无法加载原类。该约束不涉及停止共享 dev-infra。

2026-10-21 用户批准 `deepseek-flash` 并更新凭据。独立 Python 环境锁定 LiteLLM 1.106.0 与 Prisma 0.15.0（错误分类所需，不启用数据库）；进程监听 127.0.0.1:28089。只有 `chat-default` 路由，转发 deepseek/deepseek-flash，显式关闭 thinking、重试/回退/正文日志/外部追踪。提供方密钥仅进入代理进程，worker 使用独立代理密钥。Embedding 与聊天各 1 元分别累计，不删除或重建账本恢复额度。

问答 CLI、配置字段、预算和退出边界见 [AI 运行时](../ai-runtime/README.md#问答执行与当前限制)。锁定制品、123 项依赖公告扫描、真实代理边界及实际问答均有独立证据，见 [S5D 验收](../docs/qa/knowledge-qa-execution-20261021/TEST_RESULT.md)。普通 `serve` 不启动问答 worker；需要下列显式操作。

无需真实模型的恢复验收：

```bash
AGENTIC_QA_EXECUTION_IT=1 AGENTIC_CHECKPOINT_IT=1 ai-runtime/.venv/bin/pytest ai-runtime/tests -q
```

该命令仅对本项目创建随机租户/合成任务，使用本机 TCP/SSE 模型替身和临时预算文件。5 项 S5D 联合测试覆盖检索后/生成后恢复、最终重验、流中取消、真实预检与 SIGTERM 退出；`.local/s5d-*.json` / 辅助 s5b 记录保留。不会读取旧语料或调用付费模型。S5D 的真实知识接口另有独立验收，付费检索需额外显式开关，日常回归不要开启。


```bash
uv sync --project deploy/model-proxy --frozen --python 3.12 --no-build
# 首次由密钥管理工具注入 DEEPSEEK_API_KEY；不把密钥写入命令或仓库。
python3 deploy/local.py init-chat
python3 deploy/local.py start-chat
python3 deploy/local.py start-qa
python3 deploy/local.py status
# 停止/回退先停止领取任务，随后停止出口。
python3 deploy/local.py stop-qa
python3 deploy/local.py stop-chat
```

首次 `init-chat` 创建 `.local/model-proxy.json`、`chat.json`、`qa-worker.json` 与 `chat-budget-20261020.json`，权限 0600。再次执行只预检、保留文件；任何一份已有而另一份缺失时拒绝自动重建。新密钥仅更新私密 providerKey，再按上述顺序正常重启；不改代理密钥或预算账本。就绪仅 GET /v1/models，不使用会付费的代理 /health 检查。

`AGENTIC_PROXY_IT=1` 的 pytest 使用实际代理进程与本机合成提供方，验证型号转换、无重试和日志边界，不消费真实模型。真实业务验收显式运行 `ai-runtime/.venv/bin/python deploy/qa_smoke.py --confirm-paid`，复用 `.local/s5d-real-model.json` 原任务与幂等键；未知提供方返回会停下，不能删除状态再试。脚本只对合成资料建问答，并测试无资料路径；已完成记录再次运行不新增模型调用。

本次计价核验日期 2026-10-21，按官方峰值未缓存输入 2 元/百万 token、输出 8 元/百万 token，并在客户端使用字节上界、1024 输出上限与 2 倍余量。[官方计价](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)。正式累计预留为聊天 0.092600 元/4 次、Embedding 0.20 元/20 批，均非提供方账单；实际费用未查询。重启验证保留配置及两个预算摘要，不增加请求。

## 评测定义（S8B）

Knowledge 追加 V4，保存评测集、不可变修订和幂等命令回执；应用启动前沿既有迁移入口执行，不能修改已执行的 V1–V4。无需新服务、定时器或模型配置。前端 `/quality` 可创建题目、限定已发布资料、逐段标注证据，并查看历史修订；[E01](../docs/design/knowledge-first-20261020/QUALITY_CONTRACTS.md) 是唯一契约。

保存结果未知时先按原键查询 `/api/v1/knowledge/evaluations/commands/{key}`，或以完全相同输入/键重试；不得通过更换键制造重复修订。版本冲突保留草稿并核对新修订；资料发布或权限改变时隐藏旧定义正文，原提交回执仍可查询。S8B 不运行模型，不重置任何预算，也不提供执行或基线按钮。

真实 MySQL 测试沿 `deploy/local.py` 的 `test` 环境运行 `mvn -B -ntp -pl knowledge-service -am -Pintegration verify`，勿把本机数据库凭据放进命令或日志。前端 `pnpm exec playwright test tests/quality-real.spec.ts` 使用现有已发布操作资料且不调用模型；保留 `.local/s8b-quality-state.json` 恢复状态。验收边界与已知限制见 [S8B TEST_RESULT](../docs/qa/quality-datasets-20261021/TEST_RESULT.md)。

## 持久评测运行（S8C）

先沿用已批准的 Tasks / Knowledge / PG / QA 配置和模型预算。初始化独立角色后重新构建和启动 Tasks、Knowledge、qa-worker：

```bash
python3 deploy/local.py init-evaluation
mvn -B -ntp -pl knowledge-service,async-task-service -am package
python3 deploy/local.py stop-qa
python3 deploy/local.py stop --service tasks
python3 deploy/local.py stop --service knowledge
python3 deploy/local.py start --service tasks
python3 deploy/local.py start --service knowledge
python3 deploy/local.py start-qa
```

`init-evaluation` 仅建立 `knowledge-evaluation-{private,public}.pem` 与 `runtime-evaluation-{private,public}.pem` 独立身份，并为已有 `.local/qa-worker.json` 增加绝对路径 `evaluation_private_key`；不会覆盖已有密钥、模型、检查点或账本，不调用模型。未初始化不启用新图。知识服务持有自己的Tasks私钥和worker公钥；Tasks只持有相应公钥，worker只获得自身配置。不要把这些内容提交或发给浏览器。

Tasks追加V3、Knowledge追加V5，已执行迁移不能改写。默认关闭的 `knowledge.evaluation` / `task.identity.evaluation` 由本地已初始化配置启用；本地交接租户仍仅local-dev。后台交接一次一条，未知受理与取消都使用原taskId；交接耗尽在界面显式重试。`qa-worker` 顺序轮询原QA、work和新增 `knowledge-evaluation-v1`，一次只执行一个任务。本图600秒/202步/最多100题，旧图期限不扩大。

前端评测集详情选择已保存修订运行；中央确认报告后才能查看逐题结果。失败可恢复原检查点，但模型调用发出而检查点未提交的窗口可能再次计费；两个累计预算不会重置。恢复配置必须与首次运行的模型/profile一致，不可拿确定性测试的synthetic profile改成真实模型继续跑。

确定性协议/UI验收前正常停止qa-worker，设置 `AGENTIC_EVALUATION_IT=1` 与已存在的机制定义 `AGENTIC_EVALUATION_DATASET`，运行 `ai-runtime/tests/test_evaluation_execution_integration.py` 或前端 `quality-run-real.spec.ts`；固定聊天/召回只在测试工具内，报告经真实接口持久化。原任务记录在 `.local/s8c-evaluation-it-*.json` 与前端 `.local/s8c-quality-run-state.json`，未知结果先查询，不能盲目重跑。确认无排队或执行中的验收任务后恢复worker。当前真实模型质量基线由S8E交付，协议测试不替代它。

## 研究与只读助手（S6/S7）

Tasks 启动时按原迁移账号应用 V2（kind 注释与 owner/kind 分页索引）；保留 V1 及旧问答数据。现有 `start-qa` worker 顺序领取问答和 `knowledge-work-v1`，不启动第二个付费 worker。界面入口为 [研究](http://127.0.0.1:28086/research) 和 [只读助手](http://127.0.0.1:28086/assistants)。仅检索当前用户的已发布知识，不提供互联网搜索或业务写入工具。

```bash
# 不产生模型调用：隔离租户的 HTTP 受理、分页、身份、SSE 与取消
ai-runtime/.venv/bin/python deploy/work_smoke.py
# 仅在原预算授权内运行；先核对 .local/s6-work-smoke.json，保留原任务号
ai-runtime/.venv/bin/python deploy/work_smoke.py --confirm-paid
```

第一次真实验收的协议拒绝任务和探针已保留，不删除状态重试；已成功的五个案例只读重放。浏览器创建/取消验收前需正常 `stop-qa`，完成并确认无排队任务后 `start-qa`。当前累计保守预留：聊天0.510952/1元（14次）、Embedding0.26/1元（26批），非提供方账单；[重启证据](../docs/qa/research-readonly-20261021/restart-replay.json)确认凭据/账本不变、无新增调用。

回退时先停止 worker 及新建工作任务，保留中央任务和PG；V2是兼容扩展无需删除。旧 worker 不会处理工作图，新任务应交兼容版本恢复或由所有者显式取消，不改 task graph、不回滚账本、不用旧项目接管。没有执行生产部署。
