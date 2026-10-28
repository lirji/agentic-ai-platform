# S10E 验收结果

状态：本地实现与必需验收 **PASS**；Git/CI交付另见收据。源码指纹见[validation.json](validation.json)，原有界检查点作为历史证据保留。

| 验收 | 结果 | 证据 |
| --- | --- | --- |
| 活跃内容唯一性 | PASS | KnowledgeDedupIT：4路同内容不同名字/键仅一赢家；UPDATE与新上传争用同一ACTIVE唯一约束；真实MySQL/S3 |
| 撤下、删除与旧修订 | PASS | V11仅活跃最新hash占槽；墓碑/旧修订可重新上传，旧幂等键仍返回原任务且不复活资料；历史行保留 |
| 安全查重 | PASS | 本人ACTIVE最新内容精确五字段；异owner/tenant均null；401/403、缺失/非法hash400；真实网关再次核验 |
| 发布语义 | PASS | 当前内容已发布为true；失败新稿保留旧发布时为false；浏览器显示不同提示并链接同一资料的处理详情 |
| 原请求恢复 | PASS | 网络/5xx/查空/查询失败保留输入与原键；确定202/匹配仅释放当前意图；30笔容量满拒绝新增且不逐出未知请求 |
| 真实浏览器 | PASS | 既有TXT和失败PDF两例：真实POST 409响应丢失→刷新→原键重试→查重导航；旧K01原键重放仍202且jobId不变 |
| 视口 | PASS | 1440/1024/414，两种提示均无横向溢出；实际查看real-published-1440、real-draft-1024/414，操作与正文清晰 |
| 本地运行及数据 | PASS | 快照52c4cd0088b45e33，9容器健康、10项smoke；61条dev资料、两个账本hash前后相同，两类local-dev在途均0 |

Java143单元+123真实组件集成全部通过（后端`.logs/s10e-full-java.log`）；前端99项全量测试、格式、类型、ESLint和生产构建通过，宿主与最终容器均完成。4组协议浏览器通过（前端`.logs/s10e-browser-final.log`）；真实浏览器1组通过（前端`.logs/s10e-real-browser.log`）。容器构建与启动证据为后端`.logs/s10e-docker-build-serial.log`、`.logs/s10e-docker-up.log`。文档契约检查通过。

构建快照与当前两仓产品源码逐文件SHA256相符；其后仅验收/进度文档调整。Python产品源码未变，本片未重复本地Python/付费探针，远程CI另核对。已完成同一执行者的独立验证阶段和有界代码审查，未声称独立Agent认证。前端卫生检查通过；后端未配置formatter/static analyzer，记录工具限制而不编造结果。

首次测试资源争用、jsdom动画和夹具缺失已修正并完整复验，详情见[实施证据](IMPLEMENTATION_EVIDENCE.md)。Compose外部Dockerfile读取采用其生成的bake定义并仅授权既有部署目录。test/dev库均V11，禁止使用不支持V11的旧writer回退。

限制：仅本人知识空间，未来共享授权仍需新用例；不物理删除历史或对象。没有新增付费调用、生产部署或清理。S10F处理诊断仍为后续切片。
