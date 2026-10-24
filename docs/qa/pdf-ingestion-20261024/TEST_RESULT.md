# S9B 验证记录（进行中）

当前结论：**P03/P04 基础批次验证 PASS；S9B 整体未完成。** 该批次实现独立 PDF 解析、有界进程控制、结构化准备校验和引用来源消费者；尚未开放上传，也未接入提取物持久接受、K06 或前端。后续验收仍按 [PAR-01..15](../../design/knowledge-first-20261020/PARSING_ACCEPTANCE.md) 推进。

## 已验证范围

- Java 全模块无数据库回归：86 项通过。最终受影响的解析/协议/进程测试顺序复验 15 项通过；独立解析器打包通过。日志 `.logs/s9b-foundation-java.log`、`s9b-foundation-java-targeted.log`、`s9b-parser-package.log`。首次全量测试与独立解析器打包时间重叠，最终相关模块已单独串行复验，不只依赖交叠构建的测试报告。
- Python 全部默认测试：326 passed / 49 条件集成 skipped / 19 warnings；Ruff 格式/lint、47 个源文件 mypy 通过。日志 `.logs/s9b-foundation-python.log`、`s9b-foundation-mypy.log`；跳过项不计真实数据库/模型验收。
- Java/Python 使用同一份原生 PDF 与中文 PDF 金标：实际 PDFBox 提取、LlamaIndex 分块、严格 DTO、原字节 hash、空页、页序、片段 UTF-8 偏移、负零 binary64、所有摘要层一致。伪造位置、遗漏正文、重签摘要后错误偏移、重复/未知/缺失属性和布尔冒充数字均拒绝。
- 真实 JVM 故障：卡死、stdout/stderr 爆量、128MiB 堆 OOM、异常退出、父线程中断、单槽饱和及已知子孙进程终止；返回前证明进程结束和槽位归还。父进程环境仅显式设置 LANG/TZ，拒绝继承服务配置。
- 真实打包产物经过实际 ParserProcess 父进程执行两个固定 PDF，分别返回 3/4 页、摘要与共享金标一致，完成后 active=0。日志 `.logs/s9b-{native,chinese}-parent-probe-retry.log`。产物和 12 个嵌入依赖摘要见 [parser-artifact.json](parser-artifact.json)；PDFBox/FontBox 3.0.8，日志使用 JCL→SLF4J/Logback stderr，产物没有 POI 或原 commons-logging。
- 中文自建 PDF 保留中文/英文字体、空页、旋转双栏、图片页；第 4 页无文字告警，未 OCR 或推断图像文字。[样本来源](fixture-manifest.json)固定源文件及 Noto Sans SC 官方提交/字体摘要；OFL 许可随夹具保存。实际提取见 [chinese-extraction.json](chinese-extraction.json)。
- P04 HTTP 沿用原身份/owner、总期限和同一并发计数器；v2 运行时 v1 请求得到繁忙，取消后可恢复。QA/评测/研究共用的 CitationSource 接受受约束新 locator 并拒绝伪造页序。5 份登记契约与 4 份共享线格式样本检查通过。

## 修正与边界

首轮进程故障测试将 macOS Java 自行加入的 `__CF_USER_TEXT_ENCODING` 误判为父环境泄漏；独立仅 LANG/TZ 环境探针证实后修正测试，无扩大父环境继承。首次实际父进程探针把 SimpleMeterRegistry 当作 AutoCloseable，编译失败；改为显式 close 后两个样本通过。两次失败日志均保留。

夹具生成初次借用了 S9A 探针 classpath，曾显示其无 Log4j provider 告警；产品解析 JAR 使用自己的完整依赖及 stderr 配置，两次实际父进程执行均无该告警。固定夹具不能为通过测试静默重生成；完整字体只在忽略目录，仓库保存 PDF 中所需子集及许可。

没有付费调用；账本仍为聊天 0.889964/1 元（24 次）、Embedding 0.42/1 元（42 批），是保守预留，不是账单。未改运行服务 JAR；在确认无活动 local-dev 任务后正常暂停 qa-worker，以免源代码更新期间意外消费预算。运行/恢复入口见唯一 [PROGRESS_STATE](../../design/knowledge-first-20261020/PROGRESS_STATE.md)。

后续必需：提取接受 CAS/未知提交/失租恢复、兼容迁移、真实双索引混合检索/评测、K06 权限/界面、原预算内有限真实 Embedding、浏览器 1440/1024/414 和最终两仓 CI/Git 交付。当前未证明真实业务样本质量、任意缺失字体映射可恢复、多栏语义顺序、容量 SLA 或完整操作系统沙箱。JVM 堆/直接/元空间限制不等于 RSS 上限。没有独立子 Agent 审查或全局工作流 Runtime Gate 冒称。
