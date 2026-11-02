# S15C 实现与审查记录

依既有 S15C/C-OBS 只增加诊断与只读审计，未改变服务边界、租约、模型收费或持久写路径。

- Python独立OTel provider保留W3C上下文；ASGI纯中间件不读取正文，8个HTTP适配器保留原连接/超时限制。仅内部Knowledge/Tasks/Usage/Evaluation注入traceparent；外部模型去除trace/tracestate/baggage。完整检索/处理及worker有独立阶段。
- asyncio context scope以finally恢复；持久worker以taskId/epoch关联新根，不保存临时context到图/请求。采样不决定计数，HTTP错误有独立结果值，避免NonRecordingSpan丢掉错误指标。
- SDK白名单资源、固定标签、显式collector origin、有限异步batch；出口会话不继承代理/netrc，发送前剥离通用环境认证头，禁止重定向。当前不支持需要认证头的collector；启用范围由运维明确。
- Knowledge原sharing_command追加流水按owner/tenant/document及revision倒序查询；Tasks仅JOIN自身usage三表，身份在查询中再次过滤，最多5个状态事件。无跨库JOIN，无迁移，无资金池/服务执行者/摘要泄漏。
- Gateway精确GET审计路径沿既有登录/内部JWT，受众仍Tasks；新路径常量集中于ModelUsageProtocol。原UI消费者未改，新增运维查询文档化，不增加未定义运维角色。

审查为同一Agent分开执行的源码/契约/失败场景pass，不冒称独立Agent审查。重点检查：跨用户与大小写身份沿原SQL二进制约束；游标不授予权限；重试不新增审计；取消不中断原异常传播；日志错误只影响诊断计数；旧API/图版本不变。

验证结论由[TEST_RESULT](TEST_RESULT.md)与对应日志/源码摘要支持。技能：backend-architecture-design（C-OBS兼容增量）、backend-implementation、runtime-and-deploy（精确网关路由/运行文档）、implementation-validation、update-progress-docs；Git独立通过task-git-delivery/ci-cd-gate。
