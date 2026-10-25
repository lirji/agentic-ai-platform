# S9D 实现证据

2026-10-25，slice=S9D；后端基线79368c0、前端基线cdf7e47。依据[结构契约第9节](../../design/knowledge-first-20261020/STRUCTURED_PROCESSING_CONTRACTS.md)、D-P09–12/T34和既有S10A生命周期语义，实施新profile，保留P04 wire、旧三种profile及原golden身份。

| Owner / 路径 | 实际变化与原因 |
| --- | --- |
| A：ai-runtime/processing/structured.py、pipeline.py | 实际LlamaIndex Document→TextNode；真实标题分组、最多16非空块、同行格和两相邻非空页；空标题/空页/空表格行保留边界。中文、代码、Unicode和重复原文按单调位置定位。标题和前序显式表头只加至Embedding metadata，引用正文保持原字节 |
| K：StructuredSources、StructuredPreparedDocument、ProcessingProfile | Java独立重建全局来源流、分组、UTF-8边界与总重叠；多segment连续覆盖、512/64/256/100硬限额；重复、逆序、跨组、跳字和重签恶意位置拒绝。复用唯一16来源常量和既有错误目录 |
| K：ProcessingAdmissionProperties、KnowledgeService/Ingestion/KnowledgeSources | 类型化BLOCK_V2/STRUCTURE_V3选择，默认旧值，未知启动失败；仅新PDF/DOCX受理选profile；同键重放、在途/已发布任务用持久三元组，TXT/MD不变 |
| Runtime：V9、application.yml、deploy | 只扩展既有数据库profile CHECK，未改旧迁移；传递有安全默认值的选择配置；dev/test真实应用并验证关闭选择后旧新读取、重放和恢复 |
| W：独立前端processing.ts | K06 metadata闭合列表接受新增合法三元组；仍拒绝未知、错format/parser。复用原多来源显示、布局与错误恢复，不新增页面硬编码业务数据 |

三份共享golden由固定PDFBox/POI解析器和锁定LlamaIndex生成；向量明确为合成协议向量。真实模型对照另见[profile-comparison](profile-comparison.json)，不混用两类证据。自有夹具原文件及摘要在[清单](fixture-manifest.json)，生成器采用独占创建，不静默覆盖golden。

复核：无新依赖、模型、服务或并发设施。新增协议常量为正式硬边界；Code Hygiene关于16/256已命名常量的提示无需再抽取，QA中的20/60秒及分页数量是有限验收参数。Java统一格式器/静态分析器未配置，Python实际Ruff/mypy与前端Prettier/ESLint另有执行证据。按现有Java风格、中文原因注释与差异检查复核。全局MULTI_CLIENT_DRIFT_BLOCKED未由本片改写，同一Agent分阶段复核，不宣称独立Agent审查。

SKILL_HANDOFF: backend-implementation / frontend-implementation / runtime-and-deploy，slice=S9D，status=COMPLETED；changed_paths和源码摘要见[最终验证](final-validation.json)，交implementation-validation验收，再update-progress-docs及Git/CI交付；下一必要切片S10B。
