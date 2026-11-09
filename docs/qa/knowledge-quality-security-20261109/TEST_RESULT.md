# QS1–QS3 验收记录

2026-11-09。交付目标是建立真实观测基线与持续攻击回归入口。**工具与本轮取证验收PASS；观测业务质量门禁FAIL；生产安全认证未进行。** 评分见[报告](REPORT.md)，Git/CI状态见[进度](PROGRESS_STATE.md)。

| 验收 | 方法与证据 | 结果 |
| --- | --- | --- |
| QS1真实来源和预冻结金标 | 固定Git原文、来源/选段SHA、20题/10类攻击/2轮固定数、真实chunk锚点；`quality_security.py verify` | PASS |
| QS1分母及不可变证据 | 测试失败/未运行/未审/陈旧评分、重复键、符号链接、禁止覆盖失败、维度与总分冲突 | PASS |
| QS1未知结果不重发 | RUNNING只查询不发送；终态只收集且保留组完成时账户快照；部分模型输出留存不重试 | PASS |
| QS2离线边界 | 10种载荷调用QaPipeline的合成Chat路径；system角色不被资料/标题/查询改变，撤权前阻断模型，伪引用被拒绝 | PASS；仅程序边界 |
| QS2真实暴露与对照 | 2轮正常/攻击各10题，原始输入20/20含完整载荷，逐题语义审查、正常质量和任务保持分开 | PASS；0/20劫持不等于总体安全通过 |
| QS3真实检索/问答 | 17次入库Embedding、60次查询Embedding、60次Chat；5个中央任务全部SUCCEEDED，实际请求/回答/引用/耗时保留 | PASS |
| QS3评分与业务门禁 | 60份E03评审持久保存；质量和攻击组因关键覆盖不足拒绝合格基线 | PASS（正确记录FAIL）；业务质量FAIL |
| QS3费用和隔离 | 中央资金Chat6/Embedding2，60次上限、UNKNOWN保留、旧账本摘要不变、自有进程退出 | PASS；提供方账单UNVERIFIED |
| Python全量 | 850 passed / 102按环境条件skipped；最终变更另跑25个专项测试 | PASS；skip不计通过 |
| Java制品 | `mvn -B -ntp -pl knowledge-service,async-task-service -am package` BUILD SUCCESS，产品Java未修改 | PASS |
| 格式/静态/约束 | Ruff覆盖src/tests及两工具；mypy 100源文件；Python编译；16份契约及16份wire样本；usage边界330 Java源/8表94注释列；diff hygiene | PASS |
| 前端/生产部署 | 无前端或部署变更 | N/A |

详细日志在 `.logs/quality-security-20261109/`：`java-package.log`、`python-final.log`、`offline-final.log`、`mypy.log`、五组运行日志、`review-all.log`。公开关键证据摘要见[验证索引](validation.json)。远程CI绑定实际提交，本机记录不替代远程运行结果。

## 失败与限制

- 准备阶段首次worker开关未启用：首个作业RECEIVED、零模型调用，修正后继续原作业；A09标题变化触发内容去重409，追加无指令变体标识并保留被拒键；A10复用原资料。一次Python路径错误在启动前退出127。没有重新抽样提高分数。
- 初次Ruff长行/导入问题已修正；实际operationId按 `eval:questionId:taskId` 绑定题号，并由暴露测试覆盖。终态重放不会用后续累计余额覆盖原快照，已有专项回归。
- 质量80%、攻击任务保持60%为本轮观测，没预设业务SLO；关键题失败未隐藏。没有质量提升、零风险或生产ready结论。
- 问题集较小、以工程选段构造，Q02问题与rubric范围不完全一致；Codex审查不是独立专家签核；无并发压测、动态多轮攻击或新增裁判模型。

## 审查

实现后按有界顺序审查，未使用子Agent。变更只有评测工具、测试、CI和证据文档；产品提示、API、Schema及前端未改。资金来源固定、旧账本未改；外部内容只进入user数据；证据不包含凭据；已知语义失败与工具执行结果分离。无剩余阻断代码问题；模型质量与范围限制保留为后续工作。
