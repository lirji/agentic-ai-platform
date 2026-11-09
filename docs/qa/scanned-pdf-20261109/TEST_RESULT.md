# S9E 扫描PDF验收

2026-11-09，S9E本地必需验收PASS；Git/CI交付结果随后按精确提交归档。复验源码见[source-evidence](source-evidence.json)。

| 验收 | 已执行方法/证据 | 状态 |
| --- | --- | --- |
| OE01 中英、混合页与真实页号 | Tesseract5.5.4真实识别；[固定三页PDF](fixtures/mixed-scan.pdf)、[提取物](fixtures/extraction.json)、[质量测量](quality.json)。清晰受控样本78个归一字符，CER=0；原生第1页/扫描第2页/空白第3页不重编号 | PASS |
| OE02 低清与明确失败 | 36DPI[低清样本](fixtures/low-resolution.pdf)触发第2页低置信警告；全空、加密、损坏、错误版本/语言数据拒绝；正确识别也可能低置信，告警不作为准确率 | PASS |
| OE03 资源及回收 | 10页/像素分配前拒绝；真实管道超时、stderr超限、取消，保留中断并回收；父进程运行中追踪后代，意外退出也回收已知OCR子进程；旧native PDF仍200页。ParserProcessTest与TesseractProcessTest实测 | PASS |
| OE04 双端来源与真实存储 | Java/Python[共享金标](../../../knowledge-service/src/test/resources/structured-v3-ocr-golden.json)，新旧profile错配/伪造质量告警拒绝；V20真实test库迁移、S3、隔离Qdrant/ES、发布/检索/页号引用与跨owner/tenant拒绝，共25项IT及2项核心复验 | PASS |
| OE05 界面 | 前端OCR选项、10页提示、恢复命令名称、OCR/低置信页告警；251单元及局部回归，真实Chromium1440/414px、撤权隐藏、刷新重读、弹层打开/关闭；接口夹具来自实际提取物，不冒充完整实时后端E2E | PASS |
| OE05 Linux Runtime | 固定引擎/语言/Leptonica源摘要；独立引擎在无网络/只读/清空环境变量下启动通过；最终Linux/ARM64镜像在无网络/只读/384MiB限制下4.83秒完成，峰值312926208字节，输出与共享金标完全一致；[探针收据](linux-result.json) | PASS |
| 工程门禁 | 全仓Java package 228项；追加全空/加密与配置空值的定向测试、最终父进程回收复验；Python76、Ruff/mypy、前端types/lint/format/build与251单元；契约和usage边界检查通过 | PASS |

## 命令与证据

- `.logs/s9e/java-package-final.log`：全仓构建和228项单元通过；`parser-final.log`是最新29项解析测试，`parent-final.log`是最终进程回收测试，`config-binding.log`验证空OCR配置不破坏旧启动。真实OCR测试均显式提供本机路径，无跳过。
- `.logs/s9e/integration-2.log`：25项真实IT通过；`integration-focused.log`：两项核心复验通过。入口[run_integration.py](run_integration.py)，使用test库/桶和本任务28333/29300隔离搜索，模型向量为明确标注的测试替身，未调用付费提供方。
- `.logs/s9e/python-final-2.log`：76项跨语言处理回归；`mypy.log`、Ruff实际命令、`code-hygiene.log`保留。[代码卫生](code-hygiene.json)为COMPLETE_WITH_LIMITATIONS：项目没有统一Java格式器/独立静态分析器，未宣称已执行；Python检查通过。
- 前端[同日验收](https://github.com/lirji/agentic-ai-platform-web/blob/main/docs/qa/scanned-pdf-20261109/TEST_RESULT.md)保存截图、命令与准确边界。前端代码卫生COMPLETE。全仓251项与最终受影响10项分别保留。
- 引擎及语言：[原生源清单](native-artifacts.json)、[语言清单](language-artifacts.json)、[Runtime及回退边界](RUNTIME.md)。宿主macOS/ARM64实测，Linux/ARM64最终探针通过；未宣称x86_64实测或普遍业务准确率。

## 失败、修正与限制

首次Java兼容profile计数仍为5，新增版本后明确更新为6并验证OCR_V4只改变PDF；全仓回归通过。Python攻击包首次误用DOCX重签工具而KeyError，改为按真实PDF包重新计算摘要后，语义拒绝通过。

首轮集成11项失败来自原搜索实例三天前已退出，未重启或改动原实例；为本任务新增隔离搜索资源后25项全通过。原Knowledge unhealthy/QA worker重启状态在任务中只读观察，非本次改动引起，也未借此任务修复。V20仅在test库执行，原应用/生产未迁移或部署。

浏览器首次缺文档元数据夹具、JSON导入类型缺失，完善正式契约夹具后通过；撤权同时使文档和引用查询失败，恢复以真实页面刷新重新读取，不虚称一次点击能恢复所有查询。弹层首次截图捕获过渡动画，最终已禁用动画重拍并实际检查通过。新增提示单测等待DOM出现早于动画完成，沿既有表单单测用ConfigProvider关闭jsdom不能驱动的CSS动画并保留可见断言；真实浏览器保留动画行为验证，不弱化检查。

Homebrew首次安装尾部返回非零源于libxext下载失败；实际安装的Tesseract5.5.4及其运行依赖已由版本/识别探针验证。容器最初JPEG禁用选项未识别，修正正式选项并重建；训练工具依赖缺失警告符合只提供识别的范围。既有Vite体积/配置加载提示及第三方编译警告保留，没有全仓升级或宣称零漏洞。

SKILL_HANDOFF: implementation-validation，slice=S9E，status=COMPLETED，gate=PASS；代码卫生Java工具限制保留，工作树证据适用于本次产品文件。交task-git-delivery/ci-cd-gate后继续S9F，四项总任务未完成。
