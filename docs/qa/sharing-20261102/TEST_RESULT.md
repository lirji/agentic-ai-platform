# S13B/C 指定成员只读共享验收

结论：功能、全链路撤权及代码卫生复核通过；Git/CI结果见validation.json与交付收据。完整51项计划未完成，本片不代表生产部署。

范围：[C-SHARING](../../design/knowledge-first-20261020/SHARING_CONTRACTS.md)、[运行说明](../../design/knowledge-first-20261020/SHARING_RUNTIME.md)。用户2026-11-02选择同组织指定成员只读，所有者逐份授权/撤回；接收者不编辑或转授。

| 验收 | 结果与证据 |
| --- | --- |
| 授权与写边界 | KnowledgeSharingIT：同组织当前成员、跨租户/用户、只读scope拒写、伪造主体、严格JSON、并发CAS、同键审计、未知提交回执、旧授权不复活、撤下/删除与IAM故障。8基础组PASS，另2联合组PASS。 |
| 真实跨组件撤权 | `.logs/s13c-events-joint-final.log`及其s13c-joint子日志（前轮完整撤权`.logs/s13c-joint-6724540971464799120/consumers.log`）：真实Knowledge/双索引/MySQL/Tasks/PostgreSQL；共享QA、研究、评测成功后，撤权历史任务/事件404，评测正文隐藏；已确认计划PG恢复失败且无新增模型调用；生成途中撤权不交付结果；候选返回后撤权使精排调用数为0。 |
| 兼容回归 | `.logs/s13c-final-db-joint.log`：Processing7、EvaluationRun8、EvaluationDecision8、EvaluationPersistence7及Sharing10通过；AuthorizedRetrieval7通过/1装配失败，修正漏加载SQL后`.logs/s13c-authority-regression-r2.log`8项全部PASS。合计48个必要集成案例最终通过。 |
| Java普通验证 | Knowledge119、contracts11、security10通过；最终有限输入验证日志`.logs/s13c-validation-final.log`。无H2、无Mockito新增依赖。 |
| Python | `.logs/s13c-python-unit.log`620通过/97显式环境跳过；ruff format/check、mypy78源文件通过。完整联合另由Java显式启动，非默认跳过冒充。 |
| 前端 | `.logs/s13c-final-unit.log`192项通过；format/type/lint/build通过，类型解析增补另见s13c-parser日志。 |
| 浏览器 | 真实HTTP共享流程1项：授权已提交但响应丢失、原键查询、接收者只读列表/详情/检索范围、撤权5秒刷新；日志`.logs/s13b-browser-10229499330609422357/browser.log`。会话/研究/检索17项回归最终通过（16项首轮+旧fixture补共享关闭后1项复验）。 |
| 截图 | 前端`docs/qa/sharing-20261102/screenshots/`5张：owner桌面/414宽、reader列表/详情/撤权空态。已实际查看；原历史回归截图恢复，18张新回归图归档本片regression。 |
| Runtime | 默认关闭；只有同次正式OIDC配置可派生共享租户、凭据、成员；本机私密登记派生只读核对PASS，不输出秘密。新增--sharing为显式后续准备选项，未执行部署。 |

浏览器会话由测试夹具提供，API真实走隔离Java/MySQL；Node只在服务端签测试请求。另已只读核对真实Casdoor按稳定userId访问，原S13A正式登录证据沿用。本片没有创建账户、复制真实用户数据或付费模型请求。

V13只应用隔离test库，不改写迁移历史。开发/生产数据库与运行容器未迁移或更新；测试随机租户/回执/日志保留，不清空数据重测。已知限制与整改见[REVIEW](REVIEW.md)；日志/源码/截图摘要见[validation.json](validation.json)。
