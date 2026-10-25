# 知识资料契约 v1

2026-10-20。本文细化 [CONTRACTS](CONTRACTS.md) 的 K01–K04；S1 实现受理、查询及入口身份边界，S3 实现重试、发布、检索与引用。[处理契约](PROCESSING_CONTRACTS.md)补充持久准备、租约和双索引恢复语义。Q/T 尚未实现，不返回伪成功。

## 身份与错误

- 网关验证配置的 IAM RS256 JWT：签名、issuer、audience、exp/nbf、非空 sub 和租户 claim。首片本地验证使用独立测试签发密钥；生产 IAM 配置不从旧业务服务继承，不开放免鉴权模式。
- 网关清除外来 `X-Internal-Token`、`X-Service-Token`、`X-Tenant-Id`、`X-User-Id`，将已验证身份换为本项目短时内部 JWT；外部 Authorization 不传入业务服务。内部 issuer、audience 与签名密钥独立于外部 IAM，知识服务再次验签。
- 租户/用户 ID 使用 1–128 个字母、数字或 `._:-`。内部 claim 为 `tenant_id`、`sub`、空格分隔 `scope`；内部令牌有效期 60 秒，验收含 issuer/audience/过期与伪造签名拒绝。
- 写入需要 `knowledge:write`，查询需要 `knowledge:read`。S1 文档可见性固定 `OWNER`，每条 SQL 使用已验证 tenantId + ownerId；跨租户/跨用户与不存在统一返回 404，不自动授予管理员旁路。
- 错误 JSON 固定 `{code:string,message:string,traceId:string}`。traceId 为请求关联标识，不回显正文、令牌、存储路径或 SQL。展示文案集中定义，客户端按 code 判断。

| code | HTTP | 默认文案 |
| --- | --- | --- |
| `AUTH_REQUIRED` | 401 | 请提供有效的身份凭证。 |
| `AUTH_FORBIDDEN` | 403 | 当前身份没有执行此操作的权限。 |
| `KNOWLEDGE_INPUT_INVALID` | 400 | 资料参数无效，请检查后重试。 |
| `KNOWLEDGE_FILE_TOO_LARGE` | 413 | 资料大小超过当前允许范围。 |
| `KNOWLEDGE_UNSUPPORTED_FORMAT` | 415 | 当前仅支持 UTF-8 文本和 Markdown 资料。 |
| `KNOWLEDGE_NOT_FOUND` | 404 | 资料或任务不存在，或当前不可访问。 |
| `KNOWLEDGE_IDEMPOTENCY_CONFLICT` | 409 | 此提交编号已用于不同的资料请求。 |
| `KNOWLEDGE_DUPLICATE_CONTENT` | 409 | 当前资料已提交，请查看已有文档和任务。 |
| `KNOWLEDGE_STATE_CONFLICT` | 409 | 当前任务状态不允许此操作。 |
| `KNOWLEDGE_STORAGE_UNAVAILABLE` | 503 | 原文存储暂不可用，请稍后重试。 |
| `KNOWLEDGE_DATABASE_UNAVAILABLE` | 503 | 资料服务暂不可用，请稍后重试。 |
| `INTERNAL_ERROR` | 500 | 服务处理失败，请稍后重试。 |

## K01：资料受理

`POST /api/v1/knowledge/ingestions`，`multipart/form-data`：

| 输入 | 必需 | 语义 |
| --- | --- | --- |
| `Idempotency-Key` header | 是 | 16–128 个字母/数字/`._:-`；作用域为 tenantId + ownerId |
| `file` part | 是 | 文件名扩展为 `.txt` 或 `.md`，严格 UTF-8，非空且不能只含空白或 NUL；至多 1 MiB |
| `displayName` form field | 否 | 缺省用文件名；1–200 个 UTF-16 单元，不含控制字符；仅为展示，不参与对象路径 |

首片只创建文档第一版；documentId/jobId 由服务器生成 UUID，version=1。更新版本、PDF/OCR/Office 文件在后续解析与版本切片扩展，不能将字节接收当作已经支持解析。

S9B/C增量已经接入原生PDF和普通DOCX；新格式受理、媒体类型、资源上限、处理版本及K06来源以[结构化解析契约](STRUCTURED_PROCESSING_CONTRACTS.md)为准，默认独立开关关闭。S9C的V8仅扩展MIME长度与DOCX profile检查，不改变本节第一版受理与所有者幂等语义。更新/删除/重索引语义已由S10A在[生命周期契约](LIFECYCLE_CONTRACTS.md)冻结，按S10B–D实施；当前尚未启用。

- 服务端计算原文 SHA-256；请求指纹同时绑定规范化格式、展示名及内容哈希。同键同指纹返回同一 jobId/documentId；同键异参 409。
- 内容去重仅在相同租户和所有者范围内。同内容不同提交编号返回 `KNOWLEDGE_DUPLICATE_CONTENT`，不泄露其他人是否有相同资料。
- 原文先写项目独立 S3 桶，再用一个短 MySQL 事务创建文档、版本信息与持久任务。唯一约束守住幂等键及所有者内容去重；存储/事务未成功不能返回 202。
- S3 地址使用服务端生成的隔离、内容寻址路径，不接受外部对象 URL。失败后不删除可能已被并发获胜任务引用的对象；无引用对象留待有界核查清理，不跨项目清理。
- 新建及幂等重放均返回 202，`Location=/api/v1/knowledge/ingestions/{jobId}`，正文为下列 `IngestionView`。重放返回当前任务状态，不能重置处理结果。

## K02：资料查询

- `GET /api/v1/knowledge/documents?limit=20&cursor=<opaque>`：limit 范围 1–100，非法值 400；返回 `{items:DocumentView[],nextCursor:string|null}`。
- 按 documentId 字典序稳定游标分页；先应用 tenantId + ownerId 谓词，再读取 limit+1。cursor 为 Base64URL 编码的上一页 UUID，不是权限凭证；每次仍重新判权。
- `GET /api/v1/knowledge/documents/{documentId}`：返回一个 `DocumentView`，不可见与不存在均 404。

`DocumentView` 的完整字段：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| documentId | string UUID | 不透明资料 ID |
| displayName | string | 展示名 |
| version | integer | 当前受理版本，S1 为 1 |
| publishedVersion | integer 或 null | 已发布的可检索版本；S1 必须为 null |
| status | string enum | `RECEIVED / PROCESSING / READY / FAILED` |
| visibility | string enum | 当前固定 `OWNER` |
| contentType | string enum | `text/plain / text/markdown`，按已验证扩展名规范化 |
| sizeBytes | integer | 原始字节数 |
| contentHash | string | SHA-256 小写十六进制 |
| ingestionJobId | string UUID | 当前受理任务 |
| createdAt / updatedAt | string | UTC ISO 8601，数据库精度微秒 |

## K03：任务查询与重试

`GET /api/v1/knowledge/ingestions/{jobId}` 返回 `IngestionView`：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| jobId / documentId | string UUID | 服务端生成的任务/文档 ID |
| documentVersion | integer | S1 为 1 |
| status | string enum | 与文档状态集合相同 |
| stage | string enum | `ACCEPTED / PREPARING / PUBLISHING / COMPLETE` |
| contentHash | string | 原文 SHA-256 |
| errorCode | string 或 null | 安全稳定错误码，不含原始下游异常 |
| retryable | boolean | 只在 FAILED 且错误允许恢复时为 true |
| retryCount | integer | 已接受的重试次数，从 0 开始 |
| revision | integer | 乐观并发版本，从 0 开始 |
| createdAt / updatedAt | string | UTC ISO 8601 |

S1 新任务保持 `RECEIVED/ACCEPTED`、errorCode=null、retryable=false；没有 worker 时不得假装完成。

`POST /api/v1/knowledge/ingestions/{jobId}/retry` 要求写权限、原所有者和 JSON `{expectedRevision:integer}`。只接受 FAILED + retryable 且 retryCount<3 的原任务，将状态置 RECEIVED、revision/retryCount 加一，保留原输入、准备结果和成功索引；其他状态或版本冲突 409。受理返回 202 和当前 IngestionView。不接受新文件或修改原提交编号。

## K04：发布后检索与引用

`POST /api/v1/knowledge/search`，读权限，请求 `{query:string,topK?:integer,documentIds?:string[]}`：query 为 1–2000 个 Unicode 码点、整体非空白且无 NUL；topK 缺省 5、范围 1–20；documentIds 提供时必须为 1–20 个不重复 UUID。未知字段和标量类型强制转换被拒绝。返回 `{hits:KnowledgeHit[]}`，找不到资料返回空数组，模型/检索依赖失败返回稳定 503 错误，不伪装成无结果。

`KnowledgeHit={documentId:string,version:integer,chunkId:string,text:string,score:number,source:{displayName:string,locator:string}}`。score 为有限数；locator 表示原文段落位置，不使用生成文本伪造出处。仅返回当前可见已发布版本，预发布/失败版本不能检索。

`GET /api/v1/knowledge/documents/{documentId}/versions/{version}/chunks/{chunkId}` 返回同形 KnowledgeHit（score=0 表示直接引用读取，不作排名）；重新检查所有者与已发布版本，不可访问或版本已失效返回 404。K04 详细索引提交/恢复与检索错误码已在 [S3 增补](PROCESSING_CONTRACTS.md#s3java-提交恢复与读取)冻结，不由客户端猜测。

## 验证与运行边界

本地集成必须验证真实 MySQL 唯一约束、事务回滚、并发提交、重启后查询，以及真实 S3 写入和网关身份交换。运行/迁移分账号；日志不打印凭据。端口、独立资源及启动方式由 Runtime 记录。

当前默认和上限是首条可验证路径的工程边界，不是最终业务容量或生产 SLA；后续扩大格式/共享范围/版本操作必须同步契约与测试。
