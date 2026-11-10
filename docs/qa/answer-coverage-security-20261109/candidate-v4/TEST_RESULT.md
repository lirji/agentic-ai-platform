# 第四版离线验证与待执行验收

2026-11-10第四版实现及离线验证已完成；真实模型效果UNVERIFIED，业务交付Gate HOLD。旧第三版58/60及两项失败保留，不代表第四版通过。源码摘要见[implementation-evidence.json](implementation-evidence.json)，拟新增调用与资金见[funding-proposal.json](funding-proposal.json)。

| 验收 | 实际结果 | 证据/限制 |
| --- | --- | --- |
| 针对性QA/评测/复测回归 | PASS，89项 | v4-targeted.log；验证一次生成、引用和既有协议，不能证明真实语义正确 |
| 全量Python回归 | PASS，884 passed / 102 skipped | v4-python-all.log；跳过真实集成条件的项不计通过 |
| 格式、静态、类型、编译 | PASS | 正确项目目录下Ruff173文件、mypy100源、py_compile；v4-format-final/static-final/mypy.log |
| 契约与计量边界 | PASS | 16契约及16共享fixture；330 Java源、5层DAG、8表94注释字段；v4-contracts/boundaries.log |
| 原始及v1/v2/v3报告可重建 | PASS | 四份verify退出0；源数据和历史评分未修改 |
| 缺授权时无副作用 | PASS | 不匹配次数/资金、禁止探测标签在fixture/服务/资金动作前拒绝；包含v3与v4回归 |
| 新池路由、旧账目和身份 | PASS（离线/只读） | v4全部60题用新池，Embedding保持；v3真实登记manifest逐字段一致，旧3账户余额/账本未变 |
| 新池与Embedding分别不足、总次数不足 | PASS（离线） | 即使总余额够，当前池不足仍拒绝；已结束组不重复预留；未开新池 |
| 恢复与基线边界 | PASS（离线） | v3终态复制为v4新身份，旧state不改；机制eligible不自动选择基线 |
| Code Hygiene | PASS_WITH_LIMITATIONS | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；扫描器未发现子目录Ruff，实际格式/静态命令已通过；222已有语义常量，机械advisory非未处理魔法值 |
| 第四版真实60题质量/攻击/拒答 | UNVERIFIED | 原调用162次已耗尽；新增60次及3元待明确批准，未新采样 |
| 新资金登记及审查落库 | NOT_EXECUTED | v4私密state和funding文件不存在；无新增模型调用，未改数据库资金 |
| 第四版报告verify及远程CI | UNVERIFIED | CI已要求v4结果，待真实数据完成后重建校验；不拿旧报告替代 |
| Git保存与交付 | WIP / HOLD | 已本地提交fa47d4b；交付回执见DELIVERY_RESULT.json，未合并/推送；离线通过不等于真实模型验收 |
| UI、Schema、生产部署 | N/A | 本次不涉及 |

修改仅约束事实关系和条件作用域：保留原文明确相连的确认/恢复，不从共同前提新增先后、不把阶段条件扩大为通用规则。没有具体题号或答案进入提示，没有二次生成或裁判。固定题目/资料/rubric仍与原基线一致。

首次在仓库根目录执行Ruff选择了不同的默认格式，改为CI使用的ai-runtime工作目录后通过；仅重新格式化本次触及文件，未全仓格式化。最初Decimal零写法已在全部测试前修正。对应失败日志保留，不计作最终通过证据。

批准后以`--revision v4 --approved-additional-calls 222 --approved-extra-chat-cny 3`依次执行quality/control-1/attack-1/control-2/attack-2；完整60题实际调用后逐题评分、review-all/audit、report/verify --candidate v4。新CHAT上限3元，原账户不改，累计上限13元；本整改调用222，含原基线总282。未获明确授权不登记资金或执行命令。
