# S5B 验收：检查点与中央恢复

结论：**PASS（本切片）**。同一 Agent 在实现结束后按 CHECKPOINT_CONTRACTS 单独核对验收矩阵、真实测试日志和最终源码；不声称第三方审查。中央任务仍是状态与结果权威，PostgreSQL 只保存执行快照。问答模型、常驻 AI worker、研究与助手业务不在本片完成范围。

基线 `a7c245b0180b39d9e49574f0fc0ad3f2957de17d`，分支 `feat/ai-checkpoint-recovery-20261021`；最终工作树逐文件摘要、命令和证据见 [validation.json](validation.json)。验收日期 2026-10-21，模型预算跨日期累计，未新增收费调用。

## 可观察验收

| 验收 | 方法与结果 | 证据 |
| --- | --- | --- |
| 真实图重建和跨代次恢复 | PASS：第一节点完成后中断，新 worker 在新 thread 中接续，第一节点仅执行一次 | `test_real_graph_rebuilds_from_committed_checkpoint_without_replaying_first_node` |
| 并发接管与旧写隔离 | PASS：两个新 worker 同时领取仅一个成功；旧 PG 准备行真实存在，但中央拒绝旧提交，新分支恢复原确认内容 | `test_old_prepared_snapshot_cannot_become_the_new_workers_recovery_point` |
| 所有支持写入口失租约拒绝 | PASS：旧 `aput` 和 `aput_writes` 均返回稳定失租约错误 | 同上 |
| 未知提交响应 | PASS：真实中央提交检查点/结果后丢弃 HTTP 响应，查询事实后收敛；节点没有重跑 | `test_unknown_commit_response_is_reconciled_against_real_central_state`，2 个参数场景 |
| 中央成功后图收尾失败 | PASS：中央成功导致后续 checkpoint 失租约，执行入口返回中央结果；再次运行不构建图 | `test_central_success_survives_later_graph_checkpoint_failure` |
| 图已完成但中央尚未 complete | PASS：新 worker 从最终确认快照完成中央提交，无业务节点重跑 | `test_completed_graph_checkpoint_resumes_without_any_node_reexecution` |
| 内容保护与安全解码 | PASS：同键改写被真实 PG 拒绝；摘要不符时原生解码函数未执行；非法 pending 写不会被读取；私密配置 canary 未落入 metadata | 真实存储、错误凭据与元数据测试 |
| 身份与支持范围 | PASS：跨租户/伪造 owner、任意 thread/namespace/历史位置、删除入口拒绝；Java/Python TaskStatus code 一致性检查通过 | 真实边界测试、协议单元测试 |
| 大小与序列化 | PASS：2 MiB 上限、100 条 pending 上限、任意应用类、JSON/pickle 编码拒绝；真实 Human/AI/ToolMessage 可往返，线程间拒绝事件隔离 | 真实边界测试、5 项序列化测试 |
| 有界资源与取消 | PASS：HTTP 4 个在途请求，第 5 个拒绝，取消归还容量；真实 PG 1 连接配置下最多 4 个等待者、第 5 个拒绝，等待超时/取消后仍可查询；statement 超时生效 | 协议单元和真实池测试 |
| 初始化、权限与注释 | PASS：dev/test 隔离资源建立；真实 app 无 DDL/DELETE/跨项目测试库 CONNECT；5 张表全部表列注释存在；原生版本 9、平台版本 1，重复迁移账本不变 | 真实权限/迁移测试，`.logs/s5b-pg-init.log` |
| 回归、静态和构建 | PASS：150 项 pytest（113 项既有、26 项新单元、11 项真实集成）；Ruff 39 个文件格式检查、lint、27 个源码 mypy、sdist/wheel 构建通过 | `.logs/s5b-python-final.log`、`.logs/s5b-build-final.log` |
| Code Hygiene | COMPLETE_WITH_LIMITATIONS：无阻断项，根级自动发现未识别子项目 formatter；实际已用子项目 Ruff 显式覆盖变更文件 | [code-hygiene.json](code-hygiene.json) |

## 失败与修正

- PG 首次原生迁移已完成，组合 DDL 被 psycopg 预编译限制拒绝；查证部分状态后仅对组合 SQL 设置 `prepare=False`，按原记录继续成功，未删除或重置。
- 真实边界测试发现上游严格 msgpack 拒绝构造未知类型后可能返回原始字典。新增受控 serializer 监听官方安全事件，按线程隔离并在退出时解除监听；出现类型降级即失败。修复后保留原断言，全量通过。
- 首次卫生检查将 Python `TaskState` 识别为 Java `TaskStatus` 的另一套枚举；统一协议名称并新增跨语言 code 一致性检查。Python 不新增任务状态迁移规则。未修改或禁用全局 Gate。
- 初次 Ruff 的导入/长行失败已修复。`uv build --locked` 不受当前 uv 支持，按仓库既有 `uv build --no-sources` 构建成功；运行/开发依赖仍由 uv.lock 锁定，不把构建后端声称为同一锁。

## 边界与剩余事项

- 失租约的在途 PG 写可能形成孤立行；只有中央确认凭据可恢复，不承诺跨库原子事务、物理无孤立行或模型 exactly-once。未确认的只读节点可能重跑，后续模型每次尝试仍必须预留预算。
- 当前只支持根图和完整快照，无子图、DeltaChannel、time travel、人工 interrupt API、保留期清理。测试中的静态中断只用于构造退出位置。单次运行 45 秒、32 步是当前限制，不是研究任务 SLA。
- 新增依赖仅 `psycopg-binary 3.3.6`，匹配原 psycopg 版本。PyPI 版本公告为空，LGPL-3.0-only 与 wheel 摘要已核对；本机包含 libpq 18.6 / OpenSSL 3.5.8。见 [依赖增量](dependency-delta.json)。这不是全部原生库的漏洞证明或法律审查；[既有依赖风险](../knowledge-processing-20261020/TEST_RESULT.md)继续保留。
- Embedding 保守累计预留仍为 0.16 / 1 元，聊天 0 / 1 元，均非账单。原 deepseek-chat 已停用，替代模型待用户答复，不借本片开始聊天调用。
- PostgreSQL 未做备份恢复/生产容量演练；本次验证逻辑恢复与本机故障。未部署生产，未修改旧平台，不删除合成任务、孤立快照或本地密钥/预算记录。

SKILL_HANDOFF：`implementation-validation` / S5B / `COMPLETED` / `PASS`；必需验收有当前证据，后续由进度/Git 交付，再执行 Q 契约与问答片。
