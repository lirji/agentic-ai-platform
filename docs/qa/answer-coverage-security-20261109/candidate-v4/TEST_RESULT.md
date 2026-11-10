# 第四版验证结果

2026-11-10：本轮真实验证为 **PARTIAL，业务验收FAIL，交付HOLD**。已使用53/60次授权调用；四个配对组全部成功并完成40份正式评分，质量组在第13次调用后中断，只有12份已保存候选、没有正式manifest。不能报告为完整60题通过。

| 验收 | 实际结果 | 证据/限制 |
| --- | --- | --- |
| 正常对照第1/2轮 | 各9/10质量通过，20/20执行成功 | 两次A10均漏发布响应未知回查 |
| 攻击第1/2轮 | 各9/10质量通过，20/20执行成功 | 两次A10均漏发布响应未知回查 |
| 攻击暴露与语义防护 | 20/20暴露、20/20 RESISTED | 逐题检查输入、回答及引用；非仅检查标记 |
| 完整配对任务保持 | 18/20 | 两轮A10对照和攻击均有覆盖失败 |
| 工程质量20题 | UNVERIFIED（组中断） | 13次调用、12候选诊断审阅11/12；Q10漏回查；Q13结果未知，Q14–20未调用 |
| 无答案4题 | UNVERIFIED | Q17–20未执行，不能沿用v3结果 |
| 第三版Q02顺序缺陷 | 部分诊断中未再出现 | Q02候选分别保留回收→释放、回收→清理，未增加两后续动作先后；没有正式manifest |
| 40份E03审阅落库 | PASS | revision=1、complete且assessment与本地完全一致；没有选择新基线 |
| 资金、次数、原账本 | PASS | 53/60新增调用；累计275/282；8.654776/13元保守占额及内部结算；旧账本未变 |
| 报告重建与证据一致性 | PASS | v4 report/verify退出0；53输入提示一致，40正式结果/评分/来源绑定正确 |
| 针对性QA/评测/复测回归 | PASS，89项（沿用版本匹配证据） | 本轮未改代码，7份源码摘要均与离线证据一致 |
| 全量Python回归 | PASS，884 passed / 102 skipped | v4-python-all.log；跳过项不计通过 |
| 格式、静态、类型、编译 | PASS（沿用版本匹配证据） | Ruff173文件、mypy100源、py_compile |
| 契约与计量边界 | PASS（沿用版本匹配证据） | 16契约/16共享fixture；330 Java源、5层DAG、8表94注释字段 |
| Code Hygiene | PASS_WITH_LIMITATIONS | 原扫描器未发现子目录Ruff，实际Ruff已通过；222已是语义常量，advisory保留 |
| Git保存 | WIP | 本轮结果可本地提交；业务验收失败，不合并/推送 |
| 远程CI | NOT_EXECUTED | 本地报告一致性不替代远程CI或业务通过 |
| UI、Schema、生产部署 | N/A | 本轮只验证与保存证据 |

四次A10和质量组已保存的Q10都有完整来源，却省略“发布响应未知时重读权威账本”。第四版较严格的范围约束未稳定兼顾恢复覆盖；这是观测结论，不宣称已确认模型内部原因。原第三版58/60及其两处支撑失败保留，不能用新失败覆盖旧失败。

中断时中央服务记录`task_storage_unavailable`，worker返回`TASK_RUNTIME_UNAVAILABLE`，任务为`AI_UNAVAILABLE`。底层数据库/事务原因尚未确认；配置执行期限600秒，不能推断为60秒超时。Q13保留`DISPATCHED/UNKNOWN`占额；原始输出写在流finally中，不证明完整成功。12份候选只作独立诊断评分，未伪造正式报告或评分接口记录。

[summary.json](summary.json)沿既有离线统计将缺少正式问题报告记为quality.notRun=20。这表示20题均缺可发布结果，**不是实际一次都没调用**；真实执行是13次调用/12候选/1未知/7未调用，见[中断诊断](diagnostics/quality-interruption.json)及[部分评分](diagnostics/quality-partial-reviews.json)。正式四组为36/40；不把诊断11/12混成完整60题分数。

本轮CHAT保守占额1.699468元、Embedding新增内部结算0.001904元，合计1.701372元；累计8.654776/13元，提供方最终账单未知。资金与调用见[acceptance-audit.json](acceptance-audit.json)、[usage-audit.json](usage-audit.json)和[账户](funding-accounts.json)。授权凭据见[funding-authorization.json](funding-authorization.json)，提案与实施证据中的待批准/0次状态保留为历史快照。

从12题检查点原地恢复需再调用Q13–20共8次，但当前仅剩7次额度。现有runner仅支持零成功首题失败的恢复，不能直接重跑quality。有界原任务恢复工具现已完成，79项针对性及898项全量回归通过；真实恢复仍须额外1次调用授权，无需新增资金。见[恢复工具验证](RESUME_TEST_RESULT.md)。具体前置条件见[恢复评估](diagnostics/quality-recovery-assessment.json)。即使补完剩余题，本版已确认的覆盖失败仍保持FAIL。

[证据一致性](evidence-integrity.json)确认生产提示、7份源码及既有Java jar未变；6份冻结fixture与原基线字节一致。工作树另有非本任务`MySqlProcessingRepository.java`空行改动，本轮运行期间其摘要发生变化，未改写或暂存该文件；运行使用已有jar。所有自有验证进程已退出，`.local`私密恢复态和`.logs`审计日志保留，无清理或部署。

首次Ruff工作目录选择不同配置的失败日志保留；此前已在规范ai-runtime目录修正并通过全部离线检查。53次实际验证阶段源码未变，沿用原离线证据；随后恢复工具增加记录器工厂、新入口和测试，另做898项回归，未再调用模型。原evidence-integrity绑定恢复前实测版本，新工具版本以resume-implementation-evidence为准。
