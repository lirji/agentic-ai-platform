# S9C 实现证据

范围为已批准S9C及P03/P04/K06，产品实现在两仓feat/docx-ingestion-20261025；最终验证和交付状态分别见TEST_RESULT及delivery-result.json。没有新增业务服务、模型预算或生产部署。

## 实现与依据

- contracts：闭合PDF/段/格Location，显式格式与类型code；Java/Python分别验证原文绑定、全部摘要、正文顺序、矩形网格、标题和告警。原PDF/v1金标不变，DOCX不伪造页码。
- parser：POI6.0.0/XWPF沿原一次性子进程；实际ZIP边界、重复/路径/宏/CRC/DTD/外部关系校验，正文顺序、空段、样式继承、显式表头、横向/纵向矩形合并仅输出锚格。不支持修订、嵌套表、未知绘图等整体失败；真实VML/DrawingML图片告警省略。
- Knowledge：K01仅有界容器元数据识别，不提取正文；DOCX独立开关默认false。三种处理profile受理时冻结；父进程、S3、数据库接受与P04校验格式和profile一致。V8追加两表MIME128与闭合DOCX约束，保留V7及历史资料。K06沿当前发布/所有者/租户/batch判权。
- Python：规范提取块经真实LlamaIndex逐块512/64处理，分块超100时在模型前拒绝；结构数据只接受稳定类型code。来源compact locator同步严格消费者，不加入第二套分块或解析器。
- 前端：沿现有上传/文档详情/检索/引用组件，真实接口展示标题级别、表头、行列跨度及缺失信息；加载/拒绝隐藏旧正文，恢复重新核对K04/K06。页面没有硬编码样本数据。
- Runtime：dev/test V8均已应用，parsing/pdf/docx显式启用；关闭DOCX新受理不影响已发布来源。Tasks/Conversations原进程/JAR与18个旧local-dev任务保留，所有服务正常恢复。只在停止Knowledge后串行打包，不覆盖活动任务服务制品。
- 依赖审查：POI及相关固定制品/日志桥见artifacts.json、research-evidence.json。发现Boot BOM将新解析链路Commons Lang降至3.17.0；仅parser固定3.21.0消除已公布CVE-2025-48924版本范围，未扩大到全仓换栈。

## 验证记录

Java全仓117单元、Python349通过/49条件集成跳过、前端70单元均通过；42个不同真实持久/索引/解析集成测试通过。浏览器5组与19截图、HTTP11入口拒绝/7失败/3来源/45权限与版本拒绝通过。完整命令、摘要和限制见TEST_RESULT.md、final-validation.json。

固定自有中文样本15结构块（2空块）产生13片段；真实入库2批Embedding、一次检索1批，未调用聊天或Reranker。模拟向量只用于故障集成，真实模型证据与其分开。原成功PDF和TXT/MD只读复验，不重算。

## 修正与边界

- 首轮结构IT中MySQL CHECK异常实际为HY000/3819，测试改为验证真实根异常与错误号，完整复验通过，没有放宽数据库业务约束。
- 收尾增加未知绘图拒绝；最初手写DrawingML缺必需属性，改由真实XWPF写入PNG生成正例，再验证真实解析。既有金标与付费资料结果不变。
- Code Hygiene初检发现块类型比较裸值和未登记依赖；类型改稳定枚举，按已批准POI与既有SLF4J桥记录依赖后复验通过。无Java规范格式器/静态分析器属已存在限制；Python/前端有实际工具验证。
- 本机运行验证首次误调用qa_local.start，开关关闭/读取/恢复已完成；使用正式chat_local.start入口恢复问答worker，查询确认，没有重复付费上传或检索。
- U02真实业务样本未提供；受控样本不证明任意版式、OCR、容量或OS/RSS沙箱。跨段/跨页/跨格新profile属于S9D，依赖S10A，不计入当前已实现范围。

SKILL_HANDOFF: backend-implementation/frontend-implementation/runtime-and-deploy，slice=S9C，status=COMPLETED，gate=PASS_WITH_ASSUMPTIONS；产物为当前源码/迁移/配置与本证据，交implementation-validation核对。没有全局Runtime Gate或独立Agent审查冒称。
