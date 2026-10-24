# S9B 实现与审查证据

本片实现原生 PDF 入库和页级来源。契约沿用已冻结 P03/P04/K06；验证见 [TEST_RESULT](TEST_RESULT.md)。基础批次 `323e663` 已单独验证提交，最终指纹见 [final-validation](final-validation.json)，不把基础指纹当作最终版本。

| 边界 | 主要实现与审查依据 |
| --- | --- |
| 上传和 profile | `DocumentFormat` / `ProcessingProfile` 冻结格式、限制和处理三元组；`UploadRules` 校验扩展名、MIME、签名和实际字节。原 TXT/MD 1 MiB，PDF 10 MiB。Gateway/Servlet/本地身份桥上传请求 11 MiB，流式累计，不扩大其他接口 |
| 独立解析 | `knowledge-parser` PDFBox 3.0.8；实际页序、空页/非文字/近似布局告警；缺 Unicode 映射立即拒绝。`ParserProcess` 固定 argv/JAR、允许列表环境、0700 临时目录、单槽无排队、20 秒、stdout 4 MiB/stderr 64 KiB、已知子孙有界回收；无法证明退出时关闭准入 |
| 原文与提取物 | `S3PreparationStore` 分开校验原文件/提取物/准备物的字节与摘要。`IngestionRunner` 只有提取接受完成才调用 P04；已接受对象不被再次解析替换 |
| 事务/恢复 | `MySqlProcessingRepository.extracted` 在事务记录指针和 EXTRACTED 事件。未知提交仅查询同一有效租约、完全相同指针；`ProcessingMapper.xml` 用 owner/tenant/job/version/lease/epoch/status fencing。准备与已完成 sink 分别复用 |
| 兼容迁移 | V7 追加 profile/提取指针/结构来源，旧 v1 字节偏移和 hash 不重写；显式非 NULL CHECK 避免 SQL UNKNOWN 放行。没有修改已应用 V1–V6，无新基础设施或旧数据重算 |
| Python 准备 | `structured.py`/pipeline 独立严格校验 P03，以现有 LlamaIndex 512/64 对非空块分别分块，100 块上限先于 Embedding；v1/v2 共用身份、期限、单准入对象和既有模型预算 |
| 摘要/索引 | `StructuredPreparedDocument` 独立校验所有身份、版本、来源区间、覆盖和 1024 维向量摘要；`PreparedBatch` 只统一内部索引视图，v1 JSON 不变。ES/Qdrant 与 SQL 使用同一闭合兼容 profile 集合，SQL 最后复验当前发布 |
| 评测 | `EvaluationMapper` 读真实 processor_version；引用、评测、研究/问答接受闭合 locator，不把来源字符串当授权。没有为 PDF 重新执行旧付费基线 |
| K06 | `KnowledgeSources` / `KnowledgeSourceController` 复用当前发布权限，每次读取判权；统一 404，无额外正文、S3 URL、原文件下载或凭据；响应上限 256 KiB。运行身份仍只读原内部 K04/K05 |
| 前端 | PDF MIME/签名/上限及幂等恢复；`processing.ts` 绑定 K04/K06 身份、版本、摘要和页号；`CitationContent` 等完整来源验证才显示正文，重取/失败隐藏缓存。`ProcessingNotes` 显示真实页告警，搜索/详情/抽屉明确“提取文本” |

真实隔离集成覆盖 DB/S3/两路索引/解析子进程，实际模型和浏览器证据分列。测试合成向量仅用于无费用故障/权限验证；正式浏览器资料走真实接口，页面没有硬编码演示正文。

运行开关均默认关闭，启用/回退见 [deploy/README](../../../deploy/README.md#pdf-解析与页级来源s9b)。已发布 v2 必须保留 V7 以及兼容读取器；关闭新 PDF 上传后仍能读取已发布来源。构建前必须正常停止会被覆盖 JAR 的服务。本机最终制品与回退读取见 [runtime-validation](runtime-validation.json)。

审查由本 Agent 完成，重点是接受顺序、租约栅栏、未知结果查询、来源摘要、流量限制及缓存重验。没有新增服务、DOCX 生产者、OCR、跨块新 profile 或生产部署；后续计划按用户要求暂停。
