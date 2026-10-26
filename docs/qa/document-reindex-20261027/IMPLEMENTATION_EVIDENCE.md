# S10D 实现证据

范围：已批准固定快照重索引、索引核验、有界候选扩展及前端恢复。两仓原目录分支`feat/document-reindex-20261027`，基线backend `f2243d4` / frontend `dbc77b2`。复用V10、P05及现有MySQL/S3/ES/Qdrant/PG，不新增组件、依赖、迁移或物理清理。

| 位置 | 行为与原因 |
| --- | --- |
| LifecycleController / Service / Models | 严格3字段JSON，拒绝重复键/尾随JSON、错误数值或未知profile；202异步回执；指纹冻结已发布源，不读取失败新稿 |
| LifecycleMapper / MySqlLifecycleRepository | document→job锁序；独立generation、发布基线、命令及审计同事务。内容version、最新文件元数据和原ingestionJobId不变；UPDATE/REINDEX互斥；关闭开关/删除后仍可恢复原回执 |
| MySqlProcessingRepository | REINDEX重试只恢复独立处理任务，不覆盖原内容任务或失败草稿。发布沿原完整元组CAS，失败保留旧发布 |
| IngestionRunner / DualSearchIndex | 两路全部ID、数量、身份与batchDigest回读，ES另核对正文；最多100项。DONE恢复也重验，失败不发布；只重写未确认sink，不重做已绑定准备/提取 |
| KnowledgeSearch / BoundedHttp | 20→60→100；原一基排名先计分再授权过滤；只做一次查询Embedding；AI+原两路索引预算作为同一总期限，HTTP消耗剩余预算 |
| React ReindexActions / DocumentPage | 当前发布版选择处理方式；原键及非正文恢复参数在原sessionStorage登记表中保留。409显式重确认，未知命令暂停旧引用展示，终态重读发布代次与引用；状态明确属于当前处理任务 |
| Runtime | reindexesEnabled默认false，依赖generation；回填与所有写开关互斥。旧配置缺新字段仍关闭新命令；本地代理仅新增已实现POST |

反例复核及修正：跨profile真实故障测试发现原retry强制更新原内容任务，导致REINDEX无法恢复；修正后PDF/DOCX两例均通过，解析和准备次数各2（初次+重建），恢复不重复。补充完整JSON解析防止重复字段/尾随对象歧义。前端检查发现同内容代次不触发内容版本轮询，补终态权威刷新、代次变化重挂引用；未知命令期间隐藏旧引用。最初截图捕获过早，改为等待有限动画结束，并仅只读重开当前弹层重新截图，无额外重建。

测试环境修正：未引入的Mockito改手写端口；构造器补新增默认开关；jsdom每例恢复ResizeObserver替身。未降低业务断言，未新增依赖。早期失败原因记录在本文件；最终原始验证输出保留于`.logs/s10d-*`，重复运行的同名日志以最新结果为准。

同固定PDF/DOCX原文件分别运行真实LlamaIndex V2/V3算法，分块数4→2及13→6；新代次ID不同，旧金标不改。这是协议与来源机制对照，不能证明语义质量提升；受控向量样本与原标签不混用、不改旧baseline。

运行时正常停止旧Java后构建，开关关闭/开启/关闭重放均验证，再恢复本地服务和qa-worker。新建1份TXT，另有无效PDF失败新稿、两次同版重索引及两次搜索。累计Embedding70批/0.721784元，chat24次/0.889964元，各1元限额及旧资料保留。真实历史QA/研究/助手采用合成完成结果，只验证新代次对旧正文/引用/上下文的授权约束。

证据范围见[TEST_RESULT](TEST_RESULT.md)、[validation.json](validation.json)。同一Agent分阶段复核，不冒称独立Agent或机器Runtime认证。
