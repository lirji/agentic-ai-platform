# C-QA2：受控追问与检索问题（S12A）

协议标记：`contextual-qa-v1`。本增量属于已批准的S12A，只扩展指定前文的追问；原Q/T、完整引用和检索契约继续有效。S12B的答案支撑/拒答分类、研究规划和记忆不在本片引入。模型、存储和服务边界不变。

## 1. 事实、选择与不变量

- 当前v2在检索后才读取前文，直接以原问题检索，不能可靠处理“它/上述限制”等指代；完整八分量证据、同owner/会话/知识范围、PG检查点与中央租约已具备。
- Conversation仍拥有原问题与关联，Tasks拥有输入、证据清单与终态，Python只保存有界的派生查询和图状态；Knowledge保持唯一资料/授权权威。
- 采用独立`knowledge-qa-v3`，禁止给v1/v2图插入新节点改变旧快照恢复。新策略是`CONTEXT_REWRITE_V1`；旧行固定`ORIGINAL_V1`。两个code不以序号或展示文案持久化。
- 只接受一个指定成功前序轮次；同tenant/owner/会话、知识范围完全相同。v3可关联完整证据家族v2/v3，不关联旧三元组v1。改变资料范围时清除关联或明确拒绝，不静默复用旧范围。
- 历史答案仅作为解释指代的数据，不能成为本次引用或替代本次授权资料。当前回答仍只引用本次最多5条真实召回。

## 2. 公开受理与持久交接

新增`GET /api/v2/conversations/capabilities`，沿用`conversation:read`，无模型调用，固定响应：

```json
{"schemaVersion":"conversation-capabilities-v1","contextualQueryEnabled":false}
```

它表示本地受理配置，不保证模型/worker实时可用。默认关闭；能力读取失败不推断支持。原版本继续可用，已保存的未知提交不因目录变化换路由。

新增`POST /api/v3/conversations/{id}/turns`，沿用write权限、Idempotency-Key、32KiB正文及202语义。精确字段：

```json
{
  "expectedRevision":0,
  "question":"它有哪些限制？",
  "documentIds":[],
  "contextTurnId":null,
  "retrievalStrategy":"LLAMA_RRF_V1",
  "queryPolicy":"CONTEXT_REWRITE_V1"
}
```

所有原校验继续有效：问题最多2000码点、范围最多20个唯一canonical UUID、检索策略仅现有消费者闭集。无前文也可受理，但直接使用原问题且不调用改写模型。未知字段/策略/policy拒绝。

Conversation V3追加`query_policy`，非空、默认`ORIGINAL_V1`，闭集约束并写字段注释；已有迁移不改写。旧接口仍保存原policy，旧指纹/JSON保持不变；v3指纹额外固定policy与版本，同键跨版本/策略/policy拒绝。策略、policy、原问题与分派账本同一事务保存。Turn仅在v3增加`queryPolicy`，旧响应不输出null新字段。

中央输入`knowledge-qa-input-v3`精确包含v2的9个字段，加`queryPolicy`：schemaVersion、conversationId、turnId、question、documentIds、contextTurnId、contextTaskId、contextQuestion、retrievalStrategy、queryPolicy。graph固定`knowledge-qa-v3`，kind/runtime不变。重试从原行重建原输入，不读取当前默认值。

沿用现有错误目录、同键冲突、修订冲突、上下文非法、受理不可用与权限映射，不增加另一套HTTP错误体系。关闭新受理后先核对既有幂等记录，不能把成功重放变成新请求拒绝。

## 3. 改写过程与边界

新图`prepare_query → retrieve → generate → verify`。prepare_query先重验前文成功状态、所属关系、全部完整依赖，再允许模型读取历史。

- 输入只包含当前原问题、前序原问题、前序答案前2000码点；标记历史为不可信数据，不执行历史或资料中的指令。截断有显式诊断。
- 改写仅返回严格JSON对象`{"query":"..."}`，query为非空、最多2000码点字符串。拒绝未知字段、代码围栏、额外文本和超长输出；不通过第二个模型修复。
- 至多一次改写请求，复用现有ChatPort/LiteLLM及同一累计账本，无提供方自动重试，改写等待最多5秒；取消关闭流，未知费用预留不退回。原图45秒/32步总界限不扩大。
- 无上下文不调用改写；有效输出等于原问题时记ORIGINAL。有效不同输出记REWRITTEN。超时、MODEL_UNAVAILABLE或无效输出记FALLBACK并用原问题检索。
- 预算/配置错误、取消、租约丢失、上下文/权限/代次失效必须失败，不能归类为普通改写失败继续使用历史。其余未知异常不吞掉。
- 当前请求至多一次Embedding、一次现有融合/可选精排、一次回答生成；改写不允许新增文档、改变owner/topK/检索策略或执行工具。对照实验是独立验收，不在业务请求中做双检索。

## 4. 查询诊断与结果

最终`knowledge-qa-result-v3`包含v2完整信封的strategy/content/references/retrieval，并必填query。content仍为原`knowledge-qa-result-v1`，不提前改变答案语义。

```json
{
  "originalQuestion":"它有哪些限制？",
  "retrievalQuestion":"资料中该产品有哪些限制？",
  "mode":"REWRITTEN",
  "fallbackReason":null,
  "contextTruncated":false
}
```

- query精确五字段。两个问题均最多2000码点；originalQuestion必须等于中央持久输入。mode闭集ORIGINAL/REWRITTEN/FALLBACK；前两者reason为null，FALLBACK的reason仅TIMEOUT/MODEL_UNAVAILABLE/INVALID_OUTPUT。
- ORIGINAL与FALLBACK的retrievalQuestion必须等于原问题；REWRITTEN必须不同且任务有指定前文。无前文必须ORIGINAL且contextTruncated=false。布尔不能由0/1替代。
- query不返回前文答案/摘要、凭据或内部模型信息；仍受中央64KiB结果限制。派生问题不能覆盖Conversation.question或原任务input。
- 中央完成和历史读取校验新结果形状、原问题绑定、策略与完整引用；历史可见性继续重验全部依赖。前端显示原问题、实际用于检索的问题及真实改写/回退状态，不能把请求意图当执行结果。

## 5. 完整依赖、检查点与恢复

新QA当前召回最多5条；前文全部生成依赖加当前依赖去重后总量最多10条，复用现有Tasks清单上限，无Tasks占位迁移。不得截断依赖来绕过上限；超过时拒绝关联并提示重新独立提问。前文递归继承的依赖也占此上限。

prepare_query的确认快照同时保存查询诊断、完整前文依赖与截断事实。恢复时先从中央/Knowledge重验关系与全部依赖，匹配保存的完整身份；已确认的改写、检索、生成节点不重跑。生成前和提交前再次重验历史及当前资料。

只有未确认的请求可能在后续显式恢复中重新发生；不宣称模型exactly-once，原累计账本与epoch/fencing不变。完整结果快照在不重跑节点时仍执行最终核验。旧v1/v2图与终态重放维持原行为。

## 6. 门禁、前端与回退

- Conversation的`contextual-query-enabled`、Tasks的`contextual-qa-enabled`和worker的`contextual_qa_enabled`均默认false；新路由还要求既有generation/retrieval能力开启。worker缺依赖拒绝配置，不暗中转入旧图。
- 前端仅在能力明确可用、选择新检索且关联合法前文时默认走v3；无关联或未启用时沿现有v1/v2流程。未知提交持久冻结原路由/policy/策略/指纹，不因刷新或能力关闭换成旧接口。
- 本地身份桥、网关和容器代理只增加上述公开路径，不开放internal前缀。页面提供实际查询/回退原因与截断说明，窄屏及原键恢复继续验收。
- 回退优先关闭新受理并保留能读取/处理v3的服务直到在途完成；存在v3持久行时不能用不认识query_policy的旧Conversation writer重新分派。数据不回写为旧版本，不把代码回退当数据库回退。

## 7. 验收与预算

1. Java/Python/前端共享合法/非法输入、查询诊断与完整结果夹具；旧协议精确回归，拒绝同键切换版本/policy。
2. 无前文、正常指代、跨文档同范围、改变范围、失权、注入文本、长历史截断；失败前是否调用模型可观测。
3. 改写超时/断线/非法输出回退、预算/配置/权限硬失败；原问题不变，最多1次改写+1次当前检索+1次生成。
4. 真实MySQL持久受理、PG在改写后/检索后/生成后接管；历史未引用依赖失效和取消禁止后续模型/完成，旧图仍恢复。
5. 真实接口与浏览器验证实际查询、原键刷新/未知结果、能力关闭和窄屏；前端不得硬编码业务样本。
6. 对同一小样本分别记录原问题和改写问题的召回改善、无变化、退化与失败；协议模型与真实模型结果分开，不以机制通过声称语义质量普遍提升。

2026-10-30预检：聊天原1元账本已保守预留0.889964元，剩余0.110036元（非提供方账单）；不可重置或提高上限。先完成无付费机制/恢复测试，真实探针必须在发送前按原预留规则核算，额度不足时明确阻塞真实质量部分，不隐式新增费用。Embedding沿原独立账本。U04不因此获得真实精排授权。

## 8. 有界实施顺序

契约/金标 → Conversation V3与Tasks v3协议/证据读写 → Python新图与有界改写 → 前端能力/冻结意图/查询诊断 → 真实DB/PG/HTTP/浏览器与有预算的小样本对照 → 文档/审查/CI/正常Git交付。每步复用现有Owner与运行设施；不重做架构或引入新中间件。
