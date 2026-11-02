# S15A · 统一模型用量与预算设计

日期：2026-11-02。Brownfield / SCOPED；依据 S15A、C-USAGE 和用户“自行处理”的持续授权。本文是设计，实施及真实数据库验收属于 S15B。正式报文唯一来源为 [usage-v1](USAGE_CONTRACTS.md)，验证要求为 [验收矩阵](USAGE_ACCEPTANCE.md)。不增加实际付费额度，不启用重排或模型裁判，不实施生产迁移。

## 1. 事实、决定、假设与风险

| ID | 类型 | 结论及依据 |
| --- | --- | --- |
| UF01 | FACT | Chat、Embedding、Rerank 分别使用 Python 文件账本和本机文件锁，发送前预留，不释放未知结果。证据：`ai-runtime/src/agentic_ai/{qa/chat_budget.py,runtime/embedding_budget.py,retrieval/rerank_budget.py}` |
| UF02 | FACT | 当前 Chat 流请求未请求完整 usage，Embedding 解析仅返回向量，Rerank 已验证 total_tokens；不能假定每个成功结果都能获得可信完整 usage。证据：`qa/chat.py`、`adapters/openai_embedding.py`、`retrieval/rerank_client.py` |
| UF03 | FACT | Tasks 拥有独立 MySQL、短事务、worker 租约和 epoch；迁移到 V5。证据：`async-task-service/src/main/{resources/application.yml,resources/db/migration,java/com/lrj/agentic/task/application/TaskService.java}` |
| UF04 | FACT | 旧 Work operation 含 taskId/epoch，Agent 调用追加本机计数；旧 Embedding 条目没有租户/业务对象，不能据此虚构历史归属。证据：`agents/pipeline.py`、`agents/chat.py`、`runtime/embedding_budget.py` |
| UD01 | DECISION | 费用护栏由平台运营方提供资金池；用户是调用归因主体，不是收费账户。一个账户按资助方、模型用途和累计周期登记，允许组织显式绑定后共享同一池；不为每个组织复制额度，不按资料所有者扣费 |
| UD02 | DECISION | 第一版仅 CNY、LIFETIME 累计周期，无自动续期/重置/充值；Chat、Embedding、Rerank、Judge 各自独立账户，不互借。已有授权额度仅可原额迁入，不因组织绑定增加金额 |
| UD03 | DECISION | Tasks 内 `usage` 是唯一权威，采用现有 MySQL + MyBatis-Plus/Mapper XML；文件账本为迁移前权威，切换后只保留历史证据，禁止双写/自动回退 |
| UD04 | DECISION | 逻辑 operation 与实际外发 attempt 分开；每次实际外发有独立预留及一次性 dispatch 许可。幂等预留不等于供应商 exactly-once |
| UD05 | DECISION | 超时、取消、断流和失租均不能证明未收费；未知保持占额。完整可信 usage 按冻结价格折算内部成本，仍不是供应商账单 |
| UA01 | ASSUMPTION | 首版是内部成本护栏，不涉及面向用户收费、支付、税务或供应商账单同步。后续如改变费用承担规则，须新契约和显式额度授权 |
| UR01 | RISK | 配置价格、字节估算及上游计费可能不完全一致；只保证并发准入不超发内部可用额度，不承诺真实供应商账单永不超额 |
| UR02 | RISK | 发送许可响应丢失会保守占额而可能从未调用；宁可显示未知，也不重发同一许可。无供应商查询证据时不能自动回收 |
| UR03 | RISK | Tasks 故障将阻止新的付费模型请求；已发请求可能完成而结算暂不可达。保留原 attempt 查询/重报，不能为了可用性旁路文件账本 |
| UR04 | RISK | 首版 task fence 与 usage 准入在同一库事务中协作；未来抽取存在事务耦合债，不能直接改成远程查询便宣称保持相同 fencing |

已知本机累计保守占额：Chat 0.957092/1 CNY、Embedding 0.781784/1 CNY；它们不是账单。真正导入前重新读取冻结快照；这里不是可执行余额或新的费用授权。真实 Rerank/Judge 未新增预算。

## 2. 主作业与不变量

1. 经业务授权的调用先选择允许的服务端 profile、预算绑定及价格快照，再预留；无账户、无绑定、停用或额度不足均不外发。浏览器和模型不能指定付款账户/单价/实际金额。
2. 同一资金来源和用途只有一个活动累计账户；多组织/多 worker 共享该行额度，不按副本或用户复制。归因包含 tenant、actor、业务对象、purpose、profile、operation、attempt。
3. 一个 attempt 最多产生一次 `granted=true`；失败重试若确需新外发，建立新 attempt 再次预留。取消/重启/换 epoch 不重置累计成本或研究工具次数限制。
4. 账户 held 与已结算内部成本 booked 由 usage 事务维护；只有 `held + booked + 新预留 <= limit` 才准入。未知也计入 held；释放只适用于未获发送许可的 RESERVED。
5. 真实 usage 超过预留仍记录，不截断真实记录、不抹平超额；账户转 FROZEN，拒绝后续准入，显示 overrun。故该准入不变量不伪装成事后账单上界。
6. 模型正文/提示/答案/密钥不入 usage；不能用 TraceId 代替业务身份。资助账户余额及其他组织调用不可从普通用户视图泄漏。

## 3. 四层边界与所有权

| 层次 | 当前选择 | 允许 / 禁止 |
| --- | --- | --- |
| Domain | `usage` 管理内部成本准入、预留、结果计量、导入对账；Tasks 管执行租约；Knowledge 管资料授权和发布 | usage 不改 task 状态/知识发布，不拥有模型响应正文 |
| Module | `com.lrj.agentic.task.usage` 具有 contract/application/domain/infrastructure；按真实职责落类 | 仅导出命令、查询、值 DTO；不导出 Mapper、Row、数据库连接；不建空透传层 |
| Runtime | 复用 async-task-service JVM/HTTP；Python 模型适配器是客户端 | 不增加独立计量进程、Redis 权威、MQ、Outbox、调度器或第二套身份系统 |
| Deployment | 保持现有 Tasks 部署单元及自有 MySQL | 当前没有独立扩容/收费域证据，不实施抽取 |

依赖方向：Python/Knowledge → usage HTTP contract → usage application → usage repository；usage application → `TaskUsageContextPort` → Tasks 的公开只读 fence 实现。Tasks 实现只读本域任务行，不依赖 usage 内部类；Knowledge 通过签名上下文证明来源，不开放知识库给 Tasks。Conversations 仅保留 task 关联，不成为预算写入者。Gateway 只转发本人用量查询。

任务准入在一个短事务内先通过 Tasks 公共 port 锁定并检查 task/worker/epoch，再锁预算账户、operation/attempt；不在事务中做 HTTP/模型调用。取消与准入共用 task 锁以形成可解释先后顺序；已获得许可的网络外发与后续取消仍有窗口，不能撤销已付费效果。Knowledge 的短时签名上下文与外发不是跨库原子事务，沿现有撤权契约在模型外发前复核；不得称撤权能追回已发送正文。

## 4. 数据与事务

以下为 S15B 逻辑实体，尚无 CREATE TABLE，也不占用迁移编号。实现按 Tasks 当时最新版本追加迁移，表/每列写中文注释，业务 SQL 全部在 Mapper XML。

| 实体 / Owner | 关键约束与访问 |
| --- | --- |
| usage_account / usage | UUID；fundingSourceRef + kind + periodKey 唯一，sponsorRef为固定归属元数据，不能换资助方名称复制同一资金来源；CNY、LIFETIME；limit/held/booked 精确金额、version、ACTIVE/FROZEN/CLOSED。fundingSourceRef 是稳定受控引用，不保存 API key；轮换密钥不能新建免费额度 |
| usage_binding / usage | tenant + kind + profileId 唯一活动映射；只能绑定同用途账户；绑定关闭拒绝新预留/发送，不妨碍旧 attempt 结算；首版无面向浏览器的配置写 API |
| usage_price / usage | 不可变 priceVersion，kind/model/profile、计量单位和单价、最小预留、余量、输入/输出硬上限、计量完整性策略；新版本仅作用于新预留 |
| usage_operation / usage | (tenant, contextType, contextId, stepKey) 唯一；actor/purpose/profile/inputDigest 固定；生成稳定 operationId，与 epoch/attempt 无关 |
| usage_attempt / usage | UUID attemptId 全局唯一；固定 operation/account/price/worker来源、预留、状态、usage、报告摘要；同键不同指纹冲突，不重新占额 |
| usage_audit / usage | 同事务追加状态变化/操作主体/原因/关联号；无业务正文；关键命令唯一去重，不能仅靠日志作为审计 |
| usage_import / usage | sourceLedgerId 唯一、源摘要/manifest摘要/条目数/金额/目标账户/冻结证明；旧记录作为 imported UNKNOWN 占额，不伪造实时 operation/用户 |

上限、占额和单次金额用 DECIMAL(20,6)，计算 BigDecimal/Decimal；API 用规范小数字符串，token/byte 单位不可混用。S15B持久化细化：内部累计booked用DECIMAL(40,6)，因为账户冻结后仍可能收到多条在途超预留报告，不能因累计整数位溢出丢弃真实费用。单次计量和费率有硬上限，最小预留0.01限制在途条目总数；宽累计列不是新增授权额度，也不改变公开金额协议。读取采用稳定 cursor（createdAt,id），单页最多 50。单次 HTTP 有界 body、items 和超时；导入单源文件最多沿原账本 256 KiB，单事务最多 100 条，超过拒绝并另定有界迁移方案。首版不自动清理审计或历史账本。

所有额度变化与 attempt/audit 原子提交；锁顺序固定 task fence（有则）→ account → operation → attempt → audit。先非锁定定位账户，再按顺序锁定并复核，不通过预读值更新余额。死锁仅在没有外部副作用的完整事务内最多重试 2 次；连接/事务失败一律不授予发送许可。

## 5. 选择与复杂度预算

| 方案 | 选择 | 原因 / 代价 |
| --- | --- | --- |
| 继续文件账本 | 仅保留迁移前单机兼容 | 无多实例统一准入，Embedding 历史归因缺失；不能满足 S15B |
| Tasks 自有 MySQL usage 模块 | 采用 | 现有关系库和认证、短事务、任务 fence 可复用；新增表/内部 API/适配器有实际必要；热点账户串行化需后续实测 |
| 独立计费服务或 Redis 计数 | 当前否决 | 没有收费域或隔离证据；额外服务/一致性协议无必要，缓存不能作为资金准入权威 |

复用锁定 JDK21/Boot3.5/MyBatis-Plus、Python HTTPX/Pydantic/Decimal；无依赖升级和新公共组件。简单状态表足够，不引入状态机框架。日志复用 SLF4J/Logback/Micrometer 与现有 Python 关联；S15C 补跨语言追踪，S15B 仍须记录脱敏操作与准入拒绝。没有生产 QPS/P99/SLO 数据，不伪造性能目标；真实 DB 竞争结果、实例数和限额是 S15B/S16 的证据。

## 6. 迁移与回退

1. 默认 `usage.mode=LEGACY_LOCAL` 保持旧单机护栏；`CENTRAL` 对全部已配置付费模型出口生效，缺中央配置/归属则启动失败或拒绝调用，不能自动回退。隔离假提供方测试可先使用 CENTRAL，不能借此声明真实费用迁移完成。
2. 运维明确账户、资助来源、用途、允许组织、priceVersion 和导入源。旧来源没有租户时使用 `LEGACY_UNATTRIBUTED`，不猜用户；仍扣同一池余额。旧全局调用不能分摊成多份组织额度。
3. 停止旧模型写入者并等待在途请求完成/标记未知；取得文件锁、持久冻结标记，记录快照 SHA-256、版本、limit、reserved、每条 id/金额及总和。重复键、负数、非有限值、合计不符拒绝导入。冻结状态与数据库导入之间不要求分布式事务：失败保持旧写入禁用并从同一快照恢复。
4. 事务导入源唯一登记，旧预留全部按 UNKNOWN 占额；已有授权 limit 原额迁入，不新增余额。若目标已有新调用则累计而不是覆盖；重复同源同摘要返回原收据，改摘要/账户则冲突。同一源改名不得再导入，必须检查已登记源标识与内容摘要及条目唯一身份。
5. 核对前后 limit/held/booked/可用量/条目数；旧余额保守连续，启动 CENTRAL 后先做不外发的配置和余额检查，所有出口确认后才恢复获授权调用。只要有旧写入者可能活着就不放行。
6. CENTRAL 写入后不能回切可写旧文件；回滚产品代码时先禁用付费调用，保留中央账本/新表及追加审计。旧代码不认识中央账本不得继续消费原文件余额。需要重新导出/对账必须独立受控操作，不由异常 fallback 触发。

凭据和文件快照只进忽略的本地受限路径；跟踪文档保存摘要和金额核对，不保存密钥。S15B 隔离 DB 使用合成额度/假模型，可验证迁移机制；原付费账本先保持未迁移，不据此阻塞无费用的实现。

## 7. Evolution-Ready 聚合报告

本节是 `EVOLUTION_READY_ARCHITECTURE_REPORT` 的有界人工报告，非机器 Runtime Gate。评估仅 usage 目标边界，不重新评分全平台；证据为本节、§3–6 和 [正式契约](USAGE_CONTRACTS.md)，均属 DESIGN，非已实现事实。

| 维度 | 状态 / 分值 | 证据及剩余动作 |
| --- | --- | --- |
| 领域清晰、模块隔离、数据所有权、Repository隔离 | DESIGNED / 各1 | §2–4；S15B 静态依赖和 owned-table 写入检查 |
| 事务可分离 | FAIL / 0 | task fence 与准入同库短事务；UR04，抽取前须设计持久许可/远程 fencing 并验证失租窗口 |
| 契约完整、依赖方向 | DESIGNED / 各1 | usage-v1 与 §3；跨语言报文/错误验证尚待 S15B |
| 共享表债、跨域写债、Entity泄漏 | DESIGNED / 各1 | usage 仅写自有表，task 仅经 port；实现扫描未运行 |
| 测试隔离、可观测边界、配置边界、迁移复杂度 | DESIGNED / 各1 | 验收矩阵、§5–6；真实并发/故障/迁移尚未验证 |

14维中13项设计1、事务项0，设计分46.43%，Readiness LOW；有 `TRANSACTION_COUPLING_DEBT`，不得宣称可直接抽取。债务 Owner=Tasks/usage，整改触发=决定抽取前；当前同库部署允许此显式局部原子性，无跨域 Repository 暴露。其余潜在债务（共享表、跨域写、实体泄漏、循环）仅有设计约束，尚不能写已排除。

Future Map：若真实测量显示 usage 热点妨碍任务调度、独立安全/团队/发布责任形成，或正式收费域提出独立需求，则带容量/故障/Owner 证据进入既有拆分评估。条件当前 NOT_EVALUATED，无日期或 QPS 猜测；评估后才做迁移规划，不自动新增服务。静态 Fitness 在 S15B 绑定现有测试/CI：禁止其他域引用 usage Mapper/Row，禁止 usage 直接引用 TaskMapper，禁止 usage 表跨服务写、领域循环及正文/凭据持久化；数据库并发和真实鉴权另验。

## 8. 下游交接

S15A 只冻结设计和 usage-v1，不声称已有共享额度功能。S15B 必须一次完成 Tasks/API/模型出口/安全视图/迁移工具及 [UA验收](USAGE_ACCEPTANCE.md)，支持无付费隔离验证；原调用计数、研究预算和资料授权继续有效。生产费用主体实例、实时价格、容量和灾备目标仍是上线输入，不用合成测试代替。S15B 不得清空旧账本、暗开 Judge/Rerank 或扩大原预算。
