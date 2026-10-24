# S9B PDF 入库与页级引用验收

S9B **PASS / DONE**，两仓必需 CI 通过且产品提交已正常合并推送 main。PDF 正式上传、提取物持久接受、真实 Embedding、双索引发布、页级引用与前端已接通；见 [CI Gate](ci-gate.json) 与 [Git交付收据](delivery-result.json)。只完成 S9B，DOCX、OCR 和跨块结构分块仍由后续切片负责。用户要求本任务交付后暂停。

## 验证结果

| 范围 | 结果与证据 |
| --- | --- |
| Java 单元/构建 | 最终全模块 91 项通过，18 个套件，0 失败/跳过；随后正常停止 Knowledge/Gateway，再串行打包三个受影响模块并重启。`.logs/s9b-final-java.log`、`s9b-final-package.log` |
| Python | 326 passed / 49 条件集成 skipped / 19 warnings；Ruff 格式/lint、47 个源文件 mypy PASS。基础批次后 Python 源码未变；跳过项不计真实集成通过。`.logs/s9b-foundation-{python,mypy}.log` |
| 跨语言协议 | 原生/中文 PDF 的实际 PDFBox 提取、LlamaIndex 分块、严格 DTO 与摘要金标一致，保留 v1 金标；5 个契约、4 个共享样本登记检查 PASS。负零、原字节、片段 UTF-8 偏移、伪造位置/摘要/向量和未知属性等负例均拒绝 |
| 真实进程故障 | 卡死、堆 OOM、stdout/stderr 爆量、异常退出、中断、单槽饱和及已知子孙回收 PASS；真实父进程运行打包解析 JAR，两样本 3/4 页和摘要匹配，结束 active=0。见 [基础证据](foundation-validation.json) |
| 真实存储/索引集成 | 39 个不同测试 PASS：结构化处理 12、原处理 7、评测持久化 7、执行 6、比较 7。DB/S3/ES/Qdrant/解析子进程真实运行，向量为合成金标，不冒称付费模型。见 [集成证据](integration-validation.json) |
| 恢复/并发 | 接受后崩溃不重解析；准备/逐 sink 重放不重嵌入或重写完成 sink；提交成功但事务回执丢失仅查同租约同指针；过期租约不能继续；已接受对象 hash 损坏不回退重解析；数据库 profile/NULL 约束拒绝非法记录 |
| 真实 HTTP | 6 组大小/MIME/伪装/无 Content-Length 边界；4 类失败 PDF 无准备物/块/发布；2 块实际提取字节/页码/hash 核对；16 次跨 owner/tenant/运行身份拒绝。运行身份 K04 读取 PDF 和评测证据页码 PASS。见 [HTTP 证据](http-validation.json) |
| 原格式兼容 | dev/test 均应用 V7；升级前 12 个 local-dev 任务状态/摘要不变。旧 S8E 引用/基线只读复验 PASS，原 M04 supported=FAIL 保留。见 [旧链复验](legacy-v1-validation.json) |
| 关闭新受理 | 最终制品启用解析但关闭 PDF 新受理：已发布 PDF 的 6 次 K04/K06 读取 200，新上传 415，无新模型调用；随后恢复显式开启。保留 V7 和 v2 读取器，不能退回不理解 v2 的旧二进制。见 [运行证据](runtime-validation.json) |
| 前端 | 67 单元、类型/生产构建、lint/格式 PASS；4 组 Playwright PASS，1440/1024/414px、键盘焦点、刷新、加载/404 隐藏旧正文、真实加密失败均核对。最后一组只重放已保存的真实搜索响应，K04/K06 仍实际 HTTP。见[前端记录](https://github.com/lirji/agentic-ai-platform-web/blob/main/docs/qa/pdf-ingestion-20261024/TEST_RESULT.md) |

## 真实模型与样本

正式浏览器上传生成文档 `f76f053b-48d3-4e2a-a3bd-2229d929d794`、任务 `26e7ef5a-5919-47e3-b2e4-5b1e0fa67cec`（READY），两个引用块来自真实第 1/3 页。第 2 页为空，第 4 页只有图片，两页都如实告警且不参与索引。固定中文/英文、旋转双栏 PDF 原 hash 为 `1471561d350b30380fd601f536e934bce8756cbd7c885e1df651ad56340e903c`；字体子集与 OFL 来源见 [fixture-manifest](fixture-manifest.json)。

本片只增加一次真实入库和一次真实检索，共 2 批 Embedding，保守预留增加 0.02 元。累计 Embedding **44 批 / 0.44 元（上限 1 元）**，聊天 **24 次 / 0.889964 元（上限 1 元）**；不是提供方账单，未挪用 Reranker 额度。后续浏览器/HTTP/回退检查均不再调用模型。原幂等键和恢复号保留于两仓 `.local/s9b-*`，不可删除后重复付费步骤。

## 修正、审查和限制

实现期修正了缺字体 Unicode 映射会被库静默略过的问题：现在遇到无映射/替换字符即 `PARSE_INVALID`，固定缺映射 PDF 在模型前拒绝。纯图片、201 页、损坏、加密样本都有真实失败证据。边界样本来源及摘要见 [boundary-fixture-manifest](boundary-fixture-manifest.json)，没有为通过验收重生成成功金标。

原失败记录保留：macOS JVM 自动加入环境变量导致测试误报、探针 SimpleMeterRegistry close 编译错误、集成测试误取 Candidate.documentId、前端警告映射类型和 E2E 循环推断错误、已声明超限请求过早断开引起测试 EPIPE，均已修正后通过；未知长度实际字节检查没有被弱化。回退探针首次误用夹具文件名，在发起上传前失败，随后用已固定文件重验。它们不计成功验收。

本 Agent 逐项审阅租约/未知提交、摘要绑定、发布过滤、K06 权限、实际流量限制、UI 重验与缓存隐藏，未发现未处理的当前切片阻断问题；没有独立子 Agent 审查或全局工作流 Runtime Gate 冒称。代码卫生检查前端 PASS；后端无规范 Java 格式器/静态分析器，按周边风格及手工审查补足并保留限制，未将未运行工具伪称通过。最终源码指纹见 [final-validation](final-validation.json)，基础批次指纹仍单独保留。

U02 真实业务语料尚缺，少量自建样本不证明任意 PDF 版式、多栏语义顺序、OCR、容量 SLA 或完整操作系统隔离。JVM 堆/直接内存/元空间上限不等于 RSS 上限。没有生产部署或旧资料清理；失败夹具、日志、私密账本和运行状态均保留。
