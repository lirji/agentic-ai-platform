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

管理端点：`/actuator/health/liveness`、`/actuator/health/readiness`、`/actuator/prometheus`。health 不显示依赖细节；gateway readiness 不代替知识服务 readiness。链路由 Micrometer/OTel 传播，默认不开远程导出；当前不新增观测集群。

应用日志在 `.logs/{knowledge,gateway}.log`；PID 与本地密钥在 `.local/`。`stop` 验证 PID 对应本仓库 jar 后发送 SIGTERM；不强杀，不清空数据。`start` 检查端口冲突并等待真实 readiness；进程未成功则保留日志供诊断。

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
