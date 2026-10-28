# S11D 验收结果

本片协议、预算和失败机制 **PASS**；按已批准条件，U04未满足的真实模型与逐题质量消融 **UNVERIFIED**。Git/CI另记交付收据。[契约](../../design/knowledge-first-20261020/RETRIEVAL_CONTRACTS.md)，固定源码见[validation.json](validation.json)。

| 验收 | 结果 | 证据 |
| --- | --- | --- |
| RT-10 50候选与外发核权 | PASS | 最终K=2仍有50项进入模型；实际HTTP candidateTarget=50；发送前解析50项，输出前解析K项；发送前撤权无模型请求 |
| RT-11 完整模型协议 | PASS | 重复/漏项/越界/布尔或浮点索引、非法/越界分数、错误模型/形状/重复JSON键/usage/超限响应拒绝；同分沿RRF/chunkId，三类分数独立 |
| RT-12 明确失败 | PASS | 默认FAIL，无未批准的RRF_ONLY策略；配置/预算/模型失败、取消/总期限与授权失败均不降级；EMPTY不调用模型 |
| RT-13 独立持久预算 | PASS（机制） | 独立scope/配置指纹、权限/nofollow/文件锁、累计总和、fsync先行预留；重启耗尽仍拒绝；未知HTTP及取消保留预留且不重试；旧账本hash未变 |
| 框架与资源 | PASS | 原生异步BaseNodePostprocessor，Settings/global_handler/根事件隔离，禁止默认to_thread桥接；RRF/精排共享2在途，取消释放；预算专用单线程且仅1在途，取消不提前放开写账许可 |
| 实际容器链路 | PASS（模型替身） | 快照01a95560586ee526，9健康容器/10只读smoke；真实Java/DB/双索引的RRF与精排两项通过。精排一次候选、一次模型协议请求、两次权威解析、一次独立临时账本预留，见[精排证据](live-rerank-result.json)、[RRF证据](live-rrf-result.json) |
| 真实模型与消融 | UNVERIFIED（条件项） | U04账号、地域、端点、型号、价格与独立真实额度未落实；真实重排调用0，不能据替身宣称质量/费用/延迟提升 |

全Python497通过、53个显式集成默认跳过（本片/原检索两项随后单独显式运行通过）、19项已观察依赖警告；Ruff格式/检查、mypy62源码、compileall通过。307项全部Java模块/构建输入与S11C一致，复用154单元与S11B 133真实集成证据，未重复执行。前端保持4a9a280，容器构建实际通过114测试及格式/lint/类型/生产构建。

同一执行者分阶段验证，无独立Agent。447项后端输入、484项前端输入与构建manifest一致；业务资料61份、旧账本不变。新精排的临时私密账本只用于容器内HTTP替身，未创建可用于真实调用的运行配置或额度。没有迁移、生产部署或清理；现有消费者仍未切换。实现与限制见[实施证据](IMPLEMENTATION_EVIDENCE.md)、[审查](REVIEW.md)。
