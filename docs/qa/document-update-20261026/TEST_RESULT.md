# S10B 验收结果

状态：**PASS，本地实现与验收完成，Git/CI 交付另见 delivery-result.json（生成后）**。验证对象为 [validation.json](validation.json) 记录的双仓源文件摘要；本报告不把旧 S9D 的 PASS 当成本片验收。

| 验收 | 结果 | 本次证据 |
| --- | --- | --- |
| LC-01/02 身份分离、约束迁移 | PASS | Knowledge V10 / Tasks V4；真实 MySQL、准备 schema 与 DB 约束测试 |
| LC-03 更新竞争、原键恢复、异参冲突 | PASS | `KnowledgeLifecycleIT`；真实浏览器 202、原键查询/关闭写入后的原键重放 |
| LC-04/05 准备或单 sink 失败保留旧发布、CAS 原子切换 | PASS | 6 项 Lifecycle IT；真实 S3/ES/Qdrant；浏览器失败 v2 保留 v1，成功 v3 当前发布 |
| LC-06 旧任务/旧执行者不得覆盖 | PASS | 旧意图 retry 409、无有效 lease/基线拒绝；实际 HTTP 旧 v2 retryable=false 且显式重试409 |
| LC-07/08 代次金标、旧 profile 可读 | PASS | 新 P05 10 个代次结果及旧金标两语言测试；真实旧 v2/v3 来源浏览器回归，无新增模型调用 |
| LC-09 接受后的准备包复用与恢复 | PASS | 真实组件 IT 验证单 sink 失败后复用准备，不重新调用模型；11 项真实 PG/MySQL/HTTP 恢复测试 |
| LC-10 当前版本引用 | PASS | 发布后 K04/K06/K05 旧引用404、新引用200，K08 分别 false/true |
| LC-11 历史 QA/研究/助手、SSE 与上下文 | PASS | 3 类真实 Tasks 在发布前可读、发布后404；原结果摘要不改；Conversation 拒绝失效上下文409且没有新轮次；恢复前失效时模型0调用 |
| LC-15 权威故障关闭 | PASS | Knowledge 停机时三个任务 GET/SSE 均503，服务恢复后继续判权；前端404/503清除正文并能重新查询 |
| LC-16 锁序、并发与期限 | PASS | 真实 DB 竞争/CAS/lease；K08 真实 TCP 在途上限、超时、超大响应、无重试验证 |
| LC-20 旧数据迁移 | PASS | 54资料/54任务/62块原字段摘要不变、真实 S3 逐份校验、旧金标不改；回填期间模型账本不变 |
| LC-21 关闭新命令仍能读取 | PASS | create/generation/updates=false 时 P05 当前引用可读、旧引用拒绝；原键回执与重放不重新处理，随后恢复开关 |
| LC-24 更新、失败保留、未知响应、409与窄屏 | PASS | 77前端单元测试；16浏览器验收；1440/1024/414表单/失败/发布及旧来源截图实际查看，未出现页面横向溢出 |

## 检查汇总

- Java：`mvn -B -ntp -Pintegration verify`，138 单元 + 102 集成测试通过，所有模块构建成功。执行时旧服务已停止，未覆盖活动 JAR。
- Python：394 通过、51 个需显式环境的测试跳过；本片另显式运行 11 个 QA/工作真实恢复测试，全部通过。ruff format/check、mypy 49 个源文件通过。跳过项不计通过。
- React：77 项单元测试、类型、格式、ESLint、生产构建通过；13 项问答/更新/来源回归 + 3 项研究恢复/分页/未知响应浏览器测试通过。
- Runtime：迁移、K07/K08 身份/输入/owner 边界、真实发布与历史读取、停机503、关闭写入兼容、新 worker 启动通过；无额外模型质量结论。
- 文档/差异：6 个登记契约、10 个共享金标文件检查；源文件指纹、文档链接与 `git diff --check` 证据随 validation.json 登记。

日志位于两仓忽略目录 `.logs/`：后端 `s10b-java-full-recheck.log`、`s10b-python-*-delivery.log`、`s10b-restore-it-recheck.log`、`s10b-http-*.log`、`s10b-command-gates.log`、`s10b-conversation-context.log`；前端 `s10b-ui-final-*.log`、`s10b-browser-final.log`、`s10b-work-browser-recheck.log`。前端截图及观察见其 `docs/qa/document-update-20261026/TEST_RESULT.md`。

首次失败与修正见[实现证据](IMPLEMENTATION_EVIDENCE.md)：评测5项旧夹具、PG4项旧公共事件预期、Vite代理与 UI 流组件生命周期；修正后受影响检查已重跑。当前没有必需项 FAIL/UNVERIFIED。

限制：未执行生产部署或容量测试，真实业务语料/共享身份/保留要求仍沿 U01/U03/U06/U07；撤下删除、同版重索引、去重改造属于后续切片。现有 Vite 大块提示及 LangGraph 测试警告保留，不将其写成新功能失败或已解决。原 MULTI_CLIENT_DRIFT_BLOCKED 治理现状未改变，本次沿批准的手工专业 pass，不冒称独立 Agent 或机器 Runtime 认证。
