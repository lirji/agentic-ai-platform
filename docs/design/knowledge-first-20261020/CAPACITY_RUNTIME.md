# S16A多worker运行与恢复

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
