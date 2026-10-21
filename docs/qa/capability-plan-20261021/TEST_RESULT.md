# 能力实施计划的文档验证

任务：`capability-implementation-plan-20261021`；日期：2026-10-21。

结论：**计划文档验证 PASS；产品开发 PAUSED，新增任务未实施。** 本结果不代表检索质量、Reranker 效果、容量或部署验收通过。

## 对象与依据

- 基线：后端 `1e8f3b36766069764b74ca7c67cc6afeb9e9fe3e`；独立前端 `d2b4dc6957cfbc81d29a8493dc9aa284aab8e584`；参考 `langchain4j-platform@468cec425dd160e80cfc3ab378d3440a8767e4d5`。
- 产物：[主方案](../../design/knowledge-first-20261020/CAPABILITY_IMPLEMENTATION_PLAN.md)、[稳定任务清单](../../design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md#capability-depth-slices)、[契约工作包](../../design/knowledge-first-20261020/CAPABILITY_CONTRACT_BACKLOG.md)，以及同步后的原架构、选型、前端设计、入口与进度。
- 原差距与源码依据：[能力缺口](../../../.engineering/exploration/CAPABILITY_GAPS.md)及其固定证据；本轮增加[锁定框架接口证据](../../design/knowledge-first-20261020/FRAMEWORK_EVIDENCE.json)。
- 验证结果与文件 SHA-256：[validation.json](validation.json)。其中正文摘要绑定待交付内容；进度、收据和验证文件排除自引用摘要，但仍检查文档链接、范围及常见凭据格式。

## 已完成检查

| 检查 | 结果与边界 |
| --- | --- |
| 缺口覆盖 | G01–G20 全部有任务承接；主方案映射与逐任务标记均核对 |
| 稳定任务 | 51 个唯一 ID，43 PLANNED / 8 CONDITIONAL；索引与详细卡片的名称、Needs、Pass、状态一致；S0–S7 正文保持原样 |
| 依赖顺序 | DAG 无环、无未知前置；19 个契约工作包的先行设计不依赖后面的消费者；实际依赖优先于批次数字 |
| 每项范围 | 结果、Owner/路径、契约/迁移归属、失败/权限验收、Pass/Runtime 与条件明确；UI 沿真实 API 和原视觉/恢复规范 |
| 当前框架适配 | 5 个已安装版本与 uv.lock 一致；7 个框架源码和 8 个项目源码/锁定文件摘要匹配；没有新增依赖或模型调用 |
| 历史与目标区分 | 已实现的两路 RRF、问答/研究、PG fencing 不再列作空白；新增精排/质量/共享等仍未实施；原路线标为历史 |
| Markdown 与安全卫生 | 本地链接/锚点、表格列数、代码围栏及 diff whitespace 检查通过；常见密钥格式未命中，非全面安全审计 |
| 改动边界 | 只修改文档及文档证据；产品、测试、运行配置、依赖锁、前端及参考仓库未变；没有新 worktree |

结构检查使用标准库临时校验程序（位于本机 `/tmp/validate-capability-plan-20261021.py`，不是项目运行依赖）；检查正文、基线 Git 对象、锁定文件与只读仓库状态。原始计数、有效拓扑序和逐文件摘要保存在 JSON。人工对照由本任务执行者完成，不冒充独立子 Agent 审查或机器 Runtime Gate。

## 检查中修正的问题

1. 首次依赖检查把 S11E 行内描述后续 C-QA2/C-WORK2 的标签识别成当前前置。已改清范围：S11E 只适配现行 Q/work 与新检索契约，新增答案/研究语义由 S12 承担；复验通过。
2. LlamaIndex 默认融合与当前 Java 不完全等价：默认查询生成/模型解析、零基排名、Node hash 去重，均纳入适配要求；过滤后的原始名次也必须保留，防止迁移时悄悄改变排序。
3. 无答案题和有答案空召回分开计分；更换分块后重新绑定来源标注，避免用旧 chunkId 制造假退化。目标和退化门槛在比较前确定。
4. 修正文档中的“研究未接通”“联合 fencing 未验证”等过期描述；现行协议仅更新入口说明，正文语义未变。

## 未执行与恢复入口

没有运行应用测试、真实评测、付费模型、迁移、服务重启、部署或数据清理，也没有验证未来 51 项实现。U01–U07 是后续样本、身份、费用、业务、负载和环境条件，不能用本次文档 PASS 消除。

本轮仅交付完整计划。恢复开发时先做 S8A 评测口径/样本/执行契约；是否恢复以用户后续明确要求为准。唯一状态见 [PROGRESS_STATE](../../design/knowledge-first-20261020/PROGRESS_STATE.md)，Git 交付见 [delivery-result.json](delivery-result.json)。
