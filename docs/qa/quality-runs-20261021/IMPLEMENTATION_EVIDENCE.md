# S8C 持久评测与逐题结果

基于后端 `c810e959df28a44695e72f4dc67f1ed29ffdb17b`、前端 `427bcb9451752a3fa5dbcbb77835339059e7b998`，原目录任务分支 `feat/quality-runs-20261021`；实施边界为 [S8C](../../design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md#s8c--持久评测执行与逐题结果) 与 [E02](../../design/knowledge-first-20261020/EVALUATION_RUN_CONTRACTS.md)。无依赖升级、新服务或新模型授权。

| 行为 | 实现与不变量 |
| --- | --- |
| 持久受理与未知结果 | Knowledge `EvaluationRunService` / `MySqlEvaluationRunRepository`，V5 三张表、原键指纹与固定 taskId；短事务保存运行/控制意图，后台有界领取；HTTP 不进入数据库事务 |
| 执行状态与报告归属 | Tasks 专用评测身份、严格输入、中央 lease/epoch；V3 仅允许评测失败携带凭据。候选与清单不可变且按 epoch 隔离，只有中央实际接受的终态凭据才能提升为正式报告；不宣称跨库原子 fencing |
| 取消与恢复 | 未知交接始终核对原任务；恢复前确认失败报告，保存原中央 revision。恢复意图未消费时返回 resumeRequested 并拒绝取消，避免旧 FAILED 掩盖迟到恢复；中央进入新代次后可以取消 |
| 原生逐题图 | Python `evaluation/` 复用生产 QaPipeline 检索/生成/引用复核，LangGraph 根图逐题同步 checkpoint；快照只保存题号/候选引用，不保存整批答案。已提交成功题恢复不重跑，最终清单按新 epoch 重新确认 |
| 指标与费用 | LlamaIndex Recall/MRR，按 quality-v1 实现分级 nDCG；成功应答题空召回计0，无答案与执行失败不混入分母。未返回的 usage/金额保留未知，本地预留不当供应商账单 |
| 身份与资源边界 | Knowledge→Tasks 与 Runtime→Knowledge 独立签名，原 K05 仍只读；最多60秒 JWT；服务端有界正文/响应、独立线程与连接池、5秒全响应期限、无自动重定向/重试 |
| 真实界面 | React 定义页运行区、历史记录和 `/quality/:datasetId/runs/:runId`；原键未知恢复、中央进度、取消/恢复、逐题回答/标准/证据/耗时/费用；只展示已确认报告，当前授权失效隐藏正文 |
| 汇总与正文一致性 | UI 请求携带 manifestDigest，恢复完成期间清单已变返回409；隐藏旧正文并刷新汇总，不能将新答案配旧指标。恢复中的旧报告明确标注 |
| 本机接入 | `init-evaluation` 增加独立密钥并保留原 worker 配置、累计预算；服务环境按角色过滤。Tasks V3、Knowledge V5 已在项目 dev/test 应用，原迁移不修改 |

实现期间修正：失败 proof 原 CHECK 不兼容，按追加 V3 解决；JSON 数字节点比较改为规范 JSON；Pydantic 严格枚举从 wire JSON 解析；候选保存超时不再尝试用失败结果覆盖已接受的成功候选；恢复 pending 与清单切换窗口补充契约和验证。早期测试夹具方法/键长错误与 Java 无 Mockito 导致的测试编译失败日志保留，使用已安装 Micrometer NOOP 适配器完成真实传输测试，不增加依赖。

界面测试首轮 5 PASS / 1 FAIL：414px 断点折叠动画中间帧触发横溢出断言；稳定页面实际检查无溢出，改为等待布局稳定，没有隐藏溢出或放宽尺寸条件。最终7组通过，截图实际查看见前端 `docs/qa/s8c/TEST_RESULT.md`。

已复用 S8B 的原文侧栏、session/权限、提交意图、Ant Design 主题、TanStack Query；未重做壳层。确定性端口只在验收工具内，所有运行/报告/控制均经过真实服务与数据库；本片没有真实模型质量结论。

Handoff：`backend-implementation + frontend-implementation + runtime-and-deploy / S8C`；最终验收与限制见 [TEST_RESULT](TEST_RESULT.md)，源码范围见 [source-fingerprint.json](source-fingerprint.json)。

代码卫生复核修正：HTTP状态使用现有HttpStatus，Python阶段复用QaStage，前端任务类型改为模块协议常量；评测Handoff枚举明确无会话SENDING状态，不跨服务依赖会话领域类型。E01字段上限和快照题数使用集中常量。窄回归10个图/指标、3个传输、50个前端单元及两仓构建/静态检查通过，自动Gate无阻断Finding。
