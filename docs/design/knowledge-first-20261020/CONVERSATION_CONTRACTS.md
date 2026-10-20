# Q01–Q03：知识会话与可靠任务交接

状态：S5C 冻结会话受理、问题记录、上下文关联、任务交接和查询；Q02 复用已冻结 T01。模型检索/生成节点、答案内容 schema 和 React 问答页由紧随其后的执行片补齐，不用本片模拟结果冒充真实问答。

## 权威与范围

- Java `conversation-service` 拥有会话、原始问题、顺序、上下文关联与交接账本，MySQL 独立库；中央 Tasks 拥有执行状态、事件和最终答案。会话记录引用 taskId，不复制另一份可写答案/终态。
- Python 通过中央签发的任务输入执行；不得直接读会话库。用户和租户只来自已验签身份，SQL 必含 tenant/owner 谓词。未知/越权资源统一 404。
- 原 langchain4j-platform 固定 `468cec4` 的 ConversationController/ConversationHistoryReader 提供“Java 有界会话上下文、生成与引用分离”的参考；不迁入默认 chatId、ThreadLocal 身份、旧记忆、语义缓存、影子流量和旧模型实现。
- 当前每轮最多关联同会话一个已成功的前序轮次，传入该轮的 taskId/问题供执行器读取已确认答案；不自动拼接无限历史。正文原样保存，UTF-8、非空、无 NUL；question 最多 2000 Unicode 码点，title 最多 100。
- 每个会话最多 200 轮，列表分页。知识范围每轮显式固定：`documentIds` 是 0–20 个不重复 canonical UUID，空数组代表该 owner 全部获授权已发布资料；排序归一化，不代表已授权，执行和引用时由 Knowledge 重新校验。

## 身份与错误

- Gateway 增加 `/api/v1/conversations/**`，换发 `aud=agentic-ai-conversation`；读 `conversation:read`、写 `conversation:write`。公共接口不接受用户指定 tenant/owner，也不暴露服务 JWT。
- 会话使用原独立 task-submit 签名角色调用 Tasks：iss=`urn:agentic-ai:conversation`，aud=`agentic-ai-tasks`，azp=`conversation-service`，sub 为受理 owner，tenant 绑定；TTL 60 秒、iat/nbf/exp 必需。
- T01 兼容增加 `GET /internal/v1/tasks/{taskId}`，同提交者签名，scope=`task:inspect`，按 tenant/owner 查询同一公共 Task View。POST 提交仍只认 task:submit；worker 与外部身份不能借此访问。此读取用于核验前序回答和未知结果，不给会话 worker 权限。
- 复用平台 `{code,message,traceId}`，401/403 使用 PlatformError。模块错误：`CONVERSATION_INPUT_INVALID` 400、`CONVERSATION_NOT_FOUND` 404、`CONVERSATION_IDEMPOTENCY_CONFLICT` 409、`CONVERSATION_REVISION_CONFLICT` 409、`CONVERSATION_CONTEXT_INVALID` 409、`CONVERSATION_LIMIT_EXCEEDED` 409、`CONVERSATION_TASK_UNAVAILABLE` 503、`CONVERSATION_BUSY` 429、`CONVERSATION_STORAGE_UNAVAILABLE` 503、`CONVERSATION_DISPATCH_REJECTED`（账本安全原因）。
- 请求 JSON 最多 32 KiB，拒绝未知字段、重复键、数字/布尔与字符串隐式转换。幂等键为 1–128 ASCII 可打印字符，数据库保存其 SHA256；同作用域同键同规范命令返回原资源，不同命令 409。未知返回重放原命令，不新建随机键。

## Q01：创建与提问

### 创建会话

`POST /api/v1/conversations`，Idempotency-Key，body=`{"title":"知识问答"}`。

返回 `201 Conversation`：`{conversationId,title,revision:0,createdAt,updatedAt}`，UUID 由服务生成；同键同输入重放仍返回同一资源。当前不提供重命名、共享和删除接口。

### 提交问题

`POST /api/v1/conversations/{id}/turns`，Idempotency-Key：

```json
{"expectedRevision":0,"question":"资料中的约束是什么？","documentIds":[],"contextTurnId":null}
```

返回 `202 Turn`；只代表本地可靠受理，不能宣称中央任务已创建或答案已生成。

`Turn`：`{turnId,conversationId,sequence,question,documentIds,contextTurnId,taskId,dispatchState,dispatchAttempts,retryCount,revision,errorCode,createdAt,updatedAt}`。

- `sequence` 从 1 递增；会话 revision 同步递增，表示问题顺序。Turn revision 只表示交接账本版本，不代替中央 Task revision。
- `dispatchState`：`PENDING` → `SENDING` → `SUBMITTED`；未知 HTTP 返回进入有界重试，耗尽或确定拒绝为 `FAILED`。`SUBMITTED` 仅表示中央已经确认受理。
- 同键先返回原 Turn，不因当前会话 revision 已变化拒绝正常重放。不同键同 expectedRevision 并发只允许一个插入；会话行锁、版本与唯一序号保证不重复。
- contextTurnId 非空时，必须属于该 owner 的同会话前序轮次、知识范围完全相同且中央状态 SUCCEEDED。中央不可用返回 503、不写新问题；不把未完成/失败回答纳入上下文。执行时仍重验上下文答案和引用权限。
- 不声明“整个会话永远只有一个运行任务”：不同轮次可各自执行，恢复旧失败任务不改写已受理轮次的输入或上下文关联。UI 默认关联最近成功轮次，进行中的文字不作为下一轮上下文。

### 交接重试

`POST /api/v1/conversations/{id}/turns/{turnId}/retry-dispatch`，body=`{"expectedRevision":N}`，返回 `202 Turn`。

只允许 FAILED 且 retryCount<3；仍保留原 taskId、规范输入和中央幂等键，恢复本地交接不新建第二个中央任务。调用方遇到 409 先刷新 Turn。取消/恢复执行中的任务继续使用 T01，不以交接重试替代模型任务恢复。

## Q03：读取

- `GET /api/v1/conversations?cursor=&limit=20`：`{items:[Conversation],nextCursor:null|string}`；limit 1–50，按 createdAt DESC/id DESC 稳定分页，游标为服务生成的时间/ID 不透明编码，格式错误 400。
- `GET /api/v1/conversations/{id}`：Conversation。
- `GET /api/v1/conversations/{id}/turns?after=0&limit=20`：`{items:[Turn],nextCursor:null|sequence}`；sequence ASC，limit 1–50。无权会话 404，不返回可推断的计数。
- `GET /api/v1/conversations/{id}/turns/{turnId}`：Turn；保存 taskId 后按 T01 `GET /api/v1/tasks/{taskId}` 读取中央状态/最终结果。交接未确认时可能返回任务 404，应显示“正在交接”，不重建任务。
- 问题读取不依赖模型或 Tasks 在线；答案/流式读取则由中央接口给出真实可用性。S5C 不缓存上游错误为成功空答案。

## Q02：事件与结果

直接使用 T01 events/stream/cancel/resume。流式 TEXT_DELTA 是临时展示，只有中央 SUCCEEDED 的 result 是最终答案；断线带原游标重连，页面刷新按会话记录的同一 taskId 恢复。S5C 不新增重复事件流，不假造答案或进度。

## 持久交接与 Runtime

- 会话插入/问题插入/会话 revision 更新/初始 PENDING 交接在本库短事务内完成。Turn 行兼作有界分派账本，不增加 Kafka 或无需求的通用 Outbox 平台。
- 中央 taskId 在本地受理时固定，提交 key=`conversation-turn:{turnId}`。中央 input 固定为 `{schemaVersion:"knowledge-qa-input-v1",conversationId,turnId,question,documentIds,contextTurnId,contextTaskId,contextQuestion}`；无上下文时后三项 null。kind/runtime/graph 继续 T01 的 KNOWLEDGE_QA/langgraph-v1/knowledge-qa-v1。
- 分派线程按配置 tenant 领取，事务内 `FOR UPDATE SKIP LOCKED`，15 秒租约/递增 epoch；每次领取至多一条，网络调用在事务外，5 秒完整 HTTP 期限、最多 2 在途、256 KiB 响应、无 HTTP 自动重试。落账必须 CAS 当前 epoch/owner/未过期租约，旧执行者不能覆盖。
- 每个受理周期最多 5 次领取；失败后按 2/4/8/16 秒持久 nextAttemptAt 退避，最后 FAILED。进程退出保留租约，重启重新领取；未知成功提交可以重放同一中央请求。中央 409/400 拒绝记录安全原因，鉴权/配置错误不无限重试。
- 使用独立单线程循环与有界停止，不套用业务定时任务平台；显式不可变上下文跨线程，追踪新建 span，不复制用户 JWT 或事务。池/活跃/失败/交接延迟通过既有 Actuator/Micrometer 观察，不用 tenant/task 高基数标签。
- 端口 28088/29088，MySQL dev_infra 8.4 中新库 `agentic_conversations` / `_test`，各自 app/migrate 角色，V1 有完整表列注释与约束；私密配置保存忽略 `.local/conversations.json`。本片不新增模型调用或生产部署。

## S5C 验收

真实 MySQL + Gateway + Tasks：会话与问题创建/分页/重启持久；同键重放/异参冲突；同 revision 并发只接收一个；同用户跨租户/同租户异用户均不能读取、关联或重试；上下文只接受同范围成功轮次；中央提交成功响应丢失、本地进程退出/租约接管均最终绑定同一个 taskId；分派失败有界、人工恢复仍用原命令；旧 epoch 无法落账；Tasks 不可达时原问题可查且不伪造成功；错误/body/权限/连接/退出界限。S5A 受影响协议回归、构建/Code Hygiene/文档/Git 通过后交付。真实模型与可见问答页下一片单独验收。
