# S15C 跨语言诊断与业务审计验收

状态：PASS（本地机制验收）；Git/CI 状态见权威进度及交付收据。当前源文件摘要见 source-evidence.json。未运行付费模型，无生产采集切换。

| 验收 | 结果与证据 |
| --- | --- |
| OB01 | PASS：KnowledgeSharingIT.javaPythonJavaTraceCrossesRealHttpBoundaries 使用真实Java BoundedHttp/Micrometer → Python ASGI/OTel → Java Servlet/Security socket调用，trace相同、出站span不同，401错误体保留trace。12项Python诊断测试补充父子span、非法/重复/全零traceparent。 |
| OB02 | PASS：29并发asyncio上下文、嵌套取消恢复、单线程池10次复用无串号；现有worker相关窄测试通过，根span按taskId/epoch建立。Java执行器未修改，沿既有TTL/Reactor与worker边界。 |
| OB03 | PASS：HTTP安全拒绝、检索异常、模型子span的状态及整段检索/处理/worker耗时可关联；错误消息不进span。队列/索引继续沿Java job/epoch、knowledge.ingestion.events和处理诊断，原实现未修改，现有Java相关测试通过；不声称新增生产故障演练。 |
| OB04 | PASS：指标标签仅stage/outcome固定枚举；敏感合成标记不出现在span/log。实际OTLP protobuf测试证明环境认证头与资源敏感属性不外发，模型出口无内部trace/baggage。日志handler失败单独计数。 |
| OB05 | PASS：阻塞exporter、4条测试队列饱和后100个业务阶段仍在1秒内完成；真实HTTP collector 400拒绝不阻断业务、关停小于5秒。默认无专用端点时不创建exporter，即使有通用OTEL endpoint。数字是本机测试边界而非生产SLO。 |
| OB06 | PASS：UsageBudgetIT 14项无跳过；原owner能查RESERVED/DISPATCHED/UNKNOWN/SETTLED，重复报告不新增；跨actor/tenant和错误scope拒绝，no-store/脱敏。KnowledgeSharingIT 14项中12通过、2旧条件场景跳过（浏览器/评测联合）；新增共享分页、撤权后历史、越权和跨语言全部通过。发布/补偿查询复用S14C/D原契约和已验收实现，没有改写业务事实。 |
| OB07 | PASS：Java reactor verify；Python 749通过/97原条件跳过/34既有警告；最终诊断错误目录整理后61相关测试复验通过。Ruff format/check与mypy通过。前端源码未改，4组相关消费者36项通过；没有新增可见UI，视觉检查N/A。原14注册契约/15wire fixture与usage边界检查通过。 |

## 失败与修正

- 首次跨语言测试未启用Spring Boot test真实观测，trace未接续；加AutoConfigureObservability并断言真实traceparent注入后，两次真实socket复验通过，不用手填trace替代。
- 阶段装饰器最初把返回标为Awaitable，不能满足create_task的Coroutine契约；修正类型后mypy与原HTTP处理测试通过。
- 共享分页常量抽取漏类限定导致编译失败；补SharingModels限定，最终完整Java构建与两组IT重新通过。
- 卫生检查曾发现日志handler异常被吞；现在明确计入diagnostic.log.dropped，业务不中断。依赖授权来自既有S15C OTel路线与本次C-OBS契约，门禁逐包登记。

## 限制与边界

卫生门禁 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：Java仓库没有专用formatter/static analyzer；沿周边风格、编译、测试与差异检查。Python工具已实际执行，不用根目录发现器的缺失替代Python结果。没有修改全局技能规则。

新增OTel锁定依赖10包经PyPI当前版本元数据核对，兼容Python3.12、许可证见dependency-review.json，未报告漏洞；这不是完整漏洞扫描或生产安全承诺。requests从原锁定间接依赖提升为直接声明，版本未变。

原Chat/Embedding累计账本SHA256未变，Rerank/Judge无新增费用；测试资料只写已登记隔离test库/原有对象命名空间，不删除旧数据。无需数据库迁移。原服务、Docker和前端运行预览未改；临时测试HTTP/Java进程正常退出。审计数据保留期、正式采集设施和生产部署仍按原授权边界。

本地日志：.local/s15c-java-final.log、.local/s15c-python-final.log、.local/s15c-refined-tests.log、.logs/s15c-knowledge-it.log、.logs/s15c-usage-it.log、.local/s15c-web-consumers.log。可提交摘要及日志摘要见source-evidence.json；日志与私密.local不提交。
