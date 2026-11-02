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

## S14B 候选修订与内容绑定评估

协议增量 `operations-candidate-v1`。本节沿用户“自行处理”的授权，在已选owner权限内冻结实现选择；不新增运营角色或模型裁判。候选是原资料完整修正版，反馈中的选中片段只作为问题证据，不能被默认为完整文件。

### 所有权、修订和输入

- 一个反馈关联一个长期候选标识，候选可以有多份不可变修订；只允许反馈对应资料的当前owner操作。reader、他人和跨tenant统一404。原资料必须仍可由owner处理，删除后不能新建/修改候选；原反馈记录仍保留。
- 每次保存绑定当前候选expectedRevision和原资料baseRevision，保存新修订而不覆盖旧内容。修订包含完整源对象的hash/type/size、标题、局部requiredText、内容摘要与创建时间；源指针不返回浏览器。更新同时确认当前原资料修订，原资料在用户编辑期间变化时409，草稿必须保留供用户核对。
- TXT/Markdown可编辑完整替换文本；PDF/DOCX通过上传完整修正文件，沿UploadRules和已有格式开关/大小/容器验证。没有新增文件类型。TXT/Markdown编辑内容可随候选详情返回给owner，附件只返回安全元数据；所有附件实际存入现有S3，不在页面写死内容。
- requiredText最多10个唯一、非空、合法UTF-8短句，每句最多200码点；它只检查精确校验副本中是否有单个提取片段包含该字面串，区分NOT_READY/PRESENT/MISSING。不能用包含检查替代语义、引用、检索或质量评估。
- `contentDigest`覆盖schema、标题、源hash/type/size、requiredText、baseRevision；即使仅修改要求或标题也产生新修订，旧评估/批准不能自动沿用。明确的历史修订仍可查看，不自动把旧报告改标成新内容通过。

### 私有校验副本

- 准备副本是显式操作。复用Knowledge SourceStore、已有ingestion worker、处理profile和双索引；新增目的编码STANDARD/OPERATIONS_PREVIEW，默认STANDARD。preview与候选具体修订在同一数据库短事务关联，不能先作为普通资料发布后再补隐藏标记。
- 每份候选修订最多一份副本；固定提交身份及同事务唯一约束处理并发/未知结果。S3写入不在事务内，副本准备失败显示真实job错误，重试沿已有入库作业状态，不新建通用调度器。
- 副本仅owner可读，普通资料列表/上传查重/默认无范围搜索与问答检索排除副本；双索引提前过滤且MySQL最终复核。显式选择该副本的owner质量评测/核对读取可用，资料详情显式返回目的与owner可见的候选关联，前端把副本标为只读校验材料并回到候选编辑；不把READY展示成原资料修正已生效。标准文档的原行为保持：K07普通详情仍是原13字段；仅副本详情追加purpose、candidateId、candidateRevision，三者同时存在。共享授权、直接资料更新/撤下/删除/重索引和对副本再建反馈均拒绝；专用候选流程控制其生命周期。
- 源相同的副本不挤占STANDARD资料的活跃内容查重槽；新增迁移调整生成列条件，不改V11历史。旧索引没有目的字段时按STANDARD兼容，PREVIEW由服务端可信资料行提供索引元数据，不接受模型或客户端自报。
- “副本准备完成”只表示校验语料已可用，不代表原资料已修正、生效或获批准。S14C批准后仍通过原资料的真实生命周期更新发布，保留原ACL与历史。

### 统一质量绑定

- 从候选进入现有质量评测编辑/运行/人工评分界面，复用S8定义、执行、报告和ReviewRules资格；不新建评估引擎、模型裁判或以requiredText返回语义通过。
- 可关联的评测定义必须属于本人、当前可用，全部问题的检索范围精确为当前副本，绑定源hash/type/版本与候选一致；证据由现有权威选择器取得。原资料的旧评测不能冒充新副本评测。
- 关联运行时保存runId、manifestDigest、reviewDigest、候选revision/contentDigest及副本完整发布身份。运行必须来自已关联的精确定义修订，报告已被中央终态确认；任何内容、发布代次、报告清单、人工评分摘要变化都使旧绑定STALE。依赖读取失败为UNAVAILABLE，不能显示为通过。
- 审批前置资格只在BUSINESS用途、现有ReviewRules.eligibility成立、所有requiredText检查PRESENT且绑定CURRENT时成立；界面表述为“满足审批前置”，不宣称现实模型质量已被本片机制测试证明。MECHANISM记录可用于调试展示，但不满足业务审批前置。实际批准由S14C显式owner动作产生。
- 运行提交与费用仍走现有质量操作及既有预算，保存/编辑候选不自动调用模型；本片验证用确定性模型端口，不增加付费预算。

### HTTP 与恢复

统一前缀`/api/v1/knowledge/operations`；GET要求knowledge:read，命令要求knowledge:write，并在服务端核验owner。响应no-store，严格JSON元数据拒绝未知/重复字段/类型转换；文件沿现有Multipart边界。正文、源对象地址及短时凭据不进入日志。

| 路径 | 语义 |
| --- | --- |
| GET /capabilities | enabled与已有可受理格式/准备能力；不暴露密钥或内部地址 |
| GET /feedback/{feedbackId}/candidate | owner读取当前关联，尚无候选404 |
| POST /feedback/{feedbackId}/candidate | 完整文件与input元数据，expectedRevision=null、expectedDocumentRevision；创建候选与首修订 |
| GET /candidates/{id}?revision=n | owner当前或历史不可变修订、安全副本状态、定义关联与当前评估绑定 |
| POST /candidates/{id}/revisions | 完整替换文件及expectedRevision/expectedDocumentRevision/title/requiredText |
| POST /candidates/{id}/preparation | expectedRevision/contentDigest；幂等受理该修订唯一副本，不代表真实准备完成 |
| POST /candidates/{id}/datasets | expectedRevision/contentDigest/datasetId/datasetRevision；验证精确定义并关联；每修订最多10个 |
| POST /candidates/{id}/evaluation | expectedRevision/contentDigest/runId/manifestDigest/reviewDigest；保存精确运行绑定 |
| GET /commands/{key} | 只查当前owner原命令回执，关闭功能后仍可恢复，不创建副本或模型任务 |

全部POST携带UUID Idempotency-Key；唯一域tenant+owner+key，请求指纹包含操作、目标、预期修订及完整内容摘要/参数，同键异内容409。同键重放/回执不因后续编辑失效而变成新的保存；回执只陈述原接受事实，当前状态另读详情。客户端原键恢复确认摘要，冲突/超时保留当前输入；不把未确认的保存换key重发。文件/文本草稿只在当前编辑会话内保存，不隐式持久化到浏览器。统一KnowledgeError扩展OPERATIONS_DISABLED、CANDIDATE_CONFLICT、CANDIDATE_EVALUATION_INVALID等语义，沿既有错误体系。

回执增量：`requestFingerprint`是服务器按`Intent(operation,target,expectedRevision,expectedDocumentRevision,contentDigest,parameters)`计算的SHA256（记录字段顺序、UTF-8 JSON、显式null）。它与原不可变回执同事务保存，仅供当前owner核对；不返回源对象位置。浏览器用相同规范算摘要，并沿现有session意图机制只存`{requestFingerprint}`的摘要与原键，不存文本、文件或评分正文。刷新后对照回执摘要确认原意图；当前状态仍需另读详情。

### 可观察验收

C01 真实DB创建/编辑幂等、并发CAS、不可变历史、owner/reader/跨tenant隔离；修改后旧评估不当前，冲突保留输入。

C02 TXT与已有附件格式的真实源持久化/准备，错误与未知结果恢复；requiredText只给局部检查状态，不假语义通过。

C03 同修订副本唯一；副本不进普通列表/查重/默认v1/v2检索，不可共享/直接改写；显式owner范围使用同一双索引和权威校验，其他主体拒绝。

C04 当前内容与真实S8定义/报告/人工评分绑定；旧原文、其他副本、旧修订/代次/报告/评分、MECHANISM与依赖失败不可当审批前置通过；无新评测引擎。

C05 React编辑/附件/冲突/原键恢复、准备状态、质量工作台跳转/关联及反馈返回；桌面与窄屏真实服务验证和截图实看。

C06 必需静态/单元/DB/双索引/质量联合、审查/文档/Git/CI；本片不执行原资料发布、生产部署或付费模型探针。
