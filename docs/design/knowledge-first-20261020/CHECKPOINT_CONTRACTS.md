# S5B：PG 检查点与中央凭据恢复

本片依赖 S5A [T01](TASK_CONTRACTS.md)，只完成执行器可用的恢复适配和真实故障证明；问答检索/模型节点及用户会话另片接入。用户已授权沿既定路线继续，实现不依赖待答复的聊天模型选择。

## 受控写入与恢复

1. 使用已锁定 LangGraph 1.2.16、checkpoint-postgres 3.2.0 的 AsyncPostgresSaver；原生迁移结构保留，应用通过一个受控适配层访问。原生 saver 可持久化图状态，但不能单独保护外部中央租约。[官方持久化说明](https://docs.langchain.com/oss/python/langgraph/persistence)
2. 每个 adapter 固定中央签发的 tenant/user/task/epoch/worker/version。实际 threadId=`taskId:epoch`，仅根 namespace `""`；不接受任意 thread、namespace、checkpointId、客户端身份或配置。S5B 不开放子图、DeltaChannel、历史 time travel、人工 interrupt 和删除；后续实际需要时另行扩展并验证。
3. 每次 PG 写前通过中央 heartbeat 验证当前租约，写后再验证/提交。原生 `aput` 先完成不可变准备写，读取持久快照的稳定 SHA256，然后提交中央凭据。只有中央已接受的凭据指向的快照可以恢复；不能调用原生“任意 latest”。丢失提交响应先查中央，匹配完整 checkpointId/threadId/epoch/hash 才承认已提交。
4. PG checkpoints/checkpoint_blobs 添加不可变更新触发器；同键同值重放允许，不同内容拒绝。应用账号无删除/DDL 权限。避免旧执行器修改已确认历史快照或通过同 ID 重放污染接管者。
5. 指纹覆盖 checkpoint JSON、metadata、parent ID，以及引用 blob 的 channel/version/type/字节摘要；先验证原始持久数据再反序列化。使用官方严格 msgpack 白名单，禁止 pickle fallback；新库只读原生 msgpack/bytes/bytearray/null，旧 JSON 格式不接入。上游阻止类型构造但返回原始字典的降级也视为失败，官方安全事件按当前线程隔离捕获；配置元数据仅写服务器明确允许字段，不持久化 token/key/HTTP headers。
6. 新 epoch 从中央旧凭据校验出的完整快照复制到新 threadId，全部 blob 版本随快照复制，提交新凭据后再运行。原始快照不修改。pending writes 只属于当前尝试，不纳入凭据，不从旧分支恢复或反序列化；未形成完整检查点的只读节点允许重跑，真实费用仍需逐次预留。不能据此宣称 exactly-once 模型或业务副作用。
7. 失租约期间可能存在已经发出的 PG 准备写/中间写。它们属于旧 epoch，中央拒绝其凭据，且新 worker 不会读取这些孤立写；这不是跨库原子事务或物理“绝无孤立行”的承诺。真正有效的恢复分支只由中央指针决定。
8. 中央已终态时，恢复入口只返回中央结果/取消/失败事实，不再运行图；即使图最终收尾写失败也不重新生成。中央尚未终态且图已完成时，从已确认检查点取得结果，幂等提交 complete；未知返回继续对账。

## Runtime 与资源

- 共享 `dev-infra-postgres16-1`，实际 PG 16.15，127.0.0.1:45432；仅创建本项目 `agentic_checkpoints` / `_test` 两个逻辑库，各自应用/迁移角色。PUBLIC 不获得连接和 schema 写入；不接管已有同名无记录资源。
- 原生 saver `.setup()` 仅由迁移身份运行；随后在同一初始化流程补齐每张表和每列中文注释、平台迁移账本及不可变保护。平台 SQL 按摘要记录，已执行版本不得改写。应用启动不自动运行 DDL。
- Psycopg 3.3.6 已在原锁中，当前宿主缺 libpq，补充其匹配的 binary/pool extra；只补驱动实现，不升级共享 PG 或三框架。[官方安装说明](https://www.psycopg.org/psycopg3/docs/basic/install.html)
- 每 worker PG 池最多 4、等待队列最多 4、获取 3 秒、连接 3 秒、statement/lock 3 秒；中央 HTTP 最多 4 连接、5 秒请求、有界响应，任何失败不无限自动重试。单检查点总序列化内容最多 2 MiB，metadata 最多 8 KiB，单批 pending writes 最多 100 项并受同总字节界限。
- 不新增常驻 AI worker 或真实模型调用；当前以受控测试图验证框架/数据库/中央 HTTP 链路。正式问答 worker 下一片接入，继续沿用两个独立模型预算，日期切换不重置。
- 受控执行入口使用 sync durability，单次图执行最多 45 秒/32 步；终态查询先于 PG 开池。长研究图的额外时限/续租另在对应切片定义。
- 全局任务保留期尚未确定，不自动删除孤立行/历史快照。应用/迁移 Secret 仅在忽略 `.local/checkpoints.json`，0600；没有新的外部 SaaS 观测。

## 验收

真实 PG + MySQL +中央 HTTP：创建和重建 adapter 后继续同一个 LangGraph 图；旧/new worker 并发写，孤立 PG 写不成为有效快照；凭据提交响应丢失后对账；中央已成功而图收尾失败不重跑；反序列化前指纹篡改拒绝；同 checkpointId 不同内容 DB 拒绝；pending writes 不进入恢复；跨租户/任意 thread/namespace 拒绝；丢失租约后所有支持的写入口拒绝；连接/体积界限和取消归还资源；应用不能 DDL/删除，迁移重跑与全部表列注释核验。构建/类型/格式/必要现有 Python 回归、依赖核验、文档/Git 后才标 DONE。
