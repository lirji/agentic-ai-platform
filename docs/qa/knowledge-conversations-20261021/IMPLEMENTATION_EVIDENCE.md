# S5C 实施证据

依据 [CONVERSATION_CONTRACTS](../../design/knowledge-first-20261020/CONVERSATION_CONTRACTS.md)，依赖 S5B。原目录分支 `feat/knowledge-conversations-20261021`，基线 `3e0a626700c811b5cb4768f8e982cdef6f95174b`。

- `conversation-service`：会话与原问题持久化、顺序版本、幂等重放、有界上下文、只读查询及可靠交接。MyBatis XML 使用 owner 谓词、行锁、唯一约束和租约 CAS；远程状态核验与提交均在本地事务外。
- `DispatchService/Worker`：单线程、持久退避、5 次尝试与最多 3 次人工恢复；稳定 taskId/input/幂等键。响应未知可重放，旧执行者不能覆盖新的交接结果。指标不携带 tenant/task 标签。
- `TaskHttp/CentralTaskGateway`：独立有限线程池、2 个在途槽、完整响应期限/大小限制、禁止重定向；会话签名角色分别使用 submit/inspect scope。提交受理与最终生成状态明确分离。
- Gateway 新增会话 audience/路由；Tasks 只兼容增加 owner 委托 GET。TaskStatus 的稳定协议编码迁入 platform-contracts，迁移决策仍在 Tasks；Python 状态映射测试同步路径。
- V1 在本项目 dev/test 会话库实际应用，包含两张表的全部注释、约束和索引；既有 Knowledge/Tasks/PG 迁移未修改。`.local/conversations.json` 0600 保存资源归属，初始化与重启不清理问题。
- `deploy/conversation_smoke.py` 使用独立合成租户与临时进程，通过真实 Tasks 验证“已接受但响应丢失”；测试任务最终完成/取消。没有模型调用，没有修改旧平台。

实施与验证时分别使用 backend-implementation、runtime-and-deploy、implementation-validation、project-documentation、update-progress-docs 和 task-git-delivery；同一 Agent 分阶段复核，不宣称存在第二位独立审核人。沿用已记录的全局 Runtime 漂移限制，未修改全局技能。

规范采用：用户任务与 Git 授权；`~/.codex/architecture-standards.md` 的业务建模、持久化、接口契约、并发安全、容量与发布治理，以及既有个人栈的网关/日志/线程池约定。详细验收与修正见 [TEST_RESULT](TEST_RESULT.md)；当前源码绑定 [validation.json](validation.json)。

SKILL_HANDOFF：backend-implementation / S5C / COMPLETED / PASS；问答模型、UI 和研究助手继续后续切片，本片不代表完整平台完成。
