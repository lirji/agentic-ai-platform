# 独立知识平台准备进度

## 当前任务：按能力深化方案连续实施

- 标识：`capability-delivery-20261021`。用户已要求“开始实施”，按20项差距的51项计划连续推进，条件项仍按触发条件执行。
- 状态：S8A/S8B已交付；S8C DONE / 必需验收PASS / 两仓已正常合并推送origin/main并核对远程；完整计划未完成。下一项S8D人工评审、基线选择与逐题退化比较，不等待用户再说继续。
- 权威：CAPABILITY_IMPLEMENTATION_PLAN、IMPLEMENTATION_SLICES、QUALITY_CONTRACTS；本文件为唯一任务状态。S8C传输与恢复依据 [E02](EVALUATION_RUN_CONTRACTS.md)，S8D实施前补齐E03，不重做既有架构。
- Git：两仓原目录 `feat/quality-runs-20261021`，后端起点`c810e959df28a44695e72f4dc67f1ed29ffdb17b`，前端起点`427bcb9451752a3fa5dbcbb77835339059e7b998`；S8C产品提交后端`32377f2`、前端`f811c99`均已交付，无新worktree；收据提交以Git历史为准。S8B后端`02d2235`、前端`427bcb9`已合并推送。
- 当前清单：①S8C契约/身份；②Knowledge运行账本/报告与Tasks失败凭据；③LangGraph逐题恢复/QA复用；④React运行/逐题结果；⑤验收与文档已完成；⑥两仓逻辑提交/正常合并推送已完成；⑦进入S8D。
- 决策：Knowledge拥有不可变定义/报告，Tasks拥有租约/终态，PG根图逐题保存候选引用。候选写入不宣称跨库原子fencing；只提升实际接受的中央proof。未知受理/取消/恢复沿原taskId和原CAS，恢复pending继续轮询；取消在未知恢复窗口409。正文按manifestDigest核对，避免新答案混旧汇总。
- 质量口径：仍为当前生产两路RRF(60)/QA路径。空召回应答题0、无答案不适用、执行失败与未评审分开。正确引用不等于正确回答；缺失usage/金额保留未知。人工评审、基线/比较和真实模型基线尚未完成。
- 用户样本决定：先用项目架构/操作文档；固定来源和样本见QUALITY_ACCEPTANCE。当前确定性浏览器夹具不是S8E机制质量基线，更不能作为业务达标结论。
- 已验证：Knowledge6组真实DB，Tasks4新组+原17组；实际Gateway/Tasks/Knowledge/MySQL/PG恢复/取消3组，恢复pending增量1组复验；独立身份HTTP3组；清单guard1组DB+真实HTTP；有界传输3项（5秒正文期限/4在途拒绝/响应上限/不重定向不重试）。Knowledge原26+security10单元有效。Python282通过/47条件跳过，新增图/指标10项再验，mypy46源码/Ruff通过。前端50单元/构建/类型/lint/format与7组浏览器通过，1440/1024/414及引用侧栏截图实际查看。
- 证据：[S8C TEST_RESULT](../../qa/quality-runs-20261021/TEST_RESULT.md)、[实现映射](../../qa/quality-runs-20261021/IMPLEMENTATION_EVIDENCE.md)，source-fingerprint和validation记录源码/日志。代码卫生后端COMPLETE_WITH_LIMITATIONS（Java无formatter/专用静态分析），前端COMPLETE；枚举/固定协议值问题已修正，保留最初失败日志，没有豁免或冒称独立Agent审查。
- 迁移：Tasks V3、Knowledge V5均在独立dev/test执行且success=1，旧迁移未修改，新迁移也不可改写；前序V4有效。独立评测服务密钥已初始化，当前worker支持三种图，预算/既有配置保留。
- 运行：七个后端/worker与Vite28086运行，最终制品更新后核对readiness；确定性验收期间正常暂停qa-worker，验收结束确认活跃评测0后恢复。原运行记录保存在后端`.local/s8c-evaluation-it-*.json`和前端`.local/s8c-quality-run-state.json`，不盲目重复受理或删除。
- 费用：聊天0.510952/1元（14次），Embedding0.26/1元（26次）；S8A/B/C新增付费调用均0。S8E真实模型前再次核账；Reranker没有新额度。
- 授权：本地实现/测试/必要配置与正常Git提交、合并、推送main；无生产部署、清理数据/账本、强推、新模型费用或子Agent授权。`.local`/`.logs`/`.idea`及测试数据保留。
- 历史失败：旧Tasks结果CHECK、严格枚举解析、候选未知写入、恢复窗口及报告切换已修正；夹具键长/方法、Java无Mockito、浏览器断点动画帧与测试数组可空类型已修正并有针对性复验。全局Ruff默认规则不作为本仓库规范，运行脚本显式使用已有Python配置；没有新增工具链。
- 规范：continue-approved-delivery、backend/frontend-implementation、runtime-and-deploy、implementation-validation、update-progress-docs、task-git-delivery；业务建模、持久化、API、并发安全、容量/版本、发布治理与相关个人栈。无全局Engineering Runtime Gate冒称。

## 历史：20 项差距的框架化实施计划

- 标识：`capability-implementation-plan-20261021`。用户要求按本项目框架重新制定后续任务拆分和实施，并已明确本轮先交付完整计划；不恢复产品开发。
- 状态：方案 DONE / 文档验证 PASS / 内容提交已合并推送 main。原 `knowledge-depth-20261021` 保持 PAUSED；无产品/测试/运行配置改动。
- 计划：①核对原契约/架构与固定差距基线；②定义三框架/Java/React 的增量职责与兼容路线；③将 G01–G20 映射为稳定任务、依赖及可观察验收；④区分契约前置、条件项、运行/费用门禁；⑤同步原设计/切片/索引并验证；⑥正常 Git 文档交付。
- Git：原目录新任务分支 `feat/capability-implementation-plan-20261021`，起点 `1e8f3b36766069764b74ca7c67cc6afeb9e9fe3e`，开始时干净；前端 `d2b4dc6` 与参考 `468cec4` 只读，未建 worktree。
- 权威产物：CAPABILITY_IMPLEMENTATION_PLAN（主方案）、现有 IMPLEMENTATION_SLICES（稳定任务 ID 与依赖）、CAPABILITY_CONTRACT_BACKLOG（待冻结边界）；BACKEND_ARCHITECTURE/TECH_SELECTION/FRONTEND_ARCHITECTURE 仅增量同步，不另建平行架构。
- 已核对：S0–S7、P/K/Q/T/work 协议；当前锁定 Deep Agents 0.7.28、LangGraph 1.2.16、LlamaIndex core 0.14.25；本地框架接口与官方职责说明。查询编排移交须先提供授权候选原语，不能两端重复 RRF；新来源解析不能沿用原始 TXT 字节定位语义。
- 已完成：主方案、51 项任务（43 PLANNED / 8 CONDITIONAL）、20 项映射和 19 个契约工作包；现有架构/选型/前端设计及文档入口增量同步。框架证据核对 5 个锁定包、7 个安装源码和 8 个项目文件摘要。现行协议正文、产品/测试/运行文件保持不变。
- 关键决策：LlamaIndex 接管查询前先比较原 RRF 一基排名与稳定身份；统一搜索/问答/研究用例；Java 仍掌握授权、版本和唯一索引发布；新解析显式区分原文件与提取物；预算 usage 归属为方案建议，S15A 冻结后再实施。
- 验证：20 项覆盖、51 个稳定任务/索引、19 个契约工作包与依赖无环、旧 S0–S7 保留、Markdown/链接、源码摘要及仅文档范围均 PASS，见 [TEST_RESULT](../../qa/capability-plan-20261021/TEST_RESULT.md)。未运行应用测试或付费模型。
- Git 交付：方案提交 `bc460e367fd73ddd48a27a5d24ae4a52ec3fe692` 在独立任务分支完成，已 fast-forward 合并并正常推送 `origin/main`；ls-remote 与本地一致。详情见 [交付收据](../../qa/capability-plan-20261021/delivery-result.json)，末次收据提交以 Git 历史为准。
- 下一步：本轮计划内容无未完成项，交付收尾后停止；不进入 S8 实现。未来明确恢复后先做 S8A 设计，所有新增任务仍为 PLANNED 或 CONDITIONAL。
- 目录核查：仅原工作目录，无新增 worktree，前端与参考仓库干净且 HEAD 未变；没有未归属的未跟踪文件。保留 .local 凭据/账本/恢复状态、.idea 和历史日志；target/dist/检查缓存可在无需复现并获清理授权后处理，本轮未删除。
- 校验修正：S11E 对后续协议的描述已移出前置含义，复验通过；另补充过滤后原始排名保留、分块变化后标注重绑定及无答案/空召回区分，防止融合等价与质量比较口径失真。
- 运行与边界：无本轮服务/构建/模型/迁移任务；前端和参考仓库只读。U01–U07 为未来对应任务的输入，不阻止本轮计划交付，也不代表获得新费用或部署授权。
- 规范：backend-architecture-design、implementation-slicing、project-documentation、update-progress-docs、task-git-delivery；采用全局建模/持久化/契约/并发安全/容量/发布及个人技术栈相应要求。没有授权子 Agent 并行。

## 历史：能力缺口对比分析

- 标识：`capability-gap-analysis-20261021`。用户最新要求：先中断实施，对比 langchain4j-platform 与本项目的业务和实现精细化差距，输出能力欠缺文档，为后续方向提供依据。
- 状态：分析 DONE / 文档验证 PASS / 分析提交已合并推送 main；无产品代码变更。五步实施显式 PAUSED，本次报告交付后也不自动恢复。
- 计划：①固定两后端及当前前端基线；②复用参考项目已有分析并核验核心源码；③业务/实现/前端/测试/运行对照；④缺口、优先级、依赖、验收与暂不建设项；⑤五份关联分析文档及索引；⑥文档验证、正常提交合并推送。
- Git：本项目原目录 `feat/capability-gap-analysis-20261021`，起点 `d61536c`；前端 main `d2b4dc6`；参考 `../langchain4j-platform` main `468cec425dd160e80cfc3ab378d3440a8767e4d5`。开始时仅上一暂停任务的两份进度文件未提交，这些修改属于本次范围切换记录，保留并纠正暂停状态。未建 worktree。
- 产物：`.engineering/exploration/{CAPABILITY_MAP,CAPABILITY_GAPS,OPPORTUNITIES,EVOLUTION_ROADMAP,EVIDENCE_INDEX}.md`，其中 CAPABILITY_GAPS 为主阅读入口；本节仍是唯一任务状态。
- 边界：仅源码/配置/测试与已有验证证据只读取证，写分析/索引/进度文档；不修改产品/测试/运行配置/依赖，不调用付费模型，不做数据迁移/清理/部署。正常文档提交/合并/推送沿既有授权。
- 已完成：五份分析文档、20 项差距、12 项候选及条件路线；26 组证据、147 处固定提交文件引用和摘要清单。修正相关文档入口的早期状态漂移。区分实现/配置/历史验证；原两路 RRF 已实现、精排缺失、引用不等于语义正确等结论已复核。
- 验证：五份必备分析结构、20 个差距、12 个候选、26 组/147 处固定提交证据及 SHA-256、Markdown 表格/围栏/本地链接、引用 ID、改动范围与常见密钥模式检查通过；参考及前端 HEAD/工作树未变。详见 [.engineering/exploration/VALIDATION.md](../../../.engineering/exploration/VALIDATION.md)。未执行应用测试、付费模型或运行探测。
- Git 交付：分析提交 `80391e7e54a4d820111bd7cd7d663ed93c481360` 在独立任务分支完成，已 fast-forward 合并并正常推送 `origin/main`，ls-remote 核对一致；[交付收据](../../../.engineering/exploration/DELIVERY_RESULT.json)记录该提交。末次收据提交以 Git 历史为准，不递归填自身 hash。
- 下一步：本轮分析无未完成项；五步实施保持 PAUSED，等待用户明确恢复后再基于报告细化当前切片。没有新增运行中的测试/模型/迁移任务。
- 目录：无新增 worktree；前端与参考仓库未变。.local 凭据/账本/恢复状态、.idea 用户配置和历史验证日志保留；target/dist/编译测试缓存可在无需复现并获清理授权后处理，本轮未清理。
- 规范：project-capability-exploration（复用 discovery/deep-analysis 扫描方法）、project-documentation、update-progress-docs、task-git-delivery；源码事实优先、未知不冒充结论。未启动新构建/测试/付费请求；原服务和私密状态保留。

## 暂停：知识平台深度完善

- 标识：`knowledge-depth-20261021`。用户于 2026-10-21 批准此前五步建议顺序并要求实施，明确认为当前平台相较 langchain4j-platform 不够细致；以实际能力、质量和可恢复操作作为验收，不以测试数量代表业务深度。
- 完整目标与顺序：①质量评测基线；②PDF/DOCX 与引用定位（OCR 按资料需求）；③更新版本、删除、重索引与旧引用；④LlamaIndex 查询编排与独立模型 Reranker、同集效果对照；⑤正式身份/共享权限、多 worker、监控、备份恢复与部署验收。后续每项独立冻结契约和切片，不能把首片完成写成五步全部完成。
- 当前状态：PAUSED（用户明确要求先做差距对比）；尚无本轮产品实现/验证。S0–S7 已交付且前次核验源码摘要后端 29 / 前端 18 项一致，两仓起始工作树干净。
- 已执行：读取当前进度、既有契约和技能；定位参考项目 eval-service、知识解析/生命周期/检索质量模块；创建原目录任务分支，无 worktree。
- Git：后端 `/Users/liruijun/personal/LLM/agentic-ai-platform`，`feat/knowledge-quality-20261021`，起点 `d61536c`；前端 `/Users/liruijun/personal/LLM/agentic-ai-platform-web`，起点 `d2b4dc6`，未修改。参考仓库只读，不能迁入其工作区改动或直接访问旧业务数据。
- 授权：五步本地设计、实现、必要测试及正常提交/合并/推送 main；无需再次确认继续。生产部署、清理旧数据和新增模型预算没有新增授权；原聊天/Embedding 各 1 元累计上限不变，先核对账本及在途任务，禁止重发已完成探针或重置账本。
- 恢复入口：先阅读本次能力缺口报告，待用户明确恢复实施后再更新路线、冻结 S8 契约。五步原范围保留，本次分析不自动触发实现。
- 规范：enterprise-feature-development、continue-approved-delivery、backend-implementation、update-progress-docs、task-git-delivery；全局业务建模/持久化/接口/并发安全/容量/发布治理。沿用现有手工专业 pass 与证据，不冒称机器 Runtime Gate 或独立 Agent 审查。
- 运行：未启动本轮构建/测试/模型任务；原服务和 `.local/` 凭据/账本/恢复状态保留。恢复时先核对实际进程与任务。

## 历史：研究分析与只读助手

- 标识：`research-readonly-20261021`；用户已授权开始剩余研究与只读助手，继承P2、原模型/预算及正常合并推送main授权；无生产部署/清理。
- 状态：S6 DONE / S7 DONE；本次实现、验收及两仓代码正常合并推送main均已完成，交付PASS。研究报告、解释/清单/草稿、空证据真实模型均PASS；292项Python、78项Java、38项前端单元、15组联合浏览器通过。窄屏未知提交提示改为说明下方操作后，7组相关浏览器/38项单元/构建/静态复验PASS，截图已实际核对。
- 计划：S6受理/执行/恢复 → S6真实验证 → S7界面/引用 → 联合验收、文档及两仓正常合并推送；四项均已完成。契约 [WORK_TASK_CONTRACTS](WORK_TASK_CONTRACTS.md)，证据 [TEST_RESULT](../../qa/research-readonly-20261021/TEST_RESULT.md)。
- Git：后端原目录，任务分支 `feat/research-readonly-20261021`，起点 `cb2f688`，S6实现提交 `8cf26a6`，验收收尾 `77f7f5c`，交付收据 `0669018`；前端原目录 `../agentic-ai-platform-web` 同名任务分支，起点 `f68e660`，S7实现提交 `5835332`。两仓均已fast-forward合并并push origin/main，ls-remote核对一致；已回到main，末次进度文档提交以Git为准，无额外worktree。
- 决策：中央任务拥有不可变请求和结果；Deep Agents analyze为有界原子阶段，内部checkpoint关闭，外层JSON根图复用原PG/中央fencing，不放宽namespace/序列化。仅搜索/读证据，3轮模型/5次工具/2次额外搜索，单worker顺序领取两图；恢复可能再次计费。
- 验证：真实MySQL/HTTP幂等、分页、隔离；4项真实PG/中央恢复/取消/撤权；五个真实模型案例、引用和重启重放通过。首次多工具响应拒绝及原任务保留；串行工具执行、网关备用错误Mono污染SSE头、前端缺CHECKPOINT事件及提交摘要竞态已修复并回归。
- 预算：沿原两本累计账本，聊天 **0.510952/1元（14次）**，Embedding **0.26/1元（26批）**，非账单；重启配置/账本摘要不变，新增调用0。探针已完成不得重发；`.local/s6-work-smoke.json` 与前端 `.local/s7-ui-state.json` 必须保留。UI真实任务CANCELLED/attemptCount=0。
- 当前服务：Gateway28080、Knowledge28084、AI28085、Web28086、Tasks28087、Conversations28088、LiteLLM28089及worker运行；local-dev无QUEUED/RUNNING。无运行中的构建/测试/付费任务或迁移；最终日志 `.logs/s7-work-final.log`（前端）及 `.logs/s6-*`。
- 新核对：终端默认Node24.12与项目.nvmrc/engines要求24.22不符，已保留现有通过证据，官方Node24.22.0已完整下载并通过SHA-256核验，装在前端忽略目录 `.local/toolchains/`；前端服务已切至该版本，38项单元/构建/静态/15组浏览器均PASS（`.logs/s7-node24-*`）；不修改全局Node或项目版本要求。
- 交付：[DELIVERY_RESULT](../../qa/research-readonly-20261021/delivery-result.json)。源码摘要后端29项/前端18项与暂存区及已发布内容一致；19份Markdown/208处本地链接、真实密钥扫描139份PASS。末次Java readiness与Web会话通过；local-dev为8成功/2取消/1已保留失败，无排队或执行任务。
- 下一步：本次功能无待补项；后续建设按用户新要求确定。保留所有原任务/凭据/预算，不能重发探针或删除状态重测。QA在后端 `docs/qa/research-readonly-20261021/`、前端 `docs/qa/s7/`。
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
