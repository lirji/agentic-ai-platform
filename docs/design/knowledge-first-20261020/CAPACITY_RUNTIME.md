# S16A–B多worker、租户份额与恢复

本入口是显式、最多2实例的受控清单；[契约](CAPACITY_CONTRACTS.md)冻结领取/版本/预算边界。默认单worker不变，组预检不启动进程、不连接数据库、不发送模型。没有长期实例自动扩容或CENTRAL预算迁移。本轮只运行登记test库和确定性模型替身。

## 配置与资源核对

每实例沿现有WorkerConfig JSON，增加可选`graph_versions`，例如`["knowledge-work-v4"]`；省略仍按已启用版本轮询。v4需要`retrieval_enabled=true`，旧v1不需要；同进程单在途。旧图排空时保留对应版本，不改写已有任务的graph/runtime。

每实例需独立UsageSettings JSON，其中`workerId`组内唯一，schemaVersion固定`usage-runtime-v1`；模型/profile沿[USAGE_RUNTIME](USAGE_RUNTIME.md)。不能复制旧LEGACY_LOCAL账本获得第二份额度，不能因中央不可用退回本地预算。示例路径均为待替换占位符，不包含本机凭据。

私密0600普通文件 `/absolute/private/group.json`：

```json
{
  "schema_version": "worker-group-v1",
  "usage_mode": "CENTRAL",
  "workers": [
    {"worker_config": "/absolute/private/worker-a.json", "usage_config": "/absolute/private/usage-a.json"},
    {"worker_config": "/absolute/private/worker-b.json", "usage_config": "/absolute/private/usage-b.json"}
  ]
}
```

清单/配置仅接受绝对路径，拒绝符号链接、重复JSON键、超64KiB、额外字段和非私密文件。两实例Tasks/Knowledge目标及checkpoint DSN必须相同，各Usage端点必须与对应Tasks一致。若数据库凭据不同但实际指向同库，当前保守预检也会拒绝；通过统一受控checkpoint配置解决，不绕过检查。

```sh
uv run --project ai-runtime --frozen agentic-ai check-worker-group \
  --manifest /absolute/private/group.json
AI_USAGE_MODE=CENTRAL AI_USAGE_CONFIG=/absolute/private/usage-a.json \
  uv run --project ai-runtime --frozen agentic-ai check-qa-worker \
  --config /absolute/private/worker-a.json
AI_USAGE_MODE=CENTRAL AI_USAGE_CONFIG=/absolute/private/usage-b.json \
  uv run --project ai-runtime --frozen agentic-ai check-qa-worker \
  --config /absolute/private/worker-b.json
```

组预检输出`configurationOnly=true`、总在途数、逐适配器连接上限和图版本。Embedding读取同一启动环境中的实际连接配置；两实例需使用相同受控Embedding环境。更换任何配置后必须重跑。单实例预检另核对密钥、模型/价格、预算装配和PG readiness；它不领取任务，中央账户绑定与余额仍在真实每次准入时复核。两个检查均成功才使用对应环境将`check-qa-worker`改为`qa-worker`，每实例独立前台进程或受控进程管理条目。此说明不构成实际启用长期中央预算的记录。

| 连接/许可 | 每实例上限 | 两实例总上限 |
| --- | --- | --- |
| 在途任务 | 1 | 2 |
| PostgreSQL | 配置1–4 | 最多8 |
| Tasks / Usage HTTP | 各4 | 各8 |
| Knowledge HTTP | 2 | 4 |
| Evaluation HTTP | 启用时2 | 最多4 |
| Chat / ToolChat HTTP | 各1 | 合计4 |
| Embedding HTTP | 启用时按max_connections | 两份配置求和 |
| Rerank HTTP | 配置时2 | 最多4 |

连接池是上限，可能延迟建立；单任务不会同时使用Chat与ToolChat。上述不包括Java Hikari/HTTP池、在线Python API、模型代理和其他手工实例。它不是平台全局并发上限；S16B负责跨实例公平和资源准入。

## 停机、失租与排障

向明确属于该实例的PID发送SIGTERM后，worker停止领取，取消本次执行和心跳，关闭客户端/连接池；不把未完成业务标为成功/失败，也不自动退款。中央仍RUNNING的任务在数据库租约过期后由新epoch接管。提供方已开始的调用可能不能撤销，原DISPATCHED/UNKNOWN继续占额。不要直接改生产租约或删除PG恢复状态；测试中的过期SQL仅限定随机test租户/任务。

- `CENTRAL_UNAVAILABLE`：核对Tasks readiness、网络和worker公钥；保持预算拒绝，不转本地资金权威。配置错误身份/图响应同样拒绝执行。
- `TASK_RESULT_UNCONFIRMED`：用原任务ID读取中央终态；未确认不重发创建或清理快照。完成响应丢失已由原终态对账恢复。
- 有任务长期排队：核对租户与graph_versions匹配、所需能力/密钥和中央额度；不把增加worker数当作额度修复。
- 接管失败：保留中央task/epoch/确认receipt及对应PG线程快照，核对两实例DSN；只有中央确认快照可恢复。旧epoch心跳、完成和PG adapter写入均必须失败。

## 隔离复验

已有本项目test私密配置及已构建Tasks jar后，从仓库根执行：

```sh
AGENTIC_MULTI_WORKER_IT=1 LANGSMITH_TRACING=false LANGCHAIN_TRACING_V2=false \
  uv run --project ai-runtime --frozen pytest ai-runtime/tests/test_multi_worker_integration.py -q
AGENTIC_STAGED_RESEARCH_PG_IT=1 AGENTIC_RESEARCH_PG_IT=1 \
  LANGSMITH_TRACING=false LANGCHAIN_TRACING_V2=false \
  uv run --project ai-runtime --frozen pytest \
  ai-runtime/tests/test_staged_research_integration.py \
  ai-runtime/tests/test_research_execution_integration.py -q
```

夹具启动临时Tasks、独立Python子进程和受控引用端口，使用随机test租户/任务；所有模型为替身，无付费请求。前一组每场景两个竞争进程、一个后继进程；计划确认后终止本夹具自己的进程。后继恢复使用真实PG与中央HTTP。故障/完成后有界等待退出，保留`.logs/s11e-generation-pg-*/workers-*`中的私密配置/结果/日志，不停止长期服务、不清理数据。预算资金出口双实例竞争、未知占额由S15B证据支持；本组直接模型端口用于验证研究次数预算，不冒称真实提供方计费。


## S16B显式预留份额

默认关闭且不登记策略；代码升级不等于启用容量模式。本轮Docker更新继续沿用现有默认。启用前升级全部相应writer，核对所有未终结任务的tenant/lane均有份额，旧有效租约不得超额；旧队列超额保留、拒绝追加直到排空。Tasks V8与Knowledge V19只做追加迁移，应用仍仅DML。首次启用把排序配置摘要写入各自DB，此后关闭或不同配置的实例拒绝工作；不能靠改环境变量、删策略行或手工重置许可调整份额。策略变更需要后续有版本的受控方案。

下面是两租户受控验证示例，须替换为真实授权租户并评估资源后使用；不是生产推荐值。采用独立的Spring外部YAML，通过现有`--spring.config.additional-location=file:/absolute/private/capacity.yaml`载入，不能把多个重复租户键拼入JSON。Spring YAML加载拒绝重复键，配置类拒绝未知字段、零/负或超额份额。

```yaml
task:
  capacity:
    enabled: true
    max-interactive: 2
    max-background: 2
    tenants:
      tenant-a: {interactive: 1, background: 1, queued-per-lane: 8}
      tenant-b: {interactive: 1, background: 1, queued-per-lane: 8}
knowledge:
  capacity:
    enabled: true
    max-ingestions: 2
    max-searches: 2
    max-candidates: 4
    tenants:
      tenant-a: {ingestions: 1, searches: 1, candidates: 2, queued-ingestions: 8}
      tenant-b: {ingestions: 1, searches: 1, candidates: 2, queued-ingestions: 8}
```

每个服务只载入自身段。份额不可借用，未知租户拒绝；队列上限含执行中记录。Tasks的RESEARCH/KNOWLEDGE_EVALUATION使用后台lane，其余已登记kind使用交互lane；幂等重放不增加积压，满额claim返回空。Knowledge上传、更新、重索引、retry、候选私有副本与批准均遵守同一入库队列。`TASK_CAPACITY_EXCEEDED`与`KNOWLEDGE_BUSY`表示429，可保留原请求键待容量释放后重试；不要新建无界客户端重试队列。

| 资源 | 示例单实例保护层 | 跨实例有效上限/注意 |
| --- | --- | --- |
| Tasks交互/后台有效租约 | 原固定tenant Python worker逐进程1 | 各2，需为各tenant/图配置worker，额外进程不增加DB额度 |
| Knowledge入库循环/解析JVM | 各2槽、执行队列0 | 有效租约合计2；每个解析JVM原128MiB heap/32MiB direct/96MiB metaspace不变，另计宿主和管道线程 |
| Java SEARCH转发 | 2 | SEARCH有效许可2；能力目录也纳入此类许可 |
| Java本地查询/共用HTTP | 查询6、共用HTTP8 | DB SEARCH2/CANDIDATE4，入库2；传输池不是资金权威 |
| Python检索HTTP与用例 | `AI_RETRIEVAL_MAX_CONCURRENT=2`（默认1，范围1–8） | 同一进程共用配置，Java预留份额是跨实例上限；多uvicorn进程须逐个计连接 |
| Python处理API | `AI_PROCESSING_MAX_CONCURRENT=4`（原默认2，范围1–8） | 覆盖入库2+旧搜索2；新搜索另有检索入口，总量按实际入口去重核算 |
| Embedding传输 | 对应配置`max_connections`至少4，原默认10 | 本进程入库+搜索峰值4；其他worker自己的客户端/资金账户另计 |
| MySQL/Hikari | Knowledge/Tasks各默认10；每次准入短事务5秒 | 两实例每服务最多20连接（实际覆盖配置优先）；不得持连接等待模型。容量锁只用于准入/计数 |

示例只覆盖RRF及确定性模型替身；Rerank提供方、共享索引、CPU、网络和资金账户仍可能拒绝或降速，不把并发上限解释成吞吐/SLO。启用更大Java份额必须重新核对Python（最多8）、HTTP、DB、解析内存和实例总量，不能只增配额。预算仍按S15B执行；多worker使用CENTRAL，不复制LEGACY_LOCAL余额。

停止拥有租约的实例时，先停止领取、中断本地等待并关闭客户端；持久任务保留，过期后新epoch接管，旧心跳/写入无效。在线槽位期限为请求预算向上取整加5秒（最多300秒），结束时按token释放；过期或失去token的调用不能返回迟到成功。外部未知效果不等于已取消，原费用占额保留。

`*_CAPACITY_CONFIG_INVALID`应核对实例策略与DB登记、旧任务所属租户及全部writer版本；不得通过回退关闭开关绕过。数据库不可达返回依赖不可用，无本地准入后门。排查积压同时核对份额、固定tenant worker、图能力和资金；增加队列或worker不产生新配额。

复验证据及故障范围见[租户隔离验收](../../qa/tenant-capacity-isolation-20261102/TEST_RESULT.md)。新容量IT须使用新隔离test库，不能向已有登记库换策略：按`deploy/local.py environment('test')`和`deploy/task_local.py environment('test')`原配置提供连接，独立schema授予既有app DML/migrator追加DDL，执行`mvn -B -ntp -pl knowledge-service -am -Pintegration -Dit.test=KnowledgeCapacityIT -Dfailsafe.failIfNoSpecifiedTests=false verify`及Tasks对应`TaskCapacityIT`。首次启用兼容测试各用另一个未登记test库；普通回归不启用容量模式。保留schema、源与日志，不清理历史记录，不调用付费模型。

## S16C混合负载与故障复现

S16C仅在独立test schema运行两个worker和正式处理/检索/图链路，模型为确定性替身。复现命令、输入范围、故障隔离和保留策略见[QA_PLAN](../../qa/mixed-capacity-20261102/QA_PLAN.md)，实测尾延迟、排队、连接/内存和恢复见[容量报告](../../qa/mixed-capacity-20261102/CAPACITY_REPORT.md)。入口为`python3 deploy/capacity_validation.py`；默认pytest跳过该有状态实验。此验收不启用长期容量模式、不修改原Docker或费用，不替代S17恢复演练。
