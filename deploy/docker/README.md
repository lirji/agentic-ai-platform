# 本机 Docker 应用运行

此入口将已有 Gateway、Knowledge、Tasks、Conversations、AI HTTP、LiteLLM、问答 worker 和 React 工作台作为 8 个独立容器运行，另有 1 个转发固定搜索/IAM端口的容器。复用 `dev-infra` 的 MySQL 8.4、MinIO、PostgreSQL 16，以及现有 `agentic-ai-search` 的 Qdrant/ES；不创建或管理共享数据库容器、网络和卷。

正式登录入口：<http://localhost:28086>；网关仅绑定本机28080。OIDC模式复用现有Casdoor（localhost:18090，容器DNS auth-governance-casdoor:8000）和已登记的local-agentic个人空间。账号由本机0600文件`.local/identity/ACCESS.md`提供，密钥只注入Gateway。旧local-dev数据保留原归属，不自动转成正式用户资料。

本轮部署状态和验收以[最新Docker部署进度](../../docs/deployment/docker-capabilities-20261109/PROGRESS_STATE.md)为准。`--identity-mode local`保留原127.0.0.1测试身份入口兼容模式，不与正式模式混用。

## 前提与构建

个人记忆治理通过prepare的`--memory`显式启用，默认关闭新建/确认；关闭后仍可管理和遗忘已有内容。新表、有效期和兼容边界见[S18B运行说明](../../docs/qa/governed-memory-20261109/RUNTIME.md)。模型提议和知识问答只读使用通过独立`--memory-ai`开启（要求`--memory`），并配套启用既有检索和追问；运行、独立密钥及回退见[S18C运行说明](../../docs/qa/controlled-memory-20261109/RUNTIME.md)。

要求支持 `env_file.format: raw` 的 Docker Compose、JDK 21、Maven、Python 3，并已按 [本机运行](../README.md) 初始化项目专用凭据/数据和搜索服务。镜像固定版本/摘要，Python 与 Node 使用项目锁文件；不把本机虚拟环境、node_modules、密钥或账本打进镜像。

```sh
python3 deploy/docker/runtime.py prepare \
  --backend /absolute/path/agentic-ai-platform \
  --frontend /absolute/path/agentic-ai-platform-web \
  --parsing --pdf --docx --structured-profile STRUCTURE_V3 \
  --identity-mode oidc --committed-only
python3 deploy/docker/runtime.py build --backend /absolute/path/agentic-ai-platform
```

正式模式先用`python3 deploy/docker/identity_callback.py --management /path/to/existing/management-client.json`给专属应用追加精确28086回调，保留28196回调；不改其他应用或重新生成账号。后续核对/恢复Docker回调使用此工具；旧`identity_local.py --apply`严格检查单回调基线，不应用于已追加Docker回调的应用。

`--committed-only`仅归档两仓HEAD，排除其他任务的工作区改动。快照位于`.local/docker-runtime/releases/<摘要>/context`，manifest记录提交、逐文件SHA-256与模式/解析开关；这些开关也参与摘要，避免不同身份构建共用镜像标签。Compose/Dockerfile同样从快照执行。省略该参数仍可用于明确的工作区联调，但不作为正式部署来源。Maven在快照构建，不覆盖宿主JAR；镜像由Compose导出Bake并加载到本机，不推镜像仓库。

原`.local/`配置生成派生副本；两个预算文件挂载原文件，不复制或重置额度。OIDC模式网页不挂载测试签名私钥，后台worker仅在派生配置中绑定local-agentic；原宿主配置不变。`current.json`表示当前准备的候选版本，部署成功与否必须核对容器和DEPLOYMENT_RESULT，不能仅以prepare结果判断。

## 切换与验证

先完成对应源码的必要检查，再核对当前本地租户的在途任务。已观察到12GiB Docker VM在旧应用与完整镜像检查并行时耗尽内存/swap；出现此情况应先正常停止本项目应用释放资源，再构建，保留共享基础设施。首次从宿主进程切换时，在原目录依次执行 `node deploy/local.mjs stop`（前端）、`python3 deploy/local.py stop-qa`、`stop-chat`、`stop`、`stop-ai`（后端），正常退出后备份项目开发数据库。不要让新旧 Knowledge writer 同时运行。

```sh
python3 deploy/docker/runtime.py up --backend /absolute/path/agentic-ai-platform
python3 deploy/docker/runtime.py status --backend /absolute/path/agentic-ai-platform
# local模式可使用runtime.py smoke；OIDC模式使用真实浏览器登录只读验收。
```

`up` 接受当前快照的成功构建记录，等待真实 health/readiness，240 秒内未就绪返回失败。正式浏览器验收检查两个普通账号、匿名/未授权拒绝、Cookie/CSRF、退出及业务列表；不上传、不检索、不调用付费模型。容器可运行不等于未完成业务已通过功能验收。

本轮可复验入口：在后端目录用Node 24执行`node .local/docker-latest-20261102/browser-smoke.mjs`，复用相邻前端的Playwright、既有私密身份及`.local/docker-identity-20261101/legacy-document.json`（切换前记录的旧租户资料ID）。结果与无凭据截图写入本轮部署目录；不保存trace，不做业务写入。

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
| 健康 | Java readiness（包含真实双索引/桶依赖）、AI live、LiteLLM 认证 models、worker 配置/PG/预算、Web 正式匿名session=401（local模式为本地会话200）；模型生成质量不属于健康检查 |

搜索转发不解析 HTTP、不读取认证材料，只能连接上述固定目标，OIDC模式追加18090→auth-governance-casdoor:8000；最多 32 条连接，每方向 64 KiB 分段并等待背压，连接建立 3 秒/总时长 30 秒。该适配仅服务同一台 Docker Desktop 上的已有依赖，不用于远程明文传输。

不要单独重建 gateway 后保留旧网络命名空间中的其他容器。升级先 `stop`，再整体 `docker compose ... up -d --force-recreate --wait`；显式指定 `.local/docker-runtime/compose.env` 和本目录 compose.yaml。两种入口均只适用于本机。

## 停止与恢复

```sh
python3 deploy/docker/runtime.py stop --backend /absolute/path/agentic-ai-platform
docker compose --env-file /absolute/path/agentic-ai-platform/.local/docker-runtime/compose.env \
  -f /absolute/path/to/deploy/docker/compose.yaml logs --tail 80 knowledge
```

普通停止不删除容器、卷、网络、密钥、恢复状态或预算。不要在 Docker 应用运行时执行旧 `local.py start`；它不识别 Docker 内部端口，可能产生重复 worker。

前次部署迁移至Knowledge V19、Tasks V8、Conversations V3。本次S9E/F与S18B/C追加至Knowledge V21、Tasks V9、Conversations V6。切换前正常停旧应用，保存三开发库SQL备份和原字段摘要；切换后校验版本、旧字段摘要与预算文件。迁移后不能直接重启不支持新schema的旧writer；配置问题优先修复同一新schema版本。不自动逆向恢复数据；备份成功不代表恢复演练通过。

现行聊天程序要求价格在 7 天内核验；过期需实际核实后按原规则更新，不能仅改日期绕过。缺配置、账本或余额会关闭 worker，不能自动建新账本。

历史部署记录保留在[20261027部署](../../docs/deployment/docker-20261027/PROGRESS_STATE.md)。本轮日志/私密备份位于`.logs/docker-latest-20261102/`、`.local/docker-latest-20261102/`；源码/镜像收据在`.local/docker-runtime/`。无清理授权时保留这些恢复材料。

S14A反馈可在后续获授权部署的prepare阶段显式添加`--feedback`，默认关闭，选项纳入快照指纹；需要现有Tasks引用签名配置。详见[反馈Runtime](../../docs/design/knowledge-first-20261020/OPERATIONS_RUNTIME.md)。本片仅接线，未更新已运行镜像。

S14B候选修订使用独立`--operations`，默认关闭；仅派生候选入口，不隐式启用处理/评测worker。准备私有校验副本前须对明确目标追加purpose搜索映射，步骤见同一Runtime文档。本片未执行prepare/build/up或更新运行镜像。

候选批准入库须显式同时指定`--operations --operations-publication`；批准接口另受既有处理/更新/代次门禁约束，不隐式开启worker或增加模型预算。

发布后探测与补偿须显式指定`--operations --operations-recovery`，默认关闭并纳入快照指纹。它独立于新批准开关，只生成需要重新评估批准的草稿；不会直接恢复旧发布或开启worker。

2026-11-02最新固定制品为`6b7bf063f040da7a`（后端7863ae3/前端c2d12fe），9容器健康、45业务表2716行及两预算摘要一致。后端已重新构建；前端源码和全部构建输入未变，复用已验证旧镜像并核对文件层完全相同，详见本轮前端复用收据。本次保持OIDC/PDF/DOCX/STRUCTURE_V3与原开关；sharing/feedback/operations及新的容量模式没有自动启用，LEGACY_LOCAL资金模式保留。容量策略启用另遵守[容量运行说明](../../docs/design/knowledge-first-20261020/CAPACITY_RUNTIME.md)，不能通过增副本或复制账本增加额度。

S16D只读运维指标可在后续明确部署的prepare命令增加`--operational-metrics`；默认关闭并纳入制品摘要。采集器与告警配置见[告警手册](../monitoring/RUNBOOK.md)，本片没有替换运行中的镜像。

## 固定制品与独立验证（S17A）

新`build`在成功后保存私密`artifacts.json`，逐服务绑定源码指纹与不可变镜像ID。`up`核对源码快照、清单、镜像标签解析结果，再使用ID覆盖启动；增加未登记源码、修改快照或移动标签均拒绝。旧`build-result.json`不替代新收据，旧环境的`status`/`stop`仍可用。正式发布使用`--committed-only`。

隔离入口复用本文件的Compose结构与已验证制品收据，原`.local/docker-runtime/current.json`保持。当前基底收据固定为`docs/deployment/docker-latest-20261102/deployment-result.json`；新Web按冻结输入构建本地身份模式，Knowledge从冻结源码构建以支持独立检索命名空间，其余服务保留真实旧来源。宿主使用已锁定Node24.22.0和JDK21/Maven进行编译/测试，Docker只在无网络模式封装，减轻共享VM内存压力。验证制品记录工作树输入摘要，不冒称正式提交制品。私密配置在`.local/release-validation/<id>`，资源所有权与阶段分别记入`resources.json`、`state.json`，失败必须使用同一个ID继续。

```sh
# 选择未使用的8位十六进制ID；恢复已有演练时直接复用其ID。
ai-runtime/.venv/bin/python deploy/docker/runtime.py validation-prepare --backend "$PWD" --validation-id 7f6a3c91
ai-runtime/.venv/bin/python deploy/docker/validation_probe.py artifacts --id 7f6a3c91
ai-runtime/.venv/bin/python deploy/docker/validation_probe.py baseline --id 7f6a3c91
ai-runtime/.venv/bin/python deploy/docker/runtime.py validation-up --backend "$PWD" --validation-id 7f6a3c91
ai-runtime/.venv/bin/python deploy/docker/validation_probe.py before-restart --id 7f6a3c91
ai-runtime/.venv/bin/python deploy/docker/runtime.py validation-stop --backend "$PWD" --validation-id 7f6a3c91
ai-runtime/.venv/bin/python deploy/docker/runtime.py validation-up --backend "$PWD" --validation-id 7f6a3c91
ai-runtime/.venv/bin/python deploy/docker/validation_probe.py after-restart --id 7f6a3c91
```

同一宿主一次运行一组隔离验证，端口固定为Web `127.0.0.1:28096`、Gateway `127.0.0.1:28090`，管理与内部端口不发布。验证基线已有时不能覆盖，直接从未完成步骤恢复。探针创建幂等合成会话，核对真实DML权限、同源身份边界、健康/就绪与数据/预算连续性；不生成问答或调用付费模型。新资源和合成资金不能用于正式费用，也不能以演练额度替代原累计账本。全部资源保留，普通停止不删除容器、数据、索引或私密证据。

应用DB身份仅有本库DML；Flyway仍由独立迁移身份执行启动迁移，不能宣称应用进程完全不持有迁移凭据。Qdrant复用本项目实例key并锁定独立集合，尚无集合级凭据隔离。AI健康仅表示HTTP存活，模型出口在本演练中禁用；完整验收条件见[C-RELEASE](../../docs/design/knowledge-first-20261020/RELEASE_CONTRACTS.md)。

共享宿主内存不足时，需要先明确是否允许本项目短暂停服。获授权后使用`validation-pause-original`；同一`--validation-id`下的`validation-restore-original`先停止隔离应用，再按原容器ID和imageId恢复现有应用。操作阶段写入`originalPhase`，中断时先读取状态和日志；禁止改成`up`重建旧环境或用新tag替代原镜像。应将恢复放入演练调用方的`finally`，成功与失败都执行。不得停止其他项目或共享中间件。

2026-11-09已将S9E/F与S18B/C产品部署为固定制品`57540a96db9de87b`，9容器健康；新增格式和记忆开关已启用，原业务数据/预算摘要保持，详见[当前部署收据](../../docs/deployment/docker-capabilities-20261109/deployment-result.json)。前述20261102脚本和收据为历史版本，本轮复验脚本在`.local/docker-capabilities-20261109/`，不会覆盖历史证据。
