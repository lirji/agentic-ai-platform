# C-OPERATIONS · 所有者反馈与修正

协议登记：`operations-feedback-v1`；当前冻结范围S14A。用户2026-11-02授权自行处理，本轮按既有所有权采用资料所有者处理反馈并审批修正。S14B/C/D按原切片继续细化，不扩大为组织管理员。

## 权限与数据

- 提交需要knowledge:read和当前可读完整发布引用（GenerationCitationProtocol八分量）；不要求资料write，提交反馈不改变知识内容。只读共享用户可提交自己明确填写的反馈。
- Knowledge从当前资料行确定owner；客户端不得传tenant/owner/reporter/status。只返回本人提交或本人资料收到的记录，第三人/其他租户404；mine与inbox分别由reporter/owner索引分页。
- 每条消息最多2000 Unicode码点且UTF-8合法、非空白、不含NUL；不自动填入整段问题、回答、会话或研究正文。UI明确说明发送给资料所有者。类别稳定码CONTENT_ERROR、OUTDATED、MISSING_CONTEXT、ANSWER_ISSUE。
- 证据只保存完整引用身份，不保存原文快照。反馈文字是用户主动发送的记录；撤权/撤下后提交者仍可看自己发送的记录、owner仍可看已收记录，但原文读取仍由当前Knowledge引用规则控制。
- S14A状态仅OPEN，不能通过反馈API改正文或批准发布。未来状态/候选关联由S14B/C/D扩展。

## API

所有响应Cache-Control:no-store，UTC ISO8601时间；错误复用统一code/message/traceId。请求严格JSON，拒绝额外/重复key、尾随内容、浮点整数和隐式字符串转换，最大32768字节。

| 方法/路径 | 语义 |
| --- | --- |
| GET /api/v1/knowledge/feedback/capabilities | `{enabled,taskLinksEnabled}`，不披露配置或成员 |
| POST /api/v1/knowledge/feedback | 创建，必需Idempotency-Key规范UUID；200返回不可变Submission |
| GET /api/v1/knowledge/feedback/submissions/{key} | 仅原提交者查询原键Submission；关闭/撤权/证明过期仍可查询 |
| GET /api/v1/knowledge/feedback?view=mine或inbox&limit=20&cursor=... | `{items:Feedback[],nextCursor:null或opaque}`；1–20，createdAt DESC/id DESC稳定游标 |
| GET /api/v1/knowledge/feedback/{id} | 当前主体为reporter或资料owner，否则404 |
| POST /api/v1/tasks/{id}/feedback-proof | task:read；只验证本人成功任务的实际完整结果引用，返回`{proof}`，不写业务状态 |

Create：`{schemaVersion:"knowledge-feedback-v1",reference:GenerationReference,taskId:UUID|null,taskProof:string|null,category:Category,message:string}`。reference必需；taskId与taskProof同时为空或同时存在。旧三元组不得伪造完整身份，旧回答继续原阅读，从当前检索/新版回答引用发起反馈。

Submission：`{feedbackId,reference,taskId,category,message,status:"OPEN",createdAt}`，只交原提交者，作为原请求核对。所有写指纹取schema/reference.key/taskId/category/原message，不含短时proof；同键相同命令只返回原Submission，同键异命令409 IDEMPOTENCY_CONFLICT。先查已提交原键，再要求开关/证明/当前读权限；旧回放不产生第二条反馈。

Feedback：`{feedbackId,reference,taskId,taskAssociated,category,message,status,createdAt,submittedByMe,ownedByMe}`。只对reporter返回taskId；不同owner只知道已验证任务关联，不获得提交者任务访问入口。列表每页至多20条，每条消息有界；未知view/cursor拒绝。原始证明不持久化、不回显、不记录日志。

## 任务关联事实凭据

- 任务证明请求：`{reference:GenerationReference}`。Tasks用tenant+owner读取成功任务，要求generationGraph并在实际结果引用中精确匹配；完整可见性重验沿TaskKnowledgeVisibility。只在检索依赖清单出现不能冒充最终回答引用。
- JWS RS256；issuer复用urn:agentic-ai:task-knowledge-reader，aud唯一agentic-ai-knowledge-feedback-proof，azp=async-task-service，purpose=feedback-task-reference-v1，sub/tenant_id为任务本人；task_id、reference_digest绑定任务与规范完整身份；iat/nbf/exp严格有效，寿命最多60秒且零时间宽容。token最多8192字符。
- 独立aud与purpose使K08引用检查token不可当证明，证明也不能当任何原服务认证凭证。Knowledge先有正常Gateway可信登录主体，再验证proof同sub/tenant/task/ref；没有任意URL、远程JWKS或新增身份凭据。
- 默认knowledge.feedback.enabled=false、task.feedback-enabled=false；任务证明还要求既有task.knowledge-reader.enabled及Knowledge citation-reader公钥。直接引用反馈不强制启用Tasks，带taskId必须有可验证证明。

## 事务、错误与恢复

- V14新增knowledge_feedback（表/列中文注释），唯一键tenant+reporter+idempotency_key；反馈id主键，reporter/owner时间游标索引。既有MySQL、XML SQL、UTC DATETIME(6)、READ_COMMITTED短事务不超过5秒。
- 成员验证在事务外；事务内锁当前document，复用publishedScope与完整reference匹配，并复核owner/ACL。与撤权/撤下/更新串行裁决；不在事务内调用Tasks、IAM、模型或对象存储。唯一冲突在事务结束后按原键核对。
- INPUT_INVALID 400，NOT_FOUND 404（无权/无当前证据/错误任务），RETRIEVAL_EVIDENCE_STALE 409（完整引用已变化），IDEMPOTENCY_CONFLICT 409；新增FEEDBACK_DISABLED 503、FEEDBACK_PROOF_INVALID 400；数据库/成员故障沿既有503，不得当作空列表或成功。
- UI复用session intent：只存指纹和原键，不持久化反馈消息/proof；提交前读原回执，未知结果保留原键并提供查询，不能改键重复投递。确认结果核对完整原请求；错误/刷新后不展示旧权限来源正文。

## S14A 可观察验收

F01 当前owner/共享reader可提交，陌生人/跨租户/伪造owner/非当前引用拒绝。

F02 同键并发只产生一条；异内容冲突；响应丢失原键恢复；关闭/撤权后仍能核对原记录，不复活权限。

F03 mine/inbox/详情范围、游标、2000字/有界JSON与严格协议；owner不取得他人私有taskId/正文；无知识内容写入副作用。

F04 Tasks仅对本人成功且实际引用的完整身份签发；错引用、错owner、未成功、旧三元组、篡改/过期/错audience/用途/主体证明拒绝；签发后撤权仍拒绝新反馈。

F05 React引用/问答/研究入口、消息校验、提交确认/未知恢复、本人记录/owner收件箱/详情；桌面与414窄屏真实HTTP验证和截图实看。

F06 必需单元/真实MySQL/真实Tasks与Knowledge联合/静态类型构建/文档/代码卫生/分支与main CI；真实模型调用0，不执行生产部署。
