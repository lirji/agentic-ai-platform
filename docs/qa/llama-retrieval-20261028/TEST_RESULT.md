# S11C 验收结果

状态：实现与本片必需验收 **PASS**；Git/CI另见交付收据。[正式契约](../../design/knowledge-first-20261020/RETRIEVAL_CONTRACTS.md)，439项后端输入（含共享金标）、484项前端输入与固定运行版本见[validation.json](validation.json)。

| 验收 | 结果 | 证据 |
| --- | --- | --- |
| RT-04/07 融合等价 | PASS | 实际Java KnowledgeSearch和原生AuthorizedFusionRetriever执行同三组金标，比较一基RRF60、原排名空位、同分chunkId与完整身份；同文不同资料/修订不误并 |
| RT-08 框架边界 | PASS | 原生BaseRetriever/TextNode/NodeWithScore；一次Embedding/候选/融合；Settings陷阱、异常global_handler、根事件监听器证明无默认模型、全局回调或查询/正文事件；框架对象不进入JSON证据 |
| RT-09 期限/容量 | PASS | 单调总期限及阶段剩余额度；共享容量2/第三请求立即拒绝；Embedding/HTTP/候选取消释放容量，无重试、无后续阶段；S11E负责应用单例装配 |
| 严格协议/失败 | PASS | 独立身份JWT、精确字段/路径；拒绝重复/未知/snake_case字段、错序、重复身份、正文hash/来源漂移、非有限score、超限响应；失败不伪装为空召回 |
| RT-14 最终权威 | PASS（S11C部分） | 最终完整身份、顺序、正文和来源重验；真实容器拒绝旧索引代次与其他owner；公开消费者/历史引用适配留S11E |
| 实际运行 | PASS | 快照680ec91cfe82c3a9，9容器健康、10项只读smoke；新管线通过真实Java/数据库/双索引，用固定协议向量完成1次Embedding适配调用、1次候选和1次最终解析，见[live-result](live-result.json) |

全Python445通过、52个显式集成默认跳过；其中本片只读集成另以AGENTIC_RETRIEVAL_IT=1执行，1通过。Ruff格式/检查、mypy56源码、compileall通过；19个已观察依赖警告保留。Java默认verify154单元通过，包含1项测试执行全部三组共享金标。241项Java产品/SQL/构建输入与S11B逐项hash相同，沿用其133项真实集成证据，未声称本片重跑。前端源未改，容器构建实际通过114测试及格式、lint、类型、生产构建。

验证由同一执行者分阶段完成，无独立Agent。开发资料仍61份、旧Embedding/chat账本hash未变，真实模型新增调用0。旧Java/QA/研究/评测消费者未切换，策略持久化与生产装配归S11E；独立重排模型及预算归S11D。未声明业务质量提升、生产容量SLO或端到端生产取消保证。审查见[REVIEW](REVIEW.md)，实现与早期修正见[IMPLEMENTATION_EVIDENCE](IMPLEMENTATION_EVIDENCE.md)。
