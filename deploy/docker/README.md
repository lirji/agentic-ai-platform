# 本机 Docker 应用运行

此入口将已有 Gateway、Knowledge、Tasks、Conversations、AI HTTP、LiteLLM、问答 worker 和 React 工作台作为 8 个独立容器运行，另有 1 个仅转发本机搜索端口的容器。复用 `dev-infra` 的 MySQL 8.4、MinIO、PostgreSQL 16，以及现有 `agentic-ai-search` 的 Qdrant/ES；不创建或管理共享数据库容器、网络和卷。

工作台：<http://127.0.0.1:28086>；网关：<http://127.0.0.1:28080>。沿用固定 `workbench-user/local-dev` 本地测试身份、原数据库/桶/索引与两个累计模型预算。前端提供构建后的 `dist`，复用原同源身份桥；不属于生产身份接入方案。S10E 已完成后端与前端查重、原请求恢复和真实浏览器验收；本机运行快照 `52c4cd0088b45e33`，证据见[本片验收](../../docs/qa/document-dedup-20261027/TEST_RESULT.md)。部署初版记录仍作为历史保留。

## 前提与构建

要求支持 `env_file.format: raw` 的 Docker Compose、JDK 21、Maven、Python 3，并已按 [本机运行](../README.md) 初始化项目专用凭据/数据和搜索服务。镜像固定版本/摘要，Python 与 Node 使用项目锁文件；不把本机虚拟环境、node_modules、密钥或账本打进镜像。

```sh
python3 deploy/docker/runtime.py prepare \
  --backend /absolute/path/agentic-ai-platform \
  --frontend /absolute/path/agentic-ai-platform-web \
  --parsing --pdf --docx --structured-profile STRUCTURE_V3
python3 deploy/docker/runtime.py build --backend /absolute/path/agentic-ai-platform
```

`prepare` 从实际工作树复制 Git 跟踪文件和非忽略的新文件，因此包含尚未提交的代码。快照位于后端 `.local/docker-runtime/releases/<源码摘要>/context`；`manifest.json` 保存两仓基线提交、逐文件 SHA-256 与未提交属性。Maven 只在此副本运行，不覆盖活动的宿主 JAR。原 `.local/` 中配置生成派生副本；两个预算文件直接挂载原文件，不复制或重置额度。快照拒绝符号链接。

## 切换与验证

先完成对应源码的必要检查，再核对当前本地租户的在途任务。首次从宿主进程切换时，在原目录依次执行 `node deploy/local.mjs stop`（前端）、`python3 deploy/local.py stop-qa`、`stop-chat`、`stop`、`stop-ai`（后端），正常退出后备份项目开发数据库。不要让新旧 Knowledge writer 同时运行。

```sh
python3 deploy/docker/runtime.py up --backend /absolute/path/agentic-ai-platform
python3 deploy/docker/runtime.py status --backend /absolute/path/agentic-ai-platform
python3 deploy/docker/runtime.py smoke --backend /absolute/path/agentic-ai-platform
```

`up` 接受当前快照的成功构建记录，等待真实 health/readiness，240 秒内未就绪返回失败。`smoke` 只读页面、资料、会话、任务和评测列表，不上传、不做向量查询、不调用付费模型。容器可运行不等于未完成业务已通过功能验收。

## 配置与资源

| 项目 | 行为 |
| --- | --- |
| 网络 | 应用共用 gateway 网络命名空间，只有 gateway 连接既有 dev-infra。保留 Python 对 HTTP 只允许 loopback 的校验；应用间继续用 127.0.0.1 |
| 宿主端口 | 仅 127.0.0.1:28080、28086；其他应用/Actuator/模型出口只在共享命名空间访问 |
| 外部数据 | MySQL dev-infra-mysql84-1:3306、S3 dev-infra-minio-1:9000、PG dev-infra-postgres16-1:5432；搜索经固定 loopback 26333/29200 转发到既有 Qdrant/ES 容器 DNS |
| 凭据 | `.local/docker-runtime/releases/<摘要>/runtime/<服务>/`，0600；各服务只挂载所属密钥、注入所属数据库/身份配置 |
| 预算 | 原 `.local/embedding-budget-20261020.json` 与 `chat-budget-20261020.json` 按文件挂载；失败/未知预留不释放，不新增额度 |
| 开关 | 生命周期读取原 lifecycle.json；解析/PDF/DOCX/profile 使用 prepare 显式参数，默认关闭新格式受理 |
| 资源 | Java 384 MiB 堆；Knowledge 1 GiB（含解析子进程），其余 Java 768 MiB；AI 768 MiB、proxy/worker 各 1 GiB、Web 512 MiB、搜索转发 96 MiB；有界 CPU、只读根目录和独立临时目录 |
| 重启 | unless-stopped，40 秒正常退出期限；共享网络需整体升级/重建，不支持独立横向扩容 |
| 健康 | Java readiness（包含真实双索引/桶依赖）、AI live、LiteLLM 认证 models、worker 配置/PG/预算、Web 本地会话；模型生成质量不属于健康检查 |

搜索转发不解析 HTTP、不读取认证材料，只能连接上述固定目标；最多 32 条连接，每方向 64 KiB 分段并等待背压，连接建立 3 秒/总时长 30 秒。该适配仅服务同一台 Docker Desktop 上的已有依赖，不用于远程明文传输。

不要单独重建 gateway 后保留旧网络命名空间中的其他容器。升级先 `stop`，再整体 `docker compose ... up -d --force-recreate --wait`；显式指定 `.local/docker-runtime/compose.env` 和本目录 compose.yaml。固定身份入口仅适用于本机。

## 停止与恢复

```sh
python3 deploy/docker/runtime.py stop --backend /absolute/path/agentic-ai-platform
docker compose --env-file /absolute/path/agentic-ai-platform/.local/docker-runtime/compose.env \
  -f /absolute/path/to/deploy/docker/compose.yaml logs --tail 80 knowledge
```

普通停止不删除容器、卷、网络、密钥、恢复状态或预算。不要在 Docker 应用运行时执行旧 `local.py start`；它不识别 Docker 内部端口，可能产生重复 worker。

含 V11 的快照首次启动会把 Knowledge 从 V10 升为 V11，增加仅 ACTIVE 内容参与的唯一性。V11 前必须停止旧 writer；迁移后不能直接启动不支持 V11 的旧 JAR。配置故障优先修复并重启相同 V11 镜像；数据库逆向恢复不在默认停止/回滚中执行。原备份只证明保存成功，不代表恢复演练通过。

现行聊天程序要求价格在 7 天内核验；过期需实际核实后按原规则更新，不能仅改日期绕过。缺配置、账本或余额会关闭 worker，不能自动建新账本。

本次结果见 [部署记录](../../docs/deployment/docker-20261027/PROGRESS_STATE.md)。日志、私密备份和快照保存在后端 `.logs/docker-deployment-20261027/`、`.local/docker-deployment-20261027/`、`.local/docker-runtime/`；仍用于恢复时必须保留。
