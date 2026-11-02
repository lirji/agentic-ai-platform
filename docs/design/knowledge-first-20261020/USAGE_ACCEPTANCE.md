# S15A/B · 用量预算验收矩阵

S15A只验设计闭合；下列运行项是S15B必须完成的要求，已在隔离环境验收PASS，逐项证据见[S15B TEST_RESULT](../../qa/shared-usage-budget-20261102/TEST_RESULT.md)。无付费隔离夹具可证明机制，不能证明模型价格/真实账单/生产容量。正式契约：[usage-v1](USAGE_CONTRACTS.md)。

| ID | 场景 | 必须观察到的结果 / 证据 |
| --- | --- | --- |
| UA01 | 真实MySQL，多实例争同一账户的最后一份额度 | 准入总和不超过内部可用额；拒绝者无attempt/审计半条、无模型发送；账户与逐条合计一致 |
| UA02 | 同attempt并发预留/同键异输入、同operation恢复 | 同请求一次占额；异请求409；operation稳定、epoch变化不清零；事务回滚无漏账 |
| UA03 | 同attempt并发dispatch及true响应丢失 | 最多一次true；重放false；丢响应后不发送旧attempt；新实际尝试另预留，旧UNKNOWN保留 |
| UA04 | cancel/超时/断流/连接异常/worker退出/失租 | 已获许可保持占额；无TTL退款；旧worker不能新预留或dispatch；合法迟到报告仍能结算 |
| UA05 | release与dispatch竞争 | 仅一个合法迁移；释放后绝无发送许可；重复release不多退，已dispatch拒绝release |
| UA06 | 完整usage重复/乱序/响应丢失/迟到 | 一次结算；UNKNOWN→SETTLED；SETTLED不退回；同终态异payload冲突；故障恢复原attempt查询 |
| UA07 | 缺usage、部分维度、负值/float/NaN/溢出/超body | 不当0结算；保留占额并明确错误；合法超预留usage记录真值、冻结并阻止新准入 |
| UA08 | CNY/舍入/价格版本/单位 | 金额字符串和Decimal一致，向上6位；旧attempt按原价格；bytes不冒充tokens、成本不叫账单 |
| UA09 | 身份/归因/签名上下文 | 伪造tenant/actor/account、过期上下文、跨租户/其他用户查询均拒绝；共享读者归自身；资料owner不被代扣个人费用 |
| UA10 | 付费出口覆盖及恢复 | 聊天/Agent/Embedding批/重排/裁判全部通过同一中央门；无配置拒绝；假模型计数与dispatch许可一致；旧工具次数和权限不旁路 |
| UA11 | 中央服务/DB故障、取消后报告重试 | 不回退文件/不暗重试模型；结算失败保持held，原payload可重报；请求/连接/队列有界，日志无正文/密钥 |
| UA12 | 旧账本冻结导入/重复/篡改/中断 | limit原额、reserved连续、未知不释放；同源唯一；合计不符拒绝；缺历史归因不猜；无旧writer与中央双写 |
| UA13 | CENTRAL后代码回滚/重启/旧checkpoint | 额度不清零、不激活旧文件；新模式不支持的旧进程拒绝付费；审计和旧账本保留，无删除迁移 |
| UA14 | 本人安全视图及真实浏览器 | 分页稳定、金额语义清楚、loading/error/empty可区分；撤权不泄漏正文；无其他用户记录/全局余额；窄屏/键盘可用 |
| UA15 | 模块与数据库边界 | 静态/架构测试禁止跨域Mapper/Row/直接写表及循环；迁移表/列注释齐全；真实DB事务证明不以mock替代 |
| UA16 | 迁移机制与实际启用分开 | 隔离合成导入通过可交付机制；原Chat/Embedding账本金额未变，Rerank/Judge无新增预算，生产未迁移 |

故障注入在隔离测试进程/数据上执行，保留诊断和未知操作编号；不能删除真实账本/重置数据让测试通过。发现实现不满足验收时修实现，不删除矩阵项。最终记录每项PASS/FAIL/NOT_RUN及原始日志、版本、源码摘要、跳过原因；有必需NOT_RUN不能把S15B写DONE。
