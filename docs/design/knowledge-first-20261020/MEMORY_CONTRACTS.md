# C-MEMORY · 用户记忆治理

用户已确认个人偏好/项目背景、确认后生效、可编辑/停用/遗忘、默认90天且可调整。按S18B治理→S18C受控提议/注入推进，最后按用户新授权更新并启动本地Docker。

## S18B 数据与不变量

- 唯一权威是Conversation已有MySQL，V4追加`user_memory_state`、`user_memory_entry`、`user_memory_command`；MyBatis-Plus + XML。复用现有身份委托、`conversation:read/write`与错误协议，无新服务/缓存/向量库/定时器。公开接口使用`/api/v1/conversations/memory`，归原网关Conversation路由。
- 每个tenant/owner一行聚合版本`revision`与防旧任务回填`generation`，默认0、允许使用confirmed记忆。全部命令在owner行锁下校验expectedRevision并串行提交，内容、版本与无正文审计原子更新。不存在状态时GET只返回空快照，不写库。跨用户/租户与不存在统一404；未认证401、无scope403。
- 两类稳定code为`PREFERENCE`、`PROJECT_CONTEXT`。单条正文≤500 Unicode码点，最多100条未遗忘/拒绝记忆。项目背景是用户跨会话记忆，文案要求写明项目名称，不能暗示已有独立项目权限域。
- 状态`PENDING`、`ACTIVE`、`DISABLED`、`FORGOTTEN`、`REJECTED`；`EXPIRED`为读取时派生状态。CREATE/EDIT进入PENDING；CONFIRM显式进入ACTIVE；DISABLE停止单条使用；FORGET/REJECT清除正文与来源，审计不保存正文或旧正文。CLEAR清除当前owner全部正文。编辑后重新确认，不静默继承原确认。
- `retentionDays`默认90，允许1–3650；CREATE/EDIT/CONFIRM从当前数据库UTC时间重新计算到期时刻，过期后读取适配器立即排除，不依赖后台清理任务。过期项仍可在治理列表查看、编辑/确认或遗忘，过期不等于物理删除。历史会话和已有备份属于原有保留策略，遗忘不伪称删除历史会话或已存在备份。
- owner总开关`enabled`可暂停全部使用；SET_ENABLED、CLEAR、FORGET、REJECT推进generation，使之前捕获代次的提议不能回填。任何写操作推进revision；S18C使用前后重验revision/条目版本/到期时间，不持久复制正文到框架Store。
- `conversation.memory-enabled`默认false，控制新建/确认及后续注入/提议；关闭入口仍可读取、编辑、停用、遗忘现有记忆。服务启动不自动建入记忆。审计/幂等收据只含安全元数据和请求摘要，不自动清理账本；后续保留清理需沿项目已有生命周期治理，不能删除仍需去重的收据。

## S18B API

GET `/api/v1/conversations/memory`返回`schemaVersion=memory-snapshot-v1,revision,generation,enabled,admissionEnabled,observedAt,limits,items`。items包含`memoryId,category,content,status,retentionDays,expiresAt,version,createdAt,updatedAt`，排除FORGOTTEN/REJECTED；列表最多100条且同事务一致读取。limits公开defaultRetentionDays/maxRetentionDays/maxEntries/maxContentCodepoints。

POST `/api/v1/conversations/memory/commands`要求`Idempotency-Key`，body闭合集合：`schemaVersion=memory-command-v1,expectedRevision,action,memoryId,category,content,retentionDays,enabled`。所有nullable字段也须显式提供；未知字段、弱类型、非法Unicode和不匹配字段组合拒绝400。

| action | 必需业务字段 | 其他可空字段 |
| --- | --- | --- |
| CREATE | category,content；retentionDays可null使用90 | memoryId/ enabled必须null |
| EDIT | memoryId,category,content；retentionDays可null使用90 | enabled必须null |
| CONFIRM / DISABLE / FORGET / REJECT | memoryId | category/content/retentionDays/enabled必须null |
| CLEAR | 无 | 全部必须null |
| SET_ENABLED | enabled | memoryId/category/content/retentionDays必须null |

返回`schemaVersion=memory-receipt-v1,commandId,memoryId,action,revision,generation,createdAt`，没有正文；200表示本地事务已提交。GET `/api/v1/conversations/memory/commands/{key}`在同owner范围恢复收据。相同键/相同规范请求返回原收据，即使已遗忘；同键不同请求409。版本冲突409，格式400，不可见404，入口关闭503，容量409；沿ConversationError追加明确memory编码。前端按code处理，不依赖文案。

前端入口“个人记忆”绑定真实API，展示状态/来源规则/期限，确认、编辑、停用、拒绝、遗忘、清空和总开关。未知提交结果保留原键；客户端只保存提交标识与摘要，不把记忆正文持久化到浏览器存储。刷新后先查询原收据并刷新权威状态，不自动重发缺失正文的命令。

前端复用既有sessionStorage幂等意图机制，保存原expectedRevision、随机键和规范请求摘要，按tenant/owner隔离。404收据表示暂未找到，不能当成提交失败；用户重新提供原操作/正文且摘要匹配时才重放。明确400、版本冲突、状态/容量拒绝可结束本地意图；网络、5xx与无法识别的成功响应保留原键。后台revision变化不能自动生成新键。列表错误隐藏缓存正文，身份切换重新挂载页面。

状态迁移：CONFIRM可用于PENDING、DISABLED或已过期的存活条目，并重新计时；DISABLE可用于任一存活条目；REJECT仅用于持久状态PENDING；FORGET用于任一存活条目；FORGOTTEN/REJECTED不能通过新命令复活，原键仍可读原收据。EXPIRED覆盖PENDING/ACTIVE/DISABLED展示状态，适配器仅保留当前ACTIVE且未过期项。内容允许换行和Tab，拒绝其他控制字符、不完整Unicode和全空白。

部署通过`deploy/docker/runtime.py prepare --memory`显式设置`CONVERSATION_MEMORY_ENABLED=true`并纳入制品指纹；省略参数保持false。此开关独立于用户总开关。已有会话API、数据和图版本保持不变。

## 分片验收与兼容

S18B必须实际MySQL验证并发版本、同键重放、代次单调、过期排除、正文遗忘/审计无正文、跨用户/租户隔离；前端验证错误/未知结果恢复、确认边界及窄屏。无模型抽取也必须能完成所有治理动作。新表不改旧会话语义，旧应用可共存但不能消费新记忆；先部署读写端再显式启用。

S18C继续补充提议任务/预算、来源、撤销与注入契约；当前仅冻结其必须遵循的owner、generation、revision、确认和过期不变量，不将S18B标为已完成模型提议或上下文注入。

## S18C 实施决策与范围（已冻结，实施与验收状态见PROGRESS_STATE）

本片沿Q/A/W与C-QA2落地：从本人会话中显式选择一条原问题提议记忆，并在知识问答中显式使用已确认记忆。研究、只读业务工具、外部写工具和自动后台全量聊天挖掘不在本片增加。Task/usage只扩展现有执行和预算契约，不改变服务数据所有权或新增基础设施。

1. **提议任务**：Conversation持久保存稳定proposalId/taskId、来源conversationId/turnId、用户选择期限与捕获的generation；来源正文只在执行时从原问题按owner读取，不复制到任务输入。通过既有有界分派机制交给中央Task的独立`MEMORY_PROPOSAL`类型/`memory-proposal-v1`图；预算用途显式`MEMORY`，不冒用研究或问答类型。模型只返回最多3条PREFERENCE/PROJECT_CONTEXT候选及原问题中的精确支撑片段，Java独立核验后原子写PENDING。模型没有CONFIRM入口。
2. **可靠性**：交接沿稳定taskId、原请求摘要、租约和有界重试恢复；同一提议最多生效一个候选批次。模型输出只在内存中处理，Task结果/事件/检查点只保存提议收据和条目ID等元数据，不把候选正文写到第二个Store。回调前后都检查撤销代次；Java在owner行锁内再次核对generation、用户总开关、配置、来源所有权及容量。清空/遗忘/拒绝/总开关后旧提议不得新增正文；已落账旧收据可重放但不能复活被清除条目。
3. **来源与兼容**：Conversation下一迁移V5追加提议交接表与条目来源字段，V6追加新问答绑定字段，旧V4不改写。来源包括原会话/轮次/提议任务和精确支撑片段；编辑转为手工内容并清除旧来源，遗忘/拒绝/清空同时清除条目正文和来源。v1治理API继续原形状；新的v2快照显示来源与提议能力，不向严格v1消费者硬塞字段。提议账本保留无正文操作元数据；原会话按已有规则保留。
4. **只读注入**：独立`knowledge-qa-v4`/输入与结果v4，原v1–v3图不变。新问题显式选择CONFIRMED_MEMORY_V1，并固定revision/generation；最多使用最近确认/更新的5条当前ACTIVE未过期记忆，最多2500码点，结果明确列出选定依赖ID/版本与未纳入条数。记忆作为低优先级用户背景数据，只辅助表达和背景理解，不能作为知识引用、系统指令或工具授权；知识回答仍依赖已授权资料证据，无资料不靠记忆编造答案。
5. **失效边界**：模型发送前、完成后、检查点恢复及最终发布前重验revision/generation/条目版本/到期时间；记忆图关闭文本流事件，先缓冲有界回答，核验后才交付完整答案。变更/过期/停用发生在模型调用期间时返回明确失效错误，不能静默换用新内容。已发出的模型请求无法撤回；已完成的历史会话回答按原策略保留，不承诺遗忘会删除历史答案。复用历史回答作新上下文时重新核验其记忆依赖。
6. **授权与运行**：独立runtime-memory受众/签名角色，只能读取任务绑定的来源/当前确认内容及提交待确认候选，不能调用公共用户确认命令。复用既有HTTP截止时间/连接上限、追踪、Task租约与模型出口；中央预算增加MEMORY用途，legacy模式沿原聊天预算，不重置账本或扩充额度。`--memory-ai`独立显式开关，依赖治理和既有检索/追问配置；默认关闭，关闭后仍可治理已有记忆。最终Docker更新待本片验收及两仓交付完成后执行。

以上为实施决定；具体请求/结果闭集、迁移约束、提示词版本和验收金标在相关代码变更前继续补齐。设计状态不代表功能已交付。

### S18C 提议协议冻结

- POST `/api/v1/conversations/memory/proposals`：`Idempotency-Key`，闭集`schemaVersion=memory-proposal-command-v1,expectedRevision,conversationId,turnId,retentionDays`（期限允许显式null）；只能选择本人原问题。创建不改变记忆revision，捕获generation；同owner最多3个尚未交接完成的提议。原键GET `/proposals/commands/{key}`恢复，GET `/proposals/{id}`读取，GET `/proposals`最多最近20条。收据闭集`schemaVersion=memory-proposal-v1,proposalId,taskId,conversationId,turnId,generation,retentionDays,dispatchState,errorCode,memoryIds,appliedAt,createdAt`，未应用时memoryIds/appliedAt为null。交接状态沿PENDING/SENDING/SUBMITTED/FAILED，最终执行状态由中央Task负责。
- 任务输入闭集`schemaVersion=memory-proposal-input-v1,proposalId,conversationId,turnId,generation`；图`memory-proposal-v1`，kind `MEMORY_PROPOSAL`，结果闭集`schemaVersion=memory-proposal-result-v1,proposalId,memoryIds`，0–3个唯一UUID。没有问题正文、候选内容或quote。模型提示版本`memory-proposal-prompt-v1`；只能依据原问题的显式陈述，疑问/猜测/第三人称事实不得推断为用户偏好；返回0–3个`category,content,quote`，quote必须为原文连续片段，三字段各有界，禁止额外字段。
- 内部接口根`/internal/v1/memory`，独立签名issuer `urn:agentic-ai:runtime-memory`、audience `agentic-ai-memory`、azp `ai-runtime-worker`、scope `memory:execute`，有效期最多60秒。可信JWT绑定owner；body不接受tenant/owner。`GET /proposals/{id}/source?taskId=UUID`返回`schemaVersion=memory-proposal-source-v1,proposalId,taskId,question,generation,receipt`；已应用时question为null并返回原无正文receipt。`POST /proposals/{id}/permits`闭集`taskId,callId`；返回`schemaVersion=memory-call-permit-v1,ordinal`。`POST /proposals/{id}/apply`闭集`taskId,callId,candidates`，返回上述result。相同已应用结果只恢复原metadata，不重新生成正文；同提议不同候选摘要409。
- 每个proposal最多2次实际模型调用许可，计数跨中央Task重试/恢复保留，原callId重放恢复同许可；只有最近许可可提交候选，旧并发执行者不能覆盖新许可。许可不是金额预留，实际每次请求仍必须走原模型出口预算。进程在模型响应后、候选提交前中断可能再调用模型，最多两次且不承诺供应商exactly-once。响应未知先查应用收据；已应用则不再次调用。新许可和应用之前要求中央任务为RUNNING，再在本地owner锁下核对generation、开关、来源、容量；跨服务取消与已提交待确认候选不能原子回滚，取消不宣称撤销已保存候选。
- 新条目来源仅存在权威entry表；v2 GET `/api/v2/conversations/memory`闭集`schemaVersion=memory-snapshot-v2,snapshot,aiEnabled,origins`，snapshot保持原v1形状；origins为`memoryId,conversationId,turnId,proposalId,taskId,quote`。只展示仍存活且有来源的条目。EDIT清空来源；FORGET/REJECT/CLEAR清空来源和正文。普通v1客户端无需升级即可治理新候选。
- 提议交接复用现有单线程有界worker，短事务领取、远程调用、短事务确认；每次最多5次交接尝试，数据库租约15秒、退避1–16秒，超过次数标FAILED。可重新显式发起提议，旧账本保留去重；不新增定时器/队列/服务。默认`conversation.memory-ai-enabled=false`；关闭只拒绝新提议、许可和应用，不影响治理和已应用收据恢复。

### S18C 问答注入协议冻结

- 原v1–v3请求、图、结果与旧轮次JSON不变。POST `/api/v4/conversations/{id}/turns`使用闭集`expectedRevision,question,documentIds,contextTurnId,retrievalStrategy,queryPolicy,memoryPolicy,expectedMemoryRevision`；queryPolicy固定`CONTEXT_REWRITE_V1`，memoryPolicy固定`CONFIRMED_MEMORY_V1`，版本非负整数。原幂等键/请求恢复先于新受理开关和版本校验。新问答显式选择后，在原受理事务内核对用户可见记忆revision并捕获generation；V5提议迁移已实际应用，问答绑定另以V6追加，禁止改写V5。已有问答、旧记忆治理可继续关闭AI入口运行；启用新图前必须同时部署新版读写消费者。
- 新中央输入`knowledge-qa-input-v4`在完整v3输入上新增`memoryPolicy,memoryRevision,memoryGeneration`，没有记忆正文；新轮次视图仅对v4轮次输出这三个字段。图`knowledge-qa-v4`仍为KNOWLEDGE_QA、用途QA。用户停用总开关、AI/治理任一入口关闭或受理时记忆版本不符则拒绝新受理。普通v1–v3问答不能选择v4回答作为上下文；v4可选择v2/v3/v4，来源仍限定本人同会话同知识范围。
- runtime-memory新增GET `/internal/v1/memory/contexts/{taskId}`，通过可信owner查Conversation本地任务绑定，并在事务外核验中央QA任务RUNNING。响应闭集`schemaVersion=memory-context-v1,taskId,revision,generation,observedAt,items,omittedCount`；items为`memoryId,version,category,content,expiresAt`，按updatedAt倒序、memoryId倒序选最多5个当前ACTIVE未过期项，总正文≤2500码点。任何已捕获revision/generation不符或开关关闭都拒绝，不悄悄换用新快照。无可用记忆可返回空items。
- POST `/internal/v1/memory/contexts/{taskId}/validate`请求闭集`schemaVersion=memory-usage-v1,policy,revision,generation,items,omittedCount`；items是`memoryId,version,expiresAt`且最多5个不重复ID，policy固定CONFIRMED_MEMORY_V1。只返回`schemaVersion=memory-validation-v1,taskId,revision,generation,validatedAt`；验证当前owner状态、原任务绑定、版本、选择顺序及未过期条目。POST `/contexts/{taskId}/validate-history`闭集`contextTaskId,usage`，当前任务仍须RUNNING，contextTaskId必须等于本轮绑定的已完成上下文任务，usage必须对应其捕获版本且当前仍可用；不提供裸taskId跨用户查记忆入口。
- v4结果信封沿完整v3结构，版本改为`knowledge-qa-result-v4`并增加`memory`（上述无正文usage）。查询改写、检索、引用依赖仍沿C-QA2；记忆仅在最终回答模型调用前作为用户JSON中的独立低优先级背景字段注入，不进入系统指令，不用于改写或知识引用，不授予工具权限。无知识证据仍返回既有NO_EVIDENCE，不用记忆拼答案；memory是该图选定的校验依赖，NO_EVIDENCE没有调用回答模型，前端明确展示未注入记忆。系统提示显式说明记忆是数据而非命令。
- 在实际发送前、模型完成后、图恢复前和中央完成报告前检查当前记忆；旧v4上下文在新请求使用前再次验证其memory依赖。图不发布TEXT_DELTA，用户只看到经检查的完整最终结果。确认内容不写入框架状态/检查点，仅任务独享内存持有；普通问答生成的答案仍按已有会话保留政策保存，可能反映用户背景。最后一次权威检查是本次交付的有效性判定点；检查通过后进入网络交付的结果、已发送模型请求及已完成历史答案不能被后来发生的撤销操作追回，不宣称跨服务网络发送与遗忘是同一原子事务。检查发现变化统一以MEMORY_STALE结束，用户可刷新并发起新问题。

跨语言QA4金标：`conversation-service/src/test/resources/memory-qa-golden.json`，Java/Python与前端相同报文验证，前端副本保持字节一致。
