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

## 下游 Java 提交约束（S3 前补齐）

Knowledge 是唯一 MySQL/S3/Qdrant/ES 写入者。S3 准备结果、逐 sink 状态、带 epoch 的领取/心跳/提交、已发布版本和失败恢复需通过 V2/Mapper XML 及真实存储验收，不能用 Python 准备成功替代发布。K04 索引、检索与恢复细节在 S3 实施前在本文件续补。

## 依据

- [LangGraph Graph API](https://docs.langchain.com/oss/python/langgraph/graph-api)：本片固定节点图，未推导分布式业务恢复已完成。
- [LlamaIndex SentenceSplitter](https://developers.llamaindex.ai/python/framework-api-reference/node_parsers/sentence_splitter/)：固定分块实现和显式 tokenizer。
- [Deep Agents 定制](https://docs.langchain.com/oss/python/deepagents/customization)：框架组合验证不代表业务 Agent 已交付。
- [百炼价格](https://help.aliyun.com/zh/model-studio/model-pricing)：2026-10-20 页面，text-embedding-v4 北京 0.5 元/百万输入 Token、新加坡 0.514 元/百万输入 Token；调用前按实际沿用地域核对。账单以供应商为准，本轮以保守预留控制 1 元上限。
