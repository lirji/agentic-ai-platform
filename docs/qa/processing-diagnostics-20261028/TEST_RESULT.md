# S10F 验收结果

状态：实现与本地必需验收 **PASS**，Git/CI交付另见收据。正式契约为LIFECYCLE_CONTRACTS第9节；精确版本见[validation.json](validation.json)。

| 验收 | 结果 | 证据与可观察行为 |
| --- | --- | --- |
| 当前任务筛选/分页 | PASS | ProcessingDiagnosticsIT真实MySQL：ACTIVE当前任务、status/stage/retryable、上限50、跨身份/条件游标拒绝；REINDEX名称来自原修订 |
| 原CAS受理事实 | PASS | 真实test库竞争仅一方受理，RETRY事务事件可在后续推进/撤下/删除后核对；固定5字段；异owner/tenant为null，数据库故障503 |
| 严格接口与权限 | PASS | 新GET参数/DTO契约IT；当前容器16项只读HTTP检查涵盖401/403/400、缺失/重复/未知/溢出参数；[HTTP](http-result.json) |
| 有界批次与恢复 | PASS | 8项恢复单元与浏览器：最多10项、不可重试/只读无提交、串行、逐项结果、UNKNOWN停止后续、刷新不POST、查事件后沿原revision继续 |
| 持久化故障/竞态 | PASS | 发送前存储UNKNOWN，存储失败不发；已受理不可降级、批次ID阻止迟到回调覆盖新批；409先核对原事件 |
| 诊断与来源历史 | PASS | 失败阶段/修正建议；当前发布来源告警不冒充失败新稿；旧READY与当前发布区分；历史判权404/503与删除未知结果回归 |
| 三视口与真实UI | PASS | 1440/1024/414协议与只读真实浏览器；实际查看任务1440、部分结果/来源历史414及真实任务1440/失败414/来源1024；无页面横向溢出，表格局部横向滚动；[真实UI](real-ui-result.json) |
| 本地容器与预算 | PASS | 固定快照22dff8dd5e6ac896，9健康容器、10项smoke；61条资料，两类在途0，账本hash保持；[运行结果](runtime-result.json) |

Java144单元+127真实组件集成（`.logs/s10f-full-java.log`）；前端114项、格式/ESLint/类型/生产构建在最终容器中全部通过（后端`.logs/s10f-docker-final-build.log`）。10项协议浏览器通过（前端`.logs/s10f-browser-recheck-all.log`），最后异常分支修正后相关8单元/3协议重新通过（`.logs/s10f-{unit,browser}-recovery.log`）；其他7项覆盖的代码未改变。真实浏览器1项通过（`.logs/s10f-real-browser.log`），仅GET/HEAD/OPTIONS，写请求0。

固定构建输入与当前后端416/前端152个产品、配置和测试文件SHA256逐一相符。后端Java复用已验证的相同输入JAR；前端完整重建验证。Python产品源码未改变，本地不重复无关/付费探针，远程CI单独核对。test/dev Knowledge V11、Tasks V4，无新DDL。

[审查](REVIEW.md)和验证由同一执行者分阶段进行，不冒称独立Agent认证。前端代码卫生PASS；后端未配置formatter/独立静态分析，保留工具限制。失败与修正见[实施证据](IMPLEMENTATION_EVIDENCE.md)。

边界：批次保存在当前标签页sessionStorage，服务端按原任务/版本核对；不是持久批处理引擎。dev没有既有RETRY事件，正例在关闭worker的真实test库HTTP验证，不为补证据创建付费重试。没有新增付费调用、数据/对象清理或生产部署。下一切片S11A尚待实施。
