# S6/S7 实现映射

任务 `research-readonly-20261021`，2026-10-21。后端基线 cb2f688，前端基线 f68e660，原目录同名任务分支；无新依赖、服务或 worktree。授权来自本次开始实施与既有 P2、模型预算及正常合并推送约定。

| 结果 | 实现与验证入口 |
| --- | --- |
| 研究/助手持久受理、规范输入、幂等/分页/隔离 | TaskController、WorkRequests、TaskService、TaskMapper.xml、WorkKind/Mode；TaskPersistenceIT、deploy/work_smoke.py |
| 当前数据兼容 | Tasks V2 仅 kind 注释与分页索引，未改 V1；旧 QA 完整回归 |
| 有界真实 Deep Agents | ai-runtime/src/agentic_ai/agents/{chat,models,pipeline}.py；工具白名单、串行执行、次数/时间/费用、原子 analyze 与根图 JSON 恢复 |
| 受控恢复与权限 | test_work_execution_integration.py 在真实 PG/MySQL/HTTP 验证新代恢复、完成前撤权与模型中取消；不调用付费模型 |
| 共用原 worker/预算 | qa/worker.py 顺序领取 QA/work；tasks/execution.py 选择根图时限/结果字段，既有 QA 入口保持兼容 |
| 网关和事件协议 | root /tasks 受众修正；Mono.defer 防止未订阅的鉴权错误响应污染成功 Content-Type；SSE 显式媒体类型与 CHECKPOINT 游标兼容 |
| 两路 UI | 前端 src/features/work、WorkPage、App；共享引用、真实范围/分页、未知提交同键、阶段/报告/草稿与任务动作 |
| 共享消费者保护 | submissionIntent 按调用顺序串行；共享 QaStage 扩展而不维护重复枚举；stream 接受 CHECKPOINT 但不显示其内部内容；原问答/知识浏览器回归 |

正式行为、上限、恢复和回退以 [R02/A01](../../design/knowledge-first-20261020/WORK_TASK_CONTRACTS.md) 及 [运行说明](../../../deploy/README.md) 为准。源码逐项摘要见 [source-fingerprint.json](source-fingerprint.json)。本轮由同一 Agent 完成实现与单独验证 pass，没有冒称独立审查 Agent 或全局机器 Runtime Gate。
