# 生命周期验收矩阵

依据：[生命周期契约](LIFECYCLE_CONTRACTS.md)，S10A，2026-10-25。`DESIGN_CHECKED`只表示设计中已明确该行为；以下产品验收尚未运行，必须在Owner实施片给出真实证据，不能以本文件存在判PASS。

| ID | 场景与可观察断言 | Owner/必要方法 | 当前 |
| --- | --- | --- | --- |
| LC-01 | version/contentRevision、publishedRevision、generation、profile、管理revision和leaseEpoch分离；同内容重索引不增加内容修订 | S10A术语/状态/消费者对照；S10B/D真实DB | S10D PASS；[证据](../../qa/document-reindex-20261027/TEST_RESULT.md)，原S10B/C结果保留于其验收记录 |
| LC-02 | 当前固定version=1、READY equality CHECK、job/version与chunk/ordinal唯一约束、MIME/profile单映射均有迁移去向 | S10A固定源码证据；S10B/D迁移测试 | S10D PASS；[证据](../../qa/document-reindex-20261027/TEST_RESULT.md)，原S10B/C结果保留于其验收记录 |
| LC-03 | 两个不同更新请求持相同expectedRevision，只有一个受理；同键重放同回执、异参409，响应丢失可按键查询 | S10B真实MySQL竞争/HTTP故障 | S10B PASS；[证据](../../qa/document-update-20261026/TEST_RESULT.md)，S10C/D扩展另验 |
| LC-04 | 新版准备失败、单sink失败、进程退出时，旧发布版仍可检索/打开；不会借用失败新稿标题 | S10B真实DB/S3/双索引故障与UI | S10B PASS；[证据](../../qa/document-update-20261026/TEST_RESULT.md)，S10C/D扩展另验 |
| LC-05 | 双sink完成且指针CAS成功才发布；0影响行数整体回滚；两job不会同时成为当前发布 | S10B真实MySQL竞争/事务 | S10B PASS；[证据](../../qa/document-update-20261026/TEST_RESULT.md)，S10C/D扩展另验 |
| LC-06 | 已失败旧任务被新受理取代后，显式重试409；旧worker即使有剩余lease也不能写新发布 | S10B/D真实并发及过期执行者 | S10D PASS；[证据](../../qa/document-reindex-20261027/TEST_RESULT.md)，原S10B/C结果保留于其验收记录 |
| LC-07 | generation身份金标两语言一致；同内容同profile不同代ID不同；旧金标/块ID保持逐字节 | S10B/D实际DTO及固定向量共享金标 | S10D PASS；[证据](../../qa/document-reindex-20261027/TEST_RESULT.md)，原S10B/C结果保留于其验收记录 |
| LC-08 | TXT/PDF/DOCX旧profile与新profile可读；新默认改变不改变在途/历史profile；MIME匹配与读取集合分开 | S9D、S10B/D真实准备/查询 | S10D PASS；[证据](../../qa/document-reindex-20261027/TEST_RESULT.md)，原S10B/C结果保留于其验收记录 |
| LC-09 | 提取已接受不重解析、prepared已接受不重新Embedding，恢复只重做未确认sink；未知调用预算不重置 | S10B/D真实S3+索引及调用计数 | S10D PASS；[证据](../../qa/document-reindex-20261027/TEST_RESULT.md)，原S10B/C结果保留于其验收记录 |
| LC-10 | 更新成功后旧version或旧generation引用404；新来源不能冒用旧URL；当前发布版读取成功 | S10B/D真实HTTP、K04/K05/K06 | S10D PASS；[证据](../../qa/document-reindex-20261027/TEST_RESULT.md)，原S10B/C结果保留于其验收记录 |
| LC-11 | 新更新启用前保护已有QA/研究/助手答案、任务列表、Conversation上下文与SSE历史正文；无效引用不继续输出 | S10B跨服务真实HTTP，受控模型不计语义质量 | S10B PASS；[证据](../../qa/document-update-20261026/TEST_RESULT.md)，S10C/D扩展另验 |
| LC-12 | WITHDRAW/DELETE与publish并发任意提交顺序均合法；撤下提交后后续读拒绝，在途旧任务不得复活 | S10C真实DB并发+live索引迟到写 | S10C PASS；[证据](../../qa/document-visibility-20261026/TEST_RESULT.md)，后续S10D扩展另验 |
| LC-13 | owner可读撤下管理元数据；普通K02/K03/引用拒绝；删除详情404、原owner命令回执可恢复；异owner/tenant不泄露 | S10C真实身份矩阵 | S10C PASS；[证据](../../qa/document-visibility-20261026/TEST_RESULT.md)，后续S10D扩展另验 |
| LC-14 | 历史最终结果、流事件、来源标题、旧checkpoint恢复、评测定义/报告/基线及外发前检查统一阻断撤下/删除 | S10C跨服务/PG/浏览器；不仅测点击引用 | S10C PASS；[证据](../../qa/document-visibility-20261026/TEST_RESULT.md)，后续S10D扩展另验 |
| LC-15 | 权威不可用返回503且隐藏正文；非存在/无权统一拒绝；不根据缓存或索引成功继续 | S10B/C真实依赖故障 | S10C PASS；[证据](../../qa/document-visibility-20261026/TEST_RESULT.md)，后续S10D扩展另验 |
| LC-16 | 管理与worker统一锁序；claim/publish/retry/withdraw竞争无反序死锁或无界重试 | S10B/C真实MySQL并发与超时 | S10C PASS；[证据](../../qa/document-visibility-20261026/TEST_RESULT.md)，后续S10D扩展另验 |
| LC-17 | 重索引冻结当前发布内容/源hash/profile；不读取失败新稿；更新/重索引互斥；取消旧意图不清理对象 | S10D真实固定快照/故障 | S10D PASS；[证据](../../qa/document-reindex-20261027/TEST_RESULT.md)，原S10B/C结果保留于其验收记录 |
| LC-18 | 切换前两sink全量数量/ID/hash核对；同集profile对照；旧baseline保留、变化片段显式重新标注 | S9D机制对照；S10D存储对账 | S10D PASS；[证据](../../qa/document-reindex-20261027/TEST_RESULT.md)，原S10B/C结果保留于其验收记录 |
| LC-19 | 旧索引代挤占候选时20/60/100有界扩展，原排名不压缩；仍耗尽且有失效候选时503，查询Embedding只一次 | S10D真实索引故障/查询计数，S11A保持等价 | S10D PASS；[证据](../../qa/document-reindex-20261027/TEST_RESULT.md)，原S10B/C结果保留于其验收记录 |
| LC-20 | 旧18条local-dev资料及新建隔离数据回填不改变原hash/ID/预算；未知源不补造成功；升级前后兼容读取 | S10B迁移前后摘要与实际来源；不清理用户数据 | S10B PASS；[证据](../../qa/document-update-20261026/TEST_RESULT.md)，S10C/D扩展另验 |
| LC-21 | 关闭新命令仍能读取已发布新schema与识别墓碑；旧writer不能混跑；回滚不复活已撤下资料 | S10B/C/D隔离升级/回退实验 | S10D PASS；[证据](../../qa/document-reindex-20261027/TEST_RESULT.md)，原S10B/C结果保留于其验收记录 |
| LC-22 | 逻辑删除不调用S3/ES/Qdrant/备份物理删除；孤立对象不自动清理；没有业务保留天数时清理关闭 | S10C调用计数与隔离数据；U07另决定 | S10C PASS；[证据](../../qa/document-visibility-20261026/TEST_RESULT.md)，后续S10D扩展另验 |
| LC-23 | 现有owner-content唯一约束在S10E前明确冲突；S10E迁移后已删除/旧修订不误挡新上传，且不跨owner泄露 | S10E真实MySQL竞争/HTTP | S10C现有唯一约束冲突PASS；[证据](../../qa/document-visibility-20261026/TEST_RESULT.md)，S10E迁移仍待实施 |
| LC-24 | 更新UI同时显示受理/发布，失败旧版仍可用；409保留草稿，未知响应沿原键；失效清空正文，窄屏/焦点可操作 | S10B–D浏览器1440/1024/414及截图 | S10D PASS；[证据](../../qa/document-reindex-20261027/TEST_RESULT.md)，原S10B/C结果保留于其验收记录 |

S10A本片验证为：固定基线源码摘要、INV/决策/接口/矩阵/依赖完整性，表格与本地链接/围栏，受影响文档映射，同Agent第二轮反例审查。Mermaid语法可用现有渲染器时实际验证；没有渲染器则明确仅结构检查。不得新建应用测试来证明文字已被写入，不运行付费模型。

未决：U01真实语料/标注、U03正式共享身份、U06规模/SLO、U07具体保留与灾备要求。它们不阻断本地生命周期设计/隔离机制实现；不能据本片宣布生产质量、容量、历史保留合规或物理删除完成。
