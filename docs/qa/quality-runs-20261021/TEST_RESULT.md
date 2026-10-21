# S8C 运行、恢复与逐题报告验收

2026-10-21。必需验收 **PASS**；源码摘要与验证收据见 [validation.json](validation.json)。当前 Agent 实施后执行验证步骤和截图复核，没有启动子 Agent，不冒称独立人员审查。准确交付状态以 PROGRESS_STATE 为准。

| 验收 | 结果与证据 |
| --- | --- |
| E02-EXEC-01：幂等与凭据 | PASS。Knowledge 6组真实 MySQL 验证并发同键、交接未知后取消、候选不可变/旧代次隔离、正确终态凭据与单调投影、失败前缀恢复、交接租约耗尽、归属与绑定。Tasks 新4组验证 proof epoch、失败重放和公共恢复绕过拒绝 |
| E02-EXEC-02：真实恢复与取消 | PASS。实际 Gateway/Knowledge/Tasks/MySQL/PG 3组：成功前缀后失败、显式恢复仅执行剩余题；完整图快照提交后接管只重建当前代次凭据；生成等待期间取消，不提升迟到结果。检索/聊天为确定性替身，引用仍读真实 K05 |
| 失败窗口补验 | PASS。新增 resumeRequested 与恢复窗口取消冲突后，6组 DB 和受影响真实失败恢复1组复验；报告清单条件新增后，精确清单1组 DB 与真实 HTTP 409/200 通过 |
| E02-METRIC-01 | PASS。10项图/指标单元覆盖分级公式、固定Top5、空召回0、无答案不适用、模型错误不计零、每题事件/费用键隔离、金标不进入提示、候选保存结果未知不覆盖 |
| 独立角色与有界输入 | PASS。3组真实身份 HTTP 验证错误 issuer/aud/azp/scope/TTL/worker、旧 K05 密钥、公共只读权限、严格 JSON、256/128KiB 超大输入；沿原错误目录返回 |
| 传输过载与期限 | PASS。3项真实本机HTTP测试：4个在途请求占满时第5个立即拒绝，结束后容量恢复；已发送响应头但正文停顿仍在5秒期限终止；超256KiB chunked响应拒绝，重定向不跟随且不自动重试。日志 `.logs/s8c-transport-bounds-verified.log` |
| 用户操作与可恢复性 | PASS。7组浏览器覆盖真实失败报告/原任务恢复、原受理响应丢失后刷新核对及取消、空召回/无答案、引用侧栏、正文读取失败清除缓存、恢复 pending 轮询、清单摘要读取。首轮真实写操作通过后按原记录复验，不额外制造同一未知命令 |
| 视觉与键盘 | PASS。实际查看1440/1024/414px运行报告及414px引用侧栏；桌面回答/标准并列，窄屏纵向、指标与按钮可读、页面无横溢出；键盘开关引用/Escape焦点回到入口，返回保留用途筛选 |
| 构建/静态与回归 | PASS。原 Knowledge26单元、security10；新Tasks4与原Tasks17 DB；Python282通过/47条件跳过，mypy46源码通过，Ruff格式/lint通过。前端50单元、TypeScript/Vite、ESLint/Prettier通过。传输补验单独记录，不混入旧计数 |

主要后端日志：`.logs/s8c-task-proof-verified.log`、`s8c-run-persistence-refinement.log`、`s8c-manifest-guard.log`、`s8c-evaluation-real-initial.log`、`s8c-evaluation-resume-refinement.log`、`s8c-evaluation-identities.log`、`s8c-python-regression.log`。前端：`.logs/s8c-e2e-initial.log`、`s8c-e2e-final.log`、`s8c-final-{build,unit,lint}.log`。原失败日志保留，没有把首次失败省略为全程通过。

Tasks V3、Knowledge V5 均在独立 dev/test 应用；旧迁移未改写。确定性测试期间正常停止本机模型 worker，检查无排队/运行评测后恢复。累计账本仍聊天0.510952/1元（14调用）、Embedding0.26/1元（26调用），本片新增付费调用0。测试运行编号只保存在忽略文件，不包含凭据；未清理历史数据、账本或工作树。

限制：当前报告不包含未被提供方返回的 usage/账单，明确显示未知；人工判断尚未评审，不能凭合法引用判答案正确。Java 无专用 formatter/静态分析，代码卫生保留相应限制；前端原共享分块大于500KB警告仍在。没有完成生产IAM、负载SLA或灾备验证，也没有作这类承诺。S8D基线/比较与S8E真实机制质量仍需继续。
