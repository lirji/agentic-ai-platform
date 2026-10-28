# S11C 实施证据

状态：实现、离线与固定容器真实只读验收PASS；交付结果另记。基线44915aebe55f4f6e8cecd5011ee6bbc5131522e8，feat/llama-retrieval-20261028；[正式契约](../../design/knowledge-first-20261020/RETRIEVAL_CONTRACTS.md)。

新retrieval包提供严格身份/候选/贡献模型、复用K05身份和连接管理的RetrievalClient、单调根期限/应用共享容量、原生AuthorizedFusionRetriever及RrfRetrievalPipeline。先一次Embedding，Java授权候选一次请求，VECTOR→KEYWORD按原一基rank执行RRF60，最后选K并整体重新解析。JSON证据可进入后续检查点，模型/HTTP客户端/框架节点不进入持久状态。旧Java融合、QA/研究/评测消费者未切换，装配与策略落盘归S11E。

49项新离线行为测试、共享三组金标（实际旧Java KnowledgeSearch与新原生Retriever均执行）；全Python445通过、52显式集成默认跳过，19个已观察依赖警告；Ruff格式/检查、mypy56源码与compileall通过。Java默认verify154单元通过，未重复无变化的Java数据库集成；其产品/SQL沿S11B 133真实集成证据。日志`.logs/s11c-python-verified.log`、`.logs/s11c-java.log`。

现场源码发现CallbackManager([])会继承Settings或global_handler，BaseRetriever公共异步入口会发出含查询/节点的全局事件。实现用非空构造守卫和公开set_handlers([])建立空回调，并仅隔离本应用模块的dispatcher；重写原生异步入口进入_aretrieve，不改全局框架设置。测试以不可访问Settings、异常global_handler、根事件监听器证明没有默认模型/回调/内容事件。节点ID绑定完整8分量身份；同文不同资料/修订均保留。详见[框架取证](framework-evidence.json)。

初次mypy发现固定路由字符串缺Literal标注，已补类型并复验；新增集成用例的嵌入脚本长行由Ruff阻止，已换行。审查把HTTP数值改成HTTPStatus，后续全Python重新通过。早期日志保留，没有降低断言或新增依赖。代码卫生无阻断；9项数值建议为协议常量/严格字段边界，Java无formatter的仓库限制保留，Python实际Ruff检查已执行。

验证后的439项后端输入（含共享JSON金标）与隔离构建manifest逐项一致，前端484项输入保持原提交4a9a280。固定快照680ec91cfe82c3a9已启动9健康容器，10项只读smoke和1项实际原生管线集成通过。只读集成在现有容器共享网络使用独立runtime签名和固定协议向量，未开放宿主内部端口；拒绝旧代次与其他owner，资料61份和旧费用账本未变。构建、启动和测试会话均已结束；[完整验收](TEST_RESULT.md)。
