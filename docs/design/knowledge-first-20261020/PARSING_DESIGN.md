# S9A 结构化资料解析设计

2026-10-24，C-PARSE；增量设计，基线后端82eb31c、前端83c0bc6。唯一正式线格式见 [P-v2契约](STRUCTURED_PROCESSING_CONTRACTS.md)，原 [P-v1](PROCESSING_CONTRACTS.md) 保持有效。此文冻结选型与实施边界，不表示PDF/DOCX已经可在线上传。

## 事实、假设与决策

| ID | 类型 | 内容 |
| --- | --- | --- |
| F-P01 | Fact | 当前UploadRules只接受UTF-8 TXT/MD；PreparedDocument逐块核对原文件的UTF-8字节区间，不能把PDF二进制当文本定位 |
| F-P02 | Fact | IngestionRunner已按租约接受一次准备对象、逐sink发布；Knowledge拥有S3/MySQL，Python没有这些写权限 |
| F-P03 | Fact | DualSearchIndex硬过滤单个processorVersion；KnowledgeSearch和EvaluationRow都生成utf8-bytes来源，新增格式必须同时改变这些适配点 |
| F-P04 | Fact | 相同受控样本由PDFBox/POI及pypdf/python-docx读取成功；样本和观察见 [选型验收](../../qa/structured-parsing-20261024/TEST_RESULT.md) |
| A-P01 | Assumption | U02真实业务样本仍缺；先覆盖原生PDF和DOCX正文、段落、普通/矩形合并表格，不假定扫描件比例或业务质量 |
| D-P01 | Decision | 唯一二进制解析者为Knowledge管理的一次性Java子进程；PDFBox3.0.8负责PDF，POI6.0.0的XWPF负责DOCX。Python不再读同一原文件 |
| D-P02 | Decision | 按原计划保留LlamaIndex作为提取块到Document/Node及分块的唯一实现；Java独立验证返回包并唯一发布 |
| D-P03 | Decision | 原文件、提取物、准备包分别不可变且绑定摘要；解析接受后先落权威指针再调用Embedding，恢复复用已经接受的提取物 |
| D-P04 | Decision | TXT/MD继续P-v1和原处理profile；PDF/DOCX使用P-v2，不后台重算旧资料或重绑旧评测金标 |

## 候选与复杂度

| 候选 | 能力与成本 | 决策 |
| --- | --- | --- |
| Java子进程 + PDFBox3.0.8 / POI6.0.0 | 页级PDF文本、DOCX正文顺序与OOXML表格定位；探针解析依赖17个JAR约23.87MB。需子进程启动、日志桥、资源边界和临时文件生命周期 | 选定；现有Knowledge控制原文件、租约和解析作业，原文件不再转交另一个服务解析，结构字段可从同一解析结果产生 |
| Python子进程 + pypdf6.21.2 / python-docx1.2.0 | 同组样本通过，观测进程成本更低；仍需同样的资源/输出边界，合并格需要读取物理XML，不能直接把展开的row.cells当原始格 | 可行替代，未选；会把原文件或新的解析执行器引入AI运行时，不能仅因LlamaIndex有Reader就跳过隔离和Java接受步骤 |
| Java子进程 + Tika通用解析包 | 格式覆盖更广，仍须自定义页/格来源及禁止嵌入资源；引入不需要的格式适配和依赖面 | 本期不引入；仅PDF/DOCX不需要通用自动识别/递归解析。未来格式增长再按具体样本评估 |

这是INTEGRATE现有开源解析库，REUSE_EXISTING Knowledge任务/S3/MySQL，以及BUILD本项目的有界协议适配；没有新的解析HTTP服务、MQ或数据库。进程边界用于异常、超时和Java堆隔离，不把它描述为完整操作系统安全沙箱。

一次受控冷读进程观察：Java0.88秒、RSS峰值约148MiB；Python0.16秒、约38MiB。Java探针使用128MiB堆和32MiB直接内存上限。样本极小、单机单次、磁盘缓存与运行时不同，不能据此推断吞吐、P95、最大文件容量或全局优劣。生成样本的耗时没有混入这次冷读计时。

依赖许可证：PDFBox/POI为Apache-2.0；pypdf许可证含BSD三条款；python-docx为MIT。候选Python探针复用本地缓存的lxml5.4.0，仅用于自建样本，不引入产品依赖。生产解析模块在S9B/S9C锁定完整传递依赖、JAR摘要和日志绑定；本项没有改POM/uv.lock。POI6.0.0为新主版本，探针通过不替代S9C完整DOCX兼容验收。

官方依据：[PDFBox发行/Java要求](https://pdfbox.apache.org/download.html)、[PDFBox安全记录](https://pdfbox.apache.org/security.html)、[POI发行/许可证](https://poi.apache.org/download.html)、[POI组件](https://poi.apache.org/components/index.html)、[pypdf6.21.2](https://github.com/py-pdf/pypdf/releases/tag/6.21.2)、[pypdf许可证](https://github.com/py-pdf/pypdf/blob/37761bd80d76e52b1470b93a5d36f9c51765205c/LICENSE)、[python-docx1.2.0](https://github.com/python-openxml/python-docx/tree/v1.2.0)。PDFBox3.0.8已包含官方所列嵌入文件示例路径修复；本项目不启用附件提取，仍不声称解析器无漏洞。官方网页/制品元数据摘要见验收目录的research-evidence.json。

## 职责和持久流程

1. Knowledge在原上传命令中验证格式/大小、保存原文件和原始hash；二进制内容不按UTF-8解码。受理时冻结`preparationSchema/processorVersion/parserProfile`，客户端不能指定可执行文件或任意profile。
2. 原worker领取同一持久任务，读S3并验证原文件大小/hash；有已接受提取指针时读取复验，否则启动一次性解析进程。父进程传原字节和闭合格式枚举，不传S3路径、密钥、用户文件路径、URL或数据库身份。
3. 解析进程只输出规范提取包。Knowledge验证原hash、profile、结构/文本/告警上限及全部摘要，写本项目S3；短事务用原租约条件首次接受指针、摘要和解析告警。未知提交先查任务；未接受的孤立对象不能推进Embedding或发布。
4. Python P-v2只接收已授权的提取包，校验后用LlamaIndex分块并调用原Embedding适配。Java复验每个来源片段、文本/向量/批次摘要后接受不可变准备包，后续两路索引/发布仍走原机制。
5. 失租、总超时或停止时父进程终止并等待解析子进程退出；迟到输出不能接受。已接受的提取物/准备包保留；重复发布使用原chunk/batch身份。准备包尚未接受时仍可能再次付Embedding费用，沿原账本，不承诺exactly-once。

新增数据仍属于Knowledge：文档/入库任务中的固定profile、提取物S3指针、摘要/字节数、规范来源及告警、准备schema；正文和权威块仍是本库。S9B现场确认迁移下一编号，追加列/约束/注释及Mapper XML，不重写V1–V6。原字节来源列只表示v1；v2来源单独存结构字段，不填虚假的二进制偏移。具体迁移文件属于S9B实现。

解析临时目录为每次执行独占，权限0700；只读输入，输出受父进程限长，业务日志走stderr并脱敏，stdout仅协议。清理仅限本次创建且已退出的临时文件；不能清理业务S3、旧任务、账本或其他目录。没有获得历史数据清理授权。

## 隔离、资源与失败

固定可执行文件/JAR路径和参数数组，禁止shell拼接；子进程环境采用允许列表，不继承服务密钥。PDF只使用页面文本提取，不执行JavaScript、附件、XFA或外部资源；DOCX只读OOXML正文，不执行宏、OLE、altChunk或外链。ZIP条目不解压到用户路径；XML外部实体与外部DTD关闭，DOCX扩展名不能掩盖宏/加密内容。

初始配置预算见P-v2契约。父进程同时限制输入、stdout、stderr、总时间及在途数，按实际读取字节计数；不能只信Content-Length、ZIP声明或Future超时。队列仍是原数据库任务，解析槽位为1，不另建内存排队器。解析时间包含在现有120秒任务期限内，后续AI/索引/S3共享剩余期限。

JVM `-Xmx`限制堆，`MaxDirectMemorySize`限制直接内存，二者都不等于进程RSS硬上限。S9B必须验证父服务在子进程OOM/卡死/输出爆量后的存活、回收和失败状态；不得把本项小样本RSS当内存安全证明。生产操作系统级内存/CPU/文件系统/网络边界由S17固定运行制品落实；当前仅受控本地环境，不因有子进程就宣称可承载任意敌对文件的生产环境。

解析失败先于任何Embedding，不发布部分文档、不截断后继续、不把空提取物当成功。用户可见错误复用KnowledgeError机制；错误消息、告警文案按模块集中，API/任务使用稳定code。底层堆栈、文件内部路径、附件名和敏感正文不向客户端返回。

## 兼容与实施顺序

S9B先加入P-v2接收、PDF解析/页来源和公共引用适配，再开启PDF上传；S9C增加同协议DOCX解析和段/格来源；S9D在S10A冻结生命周期后引入结构分块/跨块片段，不提前改变TXT/MD分块或重算机制基线。

保持P-v1端点、schema和所有旧哈希规则。迁移前已受理的任务按原schema/profile恢复；旧准备对象按v1校验器读取。升级先部署兼容Python，再部署兼容Java/前端，最后开启新格式；不支持v2时拒绝新格式，不能把PDF转换成伪装的TXT提交。v2任务存在后回退只能用仍保留v2读取/恢复能力的版本；不能将其交给v1 worker。

原P02查询向量仍是text-embedding-v4/1024、原query处理语义。PDF/DOCX的新processorVersion加入明确的同Embedding兼容profile集合，两路候选与权威SQL使用同一允许集合；不取消租户/owner/版本/batch过滤，不建第二套RRF。现有索引字段能容纳新字符串，但须通过真实双索引/混合资料测试才可上线；模型/维度变化不在本项授权内。

公开K04/K05和E01/E02/E03的`source={displayName,locator}`线结构保持不变，locator扩展为受约束的页/段/格摘要；内部保留完整片段定位。KnowledgeSearch、EvaluationRow、证据读写及前端引用必须消费同一来源生成器，禁止一个路径仍把PDF标成utf8-bytes。详细结构/告警通过P-v2定义的独立读取端点提供，旧严格消费者不被额外字段破坏。

Python现有CitationSource只允许`utf8-bytes`正则，新增locator值并非对旧运行时透明：S9B必须先升级其闭合解析规则及QA/评测/研究共用消费者，再允许新格式进入K04/K05。原TXT规则继续成立；回退时同样保留新locator读取能力。CI需补共享来源金标，不能仅以字段名没变声称兼容。

## 风险与下一验收

| ID | 风险 | 控制/归属 |
| --- | --- | --- |
| R-P01 | PDF文字顺序、字体映射和扫描页不保证语义正确 | 展示提取文本及真实页号、固定告警；S9B中文/旋转/空页样本；OCR只在S9E触发 |
| R-P02 | 子进程不是完整OS安全沙箱，极端文件可能消耗原生资源 | 堆/输出/期限/数量边界与故障测试；生产硬边界由S17落实，当前不宣称生产安全 |
| R-P03 | 新POI主版本及真实DOCX差异未被小样本覆盖 | S9C补齐纵向合并、标题样式、表头、异常ZIP/XML/外链、已知不支持结构，失败不得静默丢内容 |
| R-P04 | 两路索引或评测来源仍只认识旧profile/字节位置 | S9B同时验收TXT+PDF搜索、直接引用、E01标注与历史S8E可读性 |
| R-P05 | 无真实业务样本 | 明确仅机制/格式验收；不创建业务质量提升或最大容量结论 |
| R-P06 | 解析成功到接受指针之间崩溃 | 同原文件/profile可重解析；已接受则只读复验，不用新结果覆盖原指针；S9B故障验收 |

S9A完成标准是选型、所有权、正式P-v2、资源边界、兼容路线和可执行样本矩阵一致；本项探针不代替S9B/C的真实HTTP、数据库、子进程故障和前端验收。
