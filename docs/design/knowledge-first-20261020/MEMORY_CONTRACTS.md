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
