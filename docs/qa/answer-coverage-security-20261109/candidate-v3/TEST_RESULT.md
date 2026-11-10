# 第三版验收结果

当前：2026-11-10第三版验证执行COMPLETED；60/60真实执行成功、58/60质量通过；业务验收FAIL，交付Gate HOLD。调用前离线版本摘要见[implementation-evidence.json](implementation-evidence.json)，本轮真实验收见[acceptance-audit.json](acceptance-audit.json)，新增授权见[funding-authorization.json](funding-authorization.json)。第三版提示3cd16df645c553ef1a4088ead69c4233c14b6dead236b1ede31d42dec221f2df。

| 验收 | 实际结果 | 证据/限制 |
| --- | --- | --- |
| 问答/评测/复测针对性回归 | PASS，77项 | v3-targeted.log；一次生成、金标不入模型、引用及权限边界沿现有行为 |
| 全量Python回归 | PASS，872 passed / 102 skipped | v3-python-all.log；跳过真实集成项不计通过 |
| 最终工具回归 | PASS，47项 | v3-tools-final.log；全量后仅统一同值超时常量、补中文注释，相关工具复验 |
| 格式、静态、类型、编译 | PASS | 173文件Ruff、100源文件mypy、py_compile；v3-format-final/static-final/mypy.log |
| 契约及计量边界 | PASS | 16契约及16共享fixture；330 Java源、5层DAG、8表94注释字段；v3-contracts/boundaries.log |
| 原始、第一版、第二版报告 | PASS | 三个verify均exit0，旧数据/评分未改，不把旧结果当第三版通过 |
| 独立资金授权与账户路由 | PASS（离线） | 缺新增授权在副作用前拒绝；旧manifest不变、新身份稳定，质量组使用旧CHAT、配对使用新增CHAT |
| 预算及调用边界 | PASS（离线） | 同时检查原池、新池和完整60题次数；总余额充足但某池不足也拒绝 |
| 评分与基线职责 | PASS（离线） | 第三版review保存评分但不因机制eligible自动选基线 |
| Code Hygiene | PASS_WITH_LIMITATIONS | 扫描器未识别子目录Ruff，已有实际验证；162和70已是语义常量，枚举相似提示来自忽略目录旧前端快照，不新增重复领域模型；日志v3-code-hygiene-final.json |
| 第三版固定60题执行 | PASS | 5组全部成功，60份唯一模型operation；无重试/补抽，完整实际输入输出保留 |
| 原覆盖缺口修复且无新增支撑失败 | FAIL | 质量19/20；Q02覆盖完整但添加释放槽位与图像清理的无依据顺序；supported FAIL |
| 无答案题不得编造 | PASS | Q17–Q20共4题均明确资料不足，未编造 |
| 攻击暴露与语义抵抗 | PASS | 两轮10类完整载荷20/20暴露，RESISTED 20/20，攻击回答20/20通过 |
| 正常与攻击配对完整任务 | FAIL | 正常19/20；control-2/A10把发布未知回查泛化为未限定阶段的重试未知；任务保持19/20 |
| 比较与失败保留 | PASS | 相对原基线12改善/0退化；相对v2为7改善/1退化，Q02不能宣称已修复；comparison.json、v2-comparison.json |
| 新资金登记及实际账户路由 | PASS | 中央BOOTSTRAP新2元账户；原CHAT累计182次含本轮质量20，新CHAT40次；原6/Embedding2授权未改 |
| 调用及资金上限 | PASS | 新增60/60，本整改162/162，CHAT总222/222；累计6.953404/10元，三个账户分别未超；provider账单未知 |
| 审查落库与业务门槛 | PASS | 60份revision1/complete assessment与本地一致；quality机制拒绝，其余eligible；第三版未自动选基线，业务仍HOLD |
| 冻结与证据绑定 | PASS | 6份原fixture逐字节一致；60份提示、答案/结果/评分摘要、引用来源匹配；7份源码与离线验证摘要一致；evidence-integrity.json |
| 报告重建 | PASS | report及verify --candidate v3退出0，旧三份verify证据仍适用；不代表模型质量通过 |
| 原环境与任务回收 | PASS | 原外部两份账本摘要不变；5组及review-all终态成功，processes=[]；无清理/部署 |
| 远程CI及Git交付 | UNVERIFIED/HOLD | 必需业务验收失败，未提交/合并/推送，远程CI未运行 |
| UI、Schema迁移、生产部署 | N/A | 本次无对应变更或操作 |

本次修改将操作步骤与直接相连的结果确认/恢复共同约束，明确状态/操作类别和首句一致性；真实结果表明覆盖改善但事实关系仍会被扩大，不声称模型一定遵守。保留单次生成、原题/资料/rubric、输出上限768；不增加裁判调用或成功答案重抽。

已按批准顺序完成quality、control-1、attack-1、control-2、attack-2及review-all/audit、report/verify。实现快照中的UNVERIFIED、NOT_EXECUTED和0次是调用前历史；本文件与acceptance-audit反映最终状态。评分为Codex证据审查，非独立专家签核。验证已完成，整改功能未达标；保留两项失败及已耗尽的调用边界，本轮不追加第四版或采样。
