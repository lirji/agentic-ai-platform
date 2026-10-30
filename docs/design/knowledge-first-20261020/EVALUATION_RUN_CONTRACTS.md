# E02 持久评测执行契约

2026-10-21，`evaluation-run-v1`；S8C 在已批准的 [质量契约](QUALITY_CONTRACTS.md)上补齐传输与失败恢复。Knowledge 拥有评测请求/逐题报告，Tasks 拥有执行租约/终态，LangGraph 使用原 PG 凭据适配器。无新服务、MQ、调度平台或模型提供方；S8D 的人工评分/基线不在本契约实施范围。

## 公共接口

根路径 `/api/v1/knowledge/evaluations`，身份沿 E01。读为 knowledge:read，写为 knowledge:write。所有正文严格 JSON、未知属性拒绝，外部写请求上限 256 KiB。runId、taskId 由 Knowledge 生成并持久绑定，浏览器不能指定执行身份/epoch。

| 接口 | 请求与结果 |
| --- | --- |
| POST `/runs` | Idempotency-Key；`{datasetId,revision,topK:5}` → 202 RunView；空定义、当前绑定失效拒绝。明确绑定指定不可变修订 |
| GET `/run-commands/{key}` | 200 RunView；404 表示尚无持久受理，未知响应只按原键原请求恢复 |
| GET `/runs?datasetId&limit&cursor` | datasetId 必填，limit 默认20且1–100，runId升序范围游标；返回 `{items:RunSummary[],nextCursor}` |
| GET `/runs/{runId}` | 200 RunView；读取当前中央事实并幂等对账派生投影，无法观察中央时 503，不伪装仍在排队 |
| GET `/runs/{runId}/questions/{questionId}` | 200 已由中央凭据确认的 QuestionReport；可带 `manifestDigest` 校验页面正在展示的清单，已变化返回409；当前资料绑定失效/无权不返回正文；尚未确认404 |
| POST `/runs/{runId}/cancel` | `{expectedRevision}` 为运行控制修订；202 RunView。持久记录取消意图，未知交接仍使用原 taskId 核对/受理后取消；中央已成功则如实保留成功，不宣称撤销 |
| POST `/runs/{runId}/resume` | `{expectedTaskRevision}`；仅中央 FAILED/retryable 且资料/定义仍有效；202 RunView。原 taskId、输入和检查点保留，运行模型配置变更须新运行；重复旧 revision 不产生第二次恢复 |
| POST `/runs/{runId}/dispatch` | `{expectedRevision}`；只在交接 FAILED 时重新开放原 taskId 的有界交接，不创建第二个运行或中央任务 |

RunSummary：`runId,datasetId,datasetRevision,definitionDigest,topK,revision,dispatchState,cancelRequested,createdAt`。dispatchState 稳定编码 `PENDING|SUBMITTED|FAILED`；运行 revision 只用于控制意图 CAS，不因轮询增加。RunView 在 summary 外提供 `bindingState,task,report,errorCode,resumeRequested`：task 为既有 T01 View 或交接未确认时 null；report 是经凭据确认的 ReportSummary 或 null。不能将 dispatchState=SUBMITTED 当成功。

ReportSummary：`epoch,manifestDigest,complete,profile,planned,succeeded,failed,unstarted,questions`；每题摘要含 `questionId,outcome,errorCode,recall,mrr,ndcg,totalMs`，按定义顺序。`complete` 表示整批题都正常执行结束（全部 SUCCEEDED），不代表质量合格；最后一题执行失败仍为不完整运行，质量判断待 S8D 人工评分。无报告时显示“尚未确认”，不能把 null 当空成功或零分。取消时可以没有报告，不把未确认候选当正式结果；中央进度仍可查询。

逐题页面必须携带当前 `manifestDigest`，避免恢复完成恰好发生在汇总读取与正文读取之间时混合两次结果。冲突后隐藏旧正文并重新核对运行清单；摘要只作一致性条件，不授予读取历史报告或绕过当前授权的权限。

S8C 固定真实当前问答路径及 topK=5；不开放浏览器自选模型、工具或 URL。每题独立问题、无会话历史，复用同一 QaPipeline 的检索/生成/引用核对函数；只区分事件/预算 operationKey 的题目作用域，避免第二题覆盖第一题流。检索仍为生产 Java 两路 RRF(60)，不造评测专用检索器。

## 持久交接和控制

Knowledge V5 追加运行账本、不可变题目候选及不可变报告清单；V1–V4 不修改。创建事务保存命令指纹、修订摘要、唯一 taskId 与 PENDING 状态。所有查询带 tenant/owner；同键唯一、同键异参409，原提交先重放再检查当前绑定。

Tasks 追加 V3 扩展结果 CHECK，仅评测 FAILED 允许非空报告凭据；旧成功/失败/取消约束保持。恢复清除当前 result，失败事件保留接受的结果/摘要；Knowledge 在持久记录恢复意图前先对账旧失败报告，不丢历史。V1/V2 不改写。

既有后台模式增加独立单在途交接循环，部署固定租户；数据库 SKIP LOCKED/30秒租约、原输入重试、每轮最多5次失败后 FAILED。未知超时不更换 taskId/提交键。外部 HTTP 不进入数据库事务，期限5秒、连接2秒、并发最多4、响应上限256 KiB，不自动重试/重定向。内部循环不是新增业务定时调度平台。取消意图与交接共用持久账本；即使中央已受理而本地响应丢失，仍可按同一 taskId 对账后取消。

恢复先持久记录原 expectedTaskRevision，再由同一交接循环执行；超时后观察 revision 已前进即确认该意图已消费，不能对新失败 revision 再自动恢复。创建/取消/恢复都不靠浏览器存活维持可靠性。`resumeRequested=true` 表示原恢复意图尚未观察到中央修订前进，客户端即使仍读到 FAILED 也应继续轮询，不能重复发起新的恢复。此窗口取消返回409，等待原恢复 CAS 结果明确后再取消排队/运行任务；不能清除未知恢复意图并按旧 FAILED 提前停止对账。

## 独立服务身份与中央适配

Knowledge → Tasks 新专用签名：issuer `urn:agentic-ai:knowledge-evaluation`，azp `knowledge-service`，audience `agentic-ai-tasks`；sub 为原 owner，tenant_id 为原租户，scope `evaluation:task`，最长60秒。公私钥与 conversation / worker / gateway 分开，默认入口关闭，配置后才启用。

Tasks 专用 `/internal/v1/evaluation-tasks` POST 受理既有 Submit，GET `/{id}` 返回该 owner 且 kind=KNOWLEDGE_EVALUATION 的 Lease，用于对账；POST `/{id}/cancel|resume` 接受既有 Revision。只接受 kind `KNOWLEDGE_EVALUATION`、runtime `langgraph-v1`、graph `knowledge-evaluation-v1` 和输入 `{schemaVersion:"evaluation-input-v1",runId,datasetId,datasetRevision,definitionDigest,topK:5}`；公共 work 入口仍只允许研究/助手，原 conversation 身份不能受理评测。

评测的公共 Tasks resume 禁止绕过 Knowledge 绑定复核；原取消可以继续由 owner 执行，因为它只停止后续副作用。中央 claim/heartbeat/checkpoint/complete 使用原 worker 身份，新增图版本而不扩大租户范围。SUCCEEDED 结果仅为 `{schemaVersion:"evaluation-completion-v1",runId,epoch,manifestDigest}`；Tasks 在同一租约锁事务中验证 result.epoch 等于 command.epoch、runId 等于冻结输入。失败命令可带相同格式的可选 `result`；仅评测类型允许，重复失败还必须匹配结果摘要。旧任务不带该字段时行为不变。

## 运行时到 Knowledge

运行时请求/报告上限128 KiB，冻结定义 open 响应上限1 MiB（含最多100份资料绑定），其他响应上限128 KiB；HTTP 全响应期限5秒、连接2秒、单任务最多2连接，不自动重试或重定向。

新专用签名 issuer `urn:agentic-ai:evaluation-worker`，azp `ai-runtime-evaluation`，audience `agentic-ai-knowledge`，scope `evaluation:worker`，sub=原owner，tenant_id=原租户，`worker_id`=中央 worker；最长60秒。独立密钥，不把 K05 只读令牌升级为写入令牌。根路径 `/internal/v1/evaluations`，所有调用限定本次 run/task/owner。

| 接口 | 语义 |
| --- | --- |
| POST `/runs/{id}/open` `{taskId,epoch,profile}` | 核对中央归属/活跃租约、当前资料绑定；首次固定安全模型 profile，此后必须一致；返回冻结定义/绑定及 profileDigest，不调用模型 |
| PUT `/runs/{id}/questions/{questionId}` `{taskId,epoch,report}` | 按 run/epoch/question 不可变保存 QuestionReport，返回 `{questionId,epoch,digest}`；同键同体重放，异体409。最多128 KiB |
| GET `/runs/{id}/questions/{questionId}?epoch&digest` | 只取该运行对应候选；由已确认检查点引用恢复，不能取任意 latest；当前资料绑定仍需有效 |
| PUT `/runs/{id}/manifests/{epoch}` `{taskId,profileDigest,complete,questions:[CandidateReceipt]}` | 清单按定义顺序、题目唯一、引用摘要/归属一致，最多100条；成功清单覆盖全部题且无失败；失败清单允许成功前缀+失败题；不可变保存后返回 manifestDigest |

Profile 为安全字段 `{model,route,maxOutputTokens,promptDigest,retrievalProfile}`，只记录已确认型号与配置，不包含 URL、密钥、文件路径或预算余额。retrievalProfile 固定 `java-rrf60-v1`；处理版本与语料已在 E01 绑定。新 worker 配置不匹配时拒绝恢复，不悄悄混合模型。真实调用前仍由既有预算账本检查并保守预留；无新增额度或重置。

QuestionReport：`questionId,outcome,result,retrieved,metrics,timing,cost,errorCode`。outcome=`SUCCEEDED|FAILED`；result 为现行 Q04 结果或失败时 null；retrieved 最多5条 `{documentId,version,chunkId,textHash,score,source}`，不复制检索原文。metrics=`{recall,mrr,ndcg}`，成功 ANSWERABLE 按正式公式计分（空召回全0），NO_ANSWER 及执行失败为 null；人工支撑/覆盖/拒答暂为 UNREVIEWED，合法引用不代表回答正确。timing 分列 retrievalMs/generationMs/verificationMs/totalMs 非负整数，未执行步骤 null；cost=`{chatAttempts,providerUsage:{inputTokens,outputTokens}|null,providerAmountCny,chatReservedCny,embeddingReservedCny}`，金额为人民币十进制字符串；分列调用数、提供方 usage/金额和本地保守预留，缺失为 null/未知，不能把部分预留当总费用或账单。

## 提交凭据、故障与恢复

候选写入前会查活跃租约，但这**不**宣称跨库原子 fencing。候选永久按 epoch 隔离且不可变；旧 epoch 的迟到孤立写不能覆盖任何较新候选或正式报告。清单写入也不构成正式报告。

只有 Tasks 实际接受 SUCCEEDED/FAILED 的终态、epoch、runId 与 manifestDigest 一致，Knowledge 才提升对应清单。清单里的旧 epoch 题目只有被新 worker 从已提交 PG 检查点显式复用并纳入新清单时才参与新报告。Knowledge 单调维护已观察中央 revision，较旧观察不能覆盖较新报告；中央始终是运行状态权威。完成响应丢失先 GET 对账；报告对账不重跑模型。FAILED/CANCELLED 没有凭据时保留“报告未确认”，不伪造全零评分。

根图每题完成后经原同步 checkpoint 适配器提交下一个题号及不可变候选引用，不把百道题的答案正文堆进图快照。失败题写安全失败候选/清单后提交中央 FAILED；检查点仍指向前一成功题，显式恢复仅重做未确认题。进程退出不等于已报告失败，租约到期后接管同一检查点。模型已发送而检查点未提交的窗口可能再次调用，沿既有预算重新预留，不承诺模型 exactly-once。

执行仍单 worker、一次一题；本图总期限600秒、最大图步数202、问题上限100；旧图仍45秒/32步，工作图保留既有期限。每个外部副作用前续租，定时心跳失败立即取消本地等待；取消不退还未知费用。任一绑定变化停止后续调用，公共报告正文隐藏。

新增 Knowledge 错误沿原目录：`KNOWLEDGE_EVALUATION_TASK_UNAVAILABLE`503、`KNOWLEDGE_EVALUATION_CONFLICT`409、`KNOWLEDGE_EVALUATION_LEASE_LOST`409；输入/大小/绑定/无权继续 E01。错误不携带问题、原文、模型响应或密钥。

## 验证与回退

真实 MySQL 验证运行同键争用、交接租约/未知响应、取消意图、资料失效、候选不可变、错误中央凭据、迟到 epoch、单调投影和跨 owner/tenant。Tasks 验证评测类型/专用身份、错误 epoch proof、失败结果重放和公共恢复绕过拒绝。Python 用确定性检索/聊天端口验证公式、失败/空召回区分、逐题 PG 重启恢复与中央提交窗口；真实模型质量仅由 S8E 证明。UI 验证运行/取消/未知恢复/逐题失败，三屏宽与当前正文授权。

迁移先扩展；停止评测领取/交接后可回到兼容旧应用，保留 V5 与已接受中央任务，不回滚数据库或清空账本。旧 worker 不认识新图时不领取，原问答/研究图继续兼容。没有生产部署或删除候选/历史的授权；保留清理仍按后续生命周期约定推进。

## S11E：版本化检索评测增量

本节协议标识`evaluation-run-v2`，冻结新路径契约，是否开放以S11E验收为准。现有E02 v1请求、模型画像、逐题报告、候选摘要、清单和基线摘要保持原字节语义；禁止回填完整代次。

- 新受理采用独立`POST /api/v2/knowledge/evaluations/runs`，请求为原datasetId/revision/topK加必填`retrievalStrategy`，仅LLAMA_RRF_V1或LLAMA_RERANK_V1。受理先把策略写入Knowledge运行账本并纳入指纹；同键换策略或换版本冲突。旧行持久策略固定JAVA_RRF_LEGACY，旧公开序列化不追加null字段。
- 新中央输入为`evaluation-input-v2`、图`knowledge-evaluation-v2`，原六字段加retrievalStrategy。分派、观察、续领和恢复按持久策略；新worker未就绪时禁止受理/领取。原`evaluation-completion-v1`小型清单凭据沿用，报告所有权仍在Knowledge。
- 画像结构不变，新`retrievalProfile`等于持久策略code；旧画像仍java-rrf60-v1。首开固定模型画像，重开必须精确一致。模型未知字段、任意route、运行中换策略均拒绝。
- 新逐题QuestionReport保留原八字段，必加`generation`对象：`schemaVersion=evaluation-evidence-v2`、`strategy`、`references`（完整八分量身份，按retrieved实际顺序，最多5项）、`retrieval`（单次实际诊断或null）。references与所有retrieved逐项匹配documentId/version/chunkId/textHash，不仅保存最终引用。旧retrieved.score仍为RRF分数，排序采用实际用例输出；实际模式只读retrieval.mode。
- retrieval诊断严格为strategy/mode/degraded/elapsedMs；degraded必须Boolean false，mode只允许与策略相符的RRF/RERANKED或EMPTY。成功题必须有诊断；EMPTY当且仅当references为空。检索完成前失败可为null且references为空；后续生成失败仍保存已完成检索的全部证据和诊断。不得把失败伪装EMPTY或伪精排。指标与原金标匹配规则不变。
- Knowledge保存候选前核验所有完整引用及原内容/指标约束。恢复候选、提交清单、公开详情/逐题报告、人工评审与基线比较统一核验现时完整代次；同内容重索引也使旧代次不可用，不退回三元组。权限/代次失效沿EVALUATION_BINDING_INVALID处理。摘要读取只批量提取精确候选中的generation，不把百道答案加载到轮询。单次完整引用核验最多20项、总题数仍100。
- 根图仍只保存逐题不可变收据，已保存成功题恢复不重新检索/调用模型；不把100题的证据累加进Tasks QA五项清单。不新增模型预算。新旧共享夹具须验证旧摘要未漂移、策略/诊断不匹配及缺失身份拒绝。
