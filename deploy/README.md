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

获授权后可运行 `ai-runtime/.venv/bin/python deploy/processing_smoke.py`，它发送少量固定合成文本，独立重算返回摘要，并先在 `.local/embedding-budget-20261020.json` 锁定预留费用；未知结果仍计入预留，达到 1 元则拒绝继续。该文件属于本轮操作预算，不能删除后重跑来重置额度；不是供应商账单或正式租户计费系统。

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
| knowledge-service | 127.0.0.1:28084 | 29084 | 进程接受请求、真实 DB、原文 bucket 可访问 |

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

切勿在带 shell tracing 的终端执行含令牌命令。首次仅支持 UTF-8 TXT/Markdown ≤1 MiB；成功返回 `RECEIVED/ACCEPTED`。本片没有处理 worker、向量发布和已完成问答，因此资料的 `publishedVersion` 保持 null。

## 持久化与故障边界

原文先写 S3，短 MySQL 事务再写文档/任务；没有跨存储原子事务。未知提交结果保留对象，不删除可能已被获胜任务引用的数据。同键重放依赖数据库唯一约束，可安全重新查询。暂未提供清理工具；非终态及仍需幂等的数据不删除。原文孤儿清理必须先核对引用、保留窗口及任务状态，在后续 worker 运维切片实现有界流程。

S1 的 `owner_id`、`created_at/updated_at`、任务 `idempotency_key/revision` 与文档的 `ingestion_job_id` 共同记录谁在何时提交哪次命令。当前没有后续写操作；增加 worker 更新前必须补充实际执行者与状态变更审计，不能用原上传者冒充系统执行身份。

`local.py test` 将测试专用资源传给 Maven Failsafe；测试产生的记录保留在独立测试库，使用随机租户避免覆盖原数据。不提供 drop、truncate 或跨项目清理命令。
