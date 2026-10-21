# T01：持久 AI 任务（S5A 冻结）

本契约细化已批准的中央任务边界。S5A 交付 Java/MySQL 权威及协议，S5B 已验证 PostgreSQL Checkpointer 联合恢复；用户侧 Q01–Q03 已在 [会话契约](CONVERSATION_CONTRACTS.md) 冻结，S5C 兼容增加 owner 委托读取。当前没有可用问答页面。

## 所有权与来源

`async-task-service` 独占任务输入、执行租约、用户可见进度/结果、持久事件及检查点提交凭据。复用参考仓库 `langchain4j-platform@468cec425dd160e80cfc3ab378d3440a8767e4d5` 中 AsyncTaskStore / JdbcAsyncTaskStore / AsyncTaskEventJournal 的中央租约、条件终态与事件语义；按本项目 MyBatis-Plus/XML、身份和有界协议裁剪。不迁入默认内存库、旧 DAG 执行器、Kafka/webhook、旧业务表或数据。

原始会话问题归 conversation 领域；这里的输入是某次执行的不可变副本，不成为长期记忆。首个 kind=`KNOWLEDGE_QA`，runtimeVersion=`langgraph-v1`、graphVersion=`knowledge-qa-v1`。未知版本/类型拒绝；研究和助手在后续契约扩展。

## 身份与传输

- 外部 `/api/v1/tasks/**` 只接收网关换发的 RS256 JWT，issuer=`urn:agentic-ai:edge`、audience=`agentic-ai-tasks`，读 `task:read`，取消/恢复 `task:write`。tenant/sub 来自验签 claim，所有查询含租户和所有者谓词；不存在或不属于当前人统一 404。
- 内部创建 `/internal/v1/tasks` 使用独立 conversation 公钥，issuer=`urn:agentic-ai:conversation`，azp=`conversation-service`，audience 同上，scope=`task:submit`，sub 为委托用户。
- S5C 内部读取 `/internal/v1/tasks/{id}` 使用同一 conversation 签名角色与 scope=`task:inspect`，同时约束 tenant/owner；不会授予 worker 权限，submit scope 不能代替 inspect。
- worker `/internal/v1/task-worker/**` 使用独立 worker 公钥，issuer=`urn:agentic-ai:runtime-worker`、azp=`ai-runtime-worker`、scope=`task:worker`，sub 为固定 worker 标识，tenant 为部署授予的处理租户。worker 不接用户 JWT，也不能请求其他租户。
- 内部服务 JWT 必须有 iat/nbf/exp，TTL≤60 秒，无时间宽限。内部接口不经网关公开。验签材料独立；任何用户正文/模型参数不能改变 worker 或 owner。
- 单个请求最多 96 KiB UTF-8，JSON 未知字段、重复字段、数字/字符串强转拒绝。错误形状沿用 `ApiError {code,message,traceId}`，不返回原始异常。

## 接口与字段

事件 code 为 STATUS/PROGRESS/TEXT_DELTA/CHECKPOINT；CHECKPOINT 仅推进客户端游标，不展示内部凭据或当成最终结果。SSE 成功必须使用 text/event-stream；错误继续返回统一 JSON。

JSON 时间均 UTC ISO8601。UUID 为规范小写带连字符格式；revision/epoch/sequence 非负整数，事件序号从 1 开始。

| 接口 | 请求 | 成功 |
| --- | --- | --- |
| POST `/internal/v1/tasks` | `Idempotency-Key` 1–128 个 ASCII `[A-Za-z0-9._:-]`；`{taskId,kind,runtimeVersion,graphVersion,input}`，input 为对象、序列化≤64 KiB | 202 Task；同租户/owner/幂等键同规范 JSON 重放原 task，同键异参 409 |
| GET `/internal/v1/tasks/{id}` | conversation 签名、`task:inspect` 与原 owner 委托 | 200 同一公共 Task；不存在/异 owner 统一 404，不暴露原始输入或租约 |
| GET `/api/v1/tasks/{id}` | 读 scope | 200 Task（不含输入、租约 owner 或检查点） |
| GET `/api/v1/tasks/{id}/events?after=0&limit=100` | after≥0，limit 1–100 | 200 `{events,nextCursor,terminal}` |
| GET `/api/v1/tasks/{id}/stream` | `Last-Event-ID`，默认 0 | 200 SSE，当前最多 100 条，id=sequence，event=稳定 code；批次末发送 heartbeat 注释与 retry=1000 后关闭，客户端带游标重连；不创建任务 |
| POST `/api/v1/tasks/{id}/cancel` | `{expectedRevision}` | 200 Task；CAS 成功即 CANCELLED，停止后续提交；已经取消同 revision/更新 revision 重放，其他终态冲突 |
| POST `/api/v1/tasks/{id}/resume` | `{expectedRevision}` | 202 Task；仅 FAILED、retryable=true、resumeCount<3 可恢复到 QUEUED，输入/历史预算保留，下一次领取新 epoch |
| POST `/internal/v1/task-worker/claim` | `{runtimeVersion,graphVersion}` | 200 `{task: Lease或null}`；按 createdAt/id 领取租户内最早可领取任务 |
| GET `/internal/v1/task-worker/{id}` | worker 身份 | 200 Lease（中央对账；终态允许查询，不能据此恢复执行） |
| POST `/internal/v1/task-worker/{id}/heartbeat` | `{epoch}` | 200 Lease；仅活跃 owner/epoch 且数据库未过期；延长 60 秒，原 epoch 不变 |
| POST `/internal/v1/task-worker/{id}/events` | `{epoch,operationKey,type,payload}` | 200 Event；type=PROGRESS/TEXT_DELTA，payload 对象≤8 KiB，按任务/operationKey 同指纹幂等 |
| POST `/internal/v1/task-worker/{id}/checkpoint` | `{epoch,operationKey,checkpointId,checkpointNamespace,sha256}` | 200 CheckpointReceipt；checkpointNamespace S5A 只允许空串，checkpointId UUID；threadId 由服务器生成 `{taskId}:{epoch}` |
| POST `/internal/v1/task-worker/{id}/complete` | `{epoch,operationKey,result}` | 200 Task；result 对象≤64 KiB，结果 SHA256 与原完成命令一致可重放，响应丢失先 GET 对账 |
| POST `/internal/v1/task-worker/{id}/fail` | `{epoch,operationKey,errorCode,retryable}` | 200 Task；errorCode 限 AI_UNAVAILABLE / BUDGET_EXCEEDED / INPUT_REJECTED / EXECUTION_FAILED；不会从输入接收任意错误提示 |

Task：`taskId,kind,status,revision,attemptCount,resumeCount,retryable,errorCode,result,resultSha256,createdAt,updatedAt`。Lease 包含 `task: Task`，另含 `tenantId,ownerId,input,runtimeVersion,graphVersion,epoch,workerId,leaseExpiresAt,checkpoint`。CheckpointReceipt：`checkpointId,checkpointNamespace,threadId,epoch,sha256`。Event：`sequence,type,payload,createdAt`。

## 不变量与失败窗口

1. QUEUED → RUNNING → SUCCEEDED/FAILED；QUEUED/RUNNING 可取消。FAILED 允许显式恢复（最多 3 次），CANCELLED/SUCCEEDED 不恢复。租约到期可接管，epoch 严格增加；单次显式恢复周期最多 5 次领取，耗尽成为 retryable FAILED。MySQL UTC 时钟裁决期限，所有写入先锁任务行再写事件，事务最多 5 秒，不在数据库事务内调用远程服务。
2. 创建/状态/事件在同一短事务提交；唯一键守住创建和事件重放。读取最后一条事件不等于终态，只有中央 Task 终态为准。事件上限每任务 4096，终态保留一个位置；超限拒绝事件而不静默丢弃。
3. heartbeat 不增加用户 revision；用户取消/完成竞争以任务行锁裁决。旧 owner、旧 epoch、过期租约、取消后完成一律拒绝，即使客户端尚在执行。完成已提交的同 operationKey/结果重放允许查询成功，但不能改写终态。
4. 检查点采用“PG 不可变准备写 → 中央凭据提交 → 只恢复凭据指向的快照”。中央 receipt 由有效租约 CAS/行锁提交，旧 worker 不能推进可恢复指针；物理 PG 孤立写可能存在，但不得被当作已提交检查点。新 epoch 使用独立物理 threadId，不读 PG 的任意 latest，也不搬运无凭据 pending writes。S5B 必须实现适配层并验证所有写路径；尚未通过前不启用 AI worker。
5. 结果先保存在图检查点，再幂等 complete。中央成功但图收尾失败时，恢复首先查询中央结果指纹，不再次生成。PG/中央无跨库原子事务；仅同一组件提交成功不能证明全链路成功。
6. 恢复前重验用户可见范围；检查点不存 JWT、私钥或模型 key。当前只读工具允许节点失败后的有界重放，模型预算在每次真实请求前独立预留，不能因重领任务重新获得费用额度。
7. 暂不自动删除任务/事件/凭据；保留期未由用户确定。最多 100 条读取，数据库索引有界，应用/迁移账号分开。归档与删除传播另行批准；本地测试只新增隔离合成记录。

## 错误目录

`TASK_INPUT_INVALID` 400、`TASK_NOT_FOUND` 404、`TASK_CONFLICT` 409、`TASK_LEASE_LOST` 409、`TASK_EVENT_LIMIT` 409、`TASK_DEPENDENCY_UNAVAILABLE` 503；身份沿用 AUTH_REQUIRED 401 / AUTH_FORBIDDEN 403。未知异常 INTERNAL_ERROR 500。失败结果码是上述允许列表，展示文案统一由任务模块映射。

## S5A 验收

真实 MySQL 验证：创建同键并发/异参；并发领取只一个胜者；数据库过期接管/旧 epoch 拒绝；heartbeat、取消、迟到完成；事件序号/同键重放/边界；完成未知响应对账；checkpoint 指针不得被旧 worker 推进；失败显式恢复和次数界限；租户/owner 隔离；真实 HTTP 身份拒绝、网关路由、重启可查。S5A 不调用聊天模型，也不声称 PG 联合 fencing 或用户问答已验收。
