# S1 实现交接

范围依据：[S1](../../design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md)、[知识契约](../../design/knowledge-first-20261020/KNOWLEDGE_CONTRACTS.md)。输入源仓库只读，按新协议实现必要规则，没有导入旧业务数据。

| 模块 | 实际实现 |
| --- | --- |
| platform-contracts / platform-security | 稳定错误形状、RS256 身份校验、租户/用户 claim、独立内部身份协议 |
| edge-gateway | 外部验签、清除伪造头、内部 JWT 换发、路由、有界连接与管理端点 |
| knowledge-service | 输入验证、S3 原文、短事务受理、所有者范围内的幂等/去重、资料游标分页与任务查询 |
| persistence | MyBatis-Plus / 实际 Mapper XML，业务 V1 全表/列注释，唯一约束与复合外键，Flyway 分权账号 |
| deploy | 独立 dev/test 数据库与桶、私密状态/密钥、本机启停、真实 HTTP 与存储故障验收 |

首次窄验证发现并修复 Java pattern 变量重名和视图 accessor；首次 HTTP 重启发现 TIME_WAIT 端口探测误判，修复后重启通过。代码卫生发现脚本 HTTP 状态裸值和轮询空 catch，已改用标准 HTTPStatus 并保留安全错误类型用于超时报告。

初始依赖审查命中 17 个包，已按选型文档升级可用补丁并重跑全链路。最终源码、测试、运行脚本与 POM 共 54 个文件的 SHA-256 清单见 [validation.json](validation.json)；独立验收结论见 [TEST_RESULT](TEST_RESULT.md)，不以本实现交接替代验收。

当前业务结果只到 `RECEIVED/ACCEPTED`，没有 worker 或发布状态伪造。后续输入及依赖见[处理准备](../../design/knowledge-first-20261020/PROCESSING_READINESS.md)。
