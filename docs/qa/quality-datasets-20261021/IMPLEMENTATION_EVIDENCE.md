# S8B 评测集与不可变修订实现

范围为 [S8B](../../design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md#s8b--评测集创建修订与标注界面)，基于后端 `39af9f6`、前端 `d2b4dc6`；两仓同名任务分支 `feat/quality-datasets-20261021`。按既有 Knowledge / MySQL / React 边界实现，不新增服务或模型依赖。

| 业务行为 | 实现与证据 |
| --- | --- |
| 创建、修订和原提交回执 | `EvaluationService`、`MySqlEvaluationRepository`；定义摘要包含完整范围与证据绑定。一个短事务保存当前指针、不可变修订和命令；同键先重放，异参冲突，修改以 expectedRevision 条件更新 |
| 并发与数据完整性 | V4 的身份复合外键、命令唯一键及 CHECK；编辑先锁评测集，再按资料 ID 顺序共享锁；无远程调用进入事务；唯一冲突在失败事务之外查获胜回执 |
| 当前授权和发布状态 | `EvaluationMapper.xml` 复用 `ProcessingMapper.publishedScope`；所有读写带 tenant/owner，历史绑定失效即隐藏正文；NO_ANSWER 也固定语料范围 |
| 有界接口 | E01 独立严格 JSON 编解码、256 KiB 流读取上限、Unicode 校验、游标绑定资源/用途、数据库分页、批量绑定最多 100 份资料 / 1000 条金标；不改变既有 K/P JSON 行为 |
| 真实编辑与证据 | React `features/quality`、两页 `QualityPage/QualityDetailPage`；真实资料名、发布原文与定位，空集可以保存，不能伪造运行结果 |
| 失败恢复 | 复用已有提交意图机制，正文只留身份隔离的内存；未知提交查询原回执或原键原输入重试；并发冲突保留草稿，查看最新修订后明确采用新版本 |
| 本地运行入口 | `deploy/local-identity.ts` 仅追加 E01 精确路由/查询参数；Origin/Host/Workbench 请求保护保持；V4 已应用于独立 dev/test，Knowledge 新制品 readiness UP |

E01 增补可选用途筛选，由数据库执行且绑定游标，避免仅过滤当前页面；正式定义见 [QUALITY_CONTRACTS](../../design/knowledge-first-20261020/QUALITY_CONTRACTS.md)。S8A 验证摘要仍是其历史版本证据。

实现验证记录：Knowledge 26 单元、21 真实数据库集成通过，另 2 组真实 HTTP 通过；新增质量部分为 11 单元、7 持久化、2 HTTP。并发测试覆盖 6 个同键创建、4 个同键编辑、两个竞争修订、旧版本不可变及失败命令不留回执。测试发布准备包是隔离库中的确定性夹具，不是模型生成质量证据。前端真实浏览器首次创建两类题目、标注并持久化；后续运行按本地回执恢复已创建数据，避免重复未知写入。

浏览器发现并修复：引用/选择/标注侧栏关闭焦点丢失、加载图标污染保存按钮可访问名称、已纠正字段残留旧错误。一个新增校验测试首次因页面内提示和汇总提示双匹配失败，改为准确定位 alert，未放宽业务断言。最终结果与截图审查见 [TEST_RESULT](TEST_RESULT.md)。

代码卫生建议人工判定：固定边界已集中在 `EvaluationProtocol`；BindingState 是修订资料绑定是否可用，与资料处理 Status 不同；事务复核由上表及真实并发测试支持。Java 仓库尚无 formatter / 专门静态分析工具，保留限制；未趁本片引入工具链。未改依赖、重置预算或调用模型，运行/比较能力由后续 S8C/D 实现。
