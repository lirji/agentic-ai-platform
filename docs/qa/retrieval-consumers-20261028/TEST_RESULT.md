# S11E 当前阶段验证

当前：后端/前端/真实HTTP/PG/浏览器验收PASS，容器代理修复待验证，S11E整体仍PARTIAL。下表保留基础增量快照，后续各专项结果按末节与JSON收据追溯；旧UNVERIFIED不代表当前状态。验证由同一执行者完成，无独立Agent。

| 验收范围 | 结果 | 证据 |
| --- | --- | --- |
| Python原有与新增离线行为 | PASS | 全量526通过/53按配置跳过，后续公开布尔/worker装配修正运行针对性复验；命令和日志摘要见JSON |
| Java全模块编译/单元 | PASS | 156项；后续受影响协议/Tasks变更运行相应模块复验 |
| Knowledge完整身份/真实HTTP | PASS | AuthorizedRetrievalIT 7项，使用真实MySQL/S3/双索引，模型替身；旧代次/跨owner/撤下及受限身份 |
| Tasks证据/上下文持久化 | PASS | TaskPersistenceIT 18项；完整清单/单调追加/摘要/结果子集/未引用依赖撤权/旧协议隔离 |
| Conversation策略持久化 | PASS | ConversationPersistenceIT 12项；同键换策略、旧指纹/输入、未知提交重放、跨版本上下文拒绝；测试库V2 |
| 默认关闭与旧历史兼容 | PASS | Tasks默认关闭/旧可见性回归，worker缺依赖拒绝新图；新旧路由独立 |
| 图恢复机制 | PASS | 新QA/work实例恢复、完整身份漂移拒绝、未引用依赖失效、已完成检索/生成不重跑；内存检查点仅证明图机制 |
| 格式/类型/静态/文档 | PASS | Ruff、format、mypy68文件、6份已登记契约与10份共享夹具检查、diff卫生 |
| 代码卫生 | PASS（工具限制） | 无阻断项；4条数值提示为512字符正文、20项核验与10项清单/结果协议上限。Java项目未配置格式化器，沿周边风格人工检查 |
| 新图真实PG+中央HTTP恢复 | UNVERIFIED | 尚未替换或装配新运行制品 |
| Evaluation v2核心 | PASS | V12策略/画像/候选/完整身份读取、Tasks版本隔离；真实MySQL22项、原生图恢复、共享夹具；见evaluation-evidence.json |
| 公共检索门面 | PASS（专项） | 严格协议/签名/许可/公开核验、真实Spring启用及关闭；见search-http-evidence.json；当前运行未启用 |
| 前端与四消费者联合路径 | UNVERIFIED | 后续接入及联合验收 |
| 两进程总容量/容器/浏览器 | UNVERIFIED | 后续联合验收，不能复用S11D快照冒充新代码证据 |
| 真实精排质量与业务消融 | UNVERIFIED | U04账号/端点/价格/独立预算条件未满足；本阶段无付费模型调用 |

旧失败日志保留：图schema丢字段与枚举JSON边界（s11e-generation-work*.log）、会话测试编译（s11e-conversation-unit.log、s11e-conversation-integration.log）。均有后续修正复验；完整命令/当前摘要见JSON。没有降低断言、删除测试或修改历史迁移以通过验证。

评测增量最新检查：Python532 PASS/53 opt-in跳过（含6项新评测测试）、Java157单元PASS，Ruff/format107文件/mypy69源码PASS；共享契约11夹具。22项真实MySQL集成必须引用s11e-evaluation-real-integration-retest.log，旧s11e-evaluation-db/java-final等命令遗漏-Pintegration，不能作为集成证据。原生图schema、测试Claimed包装、非法processor夹具CHECK和类型常量错误均已修复并保留初次失败日志。基础提交bea8c02的CI三项PASS见CI_RESULT.json，该CI不涵盖未提交的评测增量。Code Hygiene无阻断项，保留Java格式化器不可用限制。

公共门面增量：Java全模块164单元PASS、ConsumerRetrievalHttpIT2/EnabledHttpIT1/EvaluationHttpIT2真实HTTP通过，最后HttpStatus枚举调整后7项协议复验通过。Python全量549通过/53默认跳过，随后仅增加正文耗时测试，HTTP专项18通过；Ruff/format110/mypy71通过。共享契约7份/12夹具通过。初次Spring构造器装配失败已修复，代码卫生终态COMPLETE_WITH_LIMITATIONS；详见search-http-evidence.json。评测提交70a33c0对应CI三项PASS见EVALUATION_CI_RESULT.json；公共门面CI将在提交后独立记录。

公开受理增量：165单元、56真实HTTP/DB通过（Tasks19、旧可见性及关闭5、Conversation13、EvaluationRun8、Decision8、Http3）；测试补充跨版本工作幂等断言后Tasks/Conversation32项复验通过。网关受众/头清理测试已纳入单元。新评测正向服务持久与公开默认关闭分别验证，启用后的完整HTTP联合验收仍待完成。Code Hygiene无阻断，Java格式化器限制保留。检索门面6b35d0a远程CI三项PASS见SEARCH_HTTP_CI_RESULT.json，该CI不覆盖随后公开受理源代码。

完整引用读取：受影响Knowledge及上游模块单元通过，AuthorizedRetrievalIT8 + ConsumerRetrievalHttpIT2 + EnabledHttpIT1共11项真实集成通过；新增路径验证完整身份、同内容重索引、撤下、跨owner、缺字段/超限/权限。首次失败为LongNode/IntNode树比较，改按真实JSON线格式比较后通过，未改产品断言边界。详见citation-read-evidence.json。公开受理f303f4a远程CI三项PASS见PUBLIC_CONSUMER_CI_RESULT.json。

## 真实四消费者与浏览器增量

5单元、3真实Java集成、内嵌1项四消费者完整HTTP及1项生产构建浏览器测试通过。Python全量550通过/68按配置跳过；Ruff113文件/mypy71源码通过。真实服务与存储覆盖搜索/QA/work/评测，逐题接管只运行未完成题，撤下隐藏摘要正文并拒绝逐题读取；模型均为明确协议替身，新增付费调用0。见 [联合证据](four-consumer-joint-evidence.json)。
