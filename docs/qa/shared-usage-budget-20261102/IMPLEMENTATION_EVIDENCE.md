# S15B 共享预算核心实施证据

当前仅核心逻辑批次验证通过，S15B仍IN_PROGRESS，完整51项未完成。这份记录支持同一任务分批提交，不代替UA01–UA16最终TEST_RESULT、浏览器验收或生产启用。

- 已实现：Tasks持久用量/短事务/身份与安全查询、V6/V7、非Web受控账户配置及冻结旧账本导入、Python共享许可客户端/上下文/计量、Embedding逐批接入；所有默认权威仍为LEGACY_LOCAL。当前CENTRAL未接好的入口会失败，不能自动回退付费旧账本。
- 尚未完成：Chat/Agent/Rerank出口、Task入口与稳定scope装配、Knowledge业务证明签发/转发、React本人用量、完整故障/联合验收及Git main交付。
- Git基线：后端1e41545，原目录分支feat/shared-usage-budget-20261102；前端main6a18e72尚未修改。源文件摘要：[core-source-evidence.json](core-source-evidence.json)。

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
