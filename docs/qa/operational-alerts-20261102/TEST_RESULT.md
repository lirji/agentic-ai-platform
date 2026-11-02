# S16D 验收：可操作告警与运行手册

Validation=PASS；实现质量为 COMPLETE_WITH_LIMITATIONS（Java没有项目专用formatter/static工具）。绑定[源码/日志摘要](source-evidence.json)，Git/CI状态见唯一业务进度。没有启用常驻监控、更新既有Docker、发送外部消息或新增付费调用。

| 验收 | 实际结果与证据 | 结论 |
| --- | --- | --- |
| AL01 权威只读数据 | Knowledge/Tasks分别通过自己的Mapper读取真实MySQL；2项管理HTTP/脱敏/DML权限检查通过。在原test库及新增空库分别执行，空库初始化Knowledge19/Tasks8迁移后零值正确，合成待处理年龄/数量、中央95%预算及任务取消后数量正确 | PASS |
| AL02 规则触发/恢复/去重 | Prometheus2.54.1实际promtool验证8规则；每条有未触发、持续触发、恢复断言，双实例聚合为单一告警；配置和规则语法通过 | PASS |
| AL03 实际通知 | 官方Alertmanager0.27.0本机进程：3类告警触发、4轮重复提交无额外通知、3次恢复通知；只向loopback合成接收器发送。[通知收据](notification-result.json) | PASS |
| AL04 脱敏与处置 | 真HTTP指标无合成SECRET正文/owner/id，新增指标无动态标签；旧账本仅CHAT/EMBEDDING固定标签；通知标签固定。8规则均有[处置命令和恢复标准](../../../deploy/monitoring/RUNBOOK.md)；U06未定义，全部阈值标experimental | PASS |
| AL05 失败/并发/回归 | 4 Java采样测试覆盖缓存、读失败保留旧值/失败标识、恢复和慢读取时其他抓取不等待；3 Python实际文件/锁/HTTP测试覆盖损坏、权限、symlink、只读、恢复。最终受影响Java及依赖共167项通过，独立空库额外4单元+2IT通过无skip；Ruff格式/静态、15契约/15报文、usage边界、diff/本地链接通过 | PASS |

数据与进程：新增独立库`agentic_knowledge_test_s16d_f0cfb968`、`agentic_tasks_test_s16d_f0cfb968`及合成资料/日志全部保留。迁移使用既有专属迁移账号，采样使用仅DML账号；没有新增或改写迁移。所有本片Java/HTTP/Alertmanager测试进程已退出。原Chat/Embedding摘要与S16C保全收据一致，费用0。

验证入口：`python3 deploy/monitoring/validate_database.py`自动创建新唯一测试库并记录`.local/s16d-db-<id>.json`；不会复用/覆盖其他数据库。源码版本为working tree摘要，主分支CI随后验证精确提交。固定工具来源和官方校验和见[工具收据](tool-source.json)。Prometheus使用已存在镜像digest，Alertmanager因Docker镜像下载停滞而停止该次拉取，改用官方同版本darwin-arm64包并核对发布SHA-256。测试结果不是镜像构建成功声明。

失败与修正：首次Ruff误继承用户级规则，后按项目`ai-runtime/pyproject.toml`检查；补齐zip严格性并格式化。代码卫生门禁发现两处测试HTTP裸状态值，改用标准HTTPStatus后重新通知验证及门禁，全部阻断项消除。保留原失败日志，没有跳过必需项。工具仍报告常量MAX_BYTES和合成测试10分钟窗口的非阻断建议：前者沿原账本大小上限，后者只是测试通知有效期。

限制：原长期镜像没有新指标，本片不宣称已上线实时告警；Docker开关只完成解析/接线/静态验证，实际新制品启动留S17A隔离验收。默认Alertmanager无外部接收器，真实外部送达没有执行。Java专用formatter/static缺失由现有风格、Maven及人工边界审查覆盖，Python已实际运行Ruff；旧监控工具版本沿现有dev-infra，不构成最新安全认证，供应链由S17D检查。生产容量/SLO仍待U06，费用告警不证明供应商账单。

日志：`.logs/s16d-final-java.log`、`.logs/s16d-db-f0cfb968/maven.log`、`.logs/s16d/`；包含初次失败与最终复验。没有生产部署或数据清理。
