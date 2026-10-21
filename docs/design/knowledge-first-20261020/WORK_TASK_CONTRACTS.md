# R02 / A01：研究分析与只读助手

2026-10-21，按用户“可以开始做这部分”授权细化原 P2；沿用 Java 中央任务、LangGraph、Deep Agents 0.7.28、LlamaIndex、现有 K05 身份与累计预算。S6 为后端闭环，S7 为两个前端入口。没有新增服务或中间件。

## 受理、读取与所有权

Tasks 独占本次不可变请求和结果；不创建伪会话。网关可信用户身份、task:write 受理、task:read 读取；owner/tenant 均来自验签身份。研究 kind=RESEARCH，助手 kind=READONLY_ASSISTANT；runtime=langgraph-v1，graph=knowledge-work-v1；原 KNOWLEDGE_QA/knowledge-qa-v1 不变。

- POST `/api/v1/tasks`：Idempotency-Key 沿 T01；正文 `{taskId,kind,question,documentIds,mode}`。taskId 为规范 UUID；question 1–2000 字符、非空白、无 NUL/无效 Unicode；documentIds 0–20 个唯一规范 UUID，由服务端排序。研究 mode=REPORT；助手 mode=EXPLAIN/CHECKLIST/DRAFT。未知字段、类型/版本/模式组合拒绝。202 返回原 T01 Task；同键同规范命令重放，同键异参 409。
- GET `/api/v1/tasks?kind=RESEARCH&limit=20&cursor=...`：kind 仅上述两种，limit 1–20，cursor 为上一页末项 UUID；按 createdAt/taskId 倒序，cursor 必须属于本人同类型，否则 404。返回 `{items:[{task,request}],nextCursor}`，末页 nextCursor=null。
- GET `/api/v1/tasks/{id}/request`：仅返回本人研究/助手输入 `{schemaVersion:"knowledge-work-input-v1",kind,question,documentIds,mode}`；不存在、其他 owner 或问答任务均 404。
- 原 T01 查询、事件/SSE、取消、最多三次显式恢复、fencing 与完成对账全部复用。列表与输入读取不返回租约或凭据。错误沿 TASK_INPUT_INVALID/NOT_FOUND/CONFLICT 和 T01 目录。
- V2 只增加类型分页索引及 kind 字段说明，不改写 V1。保留策略仍为不自动删除，不新增清理任务。

## 执行与恢复

根图 `retrieve → analyze → verify`，每个节点完整 JSON 快照经原 FencedPostgresSaver 提交。analyze 内调用真实 Deep Agents 工具循环，明确 `checkpointer=False`；它是有界原子执行阶段，无子 Agent，无独立恢复命名空间。失败/退出从上一根图快照重新执行这个阶段；可能再次产生模型/检索费用，绝不声称模型 exactly-once。生成完成快照可直接重验并提交，无需再次生成。

Deep Agents 专用 profile 关闭 general-purpose，无其他 subagents、memory、skills、store；StateBackend 仅线程内，锁定版本 FilesystemMiddleware 强制注册 read_file，因此 tools=[read_file] 并由 profile 排除 read_file（可见/执行双拒绝），适配器再校验最终白名单，无文件工具、宿主目录、shell、浏览器、外部网络工具。关闭 summarization，避免隐藏模型调用。模型适配器再次校验实际工具白名单，仅 `search_knowledge` / `read_evidence`；协议外工具调用在执行前失败。

初始 LlamaIndex 检索 topK=5；额外搜索最多 2 次，总工具最多 5 次、模型最多 3 次/单次 analyze 执行，累计证据最多 10 条、正文总计最多 24000 字符；单模型请求沿 64 KiB/25 秒/1024 输出 token、单在途、无自动重试/回退。根图单次执行最多 120 秒/32 步；原问答保持 45 秒。心跳每 10 秒；每次工具/模型/提交前确认中央租约及证据权限。模型收到的资料为不可信数据，不能改变固定身份、范围、工具、预算或配置。

两类任务共用原 Embedding/聊天各 1 元累计账本，逐次真实发送前保守预留；重启、恢复、未知响应不退款、不重置。空证据不调用聊天；外部读取仍可能产生 Embedding 费用。当前一次只执行一个中央任务，原问答 worker 顺序轮询两种 graph，避免新增并行付费执行器。

## 结果与事件

最终结果 `schemaVersion=knowledge-work-result-v1`，含 kind、mode、answerability（ANSWERED/NO_EVIDENCE）、title、facts、analysis、output、limitations、citations、toolCalls。title ≤120；facts/analysis/output/limitations 分别 ≤6000 字符，总结果仍受 T01 64 KiB 限制。facts 是带 `[1]` 编号的资料事实；analysis 明确展示为生成推断；output 为研究结论或对应助手解释/清单/草稿；limitations 明确资料边界。草稿始终标注待人工审核，不宣称已发送或执行。

citations 沿 Q04 出处结构，编号扩展至 1–10，由已授权证据派生，必须与正文实际编号精确对应；禁止模型直接填写来源 ID/URL。ANSWERED 的 facts 至少有一条引用；NO_EVIDENCE 使用固定文案、空引用、无聊天调用。toolCalls 只记录实际工具名称及命中数量，不存身份、凭据或模型思维链。引用打开继续走 K04 当前授权，执行恢复及最终完成均重验 K05；来源撤权后不继续生成或提交。

PROGRESS payload 使用 stage=RETRIEVING/ANALYZING/VERIFYING/TOOL_READING，可带 tool 与 hitCount；均为真实已到达阶段，不制造百分比。本片不把模型中间文本当最终报告，不发送报告 TEXT_DELTA；SSE 展示阶段，最终结果由中央 Task 决定。未知模型响应/次数耗尽/无效报告明确失败，不自动修补生成或改成成功。

## 验收

真实 MySQL/HTTP 验证受理幂等、分页、owner/tenant/权限、非法输入、旧问答兼容；真实 PG+中央与受控模型验证阶段恢复、重复完成、取消/接管、撤权、工具拒绝/次数/时限/预算。使用剩余已授权额度验证真实 DeepSeek 研究与助手结果、引用、空证据和重启重放。前端覆盖两入口、范围选择、提交未知结果重用键、任务列表/详情、事件、取消/恢复、报告/草稿与引用，检查桌面及窄屏截图。受控测试不代替真实模型结果。

依据：[原架构](../platform-selection-20261020/BACKEND_ARCHITECTURE.md)、[T01](TASK_CONTRACTS.md)、[研究准备](RESEARCH_READINESS.md)。API 能力核对参考 [Deep Agents customization](https://docs.langchain.com/oss/python/deepagents/customization)，实际工具组装和关闭行为以本机锁定制品及测试为准。
