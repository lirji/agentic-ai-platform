# 知识能力迁入清单

固定来源 `langchain4j-platform@468cec425dd160e80cfc3ab378d3440a8767e4d5`。只读取源码；本片按新契约适配规则，不整仓复制，不导入旧业务数据。

| 来源 | 保留的不变量 | 本项目落点 |
| --- | --- | --- |
| `knowledge-service/.../controller/IngestionController.java` | 202 仅表示受理；Idempotency-Key；任务状态单独查询 | `/api/v1/knowledge/ingestions`，改为服务器生成 ID 与稳定中文错误 |
| `knowledge-service/.../ingest/job/IngestionSubmissionService.java` | 先校验授权、完整输入指纹、原文落存储、事务幂等、同键异参拒绝；未知结果不删除可能有效原文 | Knowledge 应用用例 + MySQL 唯一约束 + S3 原文适配器 |
| `knowledge-service/.../controller/DocumentController.java` | 资料生命周期、分页先判权、引用定位 | 新资料分页与详情；删除/多模态/公共分区不迁入 S1 |
| `knowledge-service/.../ingest/job/JdbcIngestionJobStore.java` 与 Mapper XML | 持久化状态、原子约束、任务可恢复 | 新项目 MyBatis-Plus + Mapper XML；不迁入内存 store 作为运行默认 |
| `database-migrations/.../knowledge-ingestion/V1__knowledge_ingestion_schema.sql` | 原文引用、内容指纹、所有者、版本、阶段和时间 | 新库 V1 建表，补齐全部表/字段中文注释；旧迁移不执行到新库 |
| `edge-gateway/.../CasdoorTokenExchangeFilter.java` | 外部 JWT 验签、清除伪造内部头、换发内部身份、移除外部凭据 | 独立 Gateway + Spring Security；只接受配置的 IAM，取消旧 legacy/匿名回退 |
| `deploy/dev-infra/manage.py` / README | 共享实例、独立资源、应用/迁移账号分离、私密配置 0600 | 本项目 provision/start/stop；不执行旧平台备份迁移脚本 |

实际来源路径中的 Java 包为 `src/main/java/com/lrj/platform/knowledge/` 或 `.../edge/`；数据库路径为 `src/main/resources/db/migration/knowledge-ingestion/`。前述控制器、提交用例、迁移、网关与基础设施入口均已读取核对。

## S1 的依赖闭包

后端 Maven 仓库按职责组织：`platform-contracts`（公共错误协议）、`platform-security`（JWT 校验/可信身份工具）、`knowledge-service`（知识数据唯一所有者）、`edge-gateway`（入口验签、身份交换和路由）。共享库没有业务 Mapper；网关不依赖 Knowledge 服务代码。

首片运行只需要 JDK 21、MySQL 8.4、S3 兼容存储和独立密钥。Spring Security/Actuator/Micrometer 随首次服务引入；服务寻址使用受控环境地址，当前无动态配置/服务发现需求，不机械启动 Nacos/Redis/MQ。MyBatis-Plus 使用 Boot 3 starter，SQL 在 Mapper XML；Flyway 使用迁移账号，业务账号不授予 DDL。

第一条路径默认仅接受 UTF-8 TXT/Markdown，资料 OWNER 可见。未迁入功能包括图谱、多模态、OCR、公共知识分区、审批流、定时同步、旧门户、聊天记忆和旧 Agent 调度。处理器未接通前任务保持 RECEIVED；不以内存 worker 或假向量完成任务。

## 后续依赖与需要核实的输入

1. S1 后冻结处理器与发布提交契约，接通真实 Embedding、检索存储、失败/租约恢复；仅允许 Knowledge 写权威索引。
2. 实际 Embedding 联调要核对账号、地域、模型参数及调用预算；凭据不复制进文档或 Git。
3. 前端新建独立 React 仓库，消费已验证的 K 契约；无真实业务 API 的操作不得伪造成功。
4. 生产 IAM 的 issuer/audience/租户与 scope 映射，以及 Boot 3.5 系列后续补丁来源仍需环境级落地。开发验证只证明本项目验签与隔离逻辑，不代表已完成生产登录配置。
