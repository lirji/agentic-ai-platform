# 知识平台契约基线

版本：`v1-foundation`，2026-10-20。依据：[首期约定](BRIEF.md)、[后端架构](../platform-selection-20261020/BACKEND_ARCHITECTURE.md)、[React 设计](FRONTEND_ARCHITECTURE.md)。

本文件是新项目的契约权威。**R01/H01、K01–K04 与 P01/P02 已冻结；Q/T 仍需在对应实施切片前冻结完整 schema。** 不把未来 API 清单冒充已经实现或已完成联调。

知识契约细化见 [KNOWLEDGE_CONTRACTS](KNOWLEDGE_CONTRACTS.md)：S1 冻结受理、查询及身份/错误边界；S3 已补齐 K03 重试和 K04 检索/引用。此增补不改变 R01/H01。

S2 的 Python P01/P02 与 S3 的领取、持久准备、双索引发布及检索约束见 [PROCESSING_CONTRACTS](PROCESSING_CONTRACTS.md)；启用开关关闭时维持原 H01 行为。实现与验收范围以当前进度为准。

## 1. 通用约定

- 新业务 API 使用 `/api/v1` 命名空间，由本项目网关路由；迁入旧 schema 时明确版本及适配映射，原项目文件只作固定参考，不能双向竞争维护。
- 租户、用户和权限来自已验证身份上下文，不信任请求正文、模型生成内容或任意身份头；恢复与引用读取重新判权。
- 业务 ID 为不透明字符串，版本为显式稳定值；UTC 时间使用 ISO 8601，UI 按用户时区显示。
- 错误按模块定义稳定 `code` 和中文安全文案；客户端按 code 处理。禁止输出上游响应原文、令牌、完整输入或堆栈。
- 接受命令不等于完成；状态查询和结果写入权威属于对应 Java 领域服务/中央任务，LangGraph 检查点不能代替业务终态。

## 2. R01：真实文本 Embedding 内部端口（当前冻结）

Owner：Python AI 运行时 `rag` 领域端口，受控 HTTP 适配器实现。它不是可匿名访问的 HTTP 工具；业务接入前由调用方完成身份、资料授权和模型预算检查。

### 配置

| 配置键 | 默认/约束 | 失败语义 |
| --- | --- | --- |
| `RAG_EMBEDDING_PROVIDER` | `openai`，表示 OpenAI 兼容协议；首片仅支持该协议 | `hash`、未知或尚未实现的提供方一律配置错误 |
| `RAG_EMBEDDING_MODEL` | `text-embedding-v4`；长度 1–128，仅单词字符与 `./:-` | 模型变更需要新的索引配置，不自动迁移旧向量 |
| `RAG_EMBEDDING_DIMENSIONS` | `1024`；整数 1–65536 | 响应每条向量必须与此一致；提供方实际支持的维度需另核对 |
| `RAG_EMBEDDING_BASE_URL` | 无地域默认；由环境提供 HTTPS API 基础地址，长度至多 2048 | 缺失、非 HTTPS、userinfo、query、fragment、空白和控制字符均拒绝；空的 `?`/`#` 也拒绝；凭据不编码进 URL |
| `RAG_EMBEDDING_API_KEY` | 无默认；只从环境/受控密钥注入，非空可打印 ASCII 且不含空白 | 缺失或非法字符拒绝；repr、日志、错误和 CLI 输出不得显示真实值 |
| `RAG_EMBEDDING_BATCH_SIZE` | `10`；范围 1–10 | 统一为同步接口当前有据可依的批量上限，超限配置拒绝 |
| `RAG_EMBEDDING_MAX_TEXTS` | `100`；正整数且有硬上限 1000 | 单次应用调用超过限制拒绝，不无界排队 |
| `RAG_EMBEDDING_MAX_INPUT_BYTES` | `1048576`；正整数且上限 16777216 | 整批 UTF-8 输入超过限制拒绝；此项是资源边界，不声称等于提供方 token 上限 |
| `RAG_EMBEDDING_MAX_RESPONSE_BYTES` | `4194304`；正整数且上限 67108864 | 每批实际读取的响应超限拒绝，不信任 Content-Length |
| `RAG_EMBEDDING_MAX_CONNECTIONS` | `10`；范围 1–100 | 客户端连接池与空闲连接数均有界；等待连接也受总期限限制 |
| `RAG_EMBEDDING_DEADLINE_SECONDS` | `30`；大于 0 且不超过 300 | 全部 HTTP 批次共享一次总期限，超时返回有类型的错误 |

默认值为有界工程初值，不能当容量达标承诺。来源模型及接口依据见 [Embedding 基线](BRIEF.md#embedding-落地基线)。当前没有自动选择 Ollama 或自动 hash 回退。

### 调用与响应

内部调用 `embed(texts)` 接收非空文本序列；拒绝空序列、非字符串和仅空白文本。文本按原始顺序分批，发往配置基础地址的 `/embeddings`，请求为 `model`、`input`、`dimensions` 和 `encoding_format=float`，认证仅进入 Authorization 请求头。

每批响应 `data` 必须完整覆盖本批的 0..N-1 索引；允许返回乱序，拒绝重复、缺失、越界索引和数量不符。向量值必须是有限数值，拒绝布尔、NaN、Infinity、维度错误及畸形 JSON。若响应带 `model`，须与请求一致。返回不可变结果：按输入顺序排列的向量、模型名、维度。

一批失败则整个调用失败，不返回部分向量作为成功；已经发生的模型调用不被视为撤销。首片不实现自动重试，由后续应用预算/任务策略决定是否重试。取消必须向上传播，不能变成普通失败后继续下一批。拒绝重定向，不将认证转发到另一个地址。

### 稳定错误

| Code | 中文默认文案 | 可重试含义 |
| --- | --- | --- |
| `EMBEDDING_CONFIGURATION_INVALID` | 向量模型配置无效，请检查运行配置。 | 否，先修配置 |
| `EMBEDDING_INPUT_INVALID` | 向量化输入无效或超过限制。 | 否，先修输入 |
| `EMBEDDING_UPSTREAM_REJECTED` | 向量服务拒绝请求，请检查模型配置或配额。 | 401/403/其他确定性 4xx 为否 |
| `EMBEDDING_UPSTREAM_UNAVAILABLE` | 向量服务暂不可用，请稍后重试。 | 连接错误、超时、429/5xx 为可重试候选；不自动重试 |
| `EMBEDDING_RESPONSE_INVALID` | 向量服务响应格式或维度不符合约定。 | 否，先核对模型与接口 |

异常对象只保留稳定 code、默认文案和 retryable，不携带上游 body、API Key 或原始文本。配置校验报告可提供错误字段名，不打印输入值。

## 3. H01：进程存活与配置检查（当前冻结）

- `GET /health/live` → `200 {"status":"alive","service":"agentic-ai-runtime"}`。仅表示当前 HTTP 进程可响应；不表示模型、数据库、知识库或整个平台可用。无认证的内容限于这一固定结果。
- 本片不提供会误导的 readiness 成功、业务 Embedding 公共 API 或假问答 API。后续新增依赖就绪检查必须绑定实际已接入的依赖。
- CLI `agentic-ai check-config`：合法配置返回退出码 0 及不含凭据/地址的 provider、model、dimensions 摘要；不调用模型。非法配置返回非零和安全的 `EMBEDDING_CONFIGURATION_INVALID`，不能输出底层校验输入。
- 开发启动 `agentic-ai serve --host 127.0.0.1 --port <port>` 仅提供当前已实现端点；支持端口 0 交给系统分配以便本地 smoke。部署暴露范围由运行切片另行配置。

## 4. 第一条知识路径的契约族（下游冻结清单）

这些要求足够指导迁入盘点和页面设计；在 schema 与业务策略补齐之前，相关业务切片不得标为 READY，也不得按猜测 JSON 编写 UI。

| ID | 用户动作/目标接口族 | Owner 与来源 | 必须冻结的细节 |
| --- | --- | --- | --- |
| K01 | 登记文档并提交入库 | Java Knowledge；来源 `IngestionController`、`IngestionSubmissionService` | 文件格式/体积、文档/版本登记、Idempotency-Key、内容指纹与作用域；202 返回 jobId/docId/version/状态，不宣称发布完成 |
| K02 | 文档列表与详情 | Java Knowledge；来源 `DocumentController`、`DocumentService` | 授权知识范围、稳定排序分页、版本与来源；越权/不存在统一资源策略 |
| K03 | 查询入库任务、重试 | Java Knowledge 入库状态机 | 失败阶段、逐 sink 状态、retryable；同一任务原输入重试，同键异参拒绝；版本冲突裁决 |
| K04 | 已发布资料检索与引用读取 | Java Knowledge 授权检索原语 | docId/version、原文位置、来源元数据、评分、结果上限及授权过滤；历史接口缺陷修正后才成为新契约 |
| Q01 | 提交知识问题 | 本项目会话/任务治理 + Python AI 执行 | conversationId、问题、知识范围、输入指纹、run/task 绑定、累计预算及响应模式 |
| Q02 | 流式事件与断线恢复 | 中央任务持久事件 → 网关 → React | 事件稳定 code、按任务递增游标、重放、完成/失败/取消与文本 delta 的区别；断流不重建业务任务 |
| Q03 | 会话及答案/引用查询 | 本项目会话治理，引用经 Knowledge 判权 | 消息与答案权威、引用版本、撤权、保留及删除；生成内容不能写成原文 |
| T01 | 研究/助手任务的取消与恢复 | 中央任务 MySQL + LangGraph PG | owner/epoch、graph/runtime/checkpoint 版本、幂等完成报告、失租约写拒绝、中央终态对账、恢复重验权限 |

来源提交见 [BRIEF](BRIEF.md)。原 `KnowledgeQueryRequest`/`KnowledgeHit` 可参考查询词、topK、分类、docId、version 与 sourceMetadata；旧 `DocumentController` 的字符串错误头及旧门户格式不自动成为新项目的错误契约。

## 5. 验证边界

R01 用受控 HTTP 测试服务/transport 验证协议、分批、顺序、拒绝、取消和脱敏；它证明客户端行为，不能代替百炼账号、真实语义效果、跨租户授权或 Qdrant 集成验证。真实 1024 维调用、知识质量、PG 恢复与多 worker 验证归后续 P0/P1 切片，未执行不得标通过。
