# 资料反馈运行说明

S14A/B 契约见 [OPERATIONS_CONTRACTS](OPERATIONS_CONTRACTS.md)。主动反馈、本人记录、所有者收件箱与候选编辑/内容绑定评估沿同一所有权；审批和修正发布由 S14C/D 继续实现。共享接收者没有资料编辑或转授能力。

## 配置与身份

`KNOWLEDGE_FEEDBACK_ENABLED`、`TASK_FEEDBACK_ENABLED` 默认均为 `false`。关联任务需要现有 K08 配置 `KNOWLEDGE_CITATION_READER_ENABLED/PUBLIC_KEY` 与 `TASK_KNOWLEDGE_READER_ENABLED/PUBLIC_KEY/PRIVATE_KEY/ENDPOINT`，不生成第二套密钥。Tasks 私钥只供 Tasks 签发；Knowledge 只用公钥。证明有独立 audience/purpose，最多 60 秒，仅传递主体、任务及引用摘要，不携带任务正文，也不能作为 HTTP 身份。

`deploy/feedback_local.py` 检查已有配置是否齐备并派生两个开关。`identity_runtime.py start --feedback` 可在原隔离身份入口验证不关联任务的反馈；此入口不启动 Tasks，任务关联需已有独立 Tasks 服务及正确网关地址。先检查该工具 `status`，不要与现有 28196 进程重复启动。`docker/runtime.py prepare ... --feedback --committed-only` 将开关纳入下一份不可变快照指纹；是否启用同组织共享仍独立使用 `--identity-mode oidc --sharing`。本片不执行 prepare/build/up，不修改正在运行的应用。

## 数据与关闭

Knowledge 追加 V14 `knowledge_feedback`，保存不可变主动反馈、完整引用身份、所有者/提交者与幂等指纹，不持久化任务证明。按 tenant+reporter+key 唯一，收件/发送索引稳定分页。迁移只在本片隔离 test 库执行；保留数据，不改写已执行迁移、不降级数据库。

关闭开关后禁止新提交/签发；本人历史、所有者收件箱与原键回执查询仍可用。撤回资料共享或资料失效后，已主动发送的反馈仍是处理记录；重新查看引用原文会按当前权限拒绝。所有者看不到他人 taskId 或私有任务正文。浏览器在“资料反馈”页保留原键核对入口，sessionStorage 只存摘要与提交编号，不保存反馈正文或证明。

## 验证与边界

`KnowledgeFeedbackIT` 覆盖真实 MySQL/HTTP；`TaskPersistenceIT#generationManifestIsDurableMonotonicAndNeverUsesLegacyAuthorization` 覆盖实际引用签发；显式 `AGENTIC_FEEDBACK_BROWSER=1` 由 Java 新建随机租户，`deploy/feedback_smoke.py` 启动真实 Tasks 并通过 worker 协议落库合成结果，再执行前端 `tests/feedback-real.spec.ts`。测试证明仅在 Node/Python 侧读取私密材料，不进入截图、提交或报告。预览 28216 使用独立 `.local/feedback-dist`。不调用付费模型、不更改累计预算，不删除测试记录。

跨 IAM 与 SQL 没有分布式原子快照；成员预检在事务外，事务内锁资料并重新检查 ACL 与完整发布身份。旧答案、旧截图或已发送文本无法撤回。当前验收与实际 Git 状态见 [PROGRESS_STATE](PROGRESS_STATE.md)。

## S14B 候选与私有校验副本

`KNOWLEDGE_OPERATIONS_ENABLED=false` 默认关闭。`identity_runtime.py start --operations` 和 `docker/runtime.py prepare ... --operations` 只派生该开关，不打开处理 worker、评测 worker 或改变预算；Docker 选项纳入不可变快照指纹。原身份隔离入口关闭处理，因此只开启候选编辑，并不具备准备能力。准备副本还需既有 processing/create/generation 能力；运行与实际 worker 状态应分别核对。保存候选不会调用模型。

V16 增加候选根、不可变完整修订、准备关联、精确定义关联、当前评估绑定和不可变命令回执；资料目的默认为 STANDARD，私有副本为 OPERATIONS_PREVIEW。当前只在隔离 test 库迁移，已执行文件不得改写。旧 STANDARD 索引文档没有 purpose 字段仍正常读取；新副本必须同时具备可信 purpose 索引元数据与 SQL 私有边界。

首次准备副本前，在明确获授权的目标环境执行 `python deploy/operations_local.py prepare-index --mode test`（开发环境明确改用 `dev`）。它只追加 Elasticsearch keyword 和 Qdrant keyword payload 索引，拒绝不兼容映射，不重建资源或改已有正文。本轮只执行 test；Docker prepare/up 与开发索引变更未执行。关闭候选入口后，原键回执、历史修订和已关联评估仍可核对。

浏览器从所有者反馈详情进入修正稿，TXT/Markdown 使用完整正文，PDF/DOCX 提供完整修正版。正文与文件只留在当前编辑页面，刷新后需重新提供；sessionStorage 只保存原键、摘要、操作/目标/修订等恢复基线。未知结果先查原回执，同键不同内容不能重新提交。冲突保留草稿，采用新基线必须是显式操作。

`deploy/candidate_smoke.py verify` 启动本轮动态端口 Tasks，运行 Java 随机租户夹具及真实浏览器；模型端口为确定性机制输入，中央 worker 完成、质量报告与人工评分走真实服务协议。前端先构建 `.local/candidates-dist` 并在 28226 预览；截图与日志位置见本片 QA。该命令会向隔离 test 库/桶/索引写测试记录，结束终止本轮 Tasks，保留记录及日志。当前验收状态以 PROGRESS_STATE 为准，不把机制输入或副本 READY 宣称为模型质量达标、原资料已发布。
