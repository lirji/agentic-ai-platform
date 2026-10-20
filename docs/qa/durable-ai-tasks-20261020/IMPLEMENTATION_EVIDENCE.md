# S5A 实施证据

- 范围：[T01](../../design/knowledge-first-20261020/TASK_CONTRACTS.md)，中央任务纵向 API。新增 async-task-service；根 Maven、必要共享身份常量与 Gateway 任务路由；deploy 资源/启停与验收脚本；设计/进度同步。
- Java/MySQL：短事务创建/领取/续租/事件/取消/恢复/终态；scope/租户/所有者绑定，唯一幂等键、稳定指纹、UTC 租约代次、有限自动领取与显式恢复。Mapper XML 承载全部自定义 SQL；V1 表列注释完整。
- 检查点：中央保存有效租约提交的凭据，新 epoch 独立 threadId；同键重放不倒退指针。这只是恢复协议的一部分，未接入 PG 不算联合 fencing 通过。
- Runtime：dev-infra MySQL 独立 agentic_tasks / agentic_tasks_test，应用与迁移账号分离；任务 API 28087 / 管理 29087；网关、提交者和 worker 分别验签。无聊天、PG、LiteLLM 或生产部署副作用。
- 窄验证：29 项 Maven 测试及构建通过（含 15 项真实任务 MySQL 集成、2 项任务输入、10 项共用身份、2 项 Gateway）；38 项真实 HTTP、2 项重启、3 项数据库故障断言通过。详见同目录 TEST_RESULT 和 JSON。
- 已触发：API、授权、审计事件、持久任务、数据库/配置、远程身份、就绪探针与有界停机；无需 MQ、缓存、业务定时调度或跨线程业务上下文。SSE 为同步有界快照，未新增后台 Java 执行线程。
- 未验证：真正 AI worker、PG 原生 saver 与中央凭据的恢复、问答质量/流式 UI、生产 IAM、容量/灾备；继续后续切片。
