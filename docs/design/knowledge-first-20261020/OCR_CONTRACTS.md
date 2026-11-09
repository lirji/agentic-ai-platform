# S9E 扫描PDF解析增量

2026-11-09，用户已明确启用中英扫描PDF及原生/扫描混合页。此文件是C-PARSE的有界增量；既有PDF/DOCX提取、P04/P05、授权/发布/引用协议继续有效，不修改旧profile行为。

## 选择与所有权

Knowledge继续独占原文、提取物、发布和索引；Java一次性解析进程使用现有PDFBox 3.0.8渲染并调用本地Tesseract 5.5.4。选择INTEGRATE：复用既有解析隔离，CPU运行、不外发资料；原生PDFBox不能识别扫描页，云OCR引入外部传输/费用且未授权，PaddleOCR增加模型运行栈而当前只需中英文字提取。代价是图像识别误差、语言包和本地可执行文件的版本管理；不承诺手写、多栏布局或表格结构还原。

官方依据：[Tesseract 5.5.4发布](https://github.com/tesseract-ocr/tesseract/releases/tag/5.5.4)、[命令行及TSV](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html)、[tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd)。使用固定提交的chi_sim/eng语言包（Apache-2.0）；构建清单保存来源和SHA-256，运行前拒绝错误版本/语言包。OCR能力默认关闭，管理员配置固定绝对路径；不从用户输入组装路径、语言、URL或命令。

## 固定版本与输出

- parserProfile=`pdfbox-3.0.8-tesseract-5.5.4-chi_sim-eng-v1`；processorVersion=`pdf-v4-tesseract5.5.4-pages512-overlap64-embedding-v4-1024`。
- format仍为PDF，extraction schema仍为knowledge-extraction-v1。保持原始文件hash、真实一基页序、PDF_PAGE块、来源摘要和pdf-pages引用；OCR结果标为提取文本，不当作原文转录保证。
- 新profile有图像的页面执行整页OCR；纯文字页沿既有原生提取，空白/无法映射页可进入OCR。PDF资源仅在文件内解析，不加载外链。旧PDF_V2/V3永不自动调用OCR。
- 新profile沿PDF_V3相邻页分块规则；Java/Python同时绑定parser与processor，禁止拿旧提取物冒充新profile。重索引和新受理均冻结已选profile；默认新受理不变，管理员可显式选择OCR_V4。
- 增加页级告警PDF_OCR_APPLIED、PDF_OCR_LOW_CONFIDENCE；后者当任意识别词置信度低于70或OCR页无可用文字时发出，必须同时有同页APPLIED。告警不是准确率，前端定位到具体页并提醒核对。保留PDF_LAYOUT_APPROXIMATE、PDF_NON_TEXT_CONTENT_OMITTED及空页PAGE_NO_TEXT。所有页都无文字仍失败，解析错误/超时不得以空结果当成功。
- 严格拒绝未知告警、重复/未排序告警、不存在页号、旧profile带OCR告警或低置信告警缺对应OCR页；跨语言摘要算法不变。

## 资源与失败

- 保留原10MiB文件、1MiB总提取文字、4MiB stdout、64KiB stderr、父进程20秒总期限/槽位/取消回收；旧PDF原生200页上限不变。
- OCR profile最多10页；200DPI、每页最多500万像素、整份最多5000万像素，在分配光栅前校验尺寸/旋转/UserUnit及有限值。采用图像子采样，OOM明确资源失败。
- 每次只允许一个Tesseract子进程、OMP_THREAD_LIMIT=1，每页最多5秒；其stdout/stderr分别受4MiB/64KiB上限约束。关闭stdin，无shell调用；超时/中断/超限回收子进程再释放父槽位。任务私有临时图像只在已回收后清理。
- 复用P03稳定退出码与Knowledge错误目录：格式/加密/无文字/资源上限/依赖不可用分别失败。凭据、输入文字、底层异常不进入日志/错误正文；不新增外部模型调用。

## 持久化与兼容

Knowledge V20追加扩展ck_ingestion_profile，固定OCR三元组同时适用于P04和P05；不修改历史迁移、不回填旧任务。混合部署先升级读取者再启用新受理；旧服务不识别新profile，回退前关闭OCR受理并处理在途/已发布新版本，不能宣称透明回退。配置与构建见[Runtime](../../qa/scanned-pdf-20261109/RUNTIME.md)。

## 验收

OE01：原生/中英扫描/混合页真实解析、固定页号和来源摘要；受控清晰样本按去空白字符对照CER≤5%，报告语言包、样本与测量范围。

OE02：低清/低置信/空页告警可定位；全空、损坏、加密输入和错误引擎/语言包明确失败；受控质量不冒充真实业务质量。

OE03：页数/像素/文本/管道/耗时上限、取消与子孙进程回收；超限无准备/发布副作用，旧profile回归不变。

OE04：Java/Python共享提取与来源金标、旧新profile错配拒绝、结构分块/发布/检索/引用与撤权边界；费用使用替身且原账本不变。

OE05：真实UI呈现OCR和低置信页告警、重索引选项及失败恢复；真实截图核验。Runtime构建/启动探针有独立证据，不变更现有部署。
