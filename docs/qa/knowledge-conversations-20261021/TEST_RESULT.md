# S5C 验证结果

结论：**PASS，S5C 必需验收通过**。验证对象是原问题、上下文关联和持久任务交接；真实聊天、答案质量、问答 UI 和研究助手不在本片。源码与已删除旧枚举路径绑定 [validation.json](validation.json)，交付状态见唯一 [PROGRESS_STATE](../../design/knowledge-first-20261020/PROGRESS_STATE.md)。

| 验收 | 方法与结果 | 证据 |
| --- | --- | --- |
| 并发受理和幂等 | 同键 6 并发只保存一份；不同键同 revision 仅一份成功；旧请求在版本推进后仍找回原轮次 | ConversationPersistenceIT，真实 MySQL |
| 原问题与上下文隔离 | tenant/owner/会话/知识范围约束；只接受中央成功的前序轮次；远程核验不持有数据库事务 | 11 项会话 MySQL IT；真实 HTTP |
| 持久交接和恢复 | 真实中央提交成功后丢弃 HTTP 响应，退出原分派进程、启动新进程，仍绑定同一个任务；旧 epoch 不能落账 | [40 项 HTTP](http-smoke.json)、MySQL IT |
| 有限重试 | 领取争用只有一个赢家；自动 5 次、人工最多 3 次；确定拒绝立即失败；原问题仍可读 | MySQL IT |
| 身份与 JSON 边界 | 独立会话受众；submit/inspect/worker scope 和签名隔离；跨用户/租户拒绝；未知/重复/弱类型/超大输入拒绝 | HTTP、ConversationJsonTest |
| HTTP 资源 | 实际 TCP 验证 2 在途、第 3 个请求拒绝、5 秒完整期限、256 KiB 响应和不跟随重定向；超时后槽位回收 | CentralTaskGatewayTest 4 项 |
| 依赖故障 | 临时进程连接不可达 Tasks 时原问题仍可读、上下文请求 503 且不入库；不可达 DB 时 503、readiness DOWN、安全错误 | HTTP；未停止共享设施 |
| 进程重启 | 最终制品重启后原问题、原 taskId 和中央最终结果保持一致 | [重启检查](restart-smoke.json) |
| Java 回归 | 共 45 项：安全 10、Tasks 17、Gateway 2、会话 16（5 单元/TCP + 11 MySQL）；最终会话修改重新验证 | `.logs/s5c-java-{verify,delivery}.log` |
| Python 协议与恢复回归 | 21 项中央客户端/状态编码映射；11 项真实 PG/中央恢复通过，新 Tasks 保持兼容 | `.logs/s5c-checkpoint-regression.log`；test_task_client.py |
| Runtime | dev/test V1、全部表列注释、重复初始化保留凭据和行数、HTTP/分派/执行池指标 | [运行检查](runtime-checks.json) |
| 依赖 | platform-contracts 为稳定 TaskStatus 复用已解析 jackson-annotations，制品与原 Knowledge jar 中字节相同；无新外部版本 | [依赖差异](dependency-delta.json) |
| Code Hygiene | COMPLETE_WITH_LIMITATIONS，无阻断项；21 条 advisory 已复核 | [卫生检查](code-hygiene.json) |
| 文档与差异 | 本片文档、路径、链接、示例和 diff 检查通过；只涉及本任务文件 | [文档检查](documentation-validation.json) |

45 项按各测试类的当前有效结果去重计算。最终版本在完整 HTTP 联调前补齐执行池指标并将 HTTP 数字码改为现有 HttpStatus；随后重新构建、验证会话全部 16 项、真实 40 项 HTTP 和重启。Python 代码只有状态映射测试路径变化，21 项与实际 PG 11 项均在新的 Tasks 制品上通过，没有重复声称 S5B 的旧源码摘要就是当前摘要。

## 已发现并修复

1. 首次启动和 11 项 IT 因配置类与 Bean 同名失败；重命名过滤链 Bean 后可启动。原日志 `.logs/s5c-persistence-tests.log` 保留。
2. MySQL CHECK 正确拒绝非法租约，但驱动映射为 UncategorizedSQLException；测试改为核对实际数据库 3819/FK 1452 错误码，并确认数据没被修改。未取消约束或放宽产品行为。
3. 真实 HTTP 发现数字 title 被 Jackson 转为 String，违背弱类型拒绝契约。补充 Textual 强转拒绝与回归测试，最终 400。日志 `.logs/s5c-http-smoke.log` 和修复后 `.logs/s5c-http-final.log` 保留。
4. 初次 Hygiene 指出裸 HTTP 状态、诊断吞异常与新模块依赖声明。改用现有 HttpStatus、保留安全的连接错误类型；jackson-annotations 经实际制品复用核验后登记为已批准依赖，无版本升级。
5. 根目录运行 Python 检查时没有识别 ai-runtime 的一方模块分类；从 ai-runtime 目录执行其 Ruff 配置后通过。deploy 脚本显式使用该配置，不改正确的导入顺序来迎合错误工作目录。

## 限制与人工复核

- 未配置 Java formatter/独立静态分析器，使用实际 Maven 编译、项目规范人工检查及 Diff Hygiene；Python Ruff lint/format 均实跑。卫生工具的根级自动发现限制保留，不宣称全套自动静态检查。
- Advisory 001–006 涉及事务：真实测试核验远程调用不在事务内，锁/唯一约束/回滚/幂等与 CAS 有证据；007–013 已是语义化协议常量；014–015 是隔离测试进程/HTTP 的固定等待界限；016–020 是复用 ErrorCode 的会话独立错误目录；021 的 DispatchState 表示交接状态，与中央执行 TaskStatus 职责不同。
- 没有付费模型调用或成本变化：Embedding 累计保守预留仍 0.16/1 元，聊天 0/1 元；不是供应商账单。合成完成结果只用于验证任务交接，不能充当真实答案验收。
- 没有生产 IAM、生产容量/SLA、备份恢复或跨租户公平调度验证；worker 当前绑定部署租户。原问题、中央任务与私密恢复记录保留，不自动清理；数据库 V1 已应用，后续只能追加迁移。
- 同 Agent 独立验证阶段，不冒充第二位审核者；全局技能 Runtime 的既有漂移仍在，本片通过明确的专业 pass 与实际命令留证。

SKILL_HANDOFF：implementation-validation / S5C / COMPLETED / PASS。Progress Owner 可将 S5C 标记 DONE，Git Owner 在当前修订与证据匹配后交付；下一片继续真实问答执行、聊天预算和 React 接入。
