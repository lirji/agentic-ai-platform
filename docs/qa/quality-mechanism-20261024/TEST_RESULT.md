# S8E 当前 RRF 与问答机制基线

2026-10-24。**S8E 必需验收 PASS；答案质量并非全部通过。** 后端 `aa355c5`、前端 `5960531`，产品源码未改。当前真实运行为 `dd417a9e-3cee-4ba4-bcac-fd7bc3af61d5`，中央任务 `a243cd4a-687a-4628-aa38-60d15444dc84`，已冻结基线 `bd158640-0389-4107-8491-20444637a9ae`。可信身份为 `local-dev / developer`，默认工作台 `workbench-user` 保持隔离，不能通过默认工作台身份读取这份基线。

## 结果与已知问题

8题均通过真实双路检索、聊天与引用复核，失败/未开始均为0；6道有答案题 Recall@5=1、MRR@5=0.875、nDCG@5=0.905113。M04正确证据位于第4，其余答案题金标位于第1。两个无答案题不进入召回分母，均明确拒绝编造金额。

| 样本 | 召回 / MRR / nDCG | 逐题复核 |
| --- | --- | --- |
| M01 架构职责 | 1 / 1 / 1 | 支撑、覆盖、引用定位通过 |
| M02 当前格式 | 1 / 1 / 1 | 正确区分已支持 TXT/Markdown 与规划 PDF |
| M03 融合规则 | 1 / 1 / 1 | RRF60、同分排序与权威过滤通过 |
| M04 发布门槛 | 1 / 0.25 / 0.430677 | 覆盖通过；**支撑失败**：将直接引用失效的404错误地概括到搜索；引用身份/定位仍正确 |
| M05 引用权限（关键） | 1 / 1 / 1 | 绕权拒绝与旧版本校验通过 |
| M06 中央裁定（关键） | 1 / 1 / 1 | 旧owner/epoch拒绝、中央终态与恢复凭据通过 |
| M07 餐补 | 不适用 | 拒答通过；召回包含来源说明噪声 |
| M08 合同金额 | 不适用 | 拒答通过 |

按既定 `mechanism-gate-v1`，非关键题已知支撑失败可保留为基线；未降低标准或重跑模型以取得更好结果。所有引用定位维度通过、关键题通过、必要评分齐备，因此服务端允许基线选择。评审由 Codex 阅读冻结rubric、实际答案与原文后，经可信developer身份保存；不是用户人工签核，也未启用额外模型裁判。

两道拒答文字正确，但既有 `answerability=ANSWERED` 仅反映非空召回，不代表存在业务答案。M06切分产生一个只有来源说明的块，实际参与无答案题召回。这些限制与M04语义错误留给S9D/S11/S12对应任务，不在本片修改生产策略。

## 验收证据

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| E04-BASELINE-01 固定真实资料 | PASS | 4份原文件、6份摘录均来自固定提交 `3f28379833a2`；[来源摘要/行号](source-manifest.json)、[语料](corpus/M01.md)、[真实发布绑定](corpus-bindings.json) |
| 金标先于运行冻结 | PASS | [题目与M03证据缺口](samples.json)、[逐段金标](gold-labels.json)、[定义revision 1](frozen-definition.json)；仅选择6个包含答案事实的块，来源说明块未标为答案 |
| 当前真实执行 | PASS | [运行/中央凭据](run.json)、[逐题原始报告](question-reports.json)、[源码/profile摘要](source-fingerprint.json)；真实deepseek-flash、text-embedding-v4、Java RRF60，TopK=5 |
| 指标与原文位置 | PASS | 7块全部经当前引用接口读取，字节切片与原文/textHash一致；独立公式重算6题指标、2题空指标与分母。见[验证明细](validation.json) |
| 权限失败不当质量零分 | PASS | 同owner跨tenant、同tenant跨owner的定义/运行/报告/评审/基线/引用共12次请求均404；没有发送模型或把拒绝记录成零分 |
| 合法引用与错误答案分开 | PASS | M04保留supported=FAIL，citationCorrect=PASS；[逐题评分依据](manual-reviews.json)、[持久评分](review-state.json) |
| 基线不可变选择 | PASS | [冻结基线](baseline.json)绑定当前清单/评分摘要；未创建候选或额外付费比较运行 |
| 核账与无重发 | PASS | [运行前核账](preflight.json)、[预算上界](budget-plan.json)、[运行后账本摘要](after-run-ledgers.json)；8个聊天operation对应本次8题与唯一task；只读复验未增加预留 |
| 界面与产品回归 | N/A | 本片没有产品/UI源码变化；S8D有效证据保持原适用边界，没有新增视觉通过声明 |

M03固定原文没有明确一基名次与完整候选去重键。依原验收“原章节不足则先调整题目”的规定，在运行前将题目限定为文档支持的RRF60/同分/版本校验，差异保存在samples。源码另核实 `KnowledgeSearch` 使用 `offset+i+1`、`SearchIndex.Candidate(chunkId,batchDigest)`；不将源码知识补造进检索正文。

累计聊天保守预留从0.581704增至**0.889964元/1元**（本片8次、增加0.308260）；Embedding从0.28增至**0.42元/1元**（本片14批：6批入库、8批查询，增加0.14）。提供方usage与账单金额仍未知；产品逐题cost保留null，外部账本核对不是账单。8题步骤总耗时14,259ms，单次本地样本不构成时延SLA。

## 复核与恢复

只读复核：`ai-runtime/.venv/bin/python docs/qa/quality-mechanism-20261024/verify.py`，日志 `.logs/s8e-verify-final.log`。首次验证脚本把NO_ANSWER的整个metrics=null误当字段全null，已按实际契约模型纠正；原失败 `.logs/s8e-verify.log` 保留，未修改产品或题目。

副作用恢复记录 `.local/s8e-mechanism-state.json`，入库/提交/评审日志 `.logs/s8e-{ingest,submit,review}.log`；所有任务已终态，不重跑付费步骤。源文件核验、12次无权读取与指标复算可重做且不调用模型。本次没有新增迁移、依赖、生产部署、数据清理或worktree。

SKILL_HANDOFF: `implementation-validation / S8E / COMPLETED / PASS`。下一步由progress记录DONE，Git正常交付后继续S8F；完整51项计划仍在进行。
