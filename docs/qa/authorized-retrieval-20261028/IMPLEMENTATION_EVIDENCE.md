# S11B 实施证据

状态：实现完成，全Java153单元+133集成PASS；固定容器69248f47394fb22b、29项HTTP与10项smoke PASS，Git/CI另见交付收据。基线25d9e409f41f022c50c7037b826ffaf6535df7f6，分支feat/authorized-retrieval-20261028。

依据[正式检索契约](../../design/knowledge-first-20261020/RETRIEVAL_CONTRACTS.md)。新增RuntimeRetrievalController、AuthorizedRetrieval、RetrievalModels/Protocol、RankedSearchIndex、RetrievalRepository/Mapper/XML；DualSearchIndex复用原请求并增加真实score读取。KnowledgeSearch与新原语共用KnowledgeQueryCapacity；旧DTO、路径及融合保持。Mapper复用原publishedScope，读取已发布修订名称与完整代次，不新增DDL/写API/依赖/模型调用。

窄验证：14单元与6真实MySQL/S3/Qdrant/ES/HTTP测试通过，日志`.logs/s11b-narrow-current.log`。覆盖20/60/100、原排名空位、跨源去重、全部8个身份分量、2并发/即时BUSY、同一剩余预算/中断后停止、独立RSA、严格JSON与字节边界、污染索引、失败新稿/重索引/撤下/删除、SQL依赖故障。

早期失败已修正并保留：测试var复合声明/Identity导入冲突；P05测试误用P03准备包及旧重载；直接改发布代次被真实外键拒绝，改为走真实reindex与双索引发布验证旧身份失效。没有关闭约束或放宽产品校验。日志`.logs/s11b-{unit,unit-retry,retrieval-it,retrieval-it-retry,retrieval-it-generation,retrieval-narrow-final}.log`。增加KnowledgeReindexIT断言，验证PDF/DOCX新原语来源位置与旧权威读取一致。

[代码卫生](code-hygiene.json)：无阻断项；5条数值建议均落在命名协议常量定义，保留。仓库未配置Java formatter/独立静态分析，明确工具限制，不增依赖。使用已有错误码、严格ProcessingJson、Mapper XML与原索引过滤，不打印正文/查询/向量/身份。前端与Python产品未修改，费用账本保持。

容器只向宿主发布Gateway/Web端口。首次宿主直连28084探针连接拒绝，未发送模型或业务写入；随后按实际拓扑从既有AI容器共享网络执行同一只读探针，没有开放新端口。原失败`.logs/s11b-http.log`保留。
