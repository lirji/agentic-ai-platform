# OCR运行与复验

S9E仅扩展显式OCR profile，默认受理仍为BLOCK_V2。既有PDF_V2/V3、DOCX及历史引用保持原版本；OCR不会覆盖原文件。先在Knowledge test库执行V20增量，再运行新writer；迁移保留所有旧三元组。回退旧服务前停止OCR新受理并处理在途OCR任务，旧二进制不负责读取新profile，不能只回退镜像而宣称业务兼容。

本机需固定Tesseract5.5.4可执行文件和`chi_sim+eng`语言目录；[语言清单](language-artifacts.json)固定来源与SHA-256。管理员配置`KNOWLEDGE_OCR_EXECUTABLE`、`KNOWLEDGE_OCR_DATA`绝对路径，解析器逐次校验引擎版本/语言摘要。需要新上传默认OCR时显式设置`KNOWLEDGE_STRUCTURED_PROFILE=OCR_V4`；PDF上传与解析开关仍独立。已发布PDF可在“重建检索索引”选择“扫描文字识别”。依赖缺失、路径错误、版本/语言漂移拒绝处理，不自动回退。

容器使用`deploy/docker/Ocr.Dockerfile`，仅Knowledge的可选镜像增加固定Tesseract5.5.4和[Leptonica1.87.0](https://github.com/DanBloomberg/leptonica/releases/tag/1.87.0)，[源制品摘要](native-artifacts.json)随证据保存。沿现有`runtime.py prepare`增加`--parsing --ocr`选择此镜像；`--structured-profile OCR_V4`还要求`--ocr`。`prepare`会更新本地部署候选指针，属于后续明确部署准备动作；本次验证仅直接构建隔离镜像，不调用prepare/up或变更现有app容器。构建记录最终OCI digest与原生包清单，基础包来自UBI仓库，其解析结果以实际镜像为准。

解析不调用网络，采用固定参数、20秒父期限、5秒引擎期限与有界管道；隔离镜像探针另使用Docker无网络模式，不将其等同于生产OS沙箱。受理上限10MiB；OCR profile最多10页、200DPI、每页500万/总5000万像素；父JVM保留原堆上限。页级低置信告警不等于逐字准确度，且可能在正确识别的页出现；图形语义、手写和表格结构不在此profile承诺范围。

复验入口：`run_integration.py`读取本项目test配置，仅向test数据库/桶与隔离搜索28333/29300写入；需提供上述两个OCR环境变量。Java真实OCR测试需显式`-Docr.executable`和`-Docr.data`，CI未具备引擎时会跳过，不计为真实OCR通过。原生资源测试使用受控可执行夹具，不能代替Linux镜像探针；前端浏览器测试从真实OCR金标派生接口夹具，实际发布/检索/授权由独立真实IT覆盖。

本次保留`.local/s9e`、`.logs/s9e`、test库隔离租户记录与agentic-s9e搜索容器供复现；验证结束停止本任务新建进程和容器，不删除卷、记录、既有容器或用户文件。
