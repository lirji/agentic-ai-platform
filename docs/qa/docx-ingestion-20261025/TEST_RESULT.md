# S9C DOCX 入库与段落／表格来源验收

2026-10-25，slice=S9C，本地必需验收PASS。后端基线ebcb94b、前端基线185d3fa；准确待交付版本由[源码指纹](final-validation.json)和[制品摘要](artifacts.json)绑定。Git/CI是后续交付步骤，结果单独写入delivery-result.json与ci-gate.json，不以本地测试代替远程检查。

## 可观察验收

| 验收 | 方法与证据 | 结果 |
| --- | --- | --- |
| 中文标题、空段、正文顺序、表头、横向合并与外链文字（PAR-10） | 真实XWPF提取固定自有中文DOCX；15块/2空块/13片段；稳定摘要、实际标题1/2与显式表头。首次正式上传→真实Embedding→双索引发布→检索命中；[样本清单](fixture-manifest.json) | PASS |
| 纵向/矩形合并、多个表、继承标题、空格、无表头标记（PAR-11） | DocxExtractorTest与DocxStructureTest：真实2×2锚格只出现一次；旧hMerge、多个表/中间段计数、outline覆盖与样式环；无标记不猜测header，冲突/缺网格失败 | PASS |
| 宏、Office伪装、加密、ZIP炸弹、XXE、外链、OLE、修订、嵌套表（PAR-12） | 实际ZIP条目/尺寸/压缩比/CRC与本地HTTP计数器；DTD与非链接外部关系拒绝；外链文字提取不发网络请求。HTTP11入口拒绝、7异步解析失败，均不发布或调用模型；[HTTP结果](http-validation.json) | PASS |
| 真正缺失信息告警（PAR-13） | 固定文件实际包含页眉页脚、批注、脚注尾注、图片和外链，5种告警均可追溯；VML/DrawingML实际图片通过，空绘图与非图片形状拒绝。全空文档TEXT_UNAVAILABLE | PASS |
| 资源边界与跨语言协议（PAR-05/06） | 恰好10MiB有效容器受理、+1字节413；单条16MiB/总32MiB/2000条目/压缩比100、总文本1MiB/1000结构块；分块101在Embedding前拒绝。Java/Python共享真实提取/P04金标、稳定code、类型/摘要/位置/profile错配拒绝 | PASS |
| 接受、恢复与旧格式兼容（PAR-07/08/09） | [结构21项IT](structured-it.json)及[兼容23项IT](compatibility-it.json)去重共42项：真实MySQL/S3/ES/Qdrant/子进程；格式错配拒绝，提取已接受不重解析，准备/已完成sink复用不重复Embedding；TXT/PDF/DOCX共同检索与评测profile | PASS |
| 来源权限和当前发布绑定 | 实时K04/K06核对3种来源的真实字节、标题、位置与摘要；45次跨owner/tenant、scope、匿名、未发布version拒绝。metadata/source不泄漏正文、对象路径或下载URL | PASS |
| 界面与恢复（PAR-14） | 前端70单元、5组Playwright、19张截图实际逐张查看；1440/1024/414px；加载/受控404隐藏正文，恢复重验，抽屉Escape/焦点恢复。旧PDF和Markdown实时复验。[前端报告](https://github.com/lirji/agentic-ai-platform-web/blob/main/docs/qa/docx-ingestion-20261025/TEST_RESULT.md) | PASS |
| 迁移与关闭新受理 | dev/test V8应用；18个旧local-dev任务逐行不变；Tasks/Conversations原PID/JAR保留。关闭DOCX开关时7次既有来源读取200、新上传415；最终恢复且新制品金标/6次来源读取一致。[运行结果](runtime-validation.json) | PASS |
| 构建、静态与代码规范 | Java117单元、Python349通过/49条件集成跳过/19警告、前端70单元及build/typecheck/lint/format。Java依赖补丁后重新执行解析/结构IT；Ruff/mypy通过。Code Hygiene后端COMPLETE_WITH_LIMITATIONS、前端COMPLETE | PASS（限制见下） |

结构分块沿已冻结逐块512/64 profile；跨段、跨页、跨格的新profile属于S9D，依赖S10A，不将该未来验收标成通过。原PDF金标与v1契约未改写。

## 实际模型与数据

成功文档`ab3d5d8d-0910-43b1-833f-2dda9bc9d121`，任务`dd882b66-98a8-42d2-941f-0d132b73d0ef`，READY 13片段。真实检索“资料审核需要多久？”返回合并锚格“资料审核\n两个工作日”，来源为表1第2行第1列、跨度2×2。

本片正式入库一次（2批Embedding），检索一次（1批）；保守预留新增0.03元，累计47批/0.47元，上限1元。聊天24次/0.889964元不变，无Reranker；预留非提供方账单。前端`.local/s9c-ui-state.json`保存原键和真实hits，后端`.local/s9c-http-state.json`保存失败任务，禁止清空后重复未知或成功操作。故障IT测试向量与真实模型结果分开，不以替身证明模型质量。

## 命令与版本绑定

- Java全仓：`mvn -q test`，`.logs/s9c-final-java-unit.log`；依赖最终修正后串行选择`knowledge-parser,knowledge-service -am -Pintegration verify`，限定相关单元及`KnowledgeStructuredProcessingIT`，`.logs/s9c-dependency-final-it.log`。构建前停止Knowledge，未覆盖Tasks/Conversations活动JAR。
- 兼容真实IT：`KnowledgePersistenceIT,KnowledgeProcessingIT,EvaluationPersistenceIT`各7项，及PDF/DOCX双格式已准备结果恢复2项，`.logs/s9c-storage-compatibility-it.log`。其中恢复2项也包含在最终结构21项中，去重42。
- Python：隔离`.local/ci-python-20261024`，从`ai-runtime`执行Ruff format/check、mypy、pytest；日志`.logs/s9c-python-final-{format,lint,mypy,tests}.log`。
- HTTP：`.local/ci-python-20261024/bin/python docs/qa/docx-ingestion-20261025/http_smoke.py`，`.logs/s9c-http.log`；全程模型账本hash不变。
- 前端：锁定Node24.22.0/pnpm12.14.0，`pnpm test/build/typecheck/lint/format:check`及`pnpm exec playwright test tests/docx-real.spec.ts`；日志与截图摘要见前端browser-validation.json。
- Code Hygiene：按实际编译/测试证据执行工具；批准依赖为冻结POI与原SLF4J桥，不隐式批准新基础设施。依赖真实JAR/许可证和Commons Lang定点补丁来源见[研究记录](research-evidence.json)。

## 失败修正与限制

首轮结构IT因MySQL CHECK异常类型断言过窄失败，修正为校验根SQLException错误3819后完整通过。图片正例最初手写XML不完整，改为实际XWPF生成PNG后通过；未知绘图拒绝及类型枚举修正均有回归。Boot BOM导致新传递依赖Commons Lang3.17.0，依据Apache公告仅parser覆盖到3.21.0，最终制品与金标再次通过。原失败日志保留，没有删除业务断言或重写金标来通过测试。

Java仓库没有统一格式器或静态分析器，沿既有风格、差异、编译和测试检查；Python/前端有实际工具检查。49项Python集成测试需要各自显式环境开关，本片未运行，不计PASS。既有Vite包体积、第三方警告及Flyway针对MySQL8.4的版本提示保留，真实本项目迁移已通过。

U02真实业务语料未提供，受控样本不代表任意Word版式、OCR或业务质量覆盖；无容量承诺或OS/RSS沙箱验收。未执行生产部署、清理、跨租户共享或新增预算。审查和验证由本Agent分阶段完成，没有独立子Agent或全局Runtime Gate声明；原工程Runtime多客户端漂移状态不由本片改写。

SKILL_HANDOFF: implementation-validation，slice=S9C，status=COMPLETED，gate=PASS；上述必需验收已执行，源码指纹见final-validation.json。交update-progress-docs记录本片状态，再由task-git-delivery/ci-cd-gate完成两仓交付，随后继续S10A。
