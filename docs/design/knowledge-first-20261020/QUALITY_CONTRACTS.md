# 质量评测契约

版本：`quality-v1`，2026-10-21；C-EVAL 的唯一正式入口。S8A 冻结本文件的定义、指标和执行归属；E01 可直接进入 S8B。E02/E03 的运行传输 DTO、内部服务身份和数据库迁移须在 S8C/D 实施前补齐，不能据本文件虚构已实现接口。

## 1. 归属、修订与权限

- Java Knowledge 拥有评测集、不可变定义修订、逐题业务报告、基线与比较。Tasks 拥有通用任务受理、租约、取消、恢复及终态；LangGraph 是唯一执行/恢复根图。LlamaIndex 适配检索与指标，不直写评测数据库、不另起 Workflow。
- 当前范围为已验证 `tenantId + userId` 的个人资料；身份不从请求体获取。读取、修改、分页、幂等命令查询均有数据库身份谓词，不按用户提供的 ID 先取后过滤。未知与越权资源同为 404。
- 每次保存完整定义生成递增修订；题目、顺序、标注、评分标准及名称变化都形成新修订。旧修订和已完成报告不变，旧基线不得冒充新定义的基线。前端保存采用 `expectedRevision`，冲突保留本地草稿并让用户重新核对。
- 服务端绑定每题检索范围中的**全部**资料快照：documentId、publishedVersion、sourceHash、batchDigest、processorVersion；正确证据再绑定 chunkId、textHash 和原文位置。客户端只提交引用身份和相关等级，不能声称摘要、权限或发布时间可信。
- 每次读取定义、运行、恢复、外发模型和返回报告前重新校验当前授权及发布绑定。任一范围资料失效时，定义详情仍可返回本人的修订元数据，但 `bindingState=UNAVAILABLE, definition=null, bindings=[]`，不返回失效资料名称、ID、题目、标注或原文。当前引用只支持有效发布版本；历史版本能力留待 S10。
- 列表与修订历史只返回本人的定义元数据，不包含题目、原文或资料 ID。用户自行填写的名称不作为资料访问凭证。前端失效后清空对应缓存正文，不继续显示上次成功内容。已经送出的模型请求无法因后续撤权被召回，不承诺跨服务原子撤权。

## 2. E01：评测定义

公共路径前缀：`/api/v1/knowledge/evaluations`；沿用网关可信身份、Knowledge scope 和 `ApiError`。所有时间为 UTC ISO-8601，标识为规范小写 UUID，修订从 1 开始。仅 POST 需要 Knowledge write scope；GET 需要 read scope。

| 方法与路径 | 请求 | 成功结果 |
| --- | --- | --- |
| POST `/datasets` | `Idempotency-Key`；`Definition` | 201 `Receipt`，重放同一结果 |
| POST `/datasets/{datasetId}/revisions` | 同上；`{expectedRevision, definition}` | 201 `Receipt`，条件写入 |
| GET `/commands/{key}` | 同一调用者原提交键 | 200 `Receipt`；未持久受理 404，可按原请求原键重试 |
| GET `/datasets` | `limit=20`，1–100；可选 `cursor` | 200 `{items: DatasetSummary[], nextCursor: string|null}` |
| GET `/datasets/{datasetId}` | 无 | 200 最新 `RevisionDetail` |
| GET `/datasets/{datasetId}/revisions` | 同样分页参数 | 200 `{items: RevisionSummary[], nextCursor}` |
| GET `/datasets/{datasetId}/revisions/{revision}` | 正整数修订 | 200 指定 `RevisionDetail` |
| GET `/evidence` | 必填 documentId、version；limit 默认 20，1–100；可选 cursor | 200 `{items: EvidenceOption[], nextCursor}`，只读已发布块，不调用 Embedding |

数据集按 datasetId 升序游标分页，修订按 revision 降序，证据按 chunkId 升序；游标编码排序键并绑定资源范围，非法游标返回 400。分页不是快照隔离，不承诺分页过程中并发新增项目都可见。列表上限作用于数据库查询，不先取全量。

`Definition` 的所有字段必填，允许 description/referenceAnswer 空字符串、questions 空数组（空集可保存，禁止运行）。文本按 Unicode 码点计数，拒绝 NUL、无效代理字符和除换行/制表符外的控制字符；首尾空白规范化，内部换行和顺序保留。未知属性、重复 JSON key、错类型、浮点整数、非有限值均拒绝，不把字符串/数字隐式转为其他类型。总 JSON UTF-8 请求上限 256 KiB（包括 chunked 请求）。

```json
{
  "name": "架构与操作机制集",
  "description": "验证检索与评测机制，不代表业务资料质量",
  "purpose": "MECHANISM",
  "questions": [{
    "questionId": "3d980b18-2258-4cc1-8492-920d89b6b7be",
    "question": "当前允许哪些资料格式？",
    "expectation": "ANSWERABLE",
    "critical": true,
    "rubric": "应指出当前支持 TXT/Markdown，不得把规划的 PDF 当作已上线",
    "referenceAnswer": "当前支持 UTF-8 TXT 与 Markdown。",
    "documentIds": ["05218581-1e75-4d3c-ab41-576222ac8fd5"],
    "evidence": [{"documentId":"05218581-1e75-4d3c-ab41-576222ac8fd5", "version":1, "chunkId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "relevance":2}]
  }]
}
```

上例仅是 schema 示例，ID 不代表已入库资料，不能直接当演示数据。正式演示经接口入库、按实际响应绑定。

| 字段 | 限制与稳定 code |
| --- | --- |
| name / description | 1–120 / 0–2000 码点 |
| purpose | `MECHANISM` 或 `BUSINESS`；这是用途标签，不是质量认证 |
| questions | 0–100；questionId 在集内唯一；跨修订保留同一道题的 ID |
| question / rubric / referenceAnswer | 1–2000 / 1–2000 / 0–4000 码点 |
| expectation / critical | `ANSWERABLE` 或 `NO_ANSWER`；严格 boolean |
| documentIds | 1–20 个唯一 UUID；整个集最多 100 份不同资料；明确限定检索语料，不隐含未来新资料 |
| evidence | 0–10 个唯一 documentId/version/chunkId；relevance 只能为 1（部分相关）或 2（直接支撑） |
| 有答案题 | 至少一个正确证据；所有证据属于 documentIds，且为当前可读已发布版本 |
| 无答案题 | evidence 必须为空；rubric 说明为什么现有指定语料无法回答及预期拒答边界 |

`Receipt = {datasetId, revision, definitionDigest, createdAt}`。定义摘要绑定规范化定义和服务端资料快照；同一提交键的重放返回原 receipt，即使当前最新修订已变。`DatasetSummary = {datasetId, name, purpose, revision, questionCount, createdAt, updatedAt}`；`RevisionSummary = {datasetId, name, purpose, revision, questionCount, definitionDigest, createdAt}`。

`RevisionDetail = {summary: RevisionSummary, bindingState: AVAILABLE|UNAVAILABLE, definition: Definition|null, bindings: DocumentBinding[]}`。`DocumentBinding = {documentId, version, sourceHash, batchDigest, processorVersion}`，按 documentId 排序；各题相同资料共享一份绑定。`EvidenceOption = {documentId, version, chunkId, text, textHash, source: {displayName, locator}}`；当前 locator 仍为 K04 的 `utf8-bytes:start-end`。服务端内部证据快照保留文本摘要/定位，定义响应不额外复制原文；用户选择时通过 evidence/citation 读取当前原文。

提交键为 1–128 个 `[A-Za-z0-9._:-]`；持久化 SHA-256 键摘要，作用域为 tenant/owner。指纹包括操作、资源、expectedRevision 和规范化完整定义，先查同键结果再解析当前资料绑定。数组中 question 顺序有意义；documentIds/evidence 排序规范化；相同语义顺序变化不产生异参冲突。重复项先拒绝，不能悄悄去重纠错。

一个短事务原子保存当前指针、不可变修订和命令回执；唯一键阻止并发重复，expectedRevision 的 CAS 阻止覆盖他人修改。唯一约束冲突后在失败事务**回滚之外**查获胜回执；同键异参 409。提交结果未知时 UI 保留原请求/键，只用同键查询或重试；刷新后仅保存提交键和摘要，不把题目/原文持久写入浏览器存储。

| 错误 code（沿用 KnowledgeError） | HTTP | 可观察行为 |
| --- | --- | --- |
| `KNOWLEDGE_INPUT_INVALID` | 400 | 参数、JSON、游标或定义错误；保留编辑 |
| `KNOWLEDGE_EVALUATION_TOO_LARGE` | 413 | 请求超过 256 KiB |
| `KNOWLEDGE_NOT_FOUND` | 404 | 未知/无权资源或证据；不泄露存在性 |
| `KNOWLEDGE_IDEMPOTENCY_CONFLICT` | 409 | 原键已绑定另一请求；不得自动生成新键重发 |
| `KNOWLEDGE_EVALUATION_REVISION_CONFLICT` | 409 | expectedRevision 过期；不覆盖草稿 |
| `KNOWLEDGE_EVALUATION_BINDING_INVALID` | 409 | 当前可见资料的发布状态不可绑定；重新选择资料 |
| `KNOWLEDGE_DATABASE_UNAVAILABLE` | 503 | 不能伪装为空列表/成功；写入结果可能未知，按原键恢复 |

迁移 Owner 为 Knowledge：追加 V4，不重写 V1–V3；定义、修订、命令三张表均有表/字段中文注释、身份索引、唯一约束和修订外键。持久化使用既有 MyBatis-Plus/Mapper XML。无新增中间件、定时器或模型依赖。

## 3. 指标、标注与比较口径

运行固定 datasetId/revision/definitionDigest、资料绑定、调用者权限范围、模型与处理 profile、检索策略及 Top K。当前策略为两路召回、稳定身份合并、Java RRF `1/(60+rank)`（rank 从 1 开始）、权威发布/权限检查、Top K；评测调用实际生产路径，不能另造容易通过的专用检索器。

- `ANSWERABLE`：金标为唯一证据块集合 G，去重后的结果顺序 R。Recall@K = 命中金标数 / |G|；MRR@K = 第一个命中名次的倒数，无命中为 0；nDCG@K 的 gain=`2^relevance-1`、discount=`log2(rank+1)`，IDCG 按金标等级排序取 K。空返回的三项为 0，不抛成执行失败。默认所有金标均需覆盖；等价替代证据须先标注/修订，不事后改口径提高分数。
- `NO_ANSWER`：不进入召回/MRR/nDCG 分母；单列拒答正确性。返回了一段相关但不能回答问题的正文不等于答案可支撑。当前语料没有业务事实时，应说明依据不足，不编造事实。
- 引用可访问、定位正确、实际支撑回答、回答覆盖要求分别记录。合法引用不等于正确答案。初期支撑/覆盖/拒答由人工按预先 rubric 标注 `PASS|FAIL|UNREVIEWED`；未审阅不计合格，不把缺值当 0 或 1。模型裁判默认关闭。
- 失败、取消、权限失效、超时和费用不足是执行结果，不是质量零分。报告必须同时展示计划/成功/失败/取消/未评题数、失败原因和质量指标分母，不能只显示完成子集的高均分。
- 延迟记录排队与执行、各步骤和端到端耗时；成本区分提供方 usage/金额（有据时）与本地保守预留，未知计未知。不得把保守预留称账单，不因新运行或重启清零累计额度。
- 比较必须使用同一题目定义、语料与权限快照；允许明确改变被比较的检索/模型策略，并显示变化。处理 profile 改变分块时，按 sourceHash + 原文定位重新映射金标，不能把旧 chunkId 的失配当质量退化；不能确定映射则 `NOT_COMPARABLE`。
- 基线须运行完整、资料仍有效、必要人工评分齐备，且预先定义的门禁通过。机制初始门禁：关键题不可从 PASS 退化；任一跨权返回、错误来源绑定或执行失败阻止设置基线；总体均值不能掩盖关键题。业务质量/性能阈值留给业务集校准，不能虚构达标数字。

## 4. E02/E03：执行与报告约束

S8C 使用 Tasks 的新增评测类型及版本化 LangGraph 根图；每题复用当前 RAG，不为评测启动另一个通用调度器。Knowledge 保存请求和业务结果，可靠交接绑定唯一 taskId；受理不等于运行成功。发起、取消、恢复都有稳定请求身份，恢复须重验定义、资料、权限、预算与原 checkpoint 兼容性。

逐题候选报告按 runId/taskId/leaseEpoch/questionId 隔离并不可变；旧 epoch 不能覆盖新候选或已确认报告。**一次 RPC 查租约后写 Knowledge 不能证明原子 fencing**：完成报告须绑定 Tasks 实际接受的终态/epoch/结果摘要；Knowledge 只提升与该权威凭据一致的候选为正式报告。完成确认丢失时查询并对账，不重新执行已有确定结果；不可确认时保持未完成而非伪造成功。逐题中间状态不提升为整次成功。

现有 K05 只读运行身份不能直接扩成任意报告写权限。S8C 冻结专用内部报告能力、上限、任务归属和终态凭据校验后才能开放内部端点；浏览器不能提交自评成功或租约 epoch。跨数据库不承诺事务原子性，使用稳定交接与不可变结果对账。

S8D 补齐基线选择与对比传输 schema、revision CAS、逐题差异和人工评分审计。对比只解释已固定维度的变化；放弃运行保留既有终态/部分结果，不删除审计记录。S8B 不展示尚未接通的运行/比较按钮。

## 5. 验收与变更

样本与边界见 [QUALITY_ACCEPTANCE](QUALITY_ACCEPTANCE.md)。当前适配依据为既有 K04/K05/T01/Q 协议和 [FRAMEWORK_EVIDENCE](FRAMEWORK_EVIDENCE.json)；不升级依赖。新增字段/错误/语义必须先同步本文件，再改双方类型、API/UI 测试和持久迁移。每次实现分别记录实际通过项，S8A 的文档验证不替代 S8B–E 的产品验证。
