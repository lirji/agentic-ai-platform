# S15C 诊断运行与排障

## 启用与关闭

Java 继续使用各服务已有 `TRACING_SAMPLE_PROBABILITY` 和 `OTEL_EXPORT_ENABLED`；collector端点使用Spring Boot原生 `management.otlp.tracing.endpoint` 绑定（可用 `MANAGEMENT_OTLP_TRACING_ENDPOINT`）。Python正式CLI的 `serve` / `qa-worker` 输出固定JSON阶段摘要，`check-*` 不启用采集。

Python默认不外发，显式 `AGENTIC_OTLP_ENDPOINT` 才创建 exporter；值为已经批准的collector origin，例如 `http://127.0.0.1:4318`，追加 `/v1/traces` 和 `/v1/metrics`。HTTP仅允许loopback，远端需HTTPS；不支持URL凭据、路径、query或重定向。`AGENTIC_TRACE_SAMPLE_RATIO` 默认0.1，范围0–1，上游合法采样位按ParentBased延续。通用OTEL endpoint/resource/header环境变量不作为本模块的配置来源。请求正文、URL、提示、令牌、完整异常不会进入本模块span或摘要。

队列256条，batch64条，trace批处理1秒；指标30秒导出；单批export HTTP总超时1秒，满队列丢弃诊断。服务退出由SDK关停钩子归还线程/会话；采集拒绝、阻塞与队列饱和测试见验收。Trace采样关闭仍计低基数指标，不能用trace数量代替业务调用总量。没有自动安装或启动collector，也没有修改现有Docker/本地服务运行配置。

## 定位路径

| 起点 | 可执行查询/关联 | 数据边界 |
| --- | --- | --- |
| 请求失败 | 保留错误体traceId；Python的X-Request-Id也使用当前traceId；查对应Java MDC或Python `stage_finished` | Java原X-Request-Id仍是独立requestId，错误体traceId和MDC为跨语言关联值 |
| 任务失败/恢复 | 原任务详情/errorCode/events → JSON `taskId`+`epoch` → 本轮traceId | 持久任务每attempt新trace，taskId关联重试；不把追踪上下文当租约 |
| 检索/准备 | `stage=retrieval/processing` → embedding/knowledge/rerank/usage子span | HTTP子span到响应头；完整检索/准备span计整个阶段 |
| 模型或预算 | `chat/tool_chat/embedding/rerank/usage`结果 → 本人usage页 → attempt审计 | 不传播trace到外部提供方；没有新增调用或额度 |
| 队列/索引 | 现有任务事件、处理诊断与Java worker/Parser/Index指标 → 任务/资料标识 | 无新中央日志库；已有业务状态仍为权威 |
| 授权变更 | `GET /api/v1/knowledge/sharing/documents/{id}/audit?limit=20` | 原owner，knowledge:read；beforeRevision分页，撤权保留历史 |
| 预算事件 | `GET /api/v1/usage/attempts/{id}/audit` | 本tenant/actor，task:read；no-store；不含付款账户或全局余额 |
| 发布/补偿 | 现有candidate publication/recovery查询 | 原owner，沿S14契约；不新增读其他服务库路径 |

采集不可达时先检查既有collector配置与网络，业务继续执行；不通过重跑模型或重新导入账本“补trace”。不在诊断日志中加入全文、私钥、查询、文件名或用户/租户指标标签。审计读取失败沿既有503/权限拒绝语义，不能将失败伪装为无记录。

## 复验

- `cd ai-runtime && uv sync --locked && uv run ruff format --check src tests && uv run ruff check src tests && uv run mypy && uv run pytest`。
- Java `mvn -B -ntp verify`；在既有test资源环境中运行 `UsageBudgetIT`、`KnowledgeSharingIT`。后者 `javaPythonJavaTraceCrossesRealHttpBoundaries` 临时启动本地Python测试进程，再回调真实Java安全边界；仅测试数据，不调用模型。测试需显式 `AutoConfigureObservability`，否则Spring Boot test默认关闭真实传播。
- `test_diagnostics.py` 使用实际OTLP protobuf/本地HTTP拒绝端验证脱敏和有界失败，使用阻塞exporter验证队列饱和。测试不依赖公网采集服务。

原数据库迁移不变；本片新增只读API和诊断埋点，回退代码不会回滚或删除任何授权/预算审计事实。保留期尚未作生产决定，当前不执行清理；不要将本地日志保留当生产合规承诺。
