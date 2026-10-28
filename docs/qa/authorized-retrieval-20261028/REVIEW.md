# S11B 审查

状态：源码/全Java与固定容器运行验收PASS。审查与实现由同一执行者分阶段完成，不宣称独立Agent认证。

- 授权：新SQL直接复用既有publishedScope，必须同时匹配可信tenant/owner、ACTIVE/lifecycle_ready、当前发布修订/代次/任务、READY、batch/schema/extraction与兼容profile；返回名称来自发布修订。真实索引污染/失败新稿/撤下/删除和重索引覆盖正反例。
- 完整身份：解析一次批量读取，8个字段全匹配并保持输入顺序；任何失效整体404，数据库失败503，没有旧缓存或索引正文后门。textHash由实际UTF-8正文复算；PDF/DOCX来源回归与原权威一致。
- 有界资源：128KiB严格请求、最多100×2候选、最多50解析、1MiB响应；20/60/100不重复Embedding或融合，同一向量/剩余期限。旧搜索和新原语共享2个permit且finally释放；满载不排队。中断标记保留并停止后续步骤。SQL沿既有连接/语句超时，前后检查剩余期限；不宣称网络断开可以即时终止所有底层I/O或回滚已发生效果。
- 兼容：旧SearchIndex接口/响应不变，只有DualSearchIndex新增读端口；旧Java融合保留。runtime密钥/issuer/audience/azp/TTL保持，精确两个POST只读路由；不存在Java→Python→Java递归调用或父子互占permit。未来公共门面须遵守同一边界。
- 代码治理：沿ProcessingJson/KnowledgeError/Mapper XML/ChunkSources，未新增DDL/依赖/线程池/基础设施。5条卫生建议均为已有语义常量定义；无阻断。Java formatter与独立静态分析未配置，保留限制。

源码核对后运行153单元+133真实集成全部通过。先前夹具编译、代次和外键失败在实施证据中记录，未通过削弱断言/数据库约束处理。协议向量只证明边界，不代表语义召回质量。U04未满足，Reranker与跨消费者融合属于后续切片。
