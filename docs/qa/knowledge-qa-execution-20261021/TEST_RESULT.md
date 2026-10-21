# S5D 问答执行验收

当前结论：**PASS；S5D DONE**。2026-10-21 用户批准 `deepseek-flash` 并更新 key 后，独立 LiteLLM、真实问答/追问、无资料拒答、引用、浏览器与重启保持预算均通过。S5E 界面已另行交付；研究/只读助手和生产环境尚未验收。

基线 `865aaa4ba1e0f50c91e2b206385fd78fdc027ee1`，分支 `feat/knowledge-qa-execution-20261021`。当前 35 个源码/配置/测试文件摘要 `7555d13658b6bab84132308d400657971859387d98a57aa792ff99dd94694e94`，逐文件及命令证据见 [validation.json](validation.json)。实现和 QA 为同一 Agent 分开的 pass，不声称独立审查员验证。

| 验收 | 结果 | 证据与含义 |
| --- | --- | --- |
| K05 身份与权限边界 | PASS | 39 项 Java；21 项 Python 知识单元；20 项真实 K05/Gateway HTTP，[knowledge-runtime.json](knowledge-runtime.json) |
| 图/上下文/引用 | PASS | LangGraph + LlamaIndex 原生执行；严格范围/owner/上下文、未知引用/证据漂移、空证据不生成 |
| 流/累计预算 | PASS | 51 项文件锁/协议测试；发送前预留并 fsync；损坏/缺失/耗尽拒绝，未知返回不退款 |
| 真实 PG/中央恢复 | PASS | 原 5 项真实执行集成与 11 项旧恢复；检索后/生成后接管、最终重验、取消、预检/SIGTERM，原全量 263 passed / 20 skipped |
| worker 修正回归 | PASS | 17 项相关复验，`.logs/s5d-worker-final.log` |
| 最终 Python/代理回归 | PASS | 250 passed / 36 skipped，`.logs/s5d-python-proxy-final.log`；36 项独立真实环境测试已有通过证据，相关业务源码摘要未变 |
| 独立 LiteLLM | PASS | 1.106.0 固定 wheel/lock；123 个注册依赖 OSV 无命中；[制品记录](litellm-artifact.json)、[依赖记录](proxy-dependency-audit.json) |
| 真实代理边界 | PASS | 3 项实际代理测试：deepseek-flash 路由、关闭思考、成功流、503 只一次发送、拒绝未知模型与未认证、日志无正文/密钥 |
| 实际提供方与业务 | PASS | [real-model-smoke.json](real-model-smoke.json)：2 次探针、2 次真实生成；回答/关联追问均有可读取引用，空资料不增加聊天调用；3 个中央任务 SUCCEEDED |
| 页面验收 | PASS | [browser-real-model.json](browser-real-model.json)：Chrome 查看真实两轮答案、引用原文，刷新保持，无控制台 error；S5E 既有响应式与故障矩阵继续有效 |
| 重启/重放 | PASS | [restart-replay.json](restart-replay.json)：正常停止/重新初始化/启动，重放原任务，无新模型请求，配置及两个预算摘要不变 |
| 格式/静态/构建 | PASS（限范围） | Ruff 59 文件、mypy 37 源码；原 sdist/wheel 与 Java 构建源未变，沿用 `.logs/s5d-python-build-final.log` / `s5d-knowledge-verify.log` |
| Code Hygiene | COMPLETE_WITH_LIMITATIONS | `.logs/s5d-code-hygiene-delivery.log`；22 条建议已审，无阻断项；Java formatter/独立静态分析缺失 |

## 真实结果与费用

真实会话 `af492f07-e132-47ca-83d6-b4186c7b4ccc` 使用已发布的合成《知识资料处理指引》。回答正确区分“上传受理”和“双索引均成功后发布”；追问说明沿原文件重试，复用准备结果与已成功索引；引用版本 1、原文 UTF-8 字节 0–859，已通过 API 和 UI 重新核对。空资料返回标准 NO_EVIDENCE，无引用、不调用聊天。

聊天独立账本累计 **4 次、0.092600 / 1 元**；Embedding 累计 **20 批、0.20 / 1 元**。这些是发送前保守预留，不是实际账单；没有查询提供方账单。2 次提供方探针均保留预留；首个已成功但测试断言错误，见下文。代理自算 cost 未作为人民币账单导出。计价按 2026-10-21 官方峰值输入 2 元/百万、输出 8 元/百万，客户端另加字节上界和 2 倍余量。[官方价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)

## 失败与修正

- 原异步标记与类型问题按既有 asyncio.run/strict 基线修复；没有新增测试插件或放宽校验。worker 的轮询超时由空 pass 改为明确下一轮，17 项复验通过。
- 根级自动发现 Ruff 不完整，实际统一在 ai-runtime 使用 pyproject.toml 检查 src/tests 与 5 份部署 Python 文件。Java 沿周边风格和编译/真实回归，未宣称全仓静态分析通过。
- LiteLLM 未认证请求原报 500，原因是官方代理异常分类导入了未安装的可选 prisma；独立环境补充固定 0.15.0，不启用其数据库、不修改供应商代码。认证拒绝断言未放宽，最终 3 项边界测试通过。失败日志 `.logs/s5d-proxy-integration*.log` 保留。
- 首个真实探针 HTTP 200、预期文字、正常 stop/DONE 已返回，但测试错误地要求 SSE model 含提供方名称。实际返回逻辑名 chat-default；认证 /model/info 已确认唯一 deepseek/deepseek-flash 路由。保存首次已完成观察并保留费用后，修正为分别验证逻辑别名与真实路由，再做第 2 次探针。没有重复发送未知结果，日志 `.logs/s5d-real-model-smoke.log` / `s5d-real-model-smoke-final.log`。
- Knowledge 旧进程曾因运行中 jar 被重打包发生 Logback 关闭错误；确认监听退出后正常重启。后续构建前先停对应服务，未强杀、清库或重写迁移。

## 证据复用与限制

原 27 个源码/配置/测试文件中，26 个与已通过的恢复/K05/Java/构建证据摘要完全一致；仅 deploy/local.py 增加显式聊天进程命令，由本轮初始化/正常重启/状态检查覆盖。新增独立代理锁、日志、管理入口与 smoke 另行验证。最终 250 项和前一轮 263 项有重叠，不把二者相加作为测试总量。

22 条 Code Hygiene 建议包含 18 条已命名协议常量或声明式字段上限、3 条验收脚本时间/分页常量，以及 1 条误将隔离 proxy site-packages 的 EventType 与平台 TaskEventType 对比；无需复制或重命名中央协议。Java formatter/独立静态分析不存在；全局技能 Runtime 漂移未修复，本验收保留手工专业 pass 证据。

本机服务与 local-dev 单在途问答 worker 保持运行。正式真实任务为 3 SUCCEEDED，另有旧 UI 任务 1 CANCELLED；无在途付费请求。`.local/` 私密配置、预算、中央/PG恢复记录必须保留；临时真实集成租户不会被 local-dev worker 领取。停止顺序 stop-qa → stop-chat，关闭不撤销已发请求或费用。

仅验收少量合成资料，不推断普遍答案质量。完整快照仍只支持根图；生成成功到检查点之间崩溃可能再次付费，预算不会重置。不支持子图/历史回放/删除，生产 IAM、容量、备份恢复及长期清理未验证。研究/只读助手按下一切片冻结工具与报告契约后继续。
