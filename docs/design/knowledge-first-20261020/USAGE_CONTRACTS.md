# C-USAGE · usage-v1

2026-11-02 / S15A 冻结。Owner=Tasks usage；设计见 [USAGE_DESIGN](USAGE_DESIGN.md)。这是内部成本护栏，不是收费账单。S15B 实现与验收另记；本契约不替代 P/K/Q/T/work 的权限、累计工具次数和租约规则。

## 1. 值、身份与价格

- `kind` 显式稳定码：CHAT、EMBEDDING、RERANK、JUDGE；purpose：QA、RESEARCH、ASSISTANT、INGESTION、SEARCH、EVALUATION。Judge 需要独立 profile/账户/授权，不能借 Chat 余额。
- currency 固定 CNY；金额字符串格式 `0.000000`，最大 14 位整数、6 位小数，不允许符号/指数/NaN/Infinity；非负，limit/reservation 必须正数。计算用精确十进制，预留和折算成本均向上舍入至 0.000001 CNY。不得用 float 或 JSON number 表示金额。
- byte 单位为实际 UTF-8 请求输入字节；token 单位为提供方报告的相应类别计数，均非负整数，API 最大 2147483647，实际 profile 上限更小。bytes 只是估算输入，不能显示为真实 token。
- `priceVersion` 服务端不可变，包含 profile/model/kind、input/output/total 的计价口径、每百万 token CNY、reserveMargin、minReservation、maxInputBytes/maxOutputTokens、usageCompletenessPolicy。单个 profile 仅选 INPUT_OUTPUT 或 TOTAL，不混算。价格切换不重算在途预留；保留旧快照用于迟到结算。
- CHAT/JUDGE 预留：`ceil6(max(minReservation, ((inputBytes + tokenOverhead) * inputRate + maxOutputTokens * outputRate) * reserveMargin / 1000000))`。EMBEDDING 的 UTF8_V1 使用 `inputBytes + tokenOverhead`；RERANK 的 PAIR_UTF8_V1 使用 `(queryBytes + tokenOverhead) * documentCount + documentBytes`，其中documentBytes为所有候选UTF-8字节总和，query按每篇重复计算。两者以该估算token数乘TOTAL单价/百万和余量，再取最小预留并ceil6。首版tokenOverhead=512、reserveMargin=2、minReservation=0.010000，变更必须新priceVersion，不能运行中覆盖。保守字节估算不是供应商保证；未知模型/计价维度不能准入。
- `operationId` 由服务端为 `(tenant,contextType,contextId,stepKey)` 生成，stepKey 为稳定语义节点/调用序号/批号，1–128 ASCII `[A-Za-z0-9._:-]`；同键固定 actor/purpose/kind/profile/inputDigest。epoch、TraceId、随机重试号不得成为 stepKey。多轮/批次分别建 operation。
- `attemptId` 为客户端在第一次预留前生成并保存的 UUID；同一次预留/状态查询/报告始终使用原 ID。真正外发重试使用新 ID，并带 retryOf；逻辑 operation 保持不变，旧未知仍占额。checkpoint/任务恢复不得通过新 epoch 重置余额；新 invocation 的语义序号需随既有持久状态保存。
- `inputDigest` 为 SHA-256 小写64hex，对严格规范的 profile + 输入报文语义计算；规范由各适配器显式登记并提供跨语言 golden。不存正文；同 stepKey 不同输入409，不能静默覆盖。新输入需新逻辑步骤，不能冒充重放。

计价数值全部从受控适配器的实际最终请求产生；RERANK额外提交queryBytes/documentBytes/documentCount，非RERANK三者为null。服务端按profile硬上限及正整数规则验证，不接受客户端金额/任意tokenUpperBound。price的单价和硬上限组合须先做溢出校验；单次最大费用不能溢出DECIMAL(20,6)。可信适配器是计量信任边界，若它遭篡改则不能靠摘要证明它报告的字节数真实；出口请求与统计一致性需UA10验证。

operation首次固定accountId。绑定后来改指其他账户时，同operation重试必须拒绝冲突，不跨账户拆散原调用；先完成原账户对账，新业务步骤再使用新绑定。同attempt的预留重放返回既有记录，但不绕过身份检查，也不授予发送权限。

## 2. 认证与费用归因

所有写接口仅可信服务调用，沿项目 RS256、独立 audience/issuer/azp、短时有效 JWT 与 `X-Internal-Authorization`，不接受浏览器透传 tenant/actor/account。新增 usage 受众/作用域在 platform-contracts 集中定义；不得复用普通用户的 task:write 作为计量写权限。协议 body 严格 JSON（无额外/重复 key、尾随内容、非法 Unicode/NUL），最大 32 KiB；错误沿统一 code/message/traceId。

两类上下文：

| contextType | 来源与准入复核 |
| --- | --- |
| TASK | 已验证 task worker 身份；contextId=taskId、epoch。Tasks 从本域行得到 tenant/owner/kind，拒绝外部 actor；reserve/dispatch 要当前 worker、有效租约、允许状态。通过公开 `TaskUsageContextPort` 在同事务检查/锁定，无跨模块 Mapper 引用 |
| KNOWLEDGE | Knowledge 在已授权的检索或准备入口签发 usage 专用短时上下文；包含 tenant/actor、contextId（稳定 job/request ID）、purpose、允许 kind/profile、operation 前缀、jti、iat/nbf/exp（≤30秒）、aud=usage、azp=knowledge；Tasks 验签并绑定调用方，Python 不能自行构造归因。准备 actor 来自原 job，不是 worker 服务账号；续签必须重新校验 job/权限，不能只凭旧 token |

Knowledge 上下文由现有服务签名设施和独立 usage 作用域扩展，不使用正文或客户端自报组织作为授权证明。签名原始 token 不持久化；只保存其 jti/安全归因摘要。允许组织→账户映射在服务端；有效身份但无绑定仍拒绝。profile 不能接受自定义 provider URL/密钥/账户。

reserve/dispatch 必须当前授权。report/query 使用可信同源服务身份和原 attempt 绑定；task 已失租、Knowledge 上下文已过期或用户已撤权，不阻止原实际外发的迟到 usage 记账，但不能借迟到接口取得新许可、读取正文或其他人的用量。服务凭据失效则保留未知，待合法凭据恢复后重报。

绑定校验包含tenant/context/服务issuer与azp。dispatch还须匹配attempt原worker/epoch并仍有效；新worker可在同一task的可信恢复流程查询旧attempt，但不能发送旧RESERVED或把旧epoch请求改写后复用旧attempt获取新许可。旧RESERVED可由当前可信恢复者用release CAS关闭，再为实际调用创建新attempt；它的retryOf为null，因为旧attempt没有外发许可。原worker的迟到报告只作用于其获准外发的attempt；不允许报告任意其他worker的记录。Knowledge续签可换jti但须保持原context/actor/purpose来源。已导入LEGACY_UNATTRIBUTED记录不接受普通worker推测的结算报告。

共享资料按调用者归因：接收者搜索/问答记其 actor；owner 的入库/候选准备记 owner；资料共享/撤权本身不创建费用。所有费用仍由显式绑定的运营方资金池承担，既不对用户收费也不替资料 owner 扣个人账户。

## 3. 状态、准入与结算

| 当前状态 | 命令 / 条件 | 新状态与账本效果 |
| --- | --- | --- |
| 不存在 | reserve；权限/绑定/profile有效且可用额度≥reserve | RESERVED；held增加，固定账户/价格/归因 |
| RESERVED | dispatch；仍有当前授权且账户/绑定允许 | DISPATCHED；held不变，首次且仅首次返回granted=true |
| RESERVED | release；未曾授予发送许可 | RELEASED；held减少原预留；不可重新dispatch |
| DISPATCHED | report UNKNOWN（超时/取消/失败/缺usage） | UNKNOWN；held不变 |
| DISPATCHED或UNKNOWN | report COMPLETE 且计量完整合法 | SETTLED；held减原预留，booked加折算内部成本；超预留冻结账户并记录overrun |
| UNKNOWN | 重复UNKNOWN | 原记录，不重复占额 |
| SETTLED/RELEASED | 同终态报告重放 | 同收据；不同payload409，不覆盖 |

`DISPATCHED` 表示发送许可已授予，**不证明请求到达模型**；`UNKNOWN` 可有 outcome=SUCCEEDED，但缺完整 usage 仍不结算或释放。HTTP4xx/5xx、取消、连接错误均不自动视为免费。首版没有 TTL 回收、后台退款、人工随意清零或“任务失败即释放”。

`reserve` 幂等查询可安全重放，不授权外发。`dispatch` 是一次性 CAS：第一次事务提交返回 granted=true；任何重放/状态查询返回 granted=false，即使之前是真。若 true 响应丢失，调用方不发送，查到 DISPATCHED 时标为 UNKNOWN；确需重试只能新 attempt 再预留。禁止 HTTP 客户端、LiteLLM、模型 SDK 对同次外发暗中重试；若存在供应商自身内部执行重试，不承诺控制它。

`COMPLETE` 要最终完整、适配器校验的 provider usage；INPUT_OUTPUT 必须同时有两者，TOTAL 必须有总量，未知/部分维度不能按0补齐。记录计量来源、providerRequestId（如提供）、固定 priceVersion 和 usage 报告摘要；服务器计算成本，客户端不能提交实际金额或自己选择更便宜版本。供应商请求号不是唯一去重依据，attemptId 才是记账身份。已SETTLED报告不可降低金额；确需更正/供应商账单对账属于后续协议，首版显式拒绝。

迟到 COMPLETE 可以将 UNKNOWN 结算一次；UNKNOWN 重报与 COMPLETE 并发以账户/attempt锁串行，不能从SETTLED退回UNKNOWN。报告响应丢失用原 attempt/同payload 查询或重报，无需重新外发。缺失、非法、超大 usage 保留占额并记录安全错误；合法但超过预留的 usage 不能因超额被丢弃。

## 4. 内部 API

根路径 `/internal/v1/usage`，响应均 no-store、UTC ISO8601。请求含 `schemaVersion:"usage-v1"`；除明确可空字段外拒绝null。operation/attempt UUID小写标准格式。以下是完整业务字段，身份上下文在验签头；返回封装沿现有项目 HTTP 风格，不增加第二套成功/错误外壳。

| 方法 / 路径 | 请求 | 响应及幂等 |
| --- | --- | --- |
| POST `/attempts` | `{schemaVersion,attemptId,contextType,contextId,epoch,stepKey,purpose,kind,profileId,inputDigest,inputBytes,queryBytes,documentBytes,documentCount,maxOutputTokens,retryOf}`；epoch仅TASK正整数，其余null；maxOutputTokens非生成类为0；retryOf首尝试null | `Attempt`；同attempt与规范请求摘要相同返回原记录；不同409。返回不是发送许可 |
| POST `/attempts/{id}/dispatch` | `{schemaVersion}` | `{attempt:Attempt,granted:boolean}`；仅第一次true；重放false，当前无权限不能因重复获取许可 |
| POST `/attempts/{id}/release` | `{schemaVersion,reason:"NOT_DISPATCHED"}` | `Attempt`；只有RESERVED可释放；重复RELEASED相同收据；其它409 |
| POST `/attempts/{id}/reports` | `{schemaVersion,status,outcome,usage,providerRequestId}`；status=UNKNOWN或COMPLETE，outcome=SUCCEEDED/FAILED/CANCELLED/UNKNOWN；usage=null或`{inputTokens,outputTokens,totalTokens}`，无关计价字段null | `Attempt`；UNKNOWN可升级COMPLETE；COMPLETE同摘要重放，不同409。providerRequestId可null或≤128安全字符 |
| GET `/attempts/{id}` | 无body；可信同源且绑定本tenant/context | `Attempt`；404隐藏异租户/异来源；不能返回发送许可 |

`Attempt` 字段：`schemaVersion,attemptId,operationId,contextType,contextId,stepKey,purpose,kind,profileId,priceVersion,currency,state,reservedAmount,settledAmount,usage,usageSource,outcome,retryOf,overrun,createdAt,updatedAt`；settledAmount未结算null，usage未获得null，usageSource=NONE/PROVIDER_REPORTED/LEGACY_IMPORT。结算事务失败时保留held；调用方保留原报告以重报，不能显示结算成功。响应不含认证token/密钥/账户全局余额/正文。UNKNOWN的outcome仅表达已知执行结果，不是收费结论。UNKNOWN报告必须usage=null；首次UNKNOWN报告固定摘要，重复同摘要返回原记录，异摘要409，仅COMPLETE可推进它；SETTLED后的UNKNOWN重放返回SETTLED而不回退。

retryOf须是同operation已有DISPATCHED/UNKNOWN的attempt；新attempt同样当前授权。先前SETTLED/RELEASED不能作为未知重试来源。业务主动再次生成同输入须新stepKey，防止把结果复用与实际重新调用混淆。有限自动重试策略仍由原用例决定，usage不自行重试模型。

### 错误映射

复用项目 `ApiError`、`PlatformError` 的401/403和 `TaskError.INPUT_INVALID` 的400基础错误；新语义集中在 Tasks usage 错误枚举/前端消息目录，不能靠 message 判断。稳定 code：`USAGE_NOT_FOUND`404、`USAGE_CONFLICT`409、`USAGE_LEASE_LOST`409、`USAGE_BUDGET_EXCEEDED`409、`USAGE_DISABLED`409、`USAGE_PROFILE_INVALID`400、`USAGE_REPORT_INVALID`400、`USAGE_UNAVAILABLE`503。暴露余额前先授权，额度拒绝不给出其他组织资金细节。503/网络失败绝不被解释成免费或成功。

## 5. 用户只读视图

`GET /api/v1/usage?contextType=TASK|KNOWLEDGE&contextId=<id>&cursor=<optional>&limit=20`，需要已验证的 `task:read`（Gateway仅查询转发）。Tasks 只返回当前tenant/actor自己的记录，不因组织同名、资料共享或已知taskId放开。不是自己产生的上下文返回404；历史本人审计不需要重新获得正文读取权，也不能返回正文或引用内容。旧未归因记录不分配给任何普通用户，仅供受控本地对账。

响应 `{schemaVersion:"usage-v1",items:[Attempt],nextCursor:null|string}`；limit1–50，cursor与当前用户、上下文绑定，排序createdAt/id稳定；空本人上下文可返回空列表，不能用空列表区分他人对象存在。界面只显示“预留”“已结算内部成本”“结果未知”和真实 token（若有），说明不是供应商账单；不显示运营资金池剩余量。S15B 放在现有任务/资料相关入口，不新增管理后台或充值流程。

## 6. 旧账本兼容与迁移

迁移流程以 [设计§6](USAGE_DESIGN.md#6-迁移与回退) 为准。导入不是公网上传API；受控本地命令读取冻结账本和显式manifest，通过 Tasks 专用非Web CLI入口（独立运维配置，默认关闭）执行同一usage事务规则，不新增远程管理接口。manifest包含version、sourceLedgerId、sourceSha256、fundingSourceRef、kind、accountId、limitAmount、reservedAmount、entriesCount、freezeProofRef；不含密钥。源/条目身份与摘要均防重，缺失历史归因保持LEGACY_UNATTRIBUTED。初始化账户/profile/binding也仅使用该受控CLI的显式manifest，不从普通请求自动创建；已有账户limit不被导入工具覆盖或增加，需与原授权相等，测试合成账户只能属于隔离测试配置。

LEGACY_LOCAL 与 CENTRAL 不混用同一个实际付费出口；CENTRAL故障不回退。恢复模型请求必须先知道使用哪一权威，旧任务恢复也走当前资金权威并保留旧累计占额；旧operation字符串只作为历史reference，不强行解析出不存在的用户。原研究调用次数、单次token上限和Task租约限制独立保留。

模型出口适配必须覆盖：聊天流、Deep Agents每次模型调用、入库/检索Embedding每批、Rerank、未来启用的Judge。Judge/Rerank未配置时禁止启用；不能用无预算适配器绕过CENTRAL。真实提供方usage能力与费率在启用该profile时核验，未核验则只保守占额，不凭协议示例虚构计量完成。
