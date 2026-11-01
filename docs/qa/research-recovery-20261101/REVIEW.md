# S12D 有界代码审查

2026-11-01，PASS；当前无阻断Finding。同一Agent依据已批准契约独立切换审查pass，不称独立人员审查。

| 场景 | 实现与证据 | 判定 |
| --- | --- | --- |
| 配置切换改变重放图 | v4独立API/输入/graphVersion，关闭仅拒绝新任务，旧版本保留；ResearchAllowanceIT | PASS |
| 恢复绕过冻结目标或撤权 | StagedResearchPipeline.before_restore输入比对；GenerationKnowledge重新复核完整依赖，中央epoch确认；6单元/12PG | PASS |
| 已确认计划被重复生成 | plan单独根节点，analyze仅消费快照；4阶段接管、旧v1/v3回归 | PASS |
| 未确认阶段重复费用消失 | 复用中央行锁预留，跨epoch不退还；提交未知两分支分别2/3模型次，计划始终1次 | PASS |
| 计划模型擅自调用工具 | 空工具请求不发送tools，异常tool_calls拒绝；分析保持原只读工具白名单 | PASS |
| 恢复进度泄漏或造假 | 仅v4精确三字段、有限阶段及3/5/3计数；非法额外字段/越界过滤；前端严格解析 | PASS |
| 历史状态重放请求风暴 | WorkProgress整批只刷新一次，真实恢复页刷新/取消通过 | PASS |
| 旧客户端与部署回退 | v1/v2/v3保持，结果v3兼容输入v4；13代理路由、默认false、关闭后drain说明 | PASS |

已修复Finding：恢复输入未显式对照中央；无工具计划端口原先不可用；历史状态重复失效导致503；进度事件字符串治理。受影响验证均补跑，详细日志/摘要见TEST_RESULT与validation.json。

保留局限：Java独立格式/静态工具未配置；卫生工具扫到忽略的旧制品枚举相似提示，与恢复阶段不同业务域，无需合并枚举；未验证生产模型质量/大规模容量，未启用Deep Agents内部saver。没有放宽权限、预算、并发限制或错误测试断言。
