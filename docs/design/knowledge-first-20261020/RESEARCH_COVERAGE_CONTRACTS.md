# C-WORK2 / S12C：有界研究计划、证据覆盖与缺口

2026-11-01。基于已批准S12C和既有R02/T01/完整代次检索契约冻结本片增量；不重做架构、模型选型、共享预算或工具权限。S12D分阶段恢复另行实施。协议标识`research-coverage-v1`。

## 版本与受理

新增`POST /api/v3/tasks`，正文沿v2六字段；仅`kind=RESEARCH, mode=REPORT`，必须显式`retrievalStrategy=LLAMA_RRF_V1|LLAMA_RERANK_V1`。冻结输入`knowledge-work-input-v3`，根图`knowledge-work-v3`，runtime仍langgraph-v1；taskId、tenant/owner、幂等键、正文/知识范围限制沿T01。v1/v2和只读助手完全沿旧图/旧输出，不能把已有v2任务静默交给v3。

`task.research-enabled`默认false；新增公开受理还需已有generation-enabled。关闭时503 `TASK_RESEARCH_DISABLED`，不创建任务。`GET /api/v3/tasks/capabilities`（task:read）返回`{schemaVersion:"research-capabilities-v1",enabled:boolean}`，只表示是否受理，不代表任务成功。关闭新受理不阻止历史读取、已受理v3执行/恢复。网关、开发身份桥与容器代理按同一公开路由映射Tasks受众。

列表、输入、任务/事件、取消和恢复沿v1只读/控制路径；输入读取保留真实v3版本。客户端依当前能力选择分问题研究或原研究方式，并在提交前冻结版本/策略/原键；未知受理仍先按已知taskId核对。

## 任务级调用额度

新图所有阶段和所有租约代次共享：聊天模型最多3次（计划包含在内），工具最多5次，检索最多3次（包括初始1次和最多2次工具搜索）。每次检索沿现有组件的有界Embedding/精排适配，因此Embedding最多3次、精排最多3次；金额仍由既有累计账本逐次预留，不新增或重置1元预算。计划不得按子问题再次获得完整额度。

Tasks拥有调用预留，复用已有`ai_task_event`的持久幂等操作键及任务行锁，无新表/迁移/中间件。新增worker接口：

- POST `/internal/v1/task-worker/{id}/research-allowance`：`{epoch,operationKey,kind}`；kind为`MODEL|INITIAL_SEARCH|SEARCH_TOOL|READ_TOOL`。TASK worker受众/可信租户、当前worker/epoch及RUNNING有效租约必须全部满足，仅v3研究图可用。
- 响应200：`{taskId,epoch,operationKey,kind,usage:{modelCalls,toolCalls,searchCalls}}`。`SEARCH_TOOL`同时消耗tool/search；`READ_TOOL`只tool；`INITIAL_SEARCH`只search且全任务最多一次；`MODEL`只chat。数据库事务原子判限并保存预留凭据；同时竞争最后额度最多一个成功。
- 专用事件键前缀`b:`；普通append固定`w:`，不能伪造额度事件。预留PROGRESS事件`stage=RESEARCH_ALLOWANCE`，仅包含凭据/计数，不含问题、原文、token或模型思维链。读取最多10条预留行（最多9次成功预留）；异常多行拒绝，不能静默截断少计数。
- 当前租约下同键同kind/epoch返回原凭据、不重复扣；同键异参409 TASK_CONFLICT。过期/取消/失租409 TASK_LEASE_LOST。额度不足409 `TASK_RESEARCH_LIMIT`；执行器映射INPUT_REJECTED、不可恢复。未知网络结果停止，绝不在未确认预留时执行外部调用。
- 预留发生于外部动作之前，失败、取消和未知响应不退款。重启使用新操作键，历史预留仍计入；不能凭旧凭据再发同一模型请求。预留与实际外部效果没有原子事务，不宣称exactly-once。初始检索预留后崩溃且无已确认检索快照时，任务明确失败，不免费重跑检索。
- GET同一路径（query epoch）返回当前usage，仍检查现行租约；最终结果写入此保守计数，不能从成功toolCalls长度推断全部已发生/未知调用。

## 计划、覆盖与报告

Deep Agents仍仅允许search_knowledge/read_evidence，内部checkpointer=False，无子Agent、shell、文件/网络工具、隐式摘要或额外模型。根图本片保留retrieve→analyze→verify；analyze内先生成并严格校验一次计划（计入3次模型），随后按固定计划收集/核对证据并生成报告。S12D才拆为更多可恢复根阶段。

计划最多3个子问题，稳定顺序编号`Q1..Q3`；每项`{id,question}`，question 1–200码点。原问题始终保留为唯一用户目标；计划是可读的问题拆解，不是隐藏思维链，不含工具、身份、scope、预算或权限字段。模型不得替换已冻结计划，报告必须对所有计划项各给一个结果且不增删ID。

v3最终结果为独立信封`{schemaVersion:"knowledge-work-result-v3",strategy,content,references,retrieval}`，完整来源身份/实际检索诊断沿v2约束。content不沿用v1字段冒充新结构：

- `schemaVersion:"research-report-v1",kind:"RESEARCH",mode:"REPORT",title`（≤120码点）。
- `plan:[{id,question}]`，以及`coverage:[{questionId,status,answer,evidence,gap}]`。
- status=`EVIDENCE_FOUND|GAP`。EVIDENCE_FOUND必须有至少一条`{citationId,quote}`，quote 1–240码点且是该编号授权原文的逐字子串；answer 1–800码点，引用编号必须来自该项evidence。GAP的answer为空、evidence为空，gap 1–400码点说明缺少哪些事实/冲突无法消解；禁止用GAP承载无依据结论。EVIDENCE_FOUND的gap可为空或说明剩余边界。
- `summary`≤1200码点，明确为生成总结；summary用到的引用须属于coverage已实际引用的集合，不允许独立新增来源。资料覆盖不证明语义正确，不把模型自报EVIDENCE_FOUND展示为人工质量通过。
- `outcome=EVIDENCE_FOUND|PARTIAL|INSUFFICIENT`由服务端根据coverage推导：所有项有依据且无剩余缺口/混合或仍有缺口/全部GAP。不得接受模型自报总体成功。任务执行SUCCEEDED可携带PARTIAL/INSUFFICIENT，界面必须明确业务缺口。
- `citations`由现有完整授权证据按实际使用编号派生，不能接受模型来源ID/URL；最多10条，全部citation/quote/summary编号精确闭合。`toolCalls`只包含真实完成的工具名和命中数量；`usage`为Tasks保守预留计数，明确包含未知/失败预留，不当费用账单。

空证据不调用聊天、不编造计划：用固定子问题“当前资料能否支持用户的研究目标？”形成唯一Q1，原目标仍完整保留在任务请求，coverage GAP、固定资料不足说明、outcome INSUFFICIENT、无引用/工具，初始检索计数1。非法计划、伪造引用、引文非原文子串、超长/未知字段/额度耗尽均失败，不能自动修补为成功。语义支撑仍须按S12B人工核对，不宣称引文匹配能证明结论。

## 验证与交付边界

必须覆盖：新旧受理/指纹/开关/权限；真实MySQL额度同键回放、异参、跨租户、最后额度竞争、失败/恢复不返还、普通事件不能伪造预留；纯JSON协议跨语言固定夹具；计划篡改/注入/未知工具/超预算/错误引文/混合或全部缺口；实际完整证据与撤权检查；真实MySQL+PG根图恢复保证旧版本不变；真实页面新旧任务、未完成原因、引用、调用计数、1440/414视口。

新增付费模型验证没有额外预算授权，不自动发送；可使用现有受控协议替身证明机制，并明确真实新提示质量未验证。是否需要真实模型样本再由本片验收与现有预算共同裁决，不花费超过已授权累计上限。

设计一致性：Tasks独占额度/受理/结果，Python仅消费额度并编排既有框架，Knowledge仍唯一证据权威，React仅展示实际结果；无第二预算金额账本、无服务边界或技术栈变化。无schema迁移；新入口默认关闭，回退先关闭受理并保留v3消费者直至在途任务终结。S12D不得重置本片任务额度。
