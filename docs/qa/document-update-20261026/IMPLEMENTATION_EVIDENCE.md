# S10B 实现与运行证据

范围为已批准的文档更新与原子发布。后端基线 `49d1f47`、前端基线 `b176130`，两仓原目录分支 `feat/document-update-20261025`；未创建 worktree。正式语义沿[生命周期契约](../../design/knowledge-first-20261020/LIFECYCLE_CONTRACTS.md)，不实现 S10C 撤下/删除、S10D 重索引或 S10E 查重改造。

| 边界 | 实现位置与行为 |
| --- | --- |
| P05 | Python `processing/generation.py`、Java `GenerationPreparedDocument` 独立代次 DTO；使用真实既有 LlamaIndex 算法产生新准备结果，旧 schema、金标和身份保持不变；5 个文件含 10 个代次金标 |
| Knowledge | V10、`LifecycleService`、`MySqlLifecycleRepository` 和 Mapper XML；内容修订、处理代次、管理修订、发布元组独立。命令、修订、任务与审计原子受理，按 document→job→chunks/events 加锁；双 sink、lease、activeJob 与旧发布基线共同约束发布 CAS |
| 回填 | `LifecycleInitialization` 每文档从真实 S3 原文/提取物/准备包验真，再核对数据库块投影；单文档提交，有限批次，遇到来源不一致停止。旧原文、块 ID、准备包和任务原字段不改写 |
| 历史正文 | K08 独立 RS256 身份，仅返回引用可读布尔值；Tasks V4 保存有界证据清单并绑定正文事件。公开 GET/request/list/SSE 重新判权，worker 对账保留原始不可变结果；列表按实际扫描游标推进 |
| Worker/会话 | QA/研究/助手在模型外发、恢复入口、最终提交前重验并登记证据；完整 PG 快照也不能跳过恢复校验。Conversation 既有 TaskGateway 通过受保护 Tasks 读取拒绝失效上下文 |
| React | K07 列表/详情、更新表单、内容版本与处理记录；原键恢复、409 保留输入、受理与发布分别展示；历史正文失效清除，活动 SSE 保持组件与游标。Vite v2 路由及有限本地身份白名单同步 |
| Runtime | 复用现有 MySQL/S3/ES/Qdrant/PG 和进程；新增 `deploy/lifecycle_local.py`、安全默认开关及独立 K08 密钥引用，无新增依赖或中间件 |

本地升级先停止旧 Java/AI/worker，再构建、Knowledge V10 回填、Tasks V4 及消费者升级。回填覆盖 54 份资料、54 个处理任务、62 个块，全部原字段及两个模型账本摘要不变。20 条历史隔离租户 RECEIVED 保留，升级时全库 PROCESSING 为 0、local-dev 在途为 0。完整记录在忽略目录 `.local/s10b-before-migration.json`、`.local/s10b-migration-result.json`。

先验证 K08 与历史读取，再启用 P05 新受理，最后开启更新。实际浏览器完成 v2 失败保留 v1、v3 发布替换 v1；停 Knowledge 验证 503；关闭新命令后验证新 schema 可读及原命令重放。最后恢复本地 create/generation/updates=true、backfill=false，保留 STRUCTURE_V3 及解析开关，qa-worker 已恢复运行。生产未部署。

本轮真实 Embedding 增加 4 批，累计 60 批、预留 0.621784 元；chat 仍为 24 次、0.889964 元，各 1 元累计上限未变。包含身份夹具修正产生的一份额外资料，原固定键和资料完整保留；没有删除、清零或重复未知付费步骤。合成任务/模型用于控制链路验收，不代表业务语义质量。

## 复核与修正

同一 Agent 第二轮按反例检查，并非独立 Agent 审查：发布 CAS/重试旧意图、回填真实对象与块投影、角色隔离、正文清单不可缩减、旧事件不借终态引用回放、查询游标/截止时间、完整快照恢复前置校验、React 失效缓存和流恢复。新增实现未发现未解决的阻断问题。

验证暴露并修复：旧评测夹具错误地用最新状态代替发布指针；真实 PG 合成引用原本不存在于 Knowledge，公共读取应拒绝，原始恢复进度改从隔离测试行核验；本地代理遗漏 v2 路由；终态/活动流重挂载造成查询循环。旧来源截图恢复原版本，新回归截图独立保存。未扩大到生产容量、保留清理或外部身份设计。

结果与源码摘要见 [TEST_RESULT](TEST_RESULT.md)及 [validation.json](validation.json)。
