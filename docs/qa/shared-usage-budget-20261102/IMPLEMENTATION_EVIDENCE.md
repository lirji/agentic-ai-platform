# S15B 共享预算实施批次证据

当前S15B实现及UA01–UA16机制验收已通过，最终结论见[TEST_RESULT](TEST_RESULT.md)，Git/CI另归档。下方保留核心/出口批次时点的原证据，不代表生产启用；完整51项未完成。

- 已实现：Tasks持久用量/短事务/身份与安全查询、V6/V7、非Web受控账户配置及冻结旧账本导入、Python全部现有模型出口、Task稳定上下文和Knowledge独立短时证明签发/验签/转发；默认LEGACY_LOCAL，CENTRAL故障不能自动回退旧账本。
- 当前：React本人用量、完整UA验收已通过；Git交付进度以PROGRESS_STATE为准。Judge仍未实现/启用；缺配置的共用许可请求已验证拒绝。
- Git基线：后端1e41545，原目录分支feat/shared-usage-budget-20261102；前端基线main6a18e72。源文件摘要：[core-source-evidence.json](core-source-evidence.json)。

| 验证 | 实际结果 / 证据 |
| --- | --- |
| Java相关单元与Tasks全量真实数据库/HTTP回归 | 52单元、60IT全通过且无跳过；`.logs/s15b-task-regression-it.log` |
| 领域枚举整改后的受影响复验 | 52单元、20用量IT全通过；`.logs/s15b-core-enums-it.log`；未改变Task其他行为或数据库code |
| Python全量默认回归 | 677通过、97条件跳过、34警告；`.logs/s15b-core-python-all.log`。跳过项是需显式真实外部环境/模型或恢复fixture的既有测试，不能称全链路全部通过 |
| 枚举整改后的用量/冻结/Chat/Rerank/Embedding复验 | 206通过；`.logs/s15b-core-python-enums.log`，Ruff通过，mypy86源码通过 |
| 实际非Web CLI | 合成原额导入+同命令重放通过；`.local/s15b-admin-cli.json`与`.logs/s15b-admin-cli-*.log`。只使用agentic_tasks_test，无真实模型 |
| Python→Java→MySQL联合 | lookup、一次发送许可、SETTLED后恢复新尝试、UNKNOWN、迟到结算及错误Knowledge证明拒绝通过；`.local/s15b-python-java-usage.json`。证明由测试夹具签发，Knowledge产品入口未完成 |
| 跨语言golden/CI | 共享usage-golden.json供Java/Python消费，中文实际请求摘要及金额/计量一致；CI登记14契约、15份共享报文检查通过 |
| 卫生门禁 | 修复裸状态字符串/枚举后，唯一剩余原门禁阻断来自忽略的第三方`.venv/httpcore`枚举误判；仅追加`.venv`排除边界、全部源码规则保持原样，得到IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。原失败日志保留；[逐项审查](core-hygiene-review.json) |

导入验证覆盖不可增grant、同资金来源不得复制、价格不可覆盖、配置CAS、同命令/同源重放、不同源重复条目整事务回滚、损坏/公开权限文件拒绝、已存在中央费用累加后冻结、历史HTTP_200仍为UNKNOWN。旧写入者必须完整停用；冻结工具只核验操作者提供的已停PID清单，不冒充全主机进程发现。

数据库表/字段注释齐全，V6/V7只应用test并固定摘要，后续改动须新迁移。原Chat与Embedding付费账本字节未变，累计占额仍为0.957092/1与0.781784/1 CNY；没有新增付费调用、Rerank/Judge授权或生产迁移。未清理测试记录、源/索引、忽略配置、密钥、日志或旧预览。

保留的失败证据：初次HTTP验证的URN issuer解析错误已修复；一次Python测试文件名拼错为0执行、随后正确路径通过；mypy首次新增类型错误与修正日志均保留；原卫生工具误判及有依据的范围修正分别留存，不将失败日志覆盖成成功。


## 模型出口与归因批次（核心5531976之后）

Chat/ToolChat/Rerank与Embedding统一先预留再取一次dispatch；流式只读末尾计量，缺失/部分/矛盾计量UNKNOWN。格式不合法但已有可信计量仍结算真实成本，取消不会退额。CENTRAL实际运行工厂不访问旧预算；任务和usage使用同一受控workerId。稳定阶段/题目/补充搜索/批次scope不含epoch，输入摘要绑定实际外发字节；上下文随协程隔离并恢复。

Knowledge原处理密钥签发独立usage受众的30秒证明。入库由原领取重新检查活跃文档意图及租约，旧prepare入口在CENTRAL拒绝绕过；搜索归因当前已认证的读者。证明不授予正文权限，也不包含资金池、金额和模型凭据；过期只阻止新发送，迟到计量由原attempt身份处理。没有续签或隐式模型重试接口。新配置详见[USAGE_RUNTIME](../../design/knowledge-first-20261020/USAGE_RUNTIME.md)。

| 验证 | 实际结果 / 证据 |
| --- | --- |
| Python全量默认回归 | 733通过/97条件跳过/34警告；`.logs/s15b-outlets-python-all.log` |
| 后续保守计量和工厂/context检查 | 120通过，Ruff144文件检查及mypy88源码通过；`.logs/s15b-outlets-final-python.log`、`.logs/s15b-outlets-final-mypy.log` |
| Knowledge单元与真实集成 | Knowledge126单元、关联Security10单元、35真实MySQL/HTTP/双索引IT全部通过且无跳过；`.logs/s15b-knowledge-usage-it-r2.log`。原领取/错worker/撤下/租约过期均验证 |
| 实际Java→Python→Tasks/MySQL | 两个用户共享同一合成账户，各自10token、0.000020 CNY结算；他人查询404；停止Tasks后Knowledge返回503、模型调用数保持2。提供方仅本地HTTPX传输替身，无付费；[收据](knowledge-joint-result.json) |
| source/hygiene | [出口批次摘要](outlets-source-evidence.json)、[卫生逐项审查](outlets-hygiene-review.json)。同原范围修正，零阻断项，Java工具限制保留 |

此批次仍不等于S15B最终验收。历史核心摘要保留5531976时的版本，不覆盖为新源码证据。新增失败诊断原样保留：两项旧factory替身参数未跟随装配签名后已修复；新增Java测试误引未引入Mockito后改JDK接口替身，r2全通过；卫生CLI参数顺序首次错误，修正后通过。没有修改或新增数据库迁移，原付费账本摘要保持一致。


## 本人视图与最终验收批次

按需展开的本人用量在问答、研究/助手、评测和资料当前job接入；真实API、严格DTO、6位字符串与状态分离，身份/context切换清理旧请求。246前端单元/组件、16身份桥复验、4真实DB浏览器和3共享消费者契约浏览器均通过。新增双Tasks竞争、独立worker退出/中央重启、DB不可达拒绝和CI边界检查，证据见TEST_RESULT及final-source-evidence.json。已实际查看7张界面截图，不宣称用户视觉确认或供应商账单验证。
