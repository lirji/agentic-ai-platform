# 资料反馈运行说明

S14A 契约见 [OPERATIONS_CONTRACTS](OPERATIONS_CONTRACTS.md)。当前只实现主动反馈、本人记录及资料所有者收件箱；候选编辑、审批和修正发布由 S14B/C/D 继续实现。共享接收者没有资料编辑或转授能力。

## 配置与身份

`KNOWLEDGE_FEEDBACK_ENABLED`、`TASK_FEEDBACK_ENABLED` 默认均为 `false`。关联任务需要现有 K08 配置 `KNOWLEDGE_CITATION_READER_ENABLED/PUBLIC_KEY` 与 `TASK_KNOWLEDGE_READER_ENABLED/PUBLIC_KEY/PRIVATE_KEY/ENDPOINT`，不生成第二套密钥。Tasks 私钥只供 Tasks 签发；Knowledge 只用公钥。证明有独立 audience/purpose，最多 60 秒，仅传递主体、任务及引用摘要，不携带任务正文，也不能作为 HTTP 身份。

`deploy/feedback_local.py` 检查已有配置是否齐备并派生两个开关。`identity_runtime.py start --feedback` 可在原隔离身份入口验证不关联任务的反馈；此入口不启动 Tasks，任务关联需已有独立 Tasks 服务及正确网关地址。先检查该工具 `status`，不要与现有 28196 进程重复启动。`docker/runtime.py prepare ... --feedback --committed-only` 将开关纳入下一份不可变快照指纹；是否启用同组织共享仍独立使用 `--identity-mode oidc --sharing`。本片不执行 prepare/build/up，不修改正在运行的应用。

## 数据与关闭

Knowledge 追加 V14 `knowledge_feedback`，保存不可变主动反馈、完整引用身份、所有者/提交者与幂等指纹，不持久化任务证明。按 tenant+reporter+key 唯一，收件/发送索引稳定分页。迁移只在本片隔离 test 库执行；保留数据，不改写已执行迁移、不降级数据库。

关闭开关后禁止新提交/签发；本人历史、所有者收件箱与原键回执查询仍可用。撤回资料共享或资料失效后，已主动发送的反馈仍是处理记录；重新查看引用原文会按当前权限拒绝。所有者看不到他人 taskId 或私有任务正文。浏览器在“资料反馈”页保留原键核对入口，sessionStorage 只存摘要与提交编号，不保存反馈正文或证明。

## 验证与边界

`KnowledgeFeedbackIT` 覆盖真实 MySQL/HTTP；`TaskPersistenceIT#generationManifestIsDurableMonotonicAndNeverUsesLegacyAuthorization` 覆盖实际引用签发；显式 `AGENTIC_FEEDBACK_BROWSER=1` 由 Java 新建随机租户，`deploy/feedback_smoke.py` 启动真实 Tasks 并通过 worker 协议落库合成结果，再执行前端 `tests/feedback-real.spec.ts`。测试证明仅在 Node/Python 侧读取私密材料，不进入截图、提交或报告。预览 28216 使用独立 `.local/feedback-dist`。不调用付费模型、不更改累计预算，不删除测试记录。

跨 IAM 与 SQL 没有分布式原子快照；成员预检在事务外，事务内锁资料并重新检查 ACL 与完整发布身份。旧答案、旧截图或已发送文本无法撤回。当前验收与实际 Git 状态见 [PROGRESS_STATE](PROGRESS_STATE.md)。
