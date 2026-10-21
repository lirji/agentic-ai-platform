# 证据索引与核验边界

2026-10-21。此索引支持[能力缺口](CAPABILITY_GAPS.md)，不构成新运行验收。当前底座可复用，优先建设质量、资料管理和运营；实施暂停。

## 取证范围与方法

直接核对固定提交的源码、配置、SQL、前端调用和近期 QA，旧分析仅用于定位。没有重新调用模型、执行服务、迁移或性能实验。源码链接均指向固定提交；文件摘要、行号和仓库清单核验见 [evidence-manifest.json](evidence-manifest.json)。

| 来源 | 固定提交 | 本轮操作 |
| --- | --- | --- |
| 当前后端 `A` | `d61536cbb9179db010f33d7b1cdbda737e22fc92` | 只读取证；仅 A 仓写本轮文档 |
| 当前 React `W` | `d2b4dc6957cfbc81d29a8493dc9aa284aab8e584` | 只读取证；仅 A 仓写本轮文档 |
| 参考项目 `L` | `468cec425dd160e80cfc3ab378d3440a8767e4d5` | 只读取证；仅 A 仓写本轮文档 |

“已实现”不等于“已启用/已验证/已生产运行”。下列事实为 `FACT`；业务收益与优先级为 `INFERRED`；实时启用/生产质量和容量为 `UNKNOWN` 或 `NEEDS_VERIFICATION`。没有用模块数、测试数或框架名计算质量分。

## Finding / Capability / Evidence Type

下列每项包含 File、Class、Method、Configuration、SQL·Table、Confidence；SQL/配置不适用时明确标为“—”。缺失能力以当前 API/模块/前端/配置集合和契约范围交叉判断，不能由一次关键词未命中单独得出。

<a id="e01"></a>

### E01 · 范围、模块与公开入口

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G12/G20；范围、模块与公开入口 |
| Evidence Type | SOURCE/CONFIG/INVENTORY |
| Class / Method | pom.modules；KnowledgeController |
| Configuration | 根 pom / React 路由 |
| SQL·Table | — |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前六个 Maven 模块、Python AI 与独立 React；参考覆盖更多领域。当前 controller 集合支持限定知识/任务/会话业务。
**限制：** 模块和路由数量不作质量排名；未审计外部 AgentScope 全部实现。

**File：**

- 当前后端 [pom.xml](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/pom.xml#L17)。
- 参考项目 [pom.xml](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/pom.xml#L25)。
- 当前后端 [knowledge-service/src/main/java/com/lrj/agentic/knowledge/api/KnowledgeController.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/java/com/lrj/agentic/knowledge/api/KnowledgeController.java#L25)。
- 当前 React [src/app/App.tsx](https://github.com/lirji/agentic-ai-platform-web/blob/d2b4dc6957cfbc81d29a8493dc9aa284aab8e584/src/app/App.tsx#L1)。

<a id="e02"></a>

### E02 · 上传边界、幂等与内容去重

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G02/G05；上传边界、幂等与内容去重 |
| Evidence Type | SOURCE/SQL |
| Class / Method | UploadRules.validate；KnowledgeService.submit；IngestionSubmissionService.submit |
| Configuration | MAX_BYTES；deduplicateContent |
| SQL·Table | knowledge_document / knowledge_ingestion |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前只接受 TXT/MD、严格 UTF-8 与大小限制；同键复用、新键创建新 UUID。参考受理按内容摘要筛选可见重复记录。
**限制：** 当前 hash 原文存储不是业务级重复文档阻止；参考不泄露无权资料/不自动合并旧数据。

**File：**

- 当前后端 [knowledge-service/src/main/java/com/lrj/agentic/knowledge/application/UploadRules.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/java/com/lrj/agentic/knowledge/application/UploadRules.java#L14)。
- 当前后端 [knowledge-service/src/main/java/com/lrj/agentic/knowledge/application/KnowledgeService.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/java/com/lrj/agentic/knowledge/application/KnowledgeService.java#L35)。
- 当前后端 [knowledge-service/src/main/resources/db/migration/V1__knowledge_registration.sql](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/resources/db/migration/V1__knowledge_registration.sql#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/ingest/job/IngestionSubmissionService.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/ingest/job/IngestionSubmissionService.java#L72)。
- 参考项目 [docs/delivery/knowledge-content-dedup/PROGRESS_STATE.md](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/docs/delivery/knowledge-content-dedup/PROGRESS_STATE.md#L1)。

<a id="e03"></a>

### E03 · 格式、OCR、资源边界与结构来源

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G02/G03；格式、OCR、资源边界与结构来源 |
| Evidence Type | SOURCE/CONFIG |
| Class / Method | StructuredDocumentParser.parse；ForkedDocumentParser；SourceProvenance |
| Configuration | DocumentParsingProperties / DocumentOcrProperties |
| SQL·Table | — |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 参考有专用 PDF/DOCX/工作表/CSV/Markdown 解析、受限 fallback、来源白名单、OCR 与 fork 资源限制；当前输入仍为纯文本。
**限制：** fallback 不能称为全部格式结构保真；fork 不是完备 RCE 沙箱；OCR 效果依赖样本。

**File：**

- 当前后端 [knowledge-service/src/main/java/com/lrj/agentic/knowledge/application/UploadRules.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/java/com/lrj/agentic/knowledge/application/UploadRules.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/StructuredDocumentParser.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/StructuredDocumentParser.java#L41)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/ForkedDocumentParser.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/ForkedDocumentParser.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/SourceProvenance.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/SourceProvenance.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/DocumentParsingProperties.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/DocumentParsingProperties.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/DocumentOcrProperties.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/DocumentOcrProperties.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/PdfPageOcr.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/PdfPageOcr.java#L1)。

<a id="e04"></a>

### E04 · 切分与当前来源字节定位

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G03；切分与当前来源字节定位 |
| Evidence Type | SOURCE/TEST |
| Class / Method | pipeline；KnowledgeSearch.hit；DocumentSplitterFactory |
| Configuration | chunk_size / chunk_overlap / max chunks |
| SQL·Table | knowledge_chunk |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前字符 tokenizer + 512/64 SentenceSplitter，回到原文求字节区间；参考标题/父子/语义/结构 splitter。
**限制：** 存在多策略不证明更准；当前字节摘要绑定是应保留能力。

**File：**

- 当前后端 [ai-runtime/src/agentic_ai/processing/pipeline.py](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/ai-runtime/src/agentic_ai/processing/pipeline.py#L9)。
- 当前后端 [knowledge-service/src/main/java/com/lrj/agentic/knowledge/application/KnowledgeSearch.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/java/com/lrj/agentic/knowledge/application/KnowledgeSearch.java#L75)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/DocumentSplitterFactory.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/DocumentSplitterFactory.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/StructuredDocumentSplitter.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/StructuredDocumentSplitter.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/MarkdownHeaderSplitter.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/MarkdownHeaderSplitter.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/ParentChildSplitter.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/ParentChildSplitter.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/SemanticChunkingSplitter.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/SemanticChunkingSplitter.java#L1)。

<a id="e05"></a>

### E05 · 版本、删除与共享的业务入口

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G04/G10；版本、删除与共享的业务入口 |
| Evidence Type | SOURCE/API |
| Class / Method | KnowledgeService.submit；DocumentController；DocumentService.uploadDocument/deleteInternal；DocumentShareController |
| Configuration | INITIAL_DOCUMENT_VERSION；authz mode |
| SQL·Table | 文档 registry 与发布版本 |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前没有公开文档更新/删除/重索引路由；参考支持更新版本、删除与分享。
**限制：** 参考同步兼容上传有可见性窗口，物理清理部分 best-effort；不认定其所有路径具有原子更新。

**File：**

- 当前后端 [knowledge-service/src/main/java/com/lrj/agentic/knowledge/application/KnowledgeService.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/java/com/lrj/agentic/knowledge/application/KnowledgeService.java#L23)。
- 当前后端 [knowledge-service/src/main/java/com/lrj/agentic/knowledge/api/KnowledgeController.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/java/com/lrj/agentic/knowledge/api/KnowledgeController.java#L1)。
- 当前后端 [knowledge-service/src/main/java/com/lrj/agentic/knowledge/api/KnowledgeProcessingController.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/java/com/lrj/agentic/knowledge/api/KnowledgeProcessingController.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/DocumentController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/DocumentController.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/DocumentService.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/DocumentService.java#L230)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/DocumentShareController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/DocumentShareController.java#L1)。

<a id="e06"></a>

### E06 · 准备结果、双索引发布及可靠交接

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G04/G13；准备结果、双索引发布及可靠交接 |
| Evidence Type | SOURCE/SQL/QA |
| Class / Method | IngestionRunner；ProcessingMapper；ConversationMapper |
| Configuration | processorVersion / batchDigest |
| SQL·Table | knowledge_chunk / knowledge_ingestion / conversation dispatch |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前固定准备结果、sink 状态/租约、权威 chunk 与摘要绑定；会话派发先持久化。
**限制：** 可靠发布单版不等于用户可管理完整生命周期；本轮未重跑故障测试。

**File：**

- 当前后端 [knowledge-service/src/main/java/com/lrj/agentic/knowledge/application/IngestionRunner.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/java/com/lrj/agentic/knowledge/application/IngestionRunner.java#L1)。
- 当前后端 [knowledge-service/src/main/resources/mapper/ProcessingMapper.xml](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/resources/mapper/ProcessingMapper.xml#L1)。
- 当前后端 [knowledge-service/src/main/resources/db/migration/V2__knowledge_processing.sql](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/resources/db/migration/V2__knowledge_processing.sql#L1)。
- 当前后端 [conversation-service/src/main/resources/db/migration/V1__conversations_and_dispatch.sql](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/conversation-service/src/main/resources/db/migration/V1__conversations_and_dispatch.sql#L1)。
- 当前后端 [conversation-service/src/main/resources/mapper/ConversationMapper.xml](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/conversation-service/src/main/resources/mapper/ConversationMapper.xml#L1)。

<a id="e07"></a>

### E07 · 召回、融合与权威过滤

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G07/G13；召回、融合与权威过滤 |
| Evidence Type | SOURCE/CONFIG |
| Class / Method | KnowledgeSearch.search；DualSearchIndex.search；KnowledgeQueryService.query；ParallelRetrievalExecutor.retrieve |
| Configuration | ProcessingProtocol / source.enabled / fusionStrategy / rrfK |
| SQL·Table | ProcessingMapper.published |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前两路顺序召回、RRF60、权威发布/所有者/摘要过滤后 Top K；参考可组装来源并有有界并发、融合和重排前授权。
**限制：** 参考实际路数取决于 enabled；未验证运行时具体路数；两路本身不构成缺陷。

**File：**

- 当前后端 [knowledge-service/src/main/java/com/lrj/agentic/knowledge/application/KnowledgeSearch.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/java/com/lrj/agentic/knowledge/application/KnowledgeSearch.java#L40)。
- 当前后端 [knowledge-service/src/main/java/com/lrj/agentic/knowledge/infrastructure/processing/DualSearchIndex.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/java/com/lrj/agentic/knowledge/infrastructure/processing/DualSearchIndex.java#L97)。
- 当前后端 [knowledge-service/src/main/java/com/lrj/agentic/knowledge/domain/processing/ProcessingProtocol.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/java/com/lrj/agentic/knowledge/domain/processing/ProcessingProtocol.java#L1)。
- 当前后端 [knowledge-service/src/main/java/com/lrj/agentic/knowledge/domain/processing/ProcessingClaim.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/java/com/lrj/agentic/knowledge/domain/processing/ProcessingClaim.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/KnowledgeQueryService.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/KnowledgeQueryService.java#L298)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/search/ParallelRetrievalExecutor.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/search/ParallelRetrievalExecutor.java#L43)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/search/HybridFusionService.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/search/HybridFusionService.java#L1)。

<a id="e08"></a>

### E08 · 独立重排与查询扩展

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G07；独立重排与查询扩展 |
| Evidence Type | SOURCE/CONFIG |
| Class / Method | AuthorizedRetriever；RerankConfig；BailianReranker.rerank；LlmQueryExpander |
| Configuration | app.rag.rerank；RAG_RERANK_TYPE / MODEL |
| SQL·Table | — |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前 AuthorizedRetriever 只适配授权搜索；参考有 LLM/Jina/百炼重排及 query expander。YAML 默认 llm，Compose 默认 bailian/qwen3-rerank。
**限制：** 实现/默认配置不证明真实模型质量或目前服务已启用；百炼适配失败回退初始候选，需明确可见性。

**File：**

- 当前后端 [ai-runtime/src/agentic_ai/qa/retriever.py](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/ai-runtime/src/agentic_ai/qa/retriever.py#L27)。
- 当前后端 [ai-runtime/src/agentic_ai/qa/knowledge.py](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/ai-runtime/src/agentic_ai/qa/knowledge.py#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/rerank/RerankConfig.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/rerank/RerankConfig.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/rerank/BailianReranker.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/rerank/BailianReranker.java#L35)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/rerank/LlmReranker.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/rerank/LlmReranker.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/rerank/JinaReranker.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/rerank/JinaReranker.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/query/LlmQueryExpander.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/query/LlmQueryExpander.java#L1)。
- 参考项目 [knowledge-service/src/main/resources/application.yml](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/resources/application.yml#L318)。
- 参考项目 [deploy/docker-compose.yml](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/deploy/docker-compose.yml#L490)。

<a id="e09"></a>

### E09 · 质量数据集、评分与门禁

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G01；质量数据集、评分与门禁 |
| Evidence Type | SOURCE/SQL/ASSET |
| Class / Method | QualityController；QualityService；QualityScoring.score/compare；RetrievalEvaluator |
| Configuration | topK / allowedDrop / allowedP95IncreaseMs |
| SQL·Table | retrieval quality datasets/runs migration |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 参考数据集修订/基线与权限上下文绑定，逐题文档级指标及退化比较；存在 eval-service 与200题合成资产。
**限制：** Precision 分母为实际返回的不同文档数；不是固定K分母或答案语义评分。合成问题不代表本项目真实业务质量。

**File：**

- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/quality/QualityController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/quality/QualityController.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/quality/QualityService.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/quality/QualityService.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/quality/QualityScoring.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/quality/QualityScoring.java#L12)。
- 参考项目 [database-migrations/src/main/resources/db/migration/knowledge-ingestion/V6__retrieval_quality.sql](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/database-migrations/src/main/resources/db/migration/knowledge-ingestion/V6__retrieval_quality.sql#L1)。
- 参考项目 [eval-service/src/main/java/com/lrj/platform/eval/retrieval/RetrievalEvaluator.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/eval-service/src/main/java/com/lrj/platform/eval/retrieval/RetrievalEvaluator.java#L1)。
- 参考项目 [docs/qa/resume-metrics-0727-1539/datasets/rag-golden-200.json](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/docs/qa/resume-metrics-0727-1539/datasets/rag-golden-200.json#L1)。
- 参考项目 [docs/qa/resume-metrics-0727-1539/datasets/rag-corpus-manifest.json](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/docs/qa/resume-metrics-0727-1539/datasets/rag-corpus-manifest.json#L1)。

<a id="e10"></a>

### E10 · 知识反馈、候选与审批发布

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G09；知识反馈、候选与审批发布 |
| Evidence Type | SOURCE/SQL/API |
| Class / Method | KnowledgeOperations；KnowledgeOperationsController；KnowledgePublicationService |
| Configuration | 状态 DRAFT/EVALUATED/APPROVED/PUBLISHING/PUBLISH_FAILED/PUBLISHED |
| SQL·Table | V4 knowledge_operations |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 参考有反馈、候选状态、内容绑定评估、审批发布、任务同步、发布探测及回滚草稿。当前路由未包含这些业务。
**限制：** 局部 requiredText 检查不等于通用答案质量评分；生产全栈验证范围见E25。

**File：**

- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/operations/KnowledgeOperations.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/operations/KnowledgeOperations.java#L10)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/operations/KnowledgeOperationsController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/operations/KnowledgeOperationsController.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/operations/KnowledgePublicationService.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/operations/KnowledgePublicationService.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/operations/KnowledgeOperationsService.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/operations/KnowledgeOperationsService.java#L1)。
- 参考项目 [database-migrations/src/main/resources/db/migration/knowledge-ingestion/V4__knowledge_operations.sql](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/database-migrations/src/main/resources/db/migration/knowledge-ingestion/V4__knowledge_operations.sql#L1)。

<a id="e11"></a>

### E11 · 来源同步及运营控制

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G06；来源同步及运营控制 |
| Evidence Type | SOURCE/SQL/API |
| Class / Method | KnowledgeSyncController；KnowledgeSyncService |
| Configuration | http-sync.enabled / runtime.role / sources |
| SQL·Table | V7 knowledge_http_sync / V8 knowledge_sync_operations |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 参考有配置来源、外部记录映射、poll/pause/resume/retry/runs；身份和来源受服务端限制。
**限制：** 配置为空无同步数据；不是已接通所有企业来源。当前仅上传入口。

**File：**

- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/ingest/sync/KnowledgeSyncController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/ingest/sync/KnowledgeSyncController.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/ingest/sync/KnowledgeSyncService.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/ingest/sync/KnowledgeSyncService.java#L1)。
- 参考项目 [database-migrations/src/main/resources/db/migration/knowledge-ingestion/V7__knowledge_http_sync.sql](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/database-migrations/src/main/resources/db/migration/knowledge-ingestion/V7__knowledge_http_sync.sql#L1)。
- 参考项目 [database-migrations/src/main/resources/db/migration/knowledge-ingestion/V8__knowledge_sync_operations.sql](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/database-migrations/src/main/resources/db/migration/knowledge-ingestion/V8__knowledge_sync_operations.sql#L1)。

<a id="e12"></a>

### E12 · 回答引用、语义支撑及有限上下文

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G08/G11；回答引用、语义支撑及有限上下文 |
| Evidence Type | SOURCE |
| Class / Method | QaPipeline；ConversationController.chat；LlmGroundingChecker；ConversationGuardrail |
| Configuration | MAX_HITS / SYSTEM_PROMPT / grounding enabled |
| SQL·Table | — |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前 retrieve/generate/verify 校验引用编号及授权来源，选定上一轮上下文；参考对话有历史压缩、可选 guardrail/grounding。
**限制：** 引用校验不是语义蕴含证明；参考 grounding 异常跳过评分、警告不保证答案正确。

**File：**

- 当前后端 [ai-runtime/src/agentic_ai/qa/pipeline.py](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/ai-runtime/src/agentic_ai/qa/pipeline.py#L73)。
- 当前后端 [ai-runtime/src/agentic_ai/qa/models.py](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/ai-runtime/src/agentic_ai/qa/models.py#L14)。
- 参考项目 [conversation-service/src/main/java/com/lrj/platform/conversation/ConversationController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/conversation-service/src/main/java/com/lrj/platform/conversation/ConversationController.java#L1)。
- 参考项目 [conversation-service/src/main/java/com/lrj/platform/conversation/grounding/LlmGroundingChecker.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/conversation-service/src/main/java/com/lrj/platform/conversation/grounding/LlmGroundingChecker.java#L1)。
- 参考项目 [conversation-service/src/main/java/com/lrj/platform/conversation/grounding/GroundingConfig.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/conversation-service/src/main/java/com/lrj/platform/conversation/grounding/GroundingConfig.java#L1)。
- 参考项目 [conversation-service/src/main/java/com/lrj/platform/conversation/guardrail/ConversationGuardrail.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/conversation-service/src/main/java/com/lrj/platform/conversation/guardrail/ConversationGuardrail.java#L1)。

<a id="e13"></a>

### E13 · 跨会话记忆及治理

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G11；跨会话记忆及治理 |
| Evidence Type | SOURCE/SQL/QA |
| Class / Method | GovernedProfileStore；MemoryGovernanceController；UserProfileChatService |
| Configuration | profile enabled/store；expectedGeneration |
| SQL·Table | conversation V1 user_profile_memory |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 参考 JDBC profile、候选确认/拒绝/修改/遗忘/置顶、过期与 generation；当前没有对应业务。
**限制：** 记忆与会话存储不同；不将记忆默认开启作为本项目需求。

**File：**

- 参考项目 [conversation-service/src/main/java/com/lrj/platform/conversation/memory/profile/GovernedProfileStore.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/conversation-service/src/main/java/com/lrj/platform/conversation/memory/profile/GovernedProfileStore.java#L1)。
- 参考项目 [conversation-service/src/main/java/com/lrj/platform/conversation/memory/profile/MemoryGovernanceController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/conversation-service/src/main/java/com/lrj/platform/conversation/memory/profile/MemoryGovernanceController.java#L1)。
- 参考项目 [conversation-service/src/main/java/com/lrj/platform/conversation/memory/profile/JdbcUserProfileStore.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/conversation-service/src/main/java/com/lrj/platform/conversation/memory/profile/JdbcUserProfileStore.java#L1)。
- 参考项目 [conversation-service/src/main/java/com/lrj/platform/conversation/memory/profile/UserProfileChatService.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/conversation-service/src/main/java/com/lrj/platform/conversation/memory/profile/UserProfileChatService.java#L1)。
- 参考项目 [conversation-service/src/main/java/com/lrj/platform/conversation/memory/SummarizingChatMemory.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/conversation-service/src/main/java/com/lrj/platform/conversation/memory/SummarizingChatMemory.java#L1)。
- 参考项目 [database-migrations/src/main/resources/db/migration/conversation/V1__user_profile_memory.sql](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/database-migrations/src/main/resources/db/migration/conversation/V1__user_profile_memory.sql#L1)。
- 参考项目 [docs/delivery/persistent-profile-memory/TEST_RESULT.md](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/docs/delivery/persistent-profile-memory/TEST_RESULT.md#L1)。

<a id="e14"></a>

### E14 · 只读 Agent 与默认路由边界

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G12/G20；只读 Agent 与默认路由边界 |
| Evidence Type | SOURCE/CONFIG/CONTRACT |
| Class / Method | agents.pipeline；WORK_GRAPH；Gateway route |
| Configuration | checkpointer=False / subagents=[]；baseline backend / AGENT_URI |
| SQL·Table | — |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前两类知识工具、调用次数上限、无子Agent/内部checkpoint，外层根图恢复；参考默认 Agent 路由为外部 AgentScope。
**限制：** Java agent-service 控制器不等于默认外部执行器全部已核实；原子 analyze 重做可能再计费。

**File：**

- 当前后端 [ai-runtime/src/agentic_ai/agents/pipeline.py](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/ai-runtime/src/agentic_ai/agents/pipeline.py#L7)。
- 当前后端 [ai-runtime/src/agentic_ai/agents/models.py](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/ai-runtime/src/agentic_ai/agents/models.py#L1)。
- 当前后端 [docs/design/knowledge-first-20261020/WORK_TASK_CONTRACTS.md](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/docs/design/knowledge-first-20261020/WORK_TASK_CONTRACTS.md#L1)。
- 参考项目 [edge-gateway/src/main/resources/application.yml](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/edge-gateway/src/main/resources/application.yml#L152)。
- 参考项目 [deploy/docker-compose.yml](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/deploy/docker-compose.yml#L1028)。

<a id="e15"></a>

### E15 · 中央任务、租约与检查点

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G13；中央任务、租约与检查点 |
| Evidence Type | SOURCE/SQL/QA |
| Class / Method | TaskMapper；checkpointer；qa.worker |
| Configuration | graph_versions / tenant_id / epoch |
| SQL·Table | ai_task / ai_task_event / PG snapshots |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前行锁/SKIP LOCKED、epoch/expiry 和中央凭据约束 PG；worker 顺序领取两图，租户来自配置。
**限制：** 已有 fencing 不证明多租户公平和生产容量；本轮无新负载实验。

**File：**

- 当前后端 [async-task-service/src/main/resources/mapper/TaskMapper.xml](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/async-task-service/src/main/resources/mapper/TaskMapper.xml#L26)。
- 当前后端 [async-task-service/src/main/resources/db/migration/V1__durable_tasks.sql](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/async-task-service/src/main/resources/db/migration/V1__durable_tasks.sql#L1)。
- 当前后端 [ai-runtime/src/agentic_ai/tasks/checkpointer.py](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/ai-runtime/src/agentic_ai/tasks/checkpointer.py#L1)。
- 当前后端 [ai-runtime/src/agentic_ai/qa/worker.py](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/ai-runtime/src/agentic_ai/qa/worker.py#L257)。
- 当前后端 [docs/qa/ai-checkpoint-recovery-20261021/TEST_RESULT.md](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/docs/qa/ai-checkpoint-recovery-20261021/TEST_RESULT.md#L1)。

<a id="e16"></a>

### E16 · 身份信任、开发桥与分享开关

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G10；身份信任、开发桥与分享开关 |
| Evidence Type | SOURCE/CONFIG |
| Class / Method | JwtTrust；localIdentity；CasdoorTokenExchangeFilter |
| Configuration | loopback / X-Workbench-Request / app.rag.authz.mode |
| SQL·Table | — |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前 JwtTrust 和 local identity bridge 有明确边界；参考 Casdoor 令牌交换与分享存在，ReBAC 默认 disabled。
**限制：** 不宣称当前无鉴权，不把参考 authz 代码存在写成默认 enforce；未探测当前线上权限。

**File：**

- 当前后端 [platform-security/src/main/java/com/lrj/agentic/security/JwtTrust.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/platform-security/src/main/java/com/lrj/agentic/security/JwtTrust.java#L1)。
- 当前 React [deploy/local-identity.ts](https://github.com/lirji/agentic-ai-platform-web/blob/d2b4dc6957cfbc81d29a8493dc9aa284aab8e584/deploy/local-identity.ts#L1)。
- 参考项目 [edge-gateway/src/main/java/com/lrj/platform/edge/CasdoorTokenExchangeFilter.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/edge-gateway/src/main/java/com/lrj/platform/edge/CasdoorTokenExchangeFilter.java#L1)。
- 参考项目 [knowledge-service/src/main/resources/application.yml](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/resources/application.yml#L176)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/DocumentShareController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/DocumentShareController.java#L1)。

<a id="e17"></a>

### E17 · 前端业务流程与真实API绑定

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G18/G01/G09/G10；前端业务流程与真实API绑定 |
| Evidence Type | SOURCE/UI |
| Class / Method | App/KnowledgePage；RetrievalQualityPanel；KnowledgeOperationsPanel |
| Configuration | API request functions / error-state handling |
| SQL·Table | — |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前上传/引用/任务等真实操作；参考有质量、运营、同步、来源和记忆治理面板及API调用。
**限制：** 本轮读代码/旧QA，未重新浏览器截图；比较任务完整度而非主观视觉评分。

**File：**

- 当前 React [src/app/App.tsx](https://github.com/lirji/agentic-ai-platform-web/blob/d2b4dc6957cfbc81d29a8493dc9aa284aab8e584/src/app/App.tsx#L1)。
- 当前 React [src/pages/KnowledgePage.tsx](https://github.com/lirji/agentic-ai-platform-web/blob/d2b4dc6957cfbc81d29a8493dc9aa284aab8e584/src/pages/KnowledgePage.tsx#L1)。
- 当前 React [src/features/knowledge/upload-intent.ts](https://github.com/lirji/agentic-ai-platform-web/blob/d2b4dc6957cfbc81d29a8493dc9aa284aab8e584/src/features/knowledge/upload-intent.ts#L1)。
- 当前 React [src/features/knowledge/Citation.tsx](https://github.com/lirji/agentic-ai-platform-web/blob/d2b4dc6957cfbc81d29a8493dc9aa284aab8e584/src/features/knowledge/Citation.tsx#L1)。
- 参考项目 [capability-showcase-frontend/src/modules/rag/RetrievalQualityPanel.vue](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/capability-showcase-frontend/src/modules/rag/RetrievalQualityPanel.vue#L1)。
- 参考项目 [capability-showcase-frontend/src/modules/rag/KnowledgeOperationsPanel.vue](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/capability-showcase-frontend/src/modules/rag/KnowledgeOperationsPanel.vue#L1)。
- 参考项目 [capability-showcase-frontend/src/modules/rag/KnowledgeSyncPanel.vue](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/capability-showcase-frontend/src/modules/rag/KnowledgeSyncPanel.vue#L1)。
- 参考项目 [capability-showcase-frontend/src/modules/rag/KnowledgeSourceDetails.vue](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/capability-showcase-frontend/src/modules/rag/KnowledgeSourceDetails.vue#L1)。

<a id="e18"></a>

### E18 · 已有指标、追踪和参考告警资产

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G14；已有指标、追踪和参考告警资产 |
| Evidence Type | SOURCE/CONFIG |
| Class / Method | IngestionWorker；RequestCorrelation；OtelChatModelListener；ChunkMetrics |
| Configuration | management.endpoints.web.exposure；Prometheus alerts |
| SQL·Table | — |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前Java开放 health/prometheus，有Timer及请求关联；参考含模型/chunk观测与告警配置。
**限制：** 跨Java/Python完整链路覆盖与生产告警有效性需验证；不能写当前零监控。

**File：**

- 当前后端 [knowledge-service/src/main/resources/application.yml](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/resources/application.yml#L87)。
- 当前后端 [knowledge-service/src/main/java/com/lrj/agentic/knowledge/infrastructure/processing/IngestionWorker.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/knowledge-service/src/main/java/com/lrj/agentic/knowledge/infrastructure/processing/IngestionWorker.java#L1)。
- 当前后端 [async-task-service/src/main/java/com/lrj/agentic/task/api/RequestCorrelation.java](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/async-task-service/src/main/java/com/lrj/agentic/task/api/RequestCorrelation.java#L1)。
- 参考项目 [platform-observability/src/main/java/com/lrj/platform/observability/otel/OtelChatModelListener.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/platform-observability/src/main/java/com/lrj/platform/observability/otel/OtelChatModelListener.java#L1)。
- 参考项目 [knowledge-service/src/main/java/com/lrj/platform/knowledge/observability/ChunkMetrics.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/src/main/java/com/lrj/platform/knowledge/observability/ChunkMetrics.java#L1)。
- 参考项目 [deploy/prometheus/alerts.yml](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/deploy/prometheus/alerts.yml#L1)。
- 参考项目 [deploy/test-observability-config.sh](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/deploy/test-observability-config.sh#L1)。

<a id="e19"></a>

### E19 · 预算保护与平台计量边界

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G15；预算保护与平台计量边界 |
| Evidence Type | SOURCE/CONFIG |
| Class / Method | EmbeddingBudget.reserve；ChatBudget.reserve；BudgetAuthorityController；RedisTokenBudgetTracker |
| Configuration | budget_file / api-enabled / reservation flags |
| SQL·Table | Redis token budget ledger (可选) |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前本地文件锁账本限制累计调用；参考 token budget/Redis tracker/预留结算API按配置启用。
**限制：** 本地保守预留不是供应商账单，不能证明跨主机共享配额；不读取/公布密钥或真实账本内容。

**File：**

- 当前后端 [ai-runtime/src/agentic_ai/runtime/embedding_budget.py](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/ai-runtime/src/agentic_ai/runtime/embedding_budget.py#L17)。
- 当前后端 [ai-runtime/src/agentic_ai/qa/chat_budget.py](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/ai-runtime/src/agentic_ai/qa/chat_budget.py#L23)。
- 参考项目 [conversation-service/src/main/java/com/lrj/platform/conversation/budget/BudgetAuthorityController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/conversation-service/src/main/java/com/lrj/platform/conversation/budget/BudgetAuthorityController.java#L1)。
- 参考项目 [platform-metering/src/main/java/com/lrj/platform/metering/RedisTokenBudgetTracker.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/platform-metering/src/main/java/com/lrj/platform/metering/RedisTokenBudgetTracker.java#L1)。
- 参考项目 [platform-metering/src/main/java/com/lrj/platform/metering/TokenBudgetProperties.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/platform-metering/src/main/java/com/lrj/platform/metering/TokenBudgetProperties.java#L1)。

<a id="e20"></a>

### E20 · 持续集成和供应链

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G16；持续集成和供应链 |
| Evidence Type | INVENTORY/CONFIG/QA |
| Class / Method | Git tracked-path inventory / workflow jobs |
| Configuration | GitHub Actions triggers / test/scan steps |
| SQL·Table | — |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前两仓基线均无 .github/workflows；参考有reactor、前端、契约/配置及供应链工作流。
**限制：** 本轮未读取实时远程CI；参考历史SBOM失败见E25，工作流存在不等于绿色门禁。

**File：**

- 当前后端 [ai-runtime/pyproject.toml](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/ai-runtime/pyproject.toml#L1)。
- 当前 React [package.json](https://github.com/lirji/agentic-ai-platform-web/blob/d2b4dc6957cfbc81d29a8493dc9aa284aab8e584/package.json#L1)。
- 参考项目 [.github/workflows/agentscope-cutover-ci.yml](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/.github/workflows/agentscope-cutover-ci.yml#L1)。
- 参考项目 [.github/workflows/supply-chain.yml](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/.github/workflows/supply-chain.yml#L1)。
- 参考项目 [.github/workflows/capability-showcase-frontend-ci.yml](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/.github/workflows/capability-showcase-frontend-ci.yml#L1)。

<a id="e21"></a>

### E21 · 本地运行、部署和恢复工具

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G17；本地运行、部署和恢复工具 |
| Evidence Type | SOURCE/CONFIG/DOC |
| Class / Method | deploy.local；dev-infra manage.backup |
| Configuration | Dockerfile / Compose / Helm |
| SQL·Table | MySQL / PG / S3 / indexes |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前本地Java/Python/Web启动及搜索Compose；参考全应用Compose/Helm及dev_infra迁移备份/导入工具，不等于日常灾备体系。
**限制：** 未执行脚本或重新查服务健康；不把工具存在写成生产RPO/RTO或恢复成功。

**File：**

- 当前后端 [deploy/README.md](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/deploy/README.md#L1)。
- 当前后端 [deploy/local.py](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/deploy/local.py#L1)。
- 参考项目 [deploy/docker-compose.yml](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/deploy/docker-compose.yml#L1)。
- 参考项目 [knowledge-service/Dockerfile](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/knowledge-service/Dockerfile#L1)。
- 参考项目 [deploy/helm/platform/charts/platform-lib/templates/_deployment.tpl](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/deploy/helm/platform/charts/platform-lib/templates/_deployment.tpl#L1)。
- 参考项目 [deploy/dev-infra/manage.py](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/deploy/dev-infra/manage.py#L138)。

<a id="e22"></a>

### E22 · 参考额外领域业务

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G20；参考额外领域业务 |
| Evidence Type | SOURCE/API |
| Class / Method | OrderController；WorkflowController；AnalyticsController；TaxInvoiceReviewController |
| Configuration | workflow / analytics enable conditions |
| SQL·Table | 对应领域数据不在本轮读取范围 |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 参考有订单查询、流程任务、SQL分析和税票审核入口；当前未覆盖这些领域。
**限制：** 只证明业务入口/相关实现存在，不重新验收全部领域；不自动纳入知识平台必做项。

**File：**

- 参考项目 [order-service/src/main/java/com/lrj/platform/order/OrderController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/order-service/src/main/java/com/lrj/platform/order/OrderController.java#L1)。
- 参考项目 [workflow-service/src/main/java/com/lrj/platform/workflow/controller/WorkflowController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/workflow-service/src/main/java/com/lrj/platform/workflow/controller/WorkflowController.java#L1)。
- 参考项目 [analytics-service/src/main/java/com/lrj/platform/analytics/controller/AnalyticsController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/analytics-service/src/main/java/com/lrj/platform/analytics/controller/AnalyticsController.java#L1)。
- 参考项目 [tax-service/src/main/java/com/lrj/platform/tax/TaxInvoiceReviewController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/tax-service/src/main/java/com/lrj/platform/tax/TaxInvoiceReviewController.java#L1)。

<a id="e23"></a>

### E23 · 多模态、渠道和协议入口

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G20；多模态、渠道和协议入口 |
| Evidence Type | SOURCE/API |
| Class / Method | VoiceController；VisionController；ChannelController；InteropController；A2aController |
| Configuration | ConditionalOnProperty / external channel configuration |
| SQL·Table | — |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 参考有语音/视觉、渠道及MCP/A2A相关入口；当前未提供这些业务。
**限制：** 条件启用及外部依赖分别存在；本轮未验证真实渠道账号或多模态模型效果。

**File：**

- 参考项目 [voice-service/src/main/java/com/lrj/platform/voice/VoiceController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/voice-service/src/main/java/com/lrj/platform/voice/VoiceController.java#L1)。
- 参考项目 [vision-service/src/main/java/com/lrj/platform/vision/controller/VisionController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/vision-service/src/main/java/com/lrj/platform/vision/controller/VisionController.java#L1)。
- 参考项目 [channel-service/src/main/java/com/lrj/platform/channel/ChannelController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/channel-service/src/main/java/com/lrj/platform/channel/ChannelController.java#L1)。
- 参考项目 [interop-service/src/main/java/com/lrj/platform/interop/InteropController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/interop-service/src/main/java/com/lrj/platform/interop/InteropController.java#L1)。
- 参考项目 [interop-service/src/main/java/com/lrj/platform/interop/a2a/A2aController.java](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/interop-service/src/main/java/com/lrj/platform/interop/a2a/A2aController.java#L1)。

<a id="e24"></a>

### E24 · 当前实际验证证据与边界

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G01/G12/G13/G16/G18；当前实际验证证据与边界 |
| Evidence Type | QA_RECORD/FINGERPRINT |
| Class / Method | S6/S7 TEST_RESULT / source-fingerprint |
| Configuration | 测试时配置；不是当前在线探测 |
| SQL·Table | 隔离测试数据，未重新读取业务库 |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** S6/S7有真实组件、模型、恢复、UI测试；源码摘要可追溯。
**限制：** 292 Python含另21跳过，78Java、前端38单元/15浏览器不是跨项目评分；少量合成资料/单worker非系统质量/容量。

**File：**

- 当前后端 [docs/qa/research-readonly-20261021/TEST_RESULT.md](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/docs/qa/research-readonly-20261021/TEST_RESULT.md#L1)。
- 当前后端 [docs/qa/research-readonly-20261021/source-fingerprint.json](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/docs/qa/research-readonly-20261021/source-fingerprint.json#L1)。
- 当前后端 [docs/qa/research-readonly-20261021/IMPLEMENTATION_EVIDENCE.md](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/docs/qa/research-readonly-20261021/IMPLEMENTATION_EVIDENCE.md#L1)。
- 当前 React [docs/qa/s7/TEST_RESULT.md](https://github.com/lirji/agentic-ai-platform-web/blob/d2b4dc6957cfbc81d29a8493dc9aa284aab8e584/docs/qa/s7/TEST_RESULT.md#L1)。

<a id="e25"></a>

### E25 · 参考近期专项验证而非旧总览

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G01/G02/G05/G09/G11/G16；参考近期专项验证而非旧总览 |
| Evidence Type | QA_RECORD |
| Class / Method | TEST_RESULT / PROGRESS_STATE |
| Configuration | artifact/version/source fingerprints at historical runs |
| SQL·Table | 各专项临时库验证，不代表生产数据 |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 解析/结构来源/OCR/真实ES、MySQL、容器，以及运营/记忆/去重/界面均有专项记录。
**限制：** 不同阶段测试不可相加；运营全栈部分NOT_RUN、条件跳过和远程供应链失败均保留；本轮未复跑。

**File：**

- 参考项目 [docs/delivery/knowledge-parsing-hardening/TEST_RESULT.md](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/docs/delivery/knowledge-parsing-hardening/TEST_RESULT.md#L1)。
- 参考项目 [docs/delivery/memory-knowledge-operations/TEST_RESULT.md](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/docs/delivery/memory-knowledge-operations/TEST_RESULT.md#L1)。
- 参考项目 [docs/delivery/persistent-profile-memory/TEST_RESULT.md](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/docs/delivery/persistent-profile-memory/TEST_RESULT.md#L1)。
- 参考项目 [docs/delivery/knowledge-content-dedup/PROGRESS_STATE.md](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/docs/delivery/knowledge-content-dedup/PROGRESS_STATE.md#L1)。
- 参考项目 [docs/delivery/knowledge-ingestion-ui/PROGRESS_STATE.md](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/docs/delivery/knowledge-ingestion-ui/PROGRESS_STATE.md#L1)。

<a id="e26"></a>

### E26 · 阶段完成与文档时效

| 字段 | 核对结果 |
| --- | --- |
| Finding / Capability | G19；阶段完成与文档时效 |
| Evidence Type | DOC/REVISION |
| Class / Method | docs index / prior analysis / PROGRESS_STATE |
| Configuration | — |
| SQL·Table | — |
| Confidence | FACT（限定以下事实）；限制项不作已完成结论 |

**事实：** 当前旧索引含早期未实现描述，后续S7事实已更新；参考旧分析早于近期专项。
**限制：** 索引在本次文档交付修正，证据固定为分析前基线；不能引用旧报告替代当前源码。

**File：**

- 当前后端 [docs/README.md](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/docs/README.md#L1)。
- 当前后端 [docs/doc-map.md](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/docs/doc-map.md#L1)。
- 当前后端 [docs/design/knowledge-first-20261020/PROGRESS_STATE.md](https://github.com/lirji/agentic-ai-platform/blob/d61536cbb9179db010f33d7b1cdbda737e22fc92/docs/design/knowledge-first-20261020/PROGRESS_STATE.md#L1)。
- 参考项目 [.engineering/analysis/PROJECT_CAPABILITY_REPORT.md](https://github.com/lirji/langchain4j-platform/blob/468cec425dd160e80cfc3ab378d3440a8767e4d5/.engineering/analysis/PROJECT_CAPABILITY_REPORT.md#L1)。

## 本轮验证与未知项

本轮只执行文档结构、证据文件/提交/摘要、引用链接及变更范围检查，结果见 [VALIDATION](VALIDATION.md)。未将历史 PASS 写成新执行结果，未验证实时远程 CI 或在线服务。参考近期扫描失败仅为固定记录中的历史事实。

未决：双方同集质量与成本差异、生产多租户容量、实际身份/共享启用状态、完整灾备指标、外部 AgentScope 和各额外领域真实使用情况。本报告对这些问题不补造结论。
