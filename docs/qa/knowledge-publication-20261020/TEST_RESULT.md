# S3 验证结果

日期：2026-10-20。对象：[S3 验收](../../design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md#s3-有限验收)和 [处理/发布契约](../../design/knowledge-first-20261020/PROCESSING_CONTRACTS.md)。当前 **PASS：S3 全部必需验收通过**。该报告由同一 Agent 在实现后单独执行验证 pass，不冒充第三方或多 Agent 审查。

| 验收 | 已执行的方法与证据 | 当前结果 |
| --- | --- | --- |
| S3-AC1 事务、租约和迁移 | 真实 MySQL 并发领取、超期接管、旧 owner 拒绝、重试争用、发布回滚；V3 真实 CHECK 拒绝不完整准备元数据 | PASS |
| S3-AC2 准备完整性与恢复 | Python/Java 共用真实分块器黄金包、负零 binary64、篡改向量/原文拒绝；真实进程重启不增加模型预留 | PASS |
| S3-AC3 双索引与故障 | 真实 Qdrant/ES；单 sink 故障不发布并保留准备，重试复用成功 sink；HTTP 200 中 Bulk 项失败拒绝 | PASS |
| S3-AC4 真实业务与隔离 | [真实验收](real-publication-smoke.json) 的受理→READY→搜索/引用、跨用户/租户 404/空结果、依赖失败 503；未发布与旧版本过滤由真实组件集成补充 | PASS |
| S3-AC5 有界与费用 | HTTP 超限/期限/第 4 在途拒绝；单 worker/租约重启恢复；预算损坏、超额、争锁、未知响应保留；累计预留 0.11 元 | PASS |
| S3-AC6 工程交付 | Java 41 项、Python 113 项、构建/静态检查与 Code Hygiene 已通过；ES 8.19.23 新上传/检索与旧引用保留通过，源码摘要见 [validation.json](validation.json) | PASS |

## 环境与限制

已完成业务验证使用 macOS ARM64、JDK 21.0.11、Python 3.12.13、项目 MySQL 8.4/MinIO、Qdrant 1.19.2、ES 8.15.3 smartcn、真实北京百炼 text-embedding-v4 / 1024。随后基于校验过 digest 的官方制品升级到 ES 8.19.23 smartcn，41 项 Java 测试/构建再次通过（`.logs/s3-java-tests-upgraded.log`），[升级后真实验证](post-upgrade-smoke.json)验证新资料发布/检索和旧引用/投影保留。镜像与版本见 [runtime-versions.json](runtime-versions.json)。不以测试向量证明模型质量。

没有新增 Java/Python 第三方依赖；锁文件和依赖公告基线分别沿 [S1](../knowledge-ingestion-20261020/TEST_RESULT.md)、[S2](../knowledge-processing-20261020/TEST_RESULT.md)。现有 Spring 公告适用性和 NLTK 路径例外仍保留，未声明整个依赖树或容器零漏洞。

[Code Hygiene](code-hygiene.json) 当前无阻断项，工具不能从仓库根自动发现 Python 子项目 Ruff/mypy 配置，且没有 Java 自动格式器；实际 Ruff、mypy、编译与相关测试另行执行，Java 沿周边样式人工核对。不把工具发现限制隐藏为完整自动检查。

生产 IAM、外部 TLS/代理部署、生产容量/SLA、模型检索质量基准、备份恢复演练、自动数据保留/索引重建、React、PG checkpoint、聊天/研究 Agent 均不在当前已验证结果内。用户授权的聊天预算已记录，但未消耗。

最终源码/配置共 119 个文件，SHA-256：`3a73c5e03aca8c38114a7c4b3a3ce1c03afeca2fb81ee96b1ff07a6c6368ef79`。仅修改验收脚本的状态枚举后，Ruff 与升级验收恢复重跑通过，没有新增模型调用；产品代码测试证据仍适用。文档检查见 [文档验证](documentation-validation.json)。
