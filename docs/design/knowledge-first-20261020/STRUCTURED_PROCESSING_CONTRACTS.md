# 结构化知识准备契约 v2

2026-10-24，S9A / C-PARSE，设计冻结；实现从S9B开始。选型/所有权见 [解析设计](PARSING_DESIGN.md)。原 [P01/P02/v1发布](PROCESSING_CONTRACTS.md)、K04/K05、E01/E02/E03继续有效，仅按本文件显式扩展格式、来源与读取接口。

## 1. 闭合类型和初始profile

| 类型 | 稳定code与含义 |
| --- | --- |
| DocumentFormat | `PDF`、`DOCX`；原`text/plain`/`text/markdown`继续v1 |
| BlockKind | `PDF_PAGE`、`PARAGRAPH`、`TABLE_CELL` |
| preparation schema | `knowledge-preparation-v2` |
| extraction schema | `knowledge-extraction-v1` |
| parserProfile | PDF=`pdfbox-3.0.8-native-text-v1`；DOCX=`poi-6.0.0-body-v1` |
| 初始processorVersion | PDF=`pdf-v2-pdfbox3.0.8-char512-overlap64-embedding-v4-1024`；DOCX=`docx-v2-poi6.0.0-char512-overlap64-embedding-v4-1024` |
| Embedding | 仍为`text-embedding-v4`、1024维；profile注册表固定关联parser、分块、模型和维度，禁止客户端任意拼接 |

枚举用显式稳定code，Java/Python按同一契约映射；本契约列出的字段均必需，可空字段显式传null。拒绝未知字段、未知枚举、重复JSON属性、非法Unicode/NUL、非规范UUID、布尔/文本冒充数字。SHA-256为64位小写十六进制，字节用UTF-8无BOM；不做Unicode NFC/NFKC重写。业务提示复用现有错误/消息机制，不另建全局错误目录。

S9B只启用PDF profile；S9C验收后才能启用DOCX。后续结构分块必须新profile，不用同一processorVersion悄悄更改内容或片段身份。S9D新增版本见第9节；旧版本和摘要算法不变。

## 2. 原文件受理

沿用K01同一幂等键/请求指纹和owner隔离。TXT/MD保持原1MiB及UTF-8规则；PDF/DOCX原文件上限各10MiB。后缀、请求MIME、文件签名和实际容器类型联合验证，不只相信扩展名；PDF=`application/pdf`，DOCX=`application/vnd.openxmlformats-officedocument.wordprocessingml.document`。错误MIME/签名、宏文件、其它Office ZIP和任意压缩包拒绝。

原文件`sourceHash`永远是收到的原始字节SHA-256，`sourceSize`是实际字节数。标题/正文提取、换行规范化、重试均不改变它；相同提取文字不等于相同原文件。沿用原去重范围，不跨owner泄露hash命中。

受理时持久冻结preparationSchema、processorVersion和parserProfile；在途任务不能跟随运行配置改版本。HTTP受理成功仍只表示保存/排队，不代表解析或发布完成。网关、Servlet、身份桥与前端需要匹配有界上传大小，不能扩大无关JSON接口上限。

## 3. 解析子进程协议 P03

Knowledge父进程仅用固定Java可执行文件、固定JAR及允许列表参数启动解析器；format取已验证任务，不从文件名组成命令。stdin为恰好sourceSize个原字节，读取超限即失败；不传用户URL、S3地址、凭据、SQL或任意文件路径。stdout为至多4MiB的UTF-8 JSON，仅允许一个完整对象；stderr至多64KiB且不向用户透传。环境为允许列表，临时目录任务独占。

成功退出0，输出Extraction；固定失败退出20=INVALID、21=ENCRYPTED、22=TEXT_UNAVAILABLE、23=RESOURCE_LIMIT、24=UNSUPPORTED_STRUCTURE、25=INTERNAL。失败不能同时返回成功包；其它退出、无效输出、进程启动失败由父进程映射安全错误。错误常量归解析协议，状态/code不靠日志字符串识别。

### Extraction

| 字段 | 约束 |
| --- | --- |
| schemaVersion / format / parserProfile | 上节闭合值，须与任务固定配置相等 |
| sourceHash / sourceSize | 原字节hash与1..10MiB实际大小；父进程独立比对S3读取结果 |
| blocks | 1..1000，严格原结构顺序；ordinal连续从0；包括空页/空段/空单元格记录，空文字不产生向量 |
| warnings | 0..1000条，去重并按code、locationKey排序；固定code及可定位的结构来源，不能带任意错误正文 |
| extractionDigest | 按第5节计算，绑定解析版本、原文、全部结构块和告警 |

每个block为`{ordinal,blockId,kind,text,textHash,headingLevel,location}`。text为规范提取文字，允许LF/TAB但拒绝NUL和其它C0控制符；所有block文本UTF-8总量≤1MiB。headingLevel为null或1..9，仅来自DOCX有效标题样式/outline定义，不从字号或文本内容猜测；非PARAGRAPH必须为null。textHash绑定该块完整text，空文本也有hash。

Location为按kind判别的闭合对象，不允许不相关字段：

| kind | location字段与语义 | locationKey |
| --- | --- | --- |
| PDF_PAGE | `{page}`；真实文件页序1..200，包含空页；不使用印刷页码或重排后页码 | `pdf:<page>` |
| PARAGRAPH | `{bodyIndex,paragraphIndex}`；均从1开始，bodyIndex计顶层正文段/表顺序，paragraphIndex计所有顶层段（含空段） | `docx:p:<bodyIndex>:<paragraphIndex>` |
| TABLE_CELL | `{bodyIndex,tableIndex,row,column,rowSpan,columnSpan,header}`；索引从1开始；tableIndex计顶层表，row/column为真实逻辑格坐标，span为正整数，header来自明确表头行标记 | `docx:t:<bodyIndex>:<tableIndex>:<row>:<column>:<rowSpan>:<columnSpan>:<header>`，header编码0或1 |

bodyIndex/paragraphIndex/tableIndex/row≤1000，column≤100，span不得超出所属表格边界。每个独立单元格只发一个块，矩形横/纵合并发其锚格及真实span；延续格不复制正文，不接受带冲突正文的延续格。不规则/重叠合并、缺失必要网格而无法确定坐标、嵌套表明确失败，不能把展开后的重复格当原文。空格和表头正文均保留。单元格内多个段按原序用LF连接；普通段内run/超链接按正文顺序拼接，TAB/换行作为文本保留。

### 提取语义

- PDF使用PDFBox逐页文本提取、固定按位置排序，word separator为空格、line separator为LF；不跨页拼文字，不承诺多栏阅读顺序或版式复现，不做OCR、图片识别、附件/XFA/脚本处理。空页保留空block和`PAGE_NO_TEXT`告警；所有页均无可索引文字则失败，不调用Embedding。加密PDF统一拒绝，不接受密码字段，也不尝试空密码解密降级。
- DOCX使用XWPF正文顺序，保留标题/空段/普通表格和上述矩形合并。只读XML，不加载外链。页码由排版环境决定，禁止给DOCX伪造页码。只取正文；已知页眉页脚、注释、脚注尾注、普通图片不入索引但必须给对应告警。文本框、修订标记、altChunk、嵌入OLE、嵌套表等本期不支持结构明确失败；宏文件按格式拒绝。
- 提取器仅将CRLF/CR规范为LF；PDF库本身产生的空格/行序与DOCX段连接属于固定parserProfile语义。不做修辞、OCR纠错、概括或模型补全；原文件和提取文本永远分别标识。

Warning为`{code,locationKey}`，locationKey为实际block来源或null（格式整体告警）。稳定code：`PAGE_NO_TEXT`、`PDF_LAYOUT_APPROXIMATE`、`PDF_NON_TEXT_CONTENT_OMITTED`、`DOCX_HEADER_FOOTER_OMITTED`、`DOCX_COMMENTS_OMITTED`、`DOCX_FOOTNOTES_ENDNOTES_OMITTED`、`DOCX_NON_TEXT_CONTENT_OMITTED`、`DOCX_EXTERNAL_LINK_TEXT_ONLY`。PDF后两种能力边界告警按格式给出，不伪称已检测全部图像/版面；其它告警依据真实结构。未知结构不能随意变成warning后跳过。

## 4. 结构准备接口 P04

`POST /internal/v2/knowledge/prepare`，不经公共网关。沿P01的Knowledge签名、tenant/sub、audience、scope=`knowledge:prepare`和最长60秒凭证；调用者在签发前检查当前owner/任务租约。原P01端点不接受v2字段。

请求为`{tenantId,ownerId,documentId,jobId,documentVersion,processorVersion,extraction}`。身份/UUID/版本规则沿v1；HTTP正文≤6MiB，extraction已按P03持久接受。Python独立重算提取摘要和结构/文本约束；它不持有原文件，所以不声称独立验证原始二进制hash，只能验证Java委托绑定。未知profile、字段、模型或维度在任何付费调用前拒绝。

初始LlamaIndex profile逐非空block执行既有字符SentenceSplitter语义：512码点、重叠≤64，定位到该block的原提取文本；不跨块、不加入标题/提示前缀/自动metadata到Embedding正文；空白块不调用模型。超过总100块在Embedding前整体拒绝，不截断。S9D新profile遵守第9节结构策略和下面的片段规则。

200返回`{schemaVersion,tenantId,ownerId,documentId,jobId,documentVersion,sourceHash,extractionDigest,processorVersion,embeddingModel,dimensions,chunks,batchDigest}`。

chunk为`{ordinal,chunkId,text,textHash,segments,sourceDigest,vector,vectorHash}`，ordinal连续从0，总1..100；text非空白、≤512码点；vector正好1024个有限binary64数，拒绝布尔/NaN/Inf。segment为`{blockId,startByte,endByte}`，区间指向**该提取块text的UTF-8字节**，左闭右开、非空、码点边界，不指向原文件二进制。每块1..16个segment；初始PDF/DOCX profile只允许1个，S9D新profile允许1..16个。

同一chunk的text必须等于按顺序取出segment字节后以单个LF连接的结果；连接LF是声明的结构分隔符，不冒称原文件字符。segment按原结构顺序，无重复、倒退或越界；跨chunk重叠仍≤64码点，且各非空block的非空白内容都被覆盖，不能遗漏中间文字。只校验textHash而不重建segment不合格。

sourceDigest绑定完整segment及其block来源，chunkId同时绑定tenant/owner/doc/version、原hash、提取摘要、处理profile和ordinal。Java必须全部重验后才能接受或产生索引副作用，禁止相信Python提供的页号/显示文案。

共享准入/总期限/取消/预算规则沿P01；v1与v2共用同一个并发额度，不因增加端点翻倍。AI处理关闭时两端点都关闭。P02保持原模型和查询语义；新格式使用同模型兼容profile集合，不修改原RRF算法。

## 5. 跨语言摘要（无自指、无浮点JSON规范化）

以下`H`表示UTF-8字符串的SHA-256；`joinLF`无末尾换行。所有参与摘要的字段顺序固定，标识符/闭合code/locationKey无换行，整数十进制无前导零；headingLevel的null编码`-`。数组顺序属于协议，不能排序blocks或segments。为防记录分组歧义使用固定段标记。

```text
textHash = H(text)
blockId = H(joinLF("extraction-block-v1", parserProfile, sourceHash,
                  ordinal, kind, locationKey, headingLevelOrDash, textHash))
warningKey = code + ":" + (locationKey or "-")
extractionDigest = H(joinLF(schemaVersion, format, parserProfile, sourceHash,
                           sourceSize, "blocks",
                           joinLF(each blockId + ":" + textHash),
                           "warnings", joinLF(each sorted warningKey)))
sourceDigest = H(joinLF("extraction-segments-v1", extractionDigest,
                       each blockId + ":" + startByte + ":" + endByte))
chunkId = H(joinLF("prepared-chunk-v2", tenantId, ownerId, documentId,
                  documentVersion, sourceHash, extractionDigest,
                  processorVersion, ordinal, textHash, sourceDigest))
vectorHash = SHA256(concat(each IEEE754 binary64 big-endian value))
batchDigest = H(joinLF("knowledge-preparation-v2", processorVersion,
                      embeddingModel, dimensions, extractionDigest, "chunks",
                      each chunkId + ":" + sourceDigest + ":" + vectorHash))
```

extractionDigest中blocks段和warnings段各作为一个字符串元素参加外层joinLF；空warnings段为空字符串，因此末尾会产生该字段对应的一个分隔LF，这个LF属于协议。不能使用“去掉所有末尾空行”改变空数组摘要。vectorHash保持v1负零位模式。JSON键顺序/空白不参与摘要；sourceSize与原hash仍须Java对真实字节核对。S9B必须增加共享v2金标与Java/Python实际DTO测试，任何字段/来源/摘要漂移须失败。

## 6. 来源读取 K06 与旧消费者

K04/K05的Hit及Q04/work/E02输出形状不加字段，source仍为`{displayName,locator}`，displayName来自权威文档。原`utf8-bytes:<start>-<end>`保留；新locator由Java从已验证segments生成，不信任模型文本：

- PDF：`pdf-pages:1`或`pdf-pages:1,3`，去重页号升序，不把空隙表示成连续区间。
- DOCX段：`docx-blocks:p2`；格：`docx-blocks:t1r2c1`；多个真实来源如`docx-blocks:p2,t1r2c1`，按原结构顺序去重，不能混入PDF来源。p为顶层paragraphIndex，t/r/c分别为tableIndex/row/column；跨格完整span在详细接口读取。
- locator≤512个ASCII字符，页/段/格数量≤16；解析闭合语法和正整数范围，未知前缀明确失败。Python当前CitationSource正则必须先扩展，QA/评测/研究共用它；不能默认旧运行时接受新值。无新增实际page/paragraph语义的任意字符串。

`GET /api/v1/knowledge/documents/{documentId}/versions/{version}/processing-metadata`：当前可见已发布版本返回`{documentId,version,sourceHash,format,preparationSchema,processorVersion,parserProfile,extractionDigest,warnings}`。v1返回format=`TEXT`或`MARKDOWN`，parserProfile/extractionDigest为null、warnings空；其它字段为原事实，不伪造解析器。

`GET /api/v1/knowledge/documents/{documentId}/versions/{version}/chunks/{chunkId}/source`：先执行与K04直接引用相同的当前权限/发布/batch校验，返回`{documentId,version,chunkId,sourceHash,extractionDigest,segments}`。v2的segments为`{blockId,startByte,endByte,kind,headingLevel,location}`；v1使用单条`{blockId:null,startByte,endByte,kind:"UTF8_BYTES",headingLevel:null,location:null}`，extractionDigest为null。该端点不返回额外正文、原文件下载地址或S3路径；正文继续走原K04引用。

两接口只读、无模型调用、不缓存上次权限正文；同K04身份/数据权限，无权、不存在、已撤下/失效版本统一404。每个响应≤256KiB；未发布的解析告警不经这些发布读取端点泄露，当前任务失败仍通过原ingestion错误code展示。取消/撤权后前端丢弃旧结果，加载/错误不复用旧结构。只读运行身份没有自动获得新端点权限，首期仅公共workbench读取。

UI明确显示“提取文本”、PDF真实页序/DOCX段表位置和告警；不将提取文本伪装为PDF原版渲染，不暗示DOCX有确定页码。没有新原文件下载/在线编辑能力。

## 7. 初始资源配置与错误

这些值是拒绝/隔离策略，不是已测得的容量SLA。S9B须在本机验证故障边界，配置变动不能悄悄改已固定parser/processor语义。

| 项目 | 初始限制 |
| --- | --- |
| 二进制原文 / TXT-MD | 10MiB / 原1MiB，实际流量计数 |
| PDF页 / 提取块 / 提取文本 | 200页 / 1000块 / 总1MiB UTF-8 |
| DOCX ZIP | ≤2000条目，单条解压≤16MiB、总解压≤32MiB、压缩膨胀比≤100（小条目≤100KiB不按比率误伤，但仍计总量），不得仅信ZIP头 |
| 解析执行 | 同实例1个，无内存队列；20秒壁钟期限，计入120秒任务总期限；stdout4MiB、stderr64KiB |
| JVM | 堆128MiB、直接内存32MiB、元空间96MiB；失败可回收。它们不构成总RSS硬上限 |
| P04 | 请求/返回各6MiB；v1/v2合计原并发额度；分块≤100、每块≤512码点 |

父进程收到中断/失租/超限先停止读取接受，终止子进程并有界等待，必要时强制终止该已知子进程及其子孙，不能杀按名称匹配的其它Java进程。未退出时占用槽位，不能释放名额后叠加进程。解析器不得执行其它程序；子进程清理测试必须证明没有遗留进程。

| code | HTTP映射 / retryable | 集中默认文案 |
| --- | --- | --- |
| KNOWLEDGE_UNSUPPORTED_FORMAT | 沿用415 / false | 当前仅支持已启用的文本、Markdown、PDF和DOCX资料。 |
| KNOWLEDGE_FILE_TOO_LARGE | 沿用413 / false | 资料大小超过当前允许范围。 |
| KNOWLEDGE_PARSE_INVALID | 422 / false | 资料无法解析，请检查文件是否完整。 |
| KNOWLEDGE_PARSE_ENCRYPTED | 422 / false | 暂不支持加密资料，请提供可直接读取的文件。 |
| KNOWLEDGE_PARSE_TEXT_UNAVAILABLE | 422 / false | 未提取到可索引文字，请提供含文字的资料。 |
| KNOWLEDGE_PARSE_RESOURCE_LIMIT | 422 / false | 资料超过当前解析范围，请拆分后重试。 |
| KNOWLEDGE_PARSE_UNSUPPORTED_STRUCTURE | 422 / false | 资料包含暂不支持的结构，请调整文件后重试。 |
| KNOWLEDGE_PARSER_UNAVAILABLE | 503 / true | 资料解析服务暂不可用，请稍后重试。 |

解析时间/输出/堆耗尽按RESOURCE_LIMIT；启动失败或非输入引起的基础设施异常按PARSER_UNAVAILABLE。retryable沿原最多3次显式重试，不在解析器/Python重复叠加内部重试。P-v2输入、容量、期限与结果错误复用ProcessingCode及P01 HTTP映射；hash/来源验证失败由Java按原SOURCE_INVALID/PREPARATION_INVALID处理。任务查询HTTP仍正常返回任务状态和安全code，不用上述直接错误HTTP覆盖查询协议。

## 8. 接受、恢复与版本窗口

提取指针与准备指针独立：首个合法结果由同一job/doc/version、sourceHash/profile、lease owner/epoch和未过期租约CAS接受，已接受不能覆盖。所有外部IO都在短事务之外。S3写入成功但CAS未知时先查询；不接受的对象只能保留为孤立候选，不能驱动下一阶段。

原任务总期限、失租和逐sink恢复继续有效。新worker读旧任务的v1 schema/profile，不换解析器；旧准备包不补v2字段。二进制新受理开关最后开启；Python/Java/前端/CI共享来源规则先到位。关闭新受理不删除既有任务或引用；回退版本必须仍能读取v2提取/准备对象、locator和新表列，否则暂停该类worker并保持数据，不能硬交给v1程序。

完整生命周期/更新/撤下/重索引以S10A[生命周期契约](LIFECYCLE_CONTRACTS.md)为准，按S10B–D实施；S9D只新增算法profile，不因S9新增格式自动创建内容v2、旧数据回填或索引清理。S8E原金标绑定保持原批次与原来源；新增格式的机制样本单独标注，不能把旧标注当新块质量结论。

## 9. S9D结构profile（2026-10-25冻结，实现验收另记）

| 格式 | 新processorVersion | parser / schema |
| --- | --- | --- |
| PDF | `pdf-v3-pdfbox3.0.8-pages512-overlap64-embedding-v4-1024` | 原PDF parser / preparation-v2 |
| DOCX | `docx-v3-poi6.0.0-structure512-overlap64-ctx256-embedding-v4-1024` | 原DOCX parser / preparation-v2 |

**受理与兼容。** `KNOWLEDGE_STRUCTURED_PROFILE`为类型化配置，闭合值`BLOCK_V2`（默认）或`STRUCTURE_V3`；未知值启动失败。仅新PDF/DOCX任务选择算法，TXT/MD不变，用户不能在上传API指定版本。幂等重放和任务恢复始终读取原持久三元组；切回BLOCK_V2不影响已存在v3的处理、检索和引用。数据库只追加V9三元组约束扩展；现有五种profile均与同一Embedding兼容。关闭新选择不是回滚到不认识v3的旧程序。

**Document分组。** 按真实提取块顺序遍历。非空白块以一个LF连接，原块文字不trim；空白块保留提取身份但不加入Document。DOCX遇到任何真实标题（含空标题）先结束前组；标题栈弹出大于等于当前级别的项，非空标题入栈。连续普通段/标题后的段落成组，最多16个非空块；段落与表格不能混组。表格只合并同一tableIndex/bodyIndex/锚格row的格，每组最多16个非空块；矩形合并仍只有原锚格。PDF按顺序最多两个物理相邻的非空页一组，空页阻断跨页连接。空白段可以跳过，但空白表格/行仍结束原段/行组，不跨真实结构边界。

**Node转换。** 锁定LlamaIndex SentenceSplitter的`get_nodes_from_documents`转换每个Document，字符tokenizer、保留分句符的分句器、512/64与旧版本相同；禁用metadata继承及prev/next关系。分块完成后才添加增强metadata，避免它隐式占用正文512预算。用单调有界搜索独立将Node原text定位回组文字并映射为原块UTF-8片段；不信任框架对重复文本给出的首个位置。超过100chunk/16segment或无法完整还原时，在任何Embedding调用前整体拒绝；不截断。

**增强与原文。** PDF不推断标题。DOCX每组的增强来自当前非空真实标题栈和该表中严格早于当前行的显式header锚格（原序，跳过空白格）；不猜第一行为表头，不使用后续行。固定上下文模板：有标题时`章节：\n`加标题原文以LF连接；有表头时`表头：\n`加表头原文以LF连接；两个部分用LF连接；整体上下文最多256码点，超出按INPUT_TOO_LARGE拒绝，不裁剪。组内全部Node使用同一上下文。仅存入metadata键`source_context`，metadata_template=`{value}`，text_template=`{metadata_str}\n\n{content}`；按LlamaIndex MetadataMode.EMBED生成模型输入，NONE产生回传text。无上下文时模型输入就是原text。技术ID、hash、坐标不加入Embedding，LLM模式排除此metadata，不调用默认模型/Settings。框架对增强字符串外侧strip属于固定profile；原文不会因此重写。context不进入segments，也不显示成引用原句；它可由已绑定提取物与profile确定性重建，P04不加字段。向量摘要不能证明模型输入，真实输入捕获另作验收证据。

**两端独立验证。** 在所有非空白提取块以单LF连接的规范来源流上建立原块字节起点。每chunk的segments严格原序、无重复、落在同一个上述结构组；LF拼接结果必须同时等于chunk.text和来源流从首片起点到末片终点的连续区间，禁止跳过中间非空白块。跨chunk起点与终点均严格递增，空隙只能为空白，整个重叠区间（含跨块LF）的码点总数≤64；末尾无遗漏非空白正文。初始profile仍限单segment。所有hash/ID/批次规则沿第5节，不能仅重签摘要绕过结构/覆盖限制。

**对照边界。** 固定原文件字节/hash与来源坐标，在隔离owner下接受旧/新profile，或复用同文件已有真实结果；比较块数、覆盖、跨结构来源、模型输入增强和相同查询召回。S8E原金标/批次不重绑；新ID不直接与旧ID比较。代码只验Unicode/换行/文本来源保真，不宣称AST分块。U01/U02未提供代表性业务语料时仅报告机制对照，不能宣称检索质量提升。无新聊天、Reranker、模型预算、数据库或框架依赖。
