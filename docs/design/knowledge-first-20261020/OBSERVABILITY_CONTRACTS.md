# C-OBS：跨语言诊断与业务审计（S15C）

## 约束与选择

沿用 Java Micrometer Tracing/OpenTelemetry、现有服务数据库和业务鉴权；Python 使用与已锁定 API 同版本的 OpenTelemetry SDK/OTLP HTTP exporter。无新采集服务、无默认 SaaS、无模型调用。自动 HTTP 全量埋点会携带动态 URL 等属性，当前选择固定阶段名称的手工边界埋点，避免提示、正文和查询进入采集端。HTTP 传输 span 只计发送至响应头；完整检索/任务阶段另计，不能把响应头时间当作完整流耗时。

## 关联和数据边界

- HTTP 接续合法 W3C traceparent；重复、过长或非法头按新根处理，不信任 baggage/用户身份字段。响应 X-Request-Id 与错误 traceId 使用当前 trace ID。traceId 只做排障关联，不能参与授权、幂等或业务状态判断。
- Python 到内部 Knowledge/Tasks/Usage 注入子 span traceparent；到外部模型出口只记录固定模型类别与状态，不转发内部 trace 上下文、baggage 或业务身份。
- 持久 worker 每次 task/epoch 执行建立新根，以 taskId/epoch 关联历次尝试；任务正文和checkpoint不嵌入临时追踪上下文。asyncio 子任务继承当前上下文，结束/异常/取消恢复原上下文；Java 原有 TTL/Micrometer 与 Reactor 集成继续沿用。
- Span/log 白名单：固定阶段/结果、规范 traceId/spanId、可信 taskId/epoch、稳定失败码、耗时。禁止正文、提示、模型结果、身份令牌、URL/query、文件名、异常消息/堆栈、任意用户属性。指标仅固定阶段、结果枚举；业务 ID、用户、租户、模型动态名均不作标签。

## 采集与运行

Python 默认仅在进程内生成上下文，不外发；显式配置本地既有 OTLP collector 后异步批量输出。采样、队列、batch、超时有界；采集失败或满队列可丢诊断数据，不阻止业务。采样后的 trace 不能宣称覆盖全部请求。部署配置入口与故障演练写入 OBSERVABILITY_RUNTIME.md；不自动切换长期服务。

诊断不是持久审计。审计沿业务事务写现有本服务表，不受 trace 采样或 exporter 影响。保留现有数据、不自动清理；生产保留期待运维确定，不能把本地测试策略当作合规期限。

## 受控查询

- 共享授权：扩展既有 `/api/v1/knowledge/sharing/documents/{id}/audit` GET，仅原所有者，既有 sharing 权限；按 acceptedRevision 倒序，beforeRevision 可选、limit 默认20/上限50，返回既有命令回执，nextBeforeRevision 或 null。撤权不抹除历史，重试不新增记录。
- 预算：扩展 `/api/v1/usage/attempts/{id}/audit` GET，需要 task:read，仅 operation.tenantId/actorId 等于可信调用者；不存在或越权均404。返回有界状态事件（attempt最多5种状态转换），不返回资金池、全局余额、内部服务身份、命令摘要/凭据。仅 eventType/previousState/nextState/heldDelta/bookedDelta/traceId/createdAt；no-store。
- 发布/补偿：复用现有 candidate/publication/recovery 的 owner 限定查询与持久回执，不复制第二套审计表。现有前端诊断、共享管理、发布/补偿与本人用量页面沿原权限，不开放未定义的跨租户运维角色。

## 验收

| ID | 必须观察到的结果 |
| --- | --- |
| OB01 | 真实 Java HTTP → Python → Java 的 W3C trace 同源、span 父子正确；非法/重复头不沿用 |
| OB02 | 并发请求、嵌套任务、异常/取消、线程复用后无上下文串号；worker重试按task/epoch关联 |
| OB03 | 检索/模型/队列或索引失败可由请求/任务定位；安全失败码，未泄漏原文 |
| OB04 | 标签集合有界，无用户/任务/URL等高基数标签；日志/span不含合成敏感标记 |
| OB05 | collector拒绝/阻塞/队列饱和时业务继续，关停有界；不得自动重试模型调用 |
| OB06 | 真实DB共享授权历史、预算状态事件、发布/补偿可检索；跨租户/跨用户/共享读者不可读取owner审计 |
| OB07 | 原契约消费者/前端及受影响测试通过，运行说明可执行；未启用采集不额外外发 |

官方依据：[Python传播](https://opentelemetry.io/docs/languages/python/propagation/)、[SDK手工埋点](https://opentelemetry.io/docs/languages/python/instrumentation/)。本契约为既有 C-OBS/S15C 的兼容增量，不改变服务/数据所有权。
