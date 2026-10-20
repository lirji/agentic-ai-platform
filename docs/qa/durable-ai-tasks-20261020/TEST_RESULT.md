# S5A 中央持久任务验收

**PASS：仅 S5A / T01 中央任务部分。** 验证日期 2026-10-20 至 2026-10-21；当前验收对象逐文件摘要见 [validation.json](validation.json)。完整问答、研究与助手尚未完成，PG 联合 fencing 尚未验证。实现后由同一 Agent 单独按 implementation-validation 核对，未声称第三方审查。

| 验收项 | 方法与证据 | 结果 |
| --- | --- | --- |
| 持久创建、同键重放/异参/并发完整性 | 真实 MySQL 唯一键、六线程争用；task/event 同事务 | PASS |
| 领取/heartbeat/过期接管/旧执行器拒绝 | 真实 MySQL SKIP LOCKED、UTC 期限、代次；两个执行者和过期前后边界 | PASS |
| 取消/终态/恢复限制 | 真实取消与完成并发只能一方胜出；有限五次自动领取、三次显式恢复、不可恢复终态 | PASS |
| 事件/指纹/检查点凭据 | 有序游标、重复 delta、同键冲突、指针防倒退、旧 epoch 拒绝、4096 边界保留终态槽 | PASS |
| HTTP 协议与身份隔离 | [38 项 HTTP](http-smoke.json)：三类独立签名、issuer/aud/azp/TTL/scope、tenant/owner、未知/重复 JSON、数字强转/超大正文、网关路由与知识路由回归 | PASS |
| SSE 安全和重连 | 真实 Last-Event-ID 重放；实际 Controller/MySQL 对带换行的恶意文本输出正确 JSON 转义，不生成伪造事件 | PASS |
| 重启/未知结果 | [重启查询](restart-smoke.json)，真实任务进程停止后重启，结果 SHA 与五条事件一致；完成报告重复同键返回同终态 | PASS |
| 依赖故障 | [故障实例](fault-smoke.json)连接不可达数据库，领取返回安全 503、readiness DOWN；共享 MySQL 未停机 | PASS |
| 迁移/账号/注释 | V1 dev/test 已应用；真实 schema 检查全部表列有注释，非法状态/租约组合拒绝；独立应用/迁移账号 | PASS |
| 构建与测试 | `.logs/s5a-java-final.log`，Maven verify 29 项，无失败/跳过；任务模块含 2 unit + 15 MySQL integration | PASS |
| Python Runtime 脚本 | 既有 Ruff 配置检查/格式通过；启动重复与有界退出/故障子进程回收已执行 | PASS |
| 依赖与代码卫生 | [依赖复用](dependency-reuse.json)、[代码卫生](code-hygiene.json)，无新增外部坐标，工具限制如下 | PASS_WITH_LIMITATIONS |
| 文档/源码/交付 | 同目录文档校验与源摘要；正常任务分支提交/合并/推送结果由进度记录 | PASS（Git 以实际回执为准） |

证据日志保留在忽略的 `.logs/`：`s5a-java-integration.log`、`s5a-java-final.log`、`s5a-http-smoke.log`、`s5a-restart-smoke.log`、`s5a-fault-smoke.log`、`s5a-task-fault-process.log`。合成任务和测试行保留，不删除或清空。

## 审查与限制

- 无远程调用进入 MySQL 写事务；所有 mutating 用例先锁中央行、检查身份/epoch/DB 期限再写状态和事件；完成重放额外核对原 worker、epoch、操作键与结果指纹。公共查询只返回所有者范围数据，worker 只有授予的 tenant。
- S5A 不新增 Java 线程池、MQ 或业务调度器，SSE 按最多 100 条快照关闭，客户端带游标重连。此处已验证持久流式事件协议，未声称浏览器已接上聊天流或达到高并发 SLA。
- Code Hygiene `IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS`，没有阻断项。仓库没有 Java formatter/static analyzer；根发现器不能自动发现 Python 子项目配置，Runtime 脚本已用现有 `ai-runtime/pyproject.toml` 的 Ruff 单独检查。事务/数值 advisory 已人工核对：事件上限/分页/租约/重试为冻结协议，测试端口/等待时间为有界本机配置；不新增检查器或关规则。
- 新模块 96 个第三方运行 jar 与已审查知识/网关制品逐字节一致（内部平台 jar 不计），沿用原依赖审查；没有声称新做全生态零漏洞扫描。Spring 6.2.19 公告仍保留，Boot 3.5 后续企业补丁来源与生产维护未落实。
- 新 SSE 路径返回 JSON 编码的文本事件，不使用视图片段、SseEmitter 或视图渲染；恶意换行已测试。官方 CVE-2026-47890 的“通过 SSE 发送视图片段”条件当前不成立。[Spring 公告](https://spring.io/security/cve-2026-47890/)
- 真实上下文没有 XsltView/XsltViewResolver；任务 jar 不含 WebFlux，Gateway 不含 MVC，未满足另外两项公告的当前组合条件。此结论只适用于已核查路径，后续引入视图/混合部署必须重审。[XsltView 公告](https://spring.io/security/cve-2026-47884/)、[WebFlux 功能端点公告](https://spring.io/security/cve-2026-47892/)
- 模型调用 0 次；Embedding 原累计 0.16 元保守预留保持不变，聊天仍 0 / 1 元。旧 deepseek-chat 停用，替代模型选择尚待答复；不把等待选择解释为允许换模型。
- PostgreSQL saver、孤立准备写、凭据恢复、新旧执行者并发、中央完成/PG 收尾失败与真实模型恢复必须在 S5B 验证。当前凭据 API 不能单独证明端到端 fencing。

## 失败与修正记录

1. 首次对 Runtime 脚本运行 Ruff 时错误继承工作区外全局配置；改用仓库已存在的显式配置后，只修正本次新增文件的 unused import/行长。没有弱化项目检查。
2. 初次 Code Hygiene 指出输入 UUID catch 路径形式上吞异常、测试 token 类型使用裸值；已将错误明确映射并使用任务服务身份枚举。
3. 枚举检测又误把测试用 TokenKind 与忽略 `.venv` 内 pytest TokenType 判重复；采用更准确的 TaskServiceRole 命名后再次执行 Gate 通过，没有改全局工具或禁用规则。
4. 审查数据库故障时补齐事务建立失败的 503 映射，并使用独立故障进程实际证明；SSE 公告复核补充恶意换行及真实上下文测试，最终 29 项 Java 全通过。
