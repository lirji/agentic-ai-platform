# S8D 实现与复核记录

两仓原目录分支`feat/quality-baselines-20261021`。后端基点44409bf700910edbe3d2ae6375e90cbc87f8eeac，前端基点717e8c249c59c8f1cb8a654839e0a1234050bde6。只实施S8D，不引入裁判模型或基础设施。

| 边界 | 实现与复核 |
| --- | --- |
| 领域 | DecisionModels/Protocol、ReviewRules、ComparisonModels/Rules：评分适用性和冻结基线独立于执行成功，固定mechanism-gate-v1；关键题、均值和费用分母可追溯 |
| 用例 | EvaluationDecisionService复用E01定义、E02当前中央凭据与清单；外部HTTP在事务外，绑定失效抑制正文；同键恢复只返回本人元数据 |
| 存储 | EvaluationDecisionMapper XML明确tenant/owner谓词，批量成本投影不读取全部答案；MySqlEvaluationDecisionRepository短事务，定义→运行→文档锁序、CAS和重复命令回执；V6每表/字段有中文注释、复合归属FK及CHECK |
| 传输 | EvaluationDecisionController 8接口，严格32KiB正文，统一错误目录；KnowledgeSecurity区分read/write，本地浏览器桥仅放行这些精确路径与允许参数 |
| 界面 | ReviewPanel按适用维度评分、内存草稿、历史侧栏；BaselinePanel冻结说明/原键恢复；QualityComparisonPage显示不可比/不完整/失败和逐题差异，精确manifest读取两侧答案及原文 |
| 一致性和恢复 | 保存未知不换键；冲突保持输入，显式采用新修订；答案/评分共用重新校验状态，拒绝后不继续显示缓存正文。历史侧栏关闭时和动画结束后双路径恢复焦点 |

手工复核关注：同键查询缓存与锁序、失败事务后重放、后续评分不能改写已选基线、当前清单/权限再校验、金额精度、未知分母、SQL有界与归属。未发现本片阻断问题；并发事实以真实MySQL验收为准，不以单元替身证明事务安全。

前端覆盖入口：运行详情→逐题人工评审→评分历史；质量基线→选择弹窗/历史；比较完整页→两侧回答/引用侧栏→返回。当前版本三屏及相关弹窗/侧栏截图已实际查看，详见前端docs/qa/s8d/TEST_RESULT.md。共享QuestionReportView在S8C原7组回归下通过。

Handoff：backend-implementation / frontend-implementation / implementation-validation，S8D必需验收PASS；限制及历史失败见[TEST_RESULT](TEST_RESULT.md)。完整51项计划仍继续S8E，不以本片交付替代总目标。

代码卫生初次扫描把忽略的deploy/model-proxy/.venv第三方Decision枚举误作业务重复。保留code-hygiene-unscoped.json；最终使用同一规则和Git差异，只将ConventionDiscovery输入限制为git ls-files --cached --others --exclude-standard可见文件，得到COMPLETE_WITH_LIMITATIONS，未改工具/阈值或删除环境。语义常量数值和短事务提示已人工复核；前端ComparisonStatus与资料发布Status属于不同领域，不能合并枚举。
