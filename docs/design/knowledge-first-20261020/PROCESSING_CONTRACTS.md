# 知识处理契约 v1

2026-10-20，承接 [CONTRACTS](CONTRACTS.md)、[知识契约](KNOWLEDGE_CONTRACTS.md)和已批准 Java 数据所有权。用户本轮已确认沿用原百炼账号/地域，真实 Embedding 联调累计上限 1 元。本文件定义接口；实现/验证状态以 [实施切片](IMPLEMENTATION_SLICES.md)为准。

## P01：内部准备接口

`POST /internal/v1/knowledge/prepare`，只由本项目 Knowledge 服务调用，不由网关公开。

- `X-Internal-Token` 为独立 Knowledge 服务密钥签发的 RS256 JWT，issuer=`urn:agentic-ai:knowledge`、audience=`agentic-ai-runtime`、azp=`knowledge-service`；必需 `iat/nbf/exp/sub/tenant_id/scope`，最长有效期 60 秒。验签密钥与网关换发密钥分离；`sub` 为被代理的资料所有者；租户和用户格式沿用 K 契约。
- scope 必须包含 `knowledge:prepare`；租户/所有者必须与请求相等。服务端不信任普通身份头。调用方先核对资料权限/worker 租约，再签发精确身份；Python 不替 Java 裁决当前文档是否允许发布。
- 请求 JSON 不允许未知字段，整数不接受布尔或字符串。原始 HTTP 正文上限 6 MiB，解码后原文 UTF-8 上限 1 MiB。拒绝 NUL、空白原文、非法 Unicode、非规范 UUID、哈希不符。

| 字段 | 类型与约束 |
| --- | --- |
| tenantId / ownerId | 1–128 个 ASCII 字母数字或 `._:-`，与令牌身份一致 |
| documentId / jobId | 规范小写 UUID |
| documentVersion | 正整数；本轮业务仍为 1 |
| sourceHash | 原始 UTF-8 字节 SHA-256，小写 64 位十六进制 |
| contentType | `text/plain` 或 `text/markdown` |
| sourceText | 原文全文，不下载调用方提供的 URL，不执行内容 |

返回 200 `PreparedDocument`：

- 回传 tenantId、ownerId、documentId、jobId、documentVersion、sourceHash。
- `schemaVersion="knowledge-preparation-v1"`；
- `processorVersion="text-v1-char512-overlap64-embedding-v4-1024"`；
- `embeddingModel="text-embedding-v4"`、`dimensions=1024`；
- `chunks`：1–100 个，保持原文顺序，每项 `ordinal`（从 0）、`chunkId`、`text`、`textHash`、`startByte`（包含）、`endByte`（不包含）、`vector`、`vectorHash`。
- `batchDigest`：绑定所有分块及向量摘要。

处理步骤为 LangGraph 固定图：LlamaIndex 分块 → R01 真实 Embedding → 校验封装。分块使用 SentenceSplitter，按 Unicode 码点计数（512，重叠至多 64），明确本地分句函数，不下载 NLP 语料或改变原文。每个分块必须对应原始 UTF-8 的连续字节片段；定位失败或超过 100 块时明确失败，不截断。空白边缘可不进入分块，内容不得重写。

每条向量必须是 1024 个有限数，拒绝布尔。返回前再次检查模型、维度、块/向量数量和全部值，不把类型注解当作验证。

### 跨语言摘要规则

字符串均用 UTF-8，无 BOM。所有哈希为 SHA-256 小写十六进制：

1. `textHash=sha256(text)`。
2. `chunkId=sha256(tenantId+"\n"+ownerId+"\n"+documentId+"\n"+documentVersion+"\n"+sourceHash+"\n"+processorVersion+"\n"+ordinal+"\n"+textHash)`，整数为十进制。
3. `vectorHash=sha256`（按顺序编码的 IEEE754 binary64 大端字节），保留负零语义。
4. `batchDigest=sha256`（按 ordinal 顺序，以 `"\n"` 连接的 `chunkId:startByte:endByte:vectorHash`，末尾无换行）。

这使 Java 能独立验证准备结果；摘要不是身份签名。Java 还必须与持久任务的租户、所有者、原文哈希、版本逐项比较，不能仅相信返回的 digest。

### 期限、并发与失败

- 同步有界准备：默认总期限 40 秒（1–120 秒），并发 2（1–8）；忙时立即 429，不建无限队列。R01 的期限在此总期限内。处理图无自动重试或外部观测上传；请求取消传播到底层 HTTP。
- 本片准备图不保存 checkpoint；Java 在后续片持久化准备结果后方可提交索引。进程退出后，未返回/未持久化的准备需重新执行，可能再次产生模型费用；不宣称 exactly-once 或免费重放。
- 不在本片实现研究任务、PG 恢复或业务终态。Deep Agents 的导入、构建、工具/流式兼容由隔离测试验证，不开放匿名 Agent API，不以测试模型作为业务 fallback。

## P02：内部查询向量

`POST /internal/v1/knowledge/embed-query`；相同身份协议，scope=`knowledge:query`。JSON `{tenantId,ownerId,query}`，query 为非空白字符串、至多 2000 个 Unicode 码点；身份必须匹配。返回 `{processorVersion,embeddingModel,dimensions,vector}`，固定相同模型和维度，复用 P01 的并发/期限与 R01 校验。无自动前缀或查询改写；后续索引和查询共享同一处理版本。

## 错误与运行

响应沿用 `{code,message,traceId}`；traceId 是服务器生成 UUID，不回显不可信请求头、原文、配置值或底层异常。非法 JSON/DTO 统一 400，不暴露 Pydantic 错误输入。

| code | HTTP | 中文默认文案 |
| --- | --- | --- |
| AUTH_REQUIRED | 401 | 请提供有效的身份凭证。 |
| AUTH_FORBIDDEN | 403 | 当前身份没有执行此操作的权限。 |
| PROCESSING_INPUT_INVALID | 400 | 知识处理输入无效，请核对原文与版本。 |
| PROCESSING_INPUT_TOO_LARGE | 413 | 知识处理输入超过当前允许范围。 |
| PROCESSING_CAPACITY_EXCEEDED | 429 | 知识处理繁忙，请稍后重试。 |
| PROCESSING_DEADLINE_EXCEEDED | 503 | 知识处理超时，请稍后重试。 |
| PROCESSING_RESULT_INVALID | 502 | 知识处理结果不符合约定。 |
| PROCESSING_CONFIGURATION_INVALID | 启动失败 | 知识处理配置无效，请检查运行配置。 |
| INTERNAL_ERROR | 500 | 服务处理失败，请稍后重试。 |

R01 错误沿用其 code/文案；输入错误为 400，配置错误为 503，上游不可用为 503，其他上游/响应错误为 502。无自动 hash、随机向量或空结果降级。

`AI_PROCESSING_ENABLED` 默认 `false`；关闭时保持 H01，仅有存活端点，内部业务路由不存在。启用时必须配置 `AI_SERVICE_PUBLIC_KEY`（本机 PEM 公钥路径）、合法 R01 配置和固定模型/维度，错误启动失败；`AI_PROCESSING_MAX_CONCURRENT` 默认 2，`AI_PROCESSING_DEADLINE_SECONDS` 默认 40。长任务或外部部署另行验收。

## S3：Java 提交、恢复与读取

### 领取与持久准备

- 原 `knowledge_ingestion` 是可靠交接账本；V2 增加 lease owner/epoch/deadline、attempt/nextAttempt、不可变准备对象指针/摘要、双 sink 状态；V3 显式禁止非空准备指针搭配 NULL 长度，避免 MySQL CHECK 的 UNKNOWN 放行。不增加 MQ 或第二套任务中心，不改写已应用迁移。
- 每实例一个独立处理线程，内部领取轮询默认 2 秒；心跳默认 10 秒、租约 60 秒。短事务 `FOR UPDATE SKIP LOCKED` 领取，数据库 UTC 时间裁定过期，每次领取增加 epoch/attempt/revision。HTTP、S3 和索引调用均在事务外。
- 默认关闭处理；显式启用并配置 AI/搜索端点后，自动领取 RECEIVED 及租约过期的 PROCESSING。最多连续领取 5 次，超过上限进入 FAILED，可由所有者显式重试。普通远程失败直接进入 FAILED，不无限后台重试；K03 重试最多 3 次，按次数采用 2/4/8 秒加 0–1 秒抖动。重试 CAS 且不改变原输入；取消不在本片接口范围。
- 状态更新匹配 tenant/owner/job/version、lease owner/epoch、PROCESSING 和未到期租约。心跳只续租，不制造用户 revision 变化。失去租约停止后续工作；迟到外部请求可能已执行，不能把取消当回滚。
- Java 从本项目 S3 读原文并核验大小/哈希，完整核验 P01 身份、版本、UTF-8 定位、chunk/vector/batch 摘要，再序列化为至多 6 MiB 的准备对象。对象路径绑定 job 和内容摘要；条件事务首次接受该指针，同时保存无向量的规范 chunk 与事件。接收过的准备结果不可替换，恢复只读并复核该对象，不重新调用模型。
- 准备对象写成功但数据库未知/失败时先查任务；未接受的对象不能驱动索引。重启后仍无准备指针可能再次调用模型，费用不承诺 exactly-once。本机付费验证由 Python 可选持久预算护栏在每次真实 Embedding 批次前预留，文件锁/落盘失败或额度不足拒绝发送；沿用已有 1 元账本，不清零。

### 双索引与发布

- Qdrant 使用独立 `agentic_knowledge_text_v1` 集合，1024 维 Cosine；ES 使用独立 `agentic-knowledge-text-v1` 索引，strict mapping、smartcn、1 主分片、本机 0 副本。测试另用 `_test` / `-test`，不访问旧 lc4j 数据。
- payload 身份字段为 tenantId/ownerId/documentId/version/processorVersion/chunkId/batchDigest；ES 另含 text。每批最多 100 块。Qdrant point ID 为 SHA-256(`chunkId + ":" + batchDigest`) 前 128 位的 UUID 表达；ES ID 为 `chunkId:batchDigest`。冻结准备包使重复/迟到 upsert 写入相同内容，不能覆盖另一批次。
- Qdrant `wait=true` 且确认 completed 后才记 DONE；ES Bulk `refresh=wait_for`，逐条确认 ID/状态且无 errors 才记 DONE。超时或部分失败保持 PENDING，显式重试幂等重放该 sink；已 DONE 的 sink 不重写。未完成发布的数据即使在索引可见也不能从 API 返回。
- 两个 sink DONE 后，在带租约条件的短事务中将任务 READY/COMPLETE 和文档 READY/publishedVersion=version 一起提交并记事件。旧 owner、错误批次和单 sink 完成均无法发布。发布响应未知时重读权威账本。
- 事件仅存任务、状态、阶段、执行者、epoch/revision 与安全错误码，不保存令牌/原文。当前保留所有任务、引用块和事件，无自动删除或归档；生产保留期限待业务决定。

### K03/K04 增补

- K03 重试沿用原 `{expectedRevision}`；只接受 FAILED + retryable 且 retryCount<3，版本冲突或终态重试 409。保留准备对象、块和已完成 sink。
- K04 验证 query（1–2000 Unicode 码点、严格 Unicode、非空白、无 NUL）、topK（1–20）及可选 documentIds（1–20 个不重复 UUID），禁止未知字段及 JSON 标量类型强制转换。
- 两路依赖均须成功，不静默降级为空结果。Qdrant/ES 均先限定 tenant/owner/处理版本/可选文档，各最多 100 个候选；Java 按 RRF `Σ1/(60+rank)` 合并，平分按 chunkId 排序。一次有界 MySQL 查询重新校验所有者、READY、publishedVersion、chunkId 与 batchDigest，只输出规范块文本及 `utf8-bytes:start-end` 来源定位。分数不是概率；不承诺候选窗口外全量召回。
- 直接引用从同一 MySQL 权威块/发布记录读取，score=0；失效版本、不可见块与不存在均 404。不回显搜索投影文本或 S3 路径。
- 新错误：`KNOWLEDGE_PROCESSING_DISABLED`（503）、`KNOWLEDGE_PROCESSOR_UNAVAILABLE`（503，可重试）、`KNOWLEDGE_PREPARATION_INVALID`（502，不可重试）、`KNOWLEDGE_SOURCE_INVALID`（502，不可重试）、`KNOWLEDGE_INDEX_UNAVAILABLE`（503，可重试）、`KNOWLEDGE_PROCESSING_EXHAUSTED`（503，可显式重试）、`KNOWLEDGE_BUSY`（429）。预算拒绝映射 processor unavailable，不回显金额/凭据。
- 查询同实例最多 2 个在途请求，不排队；worker 最多 1 个。AI 默认 45 秒、索引每次 8 秒，HTTP 响应上限 6 MiB，连接期限 2 秒，无内部重试。任务总期限 120 秒，心跳不延长总期限；退出停止领取、中断当前等待，未完成任务靠租约回收。
- worker 创建独立追踪 span，以 jobId 关联恢复，不持久化用户 JWT 或跨线程事务。上下文显式传参，无自定义 ThreadLocal。指标覆盖领取/成功/失败/失租、处理时长、在途、待领取/失败数，高基数身份不作指标标签。

人工一致性结论：S3 保留 Java 数据所有权及既有 K 契约，追加迁移和处理开关兼容 S1，短准备图继续不落 PG checkpoint。轮询/心跳属于内部执行维护，不是新增业务定时任务。运行时按 TECH_SELECTION 的 S3 安全补丁决策，新建本项目 Qdrant 1.19.2 / ES 8.19.23 smartcn 实例、卷与认证，不操作旧业务数据。

## 依据

- [LangGraph Graph API](https://docs.langchain.com/oss/python/langgraph/graph-api)：本片固定节点图，未推导分布式业务恢复已完成。
- [LlamaIndex SentenceSplitter](https://developers.llamaindex.ai/python/framework-api-reference/node_parsers/sentence_splitter/)：固定分块实现和显式 tokenizer。
- [Deep Agents 定制](https://docs.langchain.com/oss/python/deepagents/customization)：框架组合验证不代表业务 Agent 已交付。
- [百炼价格](https://help.aliyun.com/zh/model-studio/model-pricing)：2026-10-20 页面，text-embedding-v4 北京 0.5 元/百万输入 Token、新加坡 0.514 元/百万输入 Token；调用前按实际沿用地域核对。账单以供应商为准，本轮以保守预留控制 1 元上限。
