# S9B 基础批次实现证据

- Slice：S9B，整体 IN_PROGRESS；本批次对应 P03/P04 及新来源消费者，前端/持久发布未接入。
- `platform-contracts/.../knowledge`：纯 Java/JSON 注解线类型、闭合 PDF 枚举、稳定退出码、资源常量与可重算提取摘要；不依赖 PDFBox 或 Spring。
- `knowledge-parser`：独立一次性 JVM / PDFBox3.0.8、逐真实页提取、空页告警、加密/损坏/空文字/资源上限拒绝；只输出 stdout 协议，业务库日志 stderr；Spring Boot 插件仅打包，不启动 Spring 上下文。
- `knowledge-service/.../processing/{ParserProcess,ParserProperties}`：固定 argv/JAR、允许列表环境、任务独占 0700 临时目录、1 个无等待槽位、3 个无排队管道任务、20 秒期限、退出码映射及已知进程树有界回收。未证明回收时保留槽位并关闭后续准入。
- `StructuredPreparedDocument`：实际 v2 请求/结果 DTO，独立还原提取文字片段，校验覆盖/重叠、权限身份、版本、所有摘要及有限 1024 维向量。v1 PreparedDocument 线形状和算法不变。
- `ai-runtime/.../processing/structured.py` 与 pipeline/api：独立 P03 验证和 P04 准备、同一 LlamaIndex 字符分块器、模型调用前 100 块上限和位置证明、原预算/模型端口；v1/v2 使用同一准入对象。
- `qa/models.py`：QA、评测、研究共用闭合页/段/格 locator 读取，未新增 DOCX 解析能力。
- CI：登记 P-v2 以及两个跨语言真实 DTO 金标；保留原 P01/E02 金标；最终源码摘要见 [foundation-validation.json](foundation-validation.json)。

按用户同任务分批提交约定，这是一批已验证的基础代码；不会因此把 S9B 改为 DONE 或宣称已可在线上传 PDF。没有生产部署、新模型预算、历史数据清理、依赖全仓升级或其他任务修改。
