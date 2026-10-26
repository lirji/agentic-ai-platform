# S10C 实现证据

范围：已批准的撤下、逻辑删除及当前授权传播。基线backend `7ba26c4` / frontend `83d24db`，同一任务分支 `feat/document-visibility-20261026`。无新组件、依赖或数据库迁移；复用Knowledge V10、Tasks V4、S10B消费者守卫和已有前端意图存储。

| 位置 | 行为与原因 |
| --- | --- |
| LifecycleController / Service / Models | 严格JSON `expectedRevision`、稳定WITHDRAW/DELETE命令、owner作用域原键/指纹；已删除或关闭命令后仍能重放无正文回执 |
| MySqlLifecycleRepository / LifecycleMapper XML | document→job锁序；可见性CAS、管理revision递增、命令、审计及在途作业FAILED/租约清除同事务；不执行远程IO |
| 处理与读取 | 保留发布元组和READY历史供审计，清空activeJobId；旧worker不能续租、确认sink或发布。复用当前发布与ACTIVE联合校验阻断正文 |
| 评测 / Tasks / Python / Conversation | 新增实际撤下/删除的验证；既有当前引用权威拒绝定义正文、报告、评审/基线、历史结果/SSE、恢复与会话上下文。未复制第二套权限判断 |
| React VisibilityActions / DocumentPage | 明确影响确认、原请求编号持久化、409重新确认修订、未知命令暂停正文与更新入口；404详情保留原键恢复。撤下仅管理信息，删除不展示旧详情 |
| Runtime | 两个新开关默认false，原四字段配置升级后仍关闭新命令；仅本地有限白名单开放两个POST。回填与任何写开关互斥 |

同一Agent第二轮反例复核发现并修复两项问题：

- 同键删除在首次回执查询后提交时，事务外详情读取可能404。定向真实MySQL测试先复现失败，再改为开启命令时直接进入文档锁与原键复查，11项Lifecycle IT通过；关闭开关时仍先验证owner。
- 首次真实浏览器截图显示响应丢失时旧正文仍留在页面。改为命令在途/未知时暂停正文和更新入口，并立即重新查询管理权威；受控悬挂查询和真实删除响应丢失均验证旧正文消失、404页按原键恢复，只有一个POST。

早期实现/验收修正：测试误用了未引入的Mockito，改为手写端口；评测DTO按正式definition读取；确认按钮补稳定aria-label；HTTP探针等待Conversation的SUBMITTED，按WorkItem.task读取列表，并修正删除路径拼写。初次失败日志保留，未新增依赖或改变契约来规避失败。

本地验证正常停止旧Java进程后构建，临时关闭所有新写开关和停止Knowledge验证兼容/503，再恢复原环境。没有覆盖活动JAR、清理数据、重置费用或触碰旧S10B浏览器资料。最终新增3份隔离资料，另2次实际失效搜索；Embedding累计65批/0.671784元，chat24次/0.889964元，各1元累计上限不变。

原始状态与日志保存在两仓忽略目录`.local/s10c-*.json`、`.logs/s10c-*`；公开证据不包含签名材料、令牌、连接密码或业务原文。受控模型与合成正文仅证明权限/恢复机制，不代表业务答案质量。验证结果和源码边界见[TEST_RESULT](TEST_RESULT.md)、[validation.json](validation.json)。
