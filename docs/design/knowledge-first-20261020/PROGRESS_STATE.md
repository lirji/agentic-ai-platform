# 独立知识平台准备进度

## 当前任务：研究分析与只读助手

- 标识：`research-readonly-20261021`；用户已授权开始剩余研究与只读助手，继承P2、原模型/预算及正常合并推送main授权；无生产部署/清理。
- 状态：S6 DONE / S7 DONE，必需验收PASS；两仓本地提交完成，正在正常Git交付。研究报告、解释/清单/草稿、空证据真实模型均PASS；292项Python、78项Java、38项前端单元、15组联合浏览器通过。窄屏未知提交提示改为说明下方操作后，7组相关浏览器/38项单元/构建/静态复验PASS，截图已实际核对。
- 计划：S6受理/执行/恢复 → S6真实验证 → S7界面/引用 → 联合验收、文档及两仓正常合并推送；前三项已实现，第四项收尾中。契约 [WORK_TASK_CONTRACTS](WORK_TASK_CONTRACTS.md)，证据 [TEST_RESULT](../../qa/research-readonly-20261021/TEST_RESULT.md)。
- Git：后端原目录，分支 `feat/research-readonly-20261021`，起点 `cb2f688`，S6本地提交 `8cf26a6`；前端原目录 `../agentic-ai-platform-web` 同名分支，起点 `f68e660`，S7本地提交 `5835332`。两仓实现均已本地提交，后端仅验收/进度收尾待提交；已fetch远程main并核对无分叉，无额外worktree；修改清单可从各仓git status与实现映射恢复。
- 决策：中央任务拥有不可变请求和结果；Deep Agents analyze为有界原子阶段，内部checkpoint关闭，外层JSON根图复用原PG/中央fencing，不放宽namespace/序列化。仅搜索/读证据，3轮模型/5次工具/2次额外搜索，单worker顺序领取两图；恢复可能再次计费。
- 验证：真实MySQL/HTTP幂等、分页、隔离；4项真实PG/中央恢复/取消/撤权；五个真实模型案例、引用和重启重放通过。首次多工具响应拒绝及原任务保留；串行工具执行、网关备用错误Mono污染SSE头、前端缺CHECKPOINT事件及提交摘要竞态已修复并回归。
- 预算：沿原两本累计账本，聊天 **0.510952/1元（14次）**，Embedding **0.26/1元（26批）**，非账单；重启配置/账本摘要不变，新增调用0。探针已完成不得重发；`.local/s6-work-smoke.json` 与前端 `.local/s7-ui-state.json` 必须保留。UI真实任务CANCELLED/attemptCount=0。
- 当前服务：Gateway28080、Knowledge28084、AI28085、Web28086、Tasks28087、Conversations28088、LiteLLM28089及worker运行；local-dev无QUEUED/RUNNING。无运行中的构建/测试/付费任务或迁移；最终日志 `.logs/s7-work-final.log`（前端）及 `.logs/s6-*`。
- 新核对：终端默认Node24.12与项目.nvmrc/engines要求24.22不符，已保留现有通过证据，官方Node24.22.0已完整下载并通过SHA-256核验，装在前端忽略目录 `.local/toolchains/`；前端服务已切至该版本，38项单元/构建/静态/15组浏览器均PASS（`.logs/s7-node24-*`）；不修改全局Node或项目版本要求。
- 下一步：两仓按后端→前端顺序fast-forward合并并push main，核对远程HEAD并记录交付收据。源码摘要后端29项/前端18项与暂存区一致，文档链接/真实密钥扫描PASS。QA在后端 `docs/qa/research-readonly-20261021/`、前端 `docs/qa/s7/`。
- 目录：无额外worktree。后端 `.local/` 凭据/账本/任务状态、前端 `.local/` 恢复状态与当前运行Node工具链需保留；`.idea/` 原用户文件保留。`.logs/`、target/dist、测试/编译缓存及下载压缩包可在不需复现时清理，本次无清理授权、未删除。
- 限制：Java未配置独立格式器，后端卫生COMPLETE_WITH_LIMITATIONS（17建议已人工审）；Deep Agents无内部saver的已知durability警告；现有Vite包大小警告。少量合成资料/单worker验证不等于生产容量或系统性答案质量。
- 规范：session-handoff、continue-approved-delivery、implementation-slicing、backend/frontend-implementation、runtime-and-deploy、implementation-validation、update-progress-docs、task-git-delivery；全局业务建模/持久化/契约/并发安全/容量/发布及日志规范。手工专业pass，不冒称机器Runtime Gate、独立Agent审查或用户视觉签收。

## 历史：持久 AI 任务与知识问答

- 标识：`knowledge-qa-execution-20261021`；继承已批准的知识、问答、研究及只读助手路线。本轮模型/密钥更新及 S5D 真实问答已完成交付，完整平台尚未完成。
- 状态：S0–S5C、S5E 已交付；S5D 真实模型、问答/追问/空资料、浏览器引用与刷新恢复均 PASS，最终 250 项回归与代码卫生通过，已正常合并推送并核对远程 main。权威验收：[TEST_RESULT](../../qa/knowledge-qa-execution-20261021/TEST_RESULT.md)，当前结论 PASS。
- 用户最新决定：2026-10-21 明确使用 `deepseek-flash`，随后更新 API key。新 key 已替换本项目忽略配置的 providerKey，0600；不在文档记录凭据。Embedding 与聊天分别沿用 1 元累计上限，跨日期、重启和恢复不重置。
- Git：后端原目录 `/Users/liruijun/personal/LLM/agentic-ai-platform`，分支 `feat/knowledge-qa-execution-20261021`，起点 `865aaa4ba1e0f50c91e2b206385fd78fdc027ee1`；S5D 实现提交 `2a95d3fa9b92756e6470f76abb89f14b33bf789e` 已正常合并推送并核对。S5C 交付收据单独提交 `37a41fa`，未混入产品实现。当前仅本片交付记录收尾，最终文档提交以 Git 为准。没有额外 worktree。
- 前端：原目录 `agentic-ai-platform-web`，main/origin/main `f68e660680e22789e59a3df5ed278e9d48dc1594`，工作树干净。34 个源码摘要与 S5E 验收一致；28 项单元/组件、10 组浏览器和 10 张多尺寸截图已验收。[交付收据](../../qa/knowledge-qa-execution-20261021/frontend-delivery.json)。本轮无前端代码修改。
- 已实现：K05 独立只读身份；LlamaIndex 授权检索；LangGraph `retrieve → generate → verify`；中央事件/租约/失败与完成前权限重验；同范围前序回答；独立累计预算和单在途 worker。既有 V1/PG 迁移不改写。
- 代理：独立 LiteLLM 1.106.0 与 Prisma 0.15.0（官方可选依赖，仅错误分类，不启用数据库），锁 `deploy/model-proxy/uv.lock`；123 个注册依赖 OSV 无命中，不能推断全部依赖零风险。`chat-default → deepseek/deepseek-flash`，thinking disabled；重试/回退/正文日志/外部追踪关闭。真实代理成功流、失败只发送一次、未知型号/未认证拒绝和日志脱敏 3 项通过。
- 真实验证：新 key /models 200；2 次提供方探针、2 次真实问答生成，3 条业务任务 SUCCEEDED。首个探针已成功返回，但测试误将逻辑别名当成提供方型号而失败；核对已完成响应与认证路由后修正断言，保留两次预留，没有重发未知结果。最终 [real-model-smoke.json](../../qa/knowledge-qa-execution-20261021/real-model-smoke.json) PASS。
- 真实链路：答案与追问引用均可重新读取原文，空资料返回 NO_EVIDENCE、聊天调用增量 0。已在 Chrome 实际查看两轮回答、引用侧栏与刷新后持久结果，无控制台 error；前端响应式验证沿用未变更的 S5E 证据。
- 额度：Embedding 20 次累计保守预留 **0.20 / 1 元**；聊天 4 次累计保守预留 **0.092600 / 1 元**。这是本地保守预留，不是提供方账单。价格按 2026-10-21 官方峰值未命中输入 2 元/百万、输出 8 元/百万，再用字节上界和 2 倍余量。
- 重启：先正常停 worker/代理，再重复初始化并启动，原 3 个任务只读重放；私密配置和两个预算文件摘要保持不变，新增模型调用 0。[restart-replay.json](../../qa/knowledge-qa-execution-20261021/restart-replay.json)。
- 验证基线：原 263 项 Python（含 5 项真实执行恢复、11 项旧恢复）、39 项 Java、20 项 K05 HTTP 与构建证据保留；27 个原文件仅 deploy/local.py 因新增入口变化，其余摘要仍匹配。新入口已由真实初始化/重启验证。当前 Ruff/59 文件格式与 mypy/37 源码通过；最终 Python/代理回归 250 passed / 36 skipped；源码 35 项摘要 `7555d13658b6bab84132308d400657971859387d98a57aa792ff99dd94694e94`，日志 `.logs/s5d-python-proxy-final.log`。Code Hygiene COMPLETE_WITH_LIMITATIONS，22 条建议已审，无阻断项。
- 服务：Gateway 28080、Knowledge 28084、AI 28085、Web 28086、Tasks 28087、Conversations 28088、LiteLLM 28089；问答 worker 仅领取 local-dev、一次一个任务。停止顺序 `stop-qa` 后 `stop-chat`。当前 local-dev 为 3 SUCCEEDED / 1 CANCELLED，无排队或运行任务。
- 恢复：`.local/s5d-real-model.json` 保存真实验收原幂等键与任务号；`.local/s5d-*.json` / s5b 辅助状态、PG/中央事实、凭据和账本必须保留。真实验收会话 `af492f07-e132-47ca-83d6-b4186c7b4ccc`；运行 PID/log 在 `.local/`、`.logs/`。不删除记录重跑未知操作。
- 下一具体操作：S5D DONE；P1→P2 前置核对已完成，从 [研究准备](RESEARCH_READINESS.md) 冻结下一业务切片和工具/恢复边界。只读助手沿 BRIEF 的资料解释、清单、草稿建议，不接外部写工具。
- 交付：[S5D 收据](../../qa/knowledge-qa-execution-20261021/delivery-result.json)；59 个本片文件、35 个源码摘要与暂存区一致。13 份 Markdown/171 个本地链接检查通过；候选文件及两份运行日志无真实密钥匹配，私密路径和代理环境均被 Git 忽略。没有进行中的构建/测试/付费请求；无新 worktree，`.idea/` 保留，缓存未清理。
- 授权：现有计划本地实施、隔离资源及必要模型联调；验证后正常合并推送 main，无生产部署。没有清理数据/目录、强推或新增多 Agent。
- 规范：backend-implementation、runtime-and-deploy、implementation-validation、update-progress-docs、task-git-delivery；架构业务建模/持久化/契约/并发/容量/发布及个人追踪规范。全局技能 Runtime 漂移仍按手工专业 pass 留证，不修改全局技能，不冒称机器 Gate 或独立审查员通过。
- 限制：S5B 仍只支持根图完整快照，45 秒/32 步；不支持子图/历史回放/删除，不保证物理无孤立行或模型 exactly-once。研究 Agent、生产 IAM、容量、备份恢复与长期清理尚未验证。Java formatter/独立静态分析缺失需在最终验收中保留。

## 历史：S4 知识工作台交付

- 标识：`knowledge-workbench-20261020`；延续“继续后续的实施”，完整目标包含知识资料、问答、研究、助手与独立 React 前端。
- 状态：`S2 DONE / S3 DONE`，S3 必需验证通过，已正常合并推送 main `32bc3b4`；S4 DONE（前后端记录均已交付）；完整平台尚未完成。已冻结 S4，继续知识工作台，不等待再次确认继续。
- 模型授权：Embedding 沿用原 langchain4j-platform 百炼北京账号、text-embedding-v4 / 1024，上限 1 元；聊天沿用原聊天配置，另限 1 元。当前 Embedding 共 16 次保守预留、0.16 元（含 S2，非账单）；聊天未接入、未调用。两个预算独立，凭据只进忽略配置，不迁入旧语料。
- Git：原目录 `/Users/liruijun/personal/LLM/agentic-ai-platform`；分支 `feat/knowledge-workbench-20261020`，HEAD `2ac00ce`；S3 交付记录已单独提交，当前未提交为 S4 设计/进度与验收指针，无新 worktree。用户授权验证后按逻辑单元提交、正常合并推送 main，无生产部署。
- 已完成：Java 持久租约 worker、不可变准备复用、双索引确认后原子发布、失败 CAS 重试、授权检索与引用；Python 持久预算护栏；本项目隔离 Qdrant/ES、版本和中文分析插件；loopback S3 关闭错误的代理继承。
- 验证：[S3 TEST_RESULT](../../qa/knowledge-publication-20261020/TEST_RESULT.md)；Java 41 项（ES 8.19.23，`.logs/s3-java-tests-upgraded.log`）、Python 113 项、Ruff/mypy/构建通过；真实网关/模型/数据库/对象与索引完整链路、Knowledge 真重启、权限隔离及故障恢复通过。源码摘要见同目录 validation.json；Code Hygiene 限制与历史失败保留。
- 迁移：V1/V2/V3 已在 dev/test 应用，禁止改写。准备包、索引和发布事实仍由 Java/MySQL 决定，Python 不直写业务存储。worker 本机仅领取 local-dev。
- 运行：Gateway 28080、Knowledge 28084、AI 28085 均运行，readiness UP；Qdrant 1.19.2（26333）、ES 8.19.23 smartcn（29200）。ES 官方制品下载失败已通过分段下载及 digest 校验恢复，构建升级与新增真实资料查询通过；无运行中下载、迁移、模型或测试任务。PID/log 在 `.local/` / `.logs/`。
- 保留：`.local/` 中运行凭据/签名密钥/预算/恢复编号必须保留；搜索快照在本项目卷和 `.local/search-backups/20261020-prepatch/`，未做恢复演练；OCI 缓存和诊断目录可经授权清理，本轮不删。旧平台与 `.idea/` 未改。
- S4 结果：独立前端目录 `/Users/liruijun/personal/LLM/agentic-ai-platform-web`，分支 feat/knowledge-workbench-20261020；已提交推送私有远程 main `51dc652f1734586d8fb0fe38e1175f3d246622b9`，工作树干净。44 个源码/配置/测试摘要 `899d20ccdf823a8fc3a54de2ba89a52d6bd6b195a81865cae2d0ab2b90c8cf31`。strict 构建/类型、ESLint/Prettier、8 项单元/组件、4 组 Chromium 真实/故障测试与 Code Hygiene 通过；1440/1024/414px 截图已查看。证据在前端 docs/qa/s4/TEST_RESULT.md。
- S4 本机：前端 127.0.0.1:28086 已运行，前端 .local/web.pid 与 .logs/web.log；固定 workbench-user 开发身份，浏览器不接收令牌。3 份合成资料都已发布，其中一份来自夹具格式化改变内容，已保留并排除自动格式化，未删除数据。原生上传重放、检索及引用通过；最后 E2E `.logs/e2e-complete.log` 4 passed，无运行中测试/模型任务。
- 下一步：完成后端 S4 文档提交（前端私有远程已核对）；随后冻结 Q01–Q03/T01 和 S5，接入独立 LiteLLM、PG 检查点和聊天预算。业务助手暂无额外场景答复，采用 BRIEF 建议的只读资料解释/清单/草稿辅助，不接外部写工具。完整问答、研究与助手尚未实现。
- 采用规范：continue-approved-delivery、backend-implementation、runtime-and-deploy、implementation-validation、update-progress-docs、task-git-delivery；全局架构业务建模、持久化、契约、并发安全、容量和发布治理。沿用手工专业 pass，未改全局技能 Runtime 的已知漂移。

## 历史：S1 知识入库受理

- 标识：`knowledge-ingestion-20261020`；用户最新指令“开始执行吧”，沿已确认路线连续完成契约、切片、实现、验证、进度与正常 Git 交付，无需逐步询问继续。
- 目标：先形成独立网关、知识领域服务与项目级真实存储上的资料受理/查询路径，再按依赖推进真实向量处理及 React；没有实际完成的业务不标 DONE。
- 当前状态：`S1 DONE / 后续等待必要模型输入`。26 项 Java 测试、45 项真实 HTTP（含重启）、5 项真实依赖故障与重复初始化检查通过；[S1 TEST_RESULT](../../qa/knowledge-ingestion-20261020/TEST_RESULT.md)已完成。全平台目标尚未完成，不把资料受理当成处理/发布。代码卫生工具限制和现存 Spring 公告适用性明确保留。
- Git：`/Users/liruijun/personal/LLM/agentic-ai-platform`；任务分支 `feat/knowledge-ingestion-20261020`，起点 `b026187e44025709d768613e3136a469ca0d579c`。实现提交 `05d17791e60c6fa98735a17c2d1202d2d28e262c` 已正常 fast-forward 合并并推送 `origin/main`，`ls-remote` 核对一致；[交付记录](../../qa/knowledge-ingestion-20261020/delivery-result.json)。文档收尾沿同一任务分支提交后回到 main，最新文档提交以 Git 为准。
- 初始边界：共享 dev-infra 实例可复用，使用新项目数据库/账号/桶；不连接旧业务服务，不修改旧业务数据，不将 hash 作为默认或回退。Java 采用用户指定 JDK 21 / Boot 3.5 系列，具体补丁及维护风险按官方资料核验后登记。
- 明确实施假设：首条资料路径先按上传者本人可见，租户和用户取自验证后的身份；共享/部门授权不按请求头或 UI 值自动放开。上传受理不代表解析、向量化或发布完成。
- 已建立环境：JDK 21.0.11、Maven 3.9.12；共享 dev-infra 上新建 `agentic_knowledge` / `agentic_knowledge_test`、各自 app/migrate 账号和 `agentic-ai-knowledge` / `agentic-ai-knowledge-test` 桶。随机凭据/独立密钥仅在忽略的 `.local/`（0600）。V1 已应用，后续不得改写此迁移。
- 任务清单：①源代码迁入闭包及契约；②有界 S1 与人工一致性检查；③Java/网关与受理/查询实现；④首次需要的真实存储运行配置；⑤独立验证与交付；⑥按剩余依赖继续。
- 下一步：[处理与发布准备](PROCESSING_READINESS.md)已核对状态机/worker/准备结果参考源码并列明下一片条件。已异步询问是否沿用 langchain 的百炼账号/地域、联调预算上限 1 元人民币；必要答复未到前不调用模型或假定跨地域传输许可。答复到达后落实私密配置与额度检查，冻结准备/发布 schema、建立下一 S-ID，再实现真实处理与检索。React、问答、研究和助手仍为后续路线。
- 验证记录：最终 54 个源码/配置文件摘要 `e9f8f566e697d98ff0fd1a1224af46f868f8cc63900144bbfc498e9e7ee02110`，证据在 S1 QA 目录；`.logs/s1-final-verify.log`、`s1-smoke-final.log`、`s1-fault-smoke.log`。早期编译、TIME_WAIT、Ruff/卫生失败均修复后通过，保留记录。原 S0 Python 源码未变，根忽略规则已变，不沿用旧全文件摘要作为当前版本证明。
- 依赖：初始 17 个包命中已修复至 2 个 Spring 包/3 个公告；真实制品与上下文表明当前未启用受影响组合，详细适用性及官方修复路线见 TEST_RESULT。没有宣称零漏洞；Boot 3.5 商业补丁来源/后续维护、生产 IAM、容量与模型质量仍待落实。
- 运行中任务：网关和知识服务均运行，PID 记录 `.local/{gateway,knowledge}.pid`，日志 `.logs/{gateway,knowledge}.log`。隔离故障进程已正常退出；没有后台构建、模型或迁移任务。旧平台已有容器保持运行，不停止或替换。
- 收尾：16 份变更 Markdown、114 个本地链接/锚点、389 行表格通过；54 个暂存源码摘要与验收一致，78 个任务文件已交付，实际本机凭据/私钥未进入暂存。只有原工作目录，无额外 worktree；两个参考仓库仍干净。`.local/` 保存凭据/密钥及资源所有权必须保留，`.idea/` 为用户原文件；`.logs/`、各 `target/`、Ruff/构建缓存可在无需复现时清理，本次未删除任何目录或业务数据。
- 采用规范：沿已有架构与技术选型；已读个人 Java/网关配套及架构业务建模、契约、持久化、并发安全规范，按实现触发补读技能专题。技能 Runtime 的已知全局漂移不擅自修复，继续保留手工路由与真实验证证据。

## 历史：S0 任务目标与完成记录

- 当前任务：`knowledge-runtime-foundation-20261020`；承接已交付的 `knowledge-first-baseline-20261020`，完成实施准备并开始首个可验证代码切片。
- 初始范围：更新准备与设计文档、核对参考资产、验证并交付文档。
- 用户最新授权与活动目标：完成本部分后继续后续实施准备并开始 Coding。按既有架构推进 React 设计、正式契约、一致性审查、实施切片及首个可验证代码切片；无需再次询问是否继续。完整知识业务的后续切片仍分别记录完成状态，不以开始编码宣称全平台完成。
- 权威入口：本目录 `BRIEF.md`；架构与组件继续原位维护于 `../platform-selection-20261020/`，不建立第二套架构/选型。
- 用户已确认：按需从 `langchain4j-platform` 迁入所需能力；React 前端从零建设；知识入库、知识问答、研究分析和业务助手为优先业务；后续业务参考 AgentScope；模型与数据参考原 langchain 项目。
- 最新补充：不使用默认 HashEmbedding，允许采用参考项目中的其他真实 Embedding。已核对标准 Compose 为百炼 `text-embedding-v4` / 1024 维 / OpenAI 兼容协议，作为新项目设计默认；配置缺失和调用失败不得回退 hash。
- Git 授权：沿用户持续授权，以任务分支提交，验证后正常合并并推送 `origin/main`；不包含生产部署。

## 当前状态

- `COMPLETED`（本批）：准备基线、React 架构、首片 R01/H01 契约与实施切片已完成；S0 开始 Coding 并通过必需验收，代码已交付。完整知识业务、真实模型联调与 React 页面仍属后续路线，不代表全平台完成。
- 工作目录：`/Users/liruijun/personal/LLM/agentic-ai-platform`。
- 任务分支：`feat/knowledge-runtime-foundation-20261020`；代码提交 `1634605c03a685801e3a113e0911229e6e7655c9`，已正常合并并推送 main，`ls-remote` 核对一致。收尾进度记录沿同一分支追加提交后回到 main；最新文档提交以 Git 为准。原有 `.idea/` 保留并忽略，不提交。
- 参考基线：`langchain4j-platform@468cec425dd160e80cfc3ab378d3440a8767e4d5`、`platform-agentscope@7738cb7bedd1a31fd4260aa66b74572ca48d9ade`；只读参考，不修改源项目。

## 任务清单

1. 已完成：核对用户最新决定、历史进度、现有架构、选型与适用技能。
2. 验证通过：固化业务范围、独立运行标准、代码组织规则、资产来源及后续准备顺序；默认百炼 text-embedding-v4 / 1024 维，禁用 hash 业务默认及回退。
3. 验证通过：更新架构、选型、文档索引与来源映射，消除旧前端复用和依赖旧业务部署的表述。
4. 验证通过：11 份 Markdown、66 处本地链接/锚点、357 行表格、39 个不同固定源码对象及历史证据字节一致；修改的 Mermaid 图语法通过，未渲染。结果见 [baseline-validation.json](evidence/baseline-validation.json)；计数对应正文验收快照，进度收尾另查链接及 diff。
5. 已交付：D0 提交 `44ffff5`，已正常合并、推送并核对 `origin/main`。
6. 已完成：`FRONTEND_ARCHITECTURE.md`、`CONTRACTS.md` 与 `IMPLEMENTATION_SLICES.md`。一致性人工审查仅覆盖 S0 / R01 / H01；K/Q/T 业务 schema 仍须在后续业务切片前冻结。
7. 验证通过：`ai-runtime/` Python 包、真实 OpenAI 兼容 Embedding 适配器、有界配置与稳定错误、存活探针和 CLI；根 `.gitignore`。Python 3.12.13、`uv.lock` 已安装锁定；68 项测试包含真实本地 HTTP 进程启动和退出，未调用模型。Ruff、mypy、sdist/wheel 构建均通过。
8. 验证通过：[S0 独立验收](../../qa/runtime-foundation-20261020/TEST_RESULT.md)，源码摘要 `14914ff75803dac28f28ccf97b3f9be08f3b582d4ad046e38d703f4cf7efb57b`，逐文件及命令证据同目录 `validation.json`。27 个当前平台运行/开发依赖漏洞检查无已知漏洞、无跳过，许可证元数据已保存。Code Hygiene 无阻断项，工具发现限制保留。
9. 已交付：文档链接/锚点、表格、围栏与 diff 检查通过，结果见 [文档验证](../../qa/runtime-foundation-20261020/document-validation.json)。暂存区 20 个源码/测试/配置摘要与验证完全一致；仅本任务 37 个文件进入代码提交 `1634605`，已正常 fast-forward 合并、推送并核对远程 main。

## 恢复与边界

- 已选框架和组件沿用；独立性通过迁入所需代码、项目级数据/配置隔离和独立启动验证实现；dev_infra 等共享基础设施按既有约定接入。
- 模型配置与样本只登记来源和安全的结构信息，不读取私密凭据、不把旧运行数据自动导入新库。
- 正式 `CONTRACTS.md` 中 R01/H01 已冻结并支持 S0；K/Q/T 仅登记契约需求，不能按猜测 JSON 编写业务 UI。后续路线与可执行切片明确区分。
- 已读规范：`~/.codex/architecture-standards.md` 的业务建模、持久化、契约、并发安全、中间件、容量与发布章节；`~/.codex/personal-tech-stack.md` 的优先级、Java 路线、前后端分仓与 React 配套。
- 技能：`project-design`、`backend-architecture-design`、`frontend-architecture-design`、`project-documentation`；已核对 `implementation-slicing` 的契约前提；进度与交付分别依 `update-progress-docs`、`task-git-delivery`。
- 运行中服务、模型调用、数据迁移和部署：无。
- 验证调整：首次 Mermaid 解析遇到 DOMPurify 缺 DOM 环境，在任务临时目录补 JSDOM 后重新解析通过；没有更改项目依赖或跳过语法验证。
- S0 验证调整：初次格式/静态检查与追加测试中的超长行已修正；TestClient 弃用警告改用现有 HTTPX ASGITransport 消除。复核补齐安全异常上下文、空 query/fragment、非序列输入等拒绝后，最终 68 项无警告通过。详细失败、修正及工具限制见 TEST_RESULT。
- 公共 Runtime：`entry.py resolve` 返回 `MULTI_CLIENT_DRIFT_BLOCKED`（`frontend-design/SKILL.md` CORE_DRIFT 和 scan-input MANIFEST_DRIFT），未创建受治理 Run、未改全局技能/指纹；依据 public-engineering-workflow 的手工路由兼容规则，按已读取专业技能和原有用户授权逐项执行，保留人工 Gate/验证，不能宣称机器 Runtime 通过。
- 修改范围：`ai-runtime/`、`.gitignore`、当前设计/契约/切片与进度、README/索引/来源映射、选型中的 S0 运行证据、`docs/qa/runtime-foundation-20261020/`；没有修改参考仓库或全局技能。
- 目录保留：未创建额外 worktree；`.idea/` 属用户文件。忽略的 `.venv/` 用于继续开发，`.logs/` 保存本地验证输出，`dist/` 及 Ruff/mypy/pytest/Python 缓存可在不需要复现时清理，本次保留且未执行删除。两个参考仓库最终仍干净。
- 后续业务入口：知识领域迁入依赖盘点、完整 K01–K04 契约与 S1；随后真实存储/Embedding 集成、独立 React 知识路径及 Q/T 契约。真实模型账号调用、知识链路、数据库迁移与 React 页面尚未执行，不计入本片通过范围。

## 历史入口

- [选型与文档迁移记录](../platform-selection-20261020/PROGRESS_STATE.md)：上一任务已完成；其中当时未决范围由本轮用户确认更新，历史验证证据保持原样。
