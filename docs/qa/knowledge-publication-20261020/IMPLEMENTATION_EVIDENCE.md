# S3 实施证据

范围：[S3](../../design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md#s3-有限验收)。Java 拥有原文、任务、准备包、索引投影和发布权威；Python 只提供有界准备和向量能力。未增加 MQ、业务调度平台或第二套领域存储。

- V2 增加租约/代次、有限领取、不可变准备指针、逐索引确认、规范块与审计；V3 修复 MySQL CHECK 的 UNKNOWN 放行。两者已经应用到本项目 dev/test，不能改写历史。
- 领取、准备接受、失败、重试和发布使用短事务及 Mapper XML；所有写入带身份、版本、执行者、epoch 和有效租约条件。真实并发测试验证唯一领取、接管、旧执行者拒绝、CAS 重试、任务/文档发布原子性。
- 源对象和准备对象经大小/哈希验证。Java 独立重算身份、正文、UTF-8 定位、覆盖、重叠、向量与批次摘要。真实 LlamaIndex 生成的固定多块 Unicode 包由 Python 重算与 Java 读取共同验证；测试向量不计作模型质量。
- Qdrant/ES 使用本项目独立实例、卷、账号及 dev/test 索引。冻结批次 ID 使未知提交可安全 upsert；ES 检查 Bulk 每个 item。未发布候选再次通过 MySQL 权限/版本/批次验证，原文引用来自规范块。
- 单 worker、独立心跳/总期限、HTTP 在途和响应字节限制，搜索在途限制及稳定错误均已实现。worker 线程显式携带任务身份，追踪单独起 span；没有共享请求上下文或跨线程事务。
- Python 模型适配器每个实际 HTTP 批次前锁定已有本轮账本，落盘后才发送。损坏、争锁或超额拒绝；未知结果不退还，重试不重置账本。Embedding 1 元与已批准的聊天 1 元独立。
- 本机全局代理导致 S3 readiness 503 的问题已复现：相同 SDK/凭据直连成功。loopback S3 关闭代理继承，真实 HTTP 测试确认拒绝代理收到 0 次请求。远端对象存储仍遵循环境代理设置。

## 验证过程与保留记录

1. 早期编译发现 `SourceReference.sizeBytes/size` 不匹配，修复后编译通过。
2. Docker 新网络分配失败（地址池已满），按已有约定复用 `dev-infra` 网络，独立服务与卷初始化成功。
3. 新增数据库 CHECK 测试首次失败是 Spring 将 MySQL 3819 包装为 `UncategorizedSQLException`，数据库实际拒绝成功；改为严格断言错误码/约束名后通过。
4. Java 最新 41 项测试与 Maven verify 通过，日志 `.logs/s3-java-tests-upgraded.log`。Python 113 项、Ruff/mypy 和 sdist/wheel 构建通过，日志 `.logs/s3-{python-tests,mypy,python-build}.log`。
5. [真实业务结果](real-publication-smoke.json)：经网关、真实百炼、MySQL/S3/Qdrant/ES，完成发布、检索引用、跨用户/租户隔离、ES 故障不发布、Knowledge 真重启与准备复用。该次使用 Qdrant 1.19.2 / ES 8.15.3。
6. 真实验收沿用 `.local/s3-smoke-state.json` 中的任务编号与幂等键，最终累计 11 次保守预留/0.11 元（含 S2），实际账单未读取。恢复本身未增加模型请求；聊天未调用。
7. 搜索补丁：Qdrant 1.19.2 已升级并保留数据。ES 8.19.23 官方镜像拉取先后遇到 EOF/大层不完整；通过宿主从官方 registry 分段下载并校验 OCI digest，导入后按原 digest 构建 smartcn，升级至 8.19.23 成功；41 项 Java、[升级后真实验收](post-upgrade-smoke.json)及旧引用/投影保留均通过。没有更改共享 Docker 代理或旧平台容器。
8. 初始 ES 快照 `agentic_local/s3-prepatch-20261020` 成功，Qdrant 两个集合快照已下载；真实业务验收后另建 `s3-publication-20261020` 快照。状态在 `.local/search-backups/20261020-prepatch/`，快照存在不代表恢复演练已完成。

## 运行与维护

[本地运行](../../../deploy/README.md)是唯一运行入口。默认处理关闭；本机 worker 仅领取 `local-dev`，集成测试关闭后台领取。readiness 检查真实 DB、桶、AI 进程及索引 schema，不调用付费模型、不代表额度或质量。

已有准备包/成功索引保留，FAILED 需所有者按 revision 显式重试；租约过期任务可接管，5 次连续领取和 3 次显式重试有界。取消不回滚已发生副作用；当前没有取消 API、自动清理、全量索引重建或生产备份恢复承诺。

`.local/` 保存凭据、身份密钥、预算、任务恢复状态与快照，必须保留；`.logs/` 保留失败和最终验证证据。没有创建额外 worktree，也没有导入旧业务数据或执行生产部署。

9. 最终 Code Hygiene 发现升级验收脚本两处终态字面量，已统一使用显式 code 枚举；Ruff 和可恢复的升级验收重跑通过，预算仍为 0.11 元。事务提示人工核对：事务内只有数据库操作，准备/模型/双索引在事务外，条件更新和审计同事务；相似枚举提示来自忽略的第三方 venv，不是重复业务模型。
