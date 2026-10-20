# S5B 实施证据

依据 [CHECKPOINT_CONTRACTS](../../design/knowledge-first-20261020/CHECKPOINT_CONTRACTS.md)，依赖 S5A；实现路径为 `ai-runtime/src/agentic_ai/tasks/`、三份相应测试、pyproject/uv.lock、`deploy/checkpoint_local.py`、平台 V1 SQL、local.py 分派和文档。Java 业务代码、已应用任务/知识迁移和前端未修改。

- `CentralTaskClient`：短期服务身份、固定租户/worker、有界 HTTP 和响应、稳定路径、无自动重试。
- `FencedPostgresSaver`：epoch 物理分支、中央验租、原生准备写、原始数据 SHA256 与中央凭据提交；未知返回先查事实；恢复不读任意 latest 或 pending writes。
- `PostgresCheckpoints`：原生数据结构/写入、过滤读取投影、受限连接池与查询时限；不可变触发器防止已确认内容变化。
- `StrictCheckpointSerializer`：保留原生 msgpack 与安全消息类型，禁止旧 JSON/pickle；将上游被拒类型的降级事件转为错误，监听按当前线程隔离且及时解除。
- `run_claimed_graph`：打开 PG 前查询中央终态；同步 durability、45 秒/32 步界限，最终图快照可完成中央提交而不重跑节点。
- 初始化：本项目两个 PG 库、四个角色、五张表完整注释、迁移账本与权限。平台 V1 已实际应用，后续不得改写。私密资源记录保留在忽略 `.local/checkpoints.json`，代码/证据无密钥。

采用规范：backend-implementation 的 jobs、db-runtime、API/authz、resilience/config；全局架构业务建模、持久化、契约、并发安全、容量与发布治理。Runtime 仅初始化批准资源，不新增常驻 worker。已知全局技能 Runtime 漂移按原手工专业 pass 留证，没有修改全局技能。

实现验证最终 150 项 pytest、Ruff/mypy/构建及真实 PG/中央恢复均通过，完整结果、失败修正和未验证项见 [TEST_RESULT](TEST_RESULT.md)。发布前逐文件摘要与暂存内容匹配；S5A 交付记录作为同一连续任务的前序证据保留。

SKILL_HANDOFF：`backend-implementation` / S5B / `COMPLETED` / `PASS`，交接独立验收；不将本片完成等同于全平台完成。
