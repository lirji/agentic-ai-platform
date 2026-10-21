# S6/S7 验收

2026-10-21，研究分析与只读助手；后端 S6 必需验收 **PASS**，前端 S7 结果见独立仓库 [S7 验收](https://github.com/lirji/agentic-ai-platform-web/blob/main/docs/qa/s7/TEST_RESULT.md)。Git 实际交付以 [当前进度](../../design/knowledge-first-20261020/PROGRESS_STATE.md) 为准。

| 验收 | 方法与结果 | 证据 |
| --- | --- | --- |
| 受理/幂等/输入/分页/身份/旧任务 | 真实 MySQL 与公开 HTTP，PASS；非法 kind/mode、同键异参、跨 owner/tenant/游标、写 scope 均拒绝 | TaskPersistenceIT、[HTTP](http-smoke.json) |
| 工具与模型边界 | 只允许 search_knowledge/read_evidence；禁用文件、shell、委派与隐藏摘要；工具最多5/搜索2/模型3、预算发送前预留，未知响应不重试；PASS | test_work_pipeline.py、test_work_chat.py |
| 阶段恢复与取消/撤权 | 4项真实PG+中央测试，analyze/verify新代恢复、最终引用撤权、模型中取消；PASS | .logs/s6-recovery.log、完整Python回归 |
| 真实模型 | DeepSeek flash 的 REPORT/EXPLAIN/CHECKLIST/DRAFT 均成功，有授权引用与实际工具记录；无资料成功返回 NO_EVIDENCE，聊天增量0 | [真实模型](real-model.json) |
| 重启与结果重放 | Tasks/网关正常重启，代理/worker再启动；5个已完成任务、原文与SSE只读重放，配置和两本账本不变，新增调用0 | [重启](restart-replay.json) |
| SSE 与网关 | 成功保持 text/event-stream，缺身份仍JSON401；前端检查点只推进游标；PASS | IdentityExchangeTest、TaskPersistenceIT、公开HTTP、浏览器真实阶段 |
| 共享回归/构建 | 292 Python通过/21跳过；78 Java通过；Ruff、mypy 41源码、Python wheel/sdist、Maven构建通过 | .logs/s6-{python,java,gateway,mypy,build}-final.log |
| 代码卫生 | 后端 COMPLETE_WITH_LIMITATIONS，17建议无阻断；前端 COMPLETE | [代码卫生](code-hygiene.json)、前端S7同名文件 |

Python 21项跳过是原K05专用验收20项和独立代理1项，未把它们计为通过；对应未改边界保留 S5D 证据。本轮通过真实模型授权检索/引用及撤权恢复测试覆盖新增联合路径。Java全量76项后追加网关2项回归，总78，0失败/错误/跳过；未重复计算相同测试报告。

首次真实研究因提供方一轮返回多个工具而被严格适配拒绝；保留原失败任务与预算预留。核对协议后允许至多5个实际工具调用，并在执行锁内串行计数；随后全部真实案例通过。曾发现工具上限检查在锁外、默认子工具组装及内部 checkpoint 继承不兼容，均已修正并有测试；没有放开根图namespace或反序列化类型。真实网关错误备用 Mono 提前设置 JSON 头导致进度回放失败，已用 defer 和针对性测试修复，实际浏览器阶段回放通过。此前静态/断言/定位器失败日志保留，不作为通过证据。

人工审查重点为数据所有权、受信身份、SQL参数化/稳定排序、版本兼容、工具集合/串行次数、取消/恢复、提交未知结果及引用重验；未发现当前范围内未处置的阻断项。Java格式工具未配置：沿已有格式、编译和 diff 检查，不能宣称自动Java格式化通过。17项卫生建议：边界数值在协议模型/语义常量中，脚本超时属于测试；WorkMode与旧Mode名称相似但业务和协议独立，无重复错误目录。没有因工具限制添加第二套格式或错误体系。

Deep Agents analyze是原子阶段，进程退出可能重做该阶段并再次计费；无内部saver且显式durability=exit时锁定框架产生23条无效durability提示，已核对无隐藏持久化或子图恢复。当前仅单worker、少量合成资料；不构成广泛质量、并发容量或生产部署验收。Vite原有共享包约639KB警告保留，不新增打包依赖。

累计保守预留：聊天 **0.510952/1元，14次**；Embedding **0.26/1元，26批**，不是提供方账单。真实UI写测试在worker停止时完成，任务CANCELLED、attemptCount=0；现在worker已恢复，数据、原失败任务、私密配置/恢复编号和预算全部保留。

当前源码以 [摘要](source-fingerprint.json) 绑定；[实现映射](IMPLEMENTATION_EVIDENCE.md)。全部本地服务ready/运行，无生产部署或数据清理。
