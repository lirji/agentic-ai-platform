# S9F · 表格来源与引用验收

本地验证 PASS；Git/CI 收据单独记录，未宣称生产部署。用户最新要求是在 S9F、S18B、S18C 全部交付后更新并启动现有本地 Docker，因此当前保留原应用容器。

| 验收 | 证据与结果 |
| --- | --- |
| 真实格式与显示值 | `SheetExtractorTest` 15项：前导零、1900/1904日期、公式缓存/缺缓存、引号/字段换行/空行、多表/隐藏、合并/宽行、超限、非法UTF-8、宏/外链/DTD拒绝；PASS |
| 独立来源与代次 | Java/Python真实共享金标，P04及两个P05代次，工作表名/行列/覆盖、重签伪造/错组/摘要漂移拒绝；Java最终GenerationProcessingTest 12项，Python相关117项PASS |
| Java回归 | 全仓package 249项，243通过、6个需本机OCR配置的既有测试按条件跳过；OCR代码未变，沿用S9E实际专项证据；新增表格15项均实际执行。最终新增日期测试及P05金标以定向报告为准 |
| 真实存储链路 | 27项KnowledgeStructuredProcessingIT全部PASS，包含两种表格实际解析→MySQL/S3→Qdrant/ES→授权来源及跨用户/租户拒绝。测试向量为协议金标，未调用付费模型 |
| 浏览器与前端 | 254项单元测试、TypeScript、ESLint、Prettier、生产构建PASS；Chromium实际1440/414宽度显示工作表与合并范围，撤权隐藏、恢复重读PASS。两张截图已查看，无横向页面溢出。浏览器使用真实金标派生API夹具，存储真实性由上项IT证明 |
| Linux隔离解析 | [linux-result](linux-result.json)：固定Java21.0.12_8基镜像、ARM64、无网络/只读/384MiB，两格式输出逐字段等于共享金标，无OOM。单次含容器启动耗时XLSX7.23秒、CSV4.58秒，不是P95/容量证明 |
| 配置与迁移 | V21只扩展固定profile CHECK，真实test库20→21成功；`--spreadsheets`缺`--parsing`在prepare副作用前拒绝。新上传开关默认false，历史格式/算法共存。原dev库未迁移 |
| 文档与代码卫生 | 15份注册契约/15份既有wire fixture检查、309个Java源文件边界检查PASS。后端COMPLETE_WITH_LIMITATIONS：仓库未配置统一Java格式化/静态分析工具，沿周围风格及编译/测试审查；Python Ruff/mypy PASS。前端COMPLETE |

格式、公式、表头推定、宽表、上下文256码点及兼容边界以[契约](../../design/knowledge-first-20261020/SPREADSHEET_CONTRACTS.md)为准。Commons CSV1.14.1为已记录技术选择；卫生工具初次因未登记依赖报阻断，补充选择证据和显式依赖登记后通过，并非绕过库风险审查。

首轮编译发现POI迭代器返回Cell接口，改用确定的XSSFCell缓存访问；首轮Python新增测试误用不存在的from_wire方法已修正。配置守卫首次测试漏传已有必需参数frontend，只触发CLI参数拒绝，补齐后验证实际业务守卫。失败日志保留，没有降低业务断言。

日志在后端`.logs/s9f/`及前端`.logs/s9f-*`。固定文件见[fixture-manifest](fixture-manifest.json)，源码摘要见[source-evidence](source-evidence.json)。新增两种格式P04/P05金标在`knowledge-service/src/test/resources/`；生成脚本不会覆盖已有输出。所有受控样本不替代真实业务质量评估。

隔离搜索复用本任务`agentic-s9e-qdrant/elastic`，探针`agentic-s9f-xlsx-probe/csv-probe`均已退出，数据/镜像/日志保留。原搜索与应用容器未改动；后续统一本地部署由用户新授权执行，无生产部署或清理。
