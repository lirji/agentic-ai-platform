# 平台告警与处置（S16D）

规则是可接入的配置；当前长期应用仍运行既有制品，未开启新指标或新增常驻监控。现有 dev-infra 的 Prometheus 2.54.1 / Alertmanager 0.27.0 是其他项目的可选配置，本任务只复用其工具版本进行隔离验证，不接管或修改该项目。这里不声明旧工具版本具备最新安全补丁；供应链检查属于 S17D。

## 配置与接入

Tasks、Knowledge 通过已有 `127.0.0.1:29087/29084/actuator/prometheus` 暴露指标。宿主启动需设置 `OPERATIONS_METRICS_ENABLED=true`；下次明确部署时给已有 `deploy/docker/runtime.py prepare` 增加 `--operational-metrics`，开关参与固定制品摘要，默认关闭。开关不启动 Prometheus、不改变业务数据、队列或预算。指标缺失时必须显示采集告警，不能解释为零积压。

`prometheus.yaml` 是 Docker Desktop 下与应用 gateway **同一网络命名空间**的抓取配置：管理端点仍是 loopback，旧账本采集位于 `host.docker.internal:29192`；宿主 Prometheus 则将此地址改为 `127.0.0.1:29192`。配置目录只读挂载为 `/etc/agentic-monitoring`。不要为了抓取而将管理端口公开到外网。`alertmanager.yaml` 默认是无外部通知接收器；保留 Alertmanager 页面和 Prometheus `/api/v1/alerts` 作为观察入口。接入外部接收器须有明确目标与发送授权。

旧账本模式的只读核对命令（仓库根目录）：

```sh
python3 deploy/monitoring/legacy_budget.py \
  --chat-ledger "$PWD/.local/chat-budget-20261020.json" \
  --embedding-ledger "$PWD/.local/embedding-budget-20261020.json" --once
```

去掉 `--once` 才启动仅绑定 `127.0.0.1:29192` 的采集器；默认不常驻。CENTRAL 模式不再把冻结旧账本当活动资金池，移除旧账本抓取配置，改查 Tasks 的汇总。未创建中央账户不代表本地费用为零。采集器不创建账本；文件需0600、非符号链接、有效金额和明细总额匹配，争锁/损坏/不可读返回明确失败及NaN。

只读定位命令（宿主服务或已批准的本机端口转发）：

```sh
curl --fail --max-time 5 http://127.0.0.1:29087/actuator/health/readiness
curl --fail --max-time 5 http://127.0.0.1:29084/actuator/health/readiness
curl --fail --max-time 10 http://127.0.0.1:29087/actuator/prometheus
curl --fail --max-time 10 http://127.0.0.1:29084/actuator/prometheus
```

现有 Docker 内管理端口没有发布到宿主，不能把上面的宿主命令失败直接当作业务宕机。实际运行状态使用 `python3 deploy/docker/runtime.py status --backend "$PWD"`。已配置的 Prometheus 中查看 `up{job="agentic"}`、`agentic_ops_read_success`、`agentic_ops_last_success_seconds`，再执行规则原表达式。应用日志按已有 Compose 环境和固定制品路径查询；令牌和完整业务正文不得复制到通知中。

## 实验阈值

U06 未定义，以下全部为**实验提醒值**，不是生产 SLA。S16C 受控短测常规负载无拒绝、突发有429，不能从该实验推导生产吞吐。队列年龄120秒持续2分钟，用于发现持续积压；费用比例达到90%持续2分钟保留人工处置余量；未知费用300秒持续2分钟用于对账；端点/采样故障持续1分钟避免瞬时波动。快照超过90秒视为陈旧；SQL最多每30秒一次，独立服务端1.5秒/JDBC2秒限制及原连接/Socket超时生效。

所有规则只保留固定 service，以及旧账本固定 kind 标签。实例、URL、租户、任务、用户、账户不进入告警键；金额/时间变化不改变告警指纹。Prometheus按service聚合副本，Alertmanager按project/alertname/service/kind分组，默认30秒等待、5分钟组更新、4小时重复提醒。通知测试会在隔离配置中缩短等待；正式文件不因此改变。

<a id="service"></a>
## 端点不可达

对应 `AgenticServiceUnavailable`。先看 `up` 的 service 和 Prometheus targets 错误，再核对上述 Docker status、应用存活与 readiness；确认是网络命名空间/端口配置问题还是应用进程退出。若进程活着而 readiness 失败，查看对应数据库、桶、双索引及本服务安全失败码。只修复实际不可达项；不要重启共享 dev-infra 或其他项目。需要重启本项目时沿固定制品部署入口，不单独重建 gateway 破坏共享网络。验收恢复为所有目标 `up=1` 且原任务仍可查询。

<a id="observation"></a>
## 采样失败、过期或账本不可读

对应 `AgenticObservationUnavailable` / `AgenticLegacyBudgetUnreadable`。先核对开关和新制品是否已启动，再查 `read_success`、最后成功时间；默认关闭产生指标缺失是接入未完成，不能静默关闭此告警。Tasks/Knowledge使用各自DML账号读取本服务库；确认连接池、数据库连通与SQL执行时间，不增加DDL权限。不可读旧账本先检查权限、锁持有进程和原文件，保留原文件及恢复材料，不能新建空账本、复制余额或手改金额“恢复”。恢复后观察至少一次30秒新快照；只解除告警不证明业务数据已修复。

<a id="queue"></a>
## 持续积压

对应 `AgenticQueueBacklog`。在现有任务列表或资料处理诊断页按用户权限检查 QUEUED/RECEIVED、最老创建时间、处理失败码和最后阶段。同步查看 `agentic_ops_pending`、worker日志、图版本/tenant配置及容量策略；使用 [CAPACITY_RUNTIME](../../docs/design/knowledge-first-20261020/CAPACITY_RUNTIME.md) 的组预检确认worker有权领取现存图且连接/份额相容。延后重试也计入等待年龄。依赖故障先恢复依赖；满额先限制新受理或等待排空，不能直接增副本或复制本地资金池。不要手改lease/status或把所有任务重新提交。验收需最老等待年龄降到阈值下、任务由合法epoch推进，非只看pending数下降。

<a id="budget"></a>
## 费用接近上限

对应 `AgenticCentralBudgetNearLimit` / `AgenticLegacyBudgetNearLimit`。中央指标取 ACTIVE/FROZEN 账户最大 `(held+booked)/limit`；关闭账户不触发。旧账本使用原累计预留，不代表供应商账单。先确认当前资金模式，再在本人用量页及既有审计接口定位合法调用；聚合告警不公开账户或用户。已授权余额不足时停止新的付费请求并等待用户决定是否增加额度；未知/预留不会因重启清零。禁止通过删除账本、重新初始化或新建相同资金来源恢复余额。本轮只读检查聊天0.957092/1、Embedding0.781784/1 CNY；90%试验阈值会提醒聊天余额。

<a id="unknown"></a>
## 长时间未结算或未知调用

对应 `AgenticUsageUnknown`。沿 [USAGE_RUNTIME](../../docs/design/knowledge-first-20261020/USAGE_RUNTIME.md) 与本人 attempt 审计检查 DISPATCHED/UNKNOWN、当前任务/epoch和提供方已知结果。UNKNOWN不是可免费重试的失败；DISPATCHED也可能仍在合法运行。先查中央持久事件和原提供方回执，只有契约允许的settle/unknown命令可更新状态；不手工释放预留、不重置任务预算。验收需原调用有可解释终态或已登记人工对账状态，已发生外部调用不能用代码回退撤销。

<a id="dependency"></a>
## 处理或HTTP依赖失败

对应 `AgenticDependencyErrors`。规则监视5分钟内处理失败事件和HTTP5xx；它是线索，不能直接判定哪个依赖损坏。使用安全错误响应traceId或任务事件关联 [OBSERVABILITY_RUNTIME](../../docs/design/knowledge-first-20261020/OBSERVABILITY_RUNTIME.md) 中固定阶段，区分数据库/索引/模型/预算、解析失败与业务拒绝。确认S16C四故障报告中相同边界，再修复明确依赖；保留未知调用资金，不扩大重试。429容量拒绝不是5xx依赖故障。恢复后观察原队列继续合法推进、新错误不再增加，等待规则窗口自然恢复。

## 复验

```sh
python3 -m unittest discover -s deploy/monitoring -p 'test_*.py'
docker run --rm --network none -v "$PWD/deploy/monitoring:/etc/agentic-monitoring:ro" \
  -w /etc/agentic-monitoring --entrypoint /bin/promtool \
  prom/prometheus@sha256:f6639335d34a77d9d9db382b92eeb7fc00934be8eae81dbc03b31cfe90411a94 \
  test rules alerts.test.yaml
python3 deploy/monitoring/verify_notifications.py \
  --binary /absolute/path/to/verified/alertmanager --evidence .logs/alert-revalidation
```

通知演练使用单独本机端口与独立状态目录，结束停止自有进程，保留收据；不会发邮件、Slack、Webhook到外部。Java集成按 `deploy.local.environment("test")` 提供本项目既有测试资源，执行 `-Pintegration -Dit.test=OperationalMetricsIT -Dfailsafe.failIfNoSpecifiedTests=false`。不打印环境凭据。

配置依据：[Prometheus规则](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/)、[规则测试](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/)、[Alertmanager配置](https://prometheus.io/docs/alerting/latest/configuration/)。本地版本通过实际工具检查，不能只凭文档判PASS。
