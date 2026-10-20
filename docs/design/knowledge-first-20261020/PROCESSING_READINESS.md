# 知识处理与发布：下一阶段准备

本文保留承接 S1 时的依赖盘点。S2/S3 现已落实下表要求，worker、双索引发布及真实恢复证据见 [S3 验证](../../qa/knowledge-publication-20261020/TEST_RESULT.md)。正式契约以 [KNOWLEDGE_CONTRACTS](KNOWLEDGE_CONTRACTS.md)和 [PROCESSING_CONTRACTS](PROCESSING_CONTRACTS.md)为准，不从旧准备记录推断当前状态。

## 已具备

- 独立 Gateway 身份边界、Knowledge 原文/文档/持久任务，S3 原文可按完整性摘要核对。
- 任务 revision、文档版本、所有者、原文指纹与请求指纹；S1 当时仅受理，S3 已实现 PROCESSING/READY/FAILED 及受控恢复。
- S0 的真实 OpenAI 兼容 Embedding 客户端：text-embedding-v4 / 1024 维，批量和总期限有界，失败不回退 hash。
- 固定参考源码已核对：`IngestionJobStateMachine` 的逐 sink 状态、`IngestionJobWorker` 的 claim/心跳/revision 检查、`PreparedIngestionDocument` 的非空分块及向量数量约束。来源仍为 `langchain4j-platform@468cec425dd160e80cfc3ab378d3440a8767e4d5`。

## 已由 S2/S3 落实的设计与实现依赖

| 依赖 | 约束与落点 | 进入实施的条件 |
| --- | --- | --- |
| 真实模型环境 | 账号、地域/API 根地址、调用额度；沿用已选模型与维度 | 确认本轮账号/地域及额度，在本机私密配置注入，不将旧配置文件整份迁入 |
| 版本化准备结果 | 绑定 tenant/owner/doc/version、sourceHash、处理版本、chunk ID、原文定位、向量数量/维度/有限值及批次摘要 | 先冻结语言中立 schema；不把旧 LangChain4j `TextSegment/Embedding` 当作公共契约 |
| Worker 所有权 | MySQL claim、lease owner/epoch、期限、心跳和所有阶段条件更新；旧 owner 失去写入能力 | 新增 V2 迁移及 Mapper XML，不改已经执行的 V1；明确系统执行者审计 |
| 检索投影 | Knowledge 是唯一索引写入者；Qdrant/ES 使用新项目集合/索引、tenant/owner/doc/version/处理版本过滤 | 明确独立实例或确有授权的共享实例；现有 `lc4j-*` 专属实例不能直接视为公共实例 |
| 发布与恢复 | 准备结果可复用，逐 sink 幂等；索引完成前不改 publishedVersion；失败和未知提交结果可恢复 | 冻结提交与回滚边界、稳定错误目录、重试次数/退避/人工恢复及终态保留 |
| K03 重试、K04 查询/引用 | 失败重试保留原输入；检索再次核对 OWNER 与 publishedVersion；依赖失败不返回假空结果 | 正式补齐 K04 错误码、版本和索引提交约束，再实现 API |
| 验收 | 真实 Embedding 与独立双检索存储、过期 owner、单 sink 失败/恢复、重启与引用权限 | 不以替身向量或 RECEIVED 状态冒充知识入库完成 |

本轮用户已答复“沿用原配置，上限 1 元”。已沿用原北京地域百炼配置，S2 真实准备/查询两次调用通过，合计保守预留 0.02 元；账单未读取。当前 P01/P02 已在 [PROCESSING_CONTRACTS](PROCESSING_CONTRACTS.md) 冻结并实现。后续继续限定测试语料，所有本轮调用沿用同一预算账本，不自动导入旧业务知识。

## 后续页面与生产边界

React 仍按已批准前端架构独立建仓。资料列表、上传和任务页消费真实 K 契约；知识问答/研究/助手需各自 Q/T 契约与真实可用后端。生产 IAM 登录回跳、scope/租户映射尚未配置，本地签名令牌工具只用于 API 验收，不能作为面向业务用户的登录方案。

Java 仍沿 Boot 3.5 的既定主线。当前三个 Spring 公告按 S1 未启用的调用条件评估；新增 SSE/视图或部署形态时必须重新判断适用性，生产商业补丁或后续版本演进需另行落实，不能沿用本片的局部“不受影响”判断。
