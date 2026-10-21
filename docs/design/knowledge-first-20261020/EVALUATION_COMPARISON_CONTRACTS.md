# E03 人工评审、基线与逐题比较

2026-10-21，`evaluation-comparison-v1`；S8D 在 [quality-v1](QUALITY_CONTRACTS.md) 与 [E02](EVALUATION_RUN_CONTRACTS.md) 上补齐传输。Knowledge 继续拥有评测业务数据和短事务；复用已确认的 LlamaIndex 指标、Tasks终态和LangGraph报告，不另起执行图、裁判模型或存储服务。所有比较/评审操作均不调用模型。

## 评审归属与必要维度

评审绑定 `runId + manifestDigest + questionId`，每次保存追加不可变 revision，保存完整评分/说明、可信 reviewer、UTC时间和内容摘要；当前指针通过 expectedRevision 条件更新。初始revision=0表示未评分，不伪造一条已保存记录。不同清单不能继承评分；恢复中的旧报告可查看，不能修改为新一轮评分。

`Assessment = {citationCorrect,supported,coverage,refusal}`。适用字段为 `PASS|FAIL|UNREVIEWED`，不适用字段必须为null；没有默认PASS。citationCorrect仅在实际回答包含引用时适用（引用是否确实对应所述来源和定位）；ANSWERABLE适用supported/coverage；NO_ANSWER适用refusal。判定依据是冻结问题、rubric、原文和实际回答；引用当前可访问的自动检查不能代替人工支撑判断。执行失败/未开始题不可评分。

`note`为0–2000码点；任何FAIL以及修改已有评分必须填写说明。保存UNREVIEWED允许未完成评审，不能设置基线。字段约束/原因说明由服务端验证；数据表和HTTP错误使用既有Knowledge目录。评审人是已验证Caller，不从请求体接受身份或时间。

写入前读取当前中央事实，确认manifest对应当前终态凭据和实际SUCCEEDED题。数据库事务锁定本运行后再次检查已观察终态/清单与无待恢复意图；与Knowledge恢复操作共用该行锁。事务内按文档ID顺序锁定当前范围并再次核对冻结绑定，阻止资料发布/撤权与决策保存交错。外部HTTP不进入事务。失败的评测可评审其已确认成功前缀，但运行整体不具备基线资格。

## E03 公共接口

统一前缀 `/api/v1/knowledge/evaluations`，GET需要knowledge:read，POST需要knowledge:write。仅本人/本租户可读写，未知与无权同404。严格JSON，写正文上限32KiB，沿用256KiB外层最大边界；UUID/SHA256/Unicode/幂等键规则沿E01。所有列表有界，题数最多100。

| 接口 | 请求 / 返回 |
| --- | --- |
| GET `/runs/{runId}/reviews` | ReviewState：当前已确认清单、评分摘要和基线资格；绑定失效只返回可安全显示的元数据，questions=[]，不能返回评分说明或题目 |
| GET `/runs/{runId}/questions/{questionId}/reviews?manifestDigest&limit&before` | ReviewPage，manifestDigest必填；limit默认10、1–20；before为正整数评分revision，按revision降序。返回 `{items:ReviewRevision[],nextBefore}`；当前清单已变409 |
| POST `/runs/{runId}/questions/{questionId}/reviews` | Idempotency-Key；`{manifestDigest,expectedRevision,assessment,note}` →201 DecisionReceipt；revision=0为首次保存 |
| GET `/decision-commands/{key}` | 200原DecisionReceipt或404；未知写入先按原键查询，不自动生成第二个键 |
| GET `/datasets/{datasetId}/revisions/{datasetRevision}/baseline` | 200当前BaselineView或404；定义修订不同不会读到旧定义的基线 |
| GET `/datasets/{datasetId}/baselines?datasetRevision&limit&before` | datasetRevision必填；按baseline revision降序的BaselinePage，默认10、上限20；只列元数据 |
| POST `/datasets/{datasetId}/revisions/{datasetRevision}/baseline` | Idempotency-Key；`{expectedRevision,runId,manifestDigest,reviewDigest,note}` →201 DecisionReceipt；expectedRevision=0表示本定义尚无基线，note为1–2000码点，指针CAS |
| GET `/runs/{candidateRunId}/comparison?baselineId` | baselineId为不可变基线标识；200 ComparisonView，明确PASS/FAIL/INCOMPLETE/NOT_COMPARABLE，不用空成功掩盖缺条件 |

`DecisionReceipt = {kind,datasetId,datasetRevision,runId,manifestDigest,questionId,baselineId,revision,createdAt}`；kind=`REVIEW|BASELINE`。REVIEW的questionId非空、baselineId=null；BASELINE反之。独立decision命令作用域为tenant/owner/key，指纹包含操作/资源/预期修订和完整规范化正文；查原命令在当前绑定校验前，重放仅返回本人原元数据，不返回旧正文。并发同键唯一，失败事务回滚后才能查询获胜回执。

`ReviewRevision = {runId,manifestDigest,questionId,revision,assessment,note,reviewer,createdAt,contentDigest}`；没有原文或答案副本。`ReviewSummary = {questionId,revision,assessment,contentDigest,reviewable,complete,updatedAt}`；未保存时revision=0、contentDigest/updatedAt=null，适用维度为UNREVIEWED。`ReviewState = {runId,manifestDigest,reviewDigest,bindingState,canReview,questions,eligibility}`，无确认报告时两个digest=null。reviewDigest由manifestDigest及按定义顺序的questionId/revision/contentDigest构成；任一评分更新都会变，防止用户按旧评审设置基线。

`Eligibility = {eligible,policyVersion,reasons}`，reasons为稳定Reason编码数组。`BaselineView = {baselineId,datasetId,datasetRevision,revision,runId,manifestDigest,reviewDigest,policyVersion,note,selectedBy,createdAt,bindingState}`；历史列表不带note。绑定失效的详情note=null，不返回评审正文。基线修订是同一datasetId/定义修订内的递增选择版本。

## 基线资格与不可变选择

初始政策固定为 `mechanism-gate-v1`，不能看完比较结果临时修改容许退化。它只代表以下机制/检索质量条件，不是业务效果、时延或费用SLA认证。

设基线必须：定义至少一题、当前资料绑定有效；中央SUCCEEDED且当前proof与请求manifest一致；完整报告无执行失败或未开始；全部成功题的必要人工维度已评审；所有引用正确性维度不能FAIL；所有关键题适用维度必须PASS。关键ANSWERABLE若实际NO_EVIDENCE也不合格，不接受与可观察拒答事实冲突的覆盖PASS。非关键题已知质量不足允许作为实际基线保留，并显示失败数，不宣称全部题达标。

基线选择追加不可变记录，冻结该run/manifest、reviewDigest、每题评分revision/内容摘要/判定、policyVersion和选择说明；原人工评分历史保持可追溯。之后修改评分不会静默改写已选基线，须明确重新选择形成新版本。选择事务按“定义行→运行行→当前基线指针”顺序加锁，重新核对当前报告、评分摘要和CAS；无外部调用。并发选择同expectedRevision仅一方成功，冲突后不能覆盖。

新定义修订有独立基线指针；旧基线仍可审计，不能冒充新定义的质量。当前语料/权限失效时隐藏评审正文并禁止比较/选择；不删除旧审计记录。本片不提供删除/撤销审计的接口。

## 比较与退化判定

比较必须同tenant/owner、datasetId、datasetRevision、definitionDigest、TopK及完整语料绑定；模型/提示/检索profile可以不同，响应显式列出变化。处理版本或分块改变使定义绑定不同时返回NOT_COMPARABLE；不在本片猜测映射金标，重绑定能力由后续处理演进实现。

baselineId指定不可变评分快照，candidate使用当前确认清单及其当前评分快照。两端摘要和正文均须可按对应manifestDigest读取。候选当前代次没有确认proof时INCOMPLETE，不拿上一次报告冒充本次比较。候选FAILED且有部分报告时保留逐题成功/失败/未开始分布，门禁FAIL；未开始不补零。

`ComparisonView = {baseline,candidate,policyVersion,status,reasons,profileChanges,summary,questions}`。baseline为BaselineView；candidate包含runId/datasetId/datasetRevision/manifestDigest/reviewDigest/taskStatus。不可比时summary=null、questions=[]。比较正文不复制问题/答案/原文，UI从当前授权定义和E02精确清单读取。

`questions`按定义顺序：`{questionId,critical,expectation,baseline,candidate,delta,changes}`。每侧为 `{outcome,metrics,timing,cost,review}`；outcome=`SUCCEEDED|FAILED|UNSTARTED`。未开始的metrics/timing/cost为null；失败指标为null。review是该侧固定ReviewSummary。delta只有双方存在且适用的指标/耗时/已知金额才有值，未知不补0；changes为RETRIEVAL_IMPROVED/RETRIEVAL_REGRESSED/REVIEW_IMPROVED/REVIEW_REGRESSED/EXECUTION_FAILED/UNREVIEWED等稳定编码，可多项并存，不能用单一“变好”掩盖混合变化。

summary两侧分列计划/执行成功/失败/未开始/评审完整/人工通过题数与指标分母；检索平均值只在全部ANSWERABLE题有有效指标时返回，否则null并显示已计数/总题数。总题目耗时仅在全部题已有执行结果时返回，不含排队；中央createdAt→终态updatedAt另列elapsedMs（包含排队/等待恢复），queueMs未知为null。费用分别汇总providerUsage、providerAmountCny、chatReservedCny、embeddingReservedCny，任一题相应值未知则该总额null并显示已知题数；金额使用BigDecimal/十进制字符串，差值允许负值。成本投影由Mapper批量提取cost/timing，不为比较加载百份答案或逐题远程调用。

门禁顺序：NOT_COMPARABLE优先；任一执行失败/取消、任一引用错误、关键题已知维度PASS→FAIL、关键题任一检索指标降低、完整可计的总体Recall/MRR/nDCG均值降低，触发FAIL；必要评审缺失/当前proof未确认时INCOMPLETE；其他为PASS。关键题NO_EVIDENCE不能被人工误标覆盖。浮点计算比较仅容许1e-12数值舍入误差，不是可调业务容差。总体改善不能抵消关键题退化；时延/成本只展示变化，未校准阈值不虚构门禁达标。

Reason最少包含：BINDING_UNAVAILABLE、DIFFERENT_DEFINITION、DIFFERENT_TOP_K、CURRENT_REPORT_UNCONFIRMED、EXECUTION_INCOMPLETE、REVIEW_INCOMPLETE、CITATION_REVIEW_FAILED、CRITICAL_REVIEW_FAILED、CRITICAL_ANSWER_REFUSED、CRITICAL_RETRIEVAL_REGRESSED、AGGREGATE_RETRIEVAL_REGRESSED。参数/资源错误仍使用400/404；CAS、清单或评分摘要变化沿用 `KNOWLEDGE_EVALUATION_CONFLICT`409；基线资格不足新增 `KNOWLEDGE_EVALUATION_BASELINE_INELIGIBLE`409。

## 存储、界面与验收

Knowledge串行追加V6：评审当前指针、评审不可变修订、基线当前指针、不可变选择和decision命令。每表/字段中文COMMENT；身份复合FK/唯一键/合法值与JSON大小CHECK，Mapper XML有界查询。V5及更早已执行迁移不可改写。当前运行同一行锁串行保护评分和恢复；事务短小，不引入Redis、队列、定时器或裁判模型。

运行详情逐题报告下提供真实评分表单及历史侧栏，运行头部显示资格/设置基线/比较入口；对照使用完整页面 `/quality/:datasetId/runs/:runId/compare?baselineId=...`。没有基线、未评审、不合格、旧报告、不可比、失效/403、未知提交、409都明确解释并提供可恢复动作。表单未保存不被轮询覆盖，未知提交仅保存原键/摘要/预期修订，不持久保存评分说明；原回执恢复后才能开始新命令。权限和资格最终由服务端决定。

必需验收：真实DB并发评分/基线CAS、同键重放与异参、不可变历史/基线评分快照、当前身份/绑定、旧manifest或reviewDigest拒绝；纯规则验证关键题退化不被均值改善掩盖、不同定义不可比、缺评分/未知费用分母；真实HTTP/UI完成评分→基线→候选比较及未知保存/冲突恢复。1440/1024/414px、键盘/原文/历史侧栏和返回上下文必须实际验证。S8D不调用真实模型，S8E再核账执行机制基线。
