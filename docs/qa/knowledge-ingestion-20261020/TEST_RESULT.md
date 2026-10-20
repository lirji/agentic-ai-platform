# S1 知识资料受理与查询验收

2026-10-20。**S1 必需验收 PASS，可标 DONE。** 本结论覆盖独立网关、知识原文受理与查询，不代表完整知识库、生产部署或无已知依赖公告。验证 pass 对照契约和执行证据独立检查；未使用多个 Agent。

源码与配置共 54 个文件，摘要 `e9f8f566e697d98ff0fd1a1224af46f868f8cc63900144bbfc498e9e7ee02110`；逐文件摘要、命令、环境限制见 [validation.json](validation.json)。文档收尾不改变该源码摘要，Git 交付前再次核对暂存区。

## 验收矩阵

| 验收 | 实际证据 | 结论 |
| --- | --- | --- |
| S1-AC1 模块与构建 | JDK 21.0.11 / Maven 3.9.12；Boot 3.5.16 / Cloud 2025.0.3；四模块 `verify`、可执行 jar 打包；26 项 Java 测试，无失败/错误/跳过 | PASS |
| S1-AC2 身份与权限 | 真实 RSA 签名、外部/内部独立密钥及错配启动拒绝；真实 Gateway→Knowledge HTTP 验证伪造头、错误签名/issuer/audience/时效、scope、跨用户/租户不可读 | PASS |
| S1-AC3 原文及受理 | TXT/Markdown、严格 UTF-8/空白/NUL/大小验证；真实 S3 put/get 字节一致；MySQL 原子文档+任务，RECEIVED/ACCEPTED、publishedVersion=null | PASS |
| S1-AC4 并发和故障 | 6 路并发同键只有一份文档/任务；同键异参、同 owner 内容去重；失败插入回滚；不同用户/租户独立去重；真实无权桶故障不产生文档/任务 | PASS |
| S1-AC5 HTTP、分页和重启 | [45 项 HTTP 检查](http-smoke.json)，含游标/错误形状/重启后详情与重放；[5 项依赖故障检查](dependency-failure.json)：liveness UP、readiness 503、上传明确失败、无虚假受理 | PASS |
| S1-AC6 工程和运行约束 | 业务表/字段注释查真实 information_schema；应用 DDL 被拒；[重复初始化](runtime-idempotency.json)不改变凭据和记录；Ruff、质量 Gate、源码摘要及文档检查 | PASS（工具限制如下） |

测试组成：JWT 信任 10、输入边界 7、真实 MySQL/S3 及上下文 7、Gateway 密钥配置 2。Socket 检查另计 45+5；不把同一测试的重跑累计为更多覆盖。没有调用真实模型或旧业务服务。

## 依赖与安全审查

[初始 OSV 查询](dependency-audit-initial.json)为 178 个运行坐标、17 个包命中公告；按照 [TECH_SELECTION](../../design/platform-selection-20261020/TECH_SELECTION.md)固定补丁并验证后，[最终查询](dependency-audit.json)为 175 个坐标，2 个 Spring 包、3 个不同公告仍命中，没有分页遗漏。**不是零漏洞扫描结论。**

以下是针对当前 S1 调用条件的适用性判断，不是从数据库中忽略公告：

| 公告 | 当前证据与判断 |
| --- | --- |
| CVE-2026-47892 / GHSA-9qf2-26p9-2q2q | 官方条件为 WebFlux 功能端点与 DispatcherServlet 组合；实际 Gateway jar 没有 spring-webmvc，Knowledge jar 没有 spring-webflux，当前不满足组合条件。[Spring 公告](https://spring.io/security/cve-2026-47892/) |
| CVE-2026-47890 / GHSA-j9f9-w8pj-32f8 | 当前没有视图片段 SSE；Controller 只返回固定 JSON，真实上下文检查无 SseEmitter/View 返回路径。未来流式功能必须重审，当前条件不成立。[Spring 公告](https://spring.io/security/cve-2026-47890/) |
| CVE-2026-47884 / GHSA-pc63-qcmh-9cmg | 当前没有 XsltView/XsltViewResolver，也没有隐式名称的视图渲染；真实上下文测试验证 bean 缺失，当前条件不成立。[Spring 公告](https://spring.io/security/cve-2026-47884/) |

Spring 6.2.20 修复需企业补丁来源，OSS 修复位于 7.0.9。此维护路线尚未落实；不能把当前不受影响的范围扩大到后续能力或生产认证。没有擅自将 Spring 7 混入 Boot 3.5。

## 工具结果与限制

- [Code Hygiene](code-hygiene.json)：`IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS`，CODE_HYGIENE / REPO_CONVENTION / DIFF_HYGIENE / COMPILE / UNIT_TEST 通过，无阻断 Finding。边界常量与 TransactionTemplate 的 advisory 已人工对照契约和短事务语义。
- 根 Java 工程没有既有格式化器或静态分析器；Gate 的 FORMAT 为工具缺失、STATIC_ANALYSIS 为未配置，不能写成已执行 Java formatter。未为门禁临时引入第二套依赖。Python 运行脚本复用已有 Ruff 配置，format/check 均通过；Java 编译及周边风格检查通过。
- Flyway 11.7.2 对 MySQL 8.4 发出“已测试至 8.1”的版本告警；本片创建/校验迁移、事务/并发/重启已实测，不能证明所有未来迁移兼容。
- 公共技能 Runtime 仍有已知全局漂移，按既有手工路由兼容规则执行；没有机器 Runtime PASS 声明，也没有改写全局技能指纹。
- 前端视觉 N/A（本片无页面）；生产 IAM 登录、外部部署、模型语义/费用、worker/向量发布、容量 SLA 与灾备未验证，不计为本片通过范围。

## 失败与修正留痕

1. 初期编译失败：pattern 变量重名、视图 accessor 不一致，均修正后完整构建通过。
2. 初期 HTTP 重启失败：端口探测未设置 SO_REUSEADDR，误判 TIME_WAIT；修复后多次进程重启和数据查询通过。
3. 初期 Ruff/代码卫生失败：脚本行长、裸 HTTP 状态和轮询空 catch；完成有界修正后重跑通过。
4. 初期依赖查询脚本在系统 Python 3.9 上不支持 `zip(strict=True)`；改用项目已有 Python 3.12 工具环境后完整执行，未丢弃公告命中。
5. 初始 BOM 公告命中不是测试噪声；已修复 15 个包的版本命中，保留剩余 2 个包的实际适用性和维护限制。

运行说明：[deploy/README](../../../deploy/README.md)。`.local/` 的凭据、密钥及数据归属记录必须保留；`.logs/`、`target/` 为本机验证/构建产物，未提交。不清理业务数据或原有 `.idea/`。后续入口：[处理与发布准备](../../design/knowledge-first-20261020/PROCESSING_READINESS.md)。
