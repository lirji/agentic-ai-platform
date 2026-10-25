# S9D 结构分块与处理profile验收

2026-10-25，slice=S9D，本地必需验收PASS；两仓实现已正常合并推送main，CI全部通过；见[交付收据](delivery-result.json)和[CI门禁](ci-gate.json)。源码版本以[final-validation](final-validation.json)绑定，实施说明见[IMPLEMENTATION_EVIDENCE](IMPLEMENTATION_EVIDENCE.md)。本片未启用更新、撤下、重索引或生产部署。

| 验收 | 实际证据 | 结果 |
| --- | --- | --- |
| STR-01/02：真实Node、完整来源、确定身份 | 锁定LlamaIndex Document→TextNode，三份共享golden；中文/英文/代码文本/emoji/组合字符/长段/重复段/16与17块、空块与章节边界。旧逐块golden未变 | PASS |
| STR-03/04：边界与恶意重签 | 两语言独立来源校验；跨组/逆序/跳段/重复/UTF-8截断/增强内容混入均拒绝；总重叠含LF严格64/65、正文512、上下文256/257及100/101块；超限在模型前失败 | PASS |
| STR-05/06：metadata与共享契约 | 捕获实际Embedding端口输入，真实标题堆栈与严格前序显式表头增强，MetadataMode.NONE引用仍为原文；没有默认LLM、Settings调用或标题猜测 | PASS |
| STR-07：约束、幂等和恢复 | [45项真实IT](integration-result.json)：结构24、持久7、原处理7、评测7；真实MySQL/S3/ES/Qdrant/解析子进程，新旧五profile并存、prepare/sink恢复与租约。V9 dev/test PASS | PASS |
| STR-07：关闭新选择 | [切换验证](profile-switch-validation.json)：BLOCK_V2下原v3同键仍原job，新坏DOCX持久v2且解析前置失败；47来源/42拒绝仍通过；最后恢复STRUCTURE_V3，[运行证据](runtime-validation.json) | PASS |
| STR-08：同字节profile机制对照 | 自有DOCX同hash13→6块、PDF同hash4→2块；同查询的目标来源两profile均rank1。[实际模型结果](profile-comparison.json)保存原hits与摘要，无S8E标注重绑 | PASS（机制范围） |
| STR-09：实际来源与权限 | [47片段逐UTF-8字节还原、42次跨owner/tenant拒绝](http-source-validation.json)；包含全部7组旧新资料，读取阶段零模型调用 | PASS |
| STR-09：界面 | 前端72单元、2组Playwright；跨段/同行格/相邻页及旧DOCX，1440/1024/414px，加载/404隐藏原文、实际重读恢复；11正式截图和1诊断截图已查看。见[前端验收](https://github.com/lirji/agentic-ai-platform-web/blob/main/docs/qa/structured-chunking-20261025/TEST_RESULT.md) | PASS |
| 构建、静态和运行保护 | Java126单元；Python371通过/49条件IT跳过/19既有警告；Ruff/mypy及前端build/types/lint/format通过。最终仅Knowledge选模块package；原27行不变，Tasks/Conversations/Gateway PID/JAR保留，本地在途0 | PASS |

实际Embedding新增9批，账本新增预留**0.111784元**（7×0.01及2个长输入批次×0.020892），累计56批/**0.581784元**，上限1元；聊天24次/0.889964元未变。此前草稿将每批按0.01简算，现已按账本逐项修正，预留不代表提供方账单。成功6次入库、3次新检索已保存，禁止清空`.local/s9d-http-state.json`或重跑成功/未知付费操作。

## 命令与证据

- Java：`mvn -B -ntp test`，`.logs/s9d-java-final-unit.log`；真实IT由`run_integration.py`调用test-compile/failsafe，`.logs/s9d-real-integration-retry.log`；复用常量后Knowledge最终package，`.logs/s9d-package-final.log`，不覆盖其它活动JAR。
- Python：从ai-runtime执行Ruff format/check、mypy、pytest，`.logs/s9d-python-final-{format,lint,mypy,tests}.log`；QA脚本另做Ruff和语法检查，无再次生成golden或模型调用。
- 前端：锁定Node24.22.0/pnpm12.14.0，test/build/typecheck/lint/format；真实浏览器`playwright test tests/structured-profile-real.spec.ts`。截图前按目标宽度重新加载并等待真实正文。
- 恢复后只读：`http_profiles.py read`，`.logs/s9d-final-reads.log`；[运行证据](runtime-validation.json)绑定最终制品、V9、原数据和账本。
- [Code Hygiene](code-hygiene.json)后端COMPLETE_WITH_LIMITATIONS（无统一Java格式器/静态分析器），前端COMPLETE；Python和前端工具实际执行通过，不以工具扫描代替事务、模型或运行验收。

## 修正与限制

首次mypy提示BaseNode不能直接设置TextNode字段，改为实际类型校验；IT测试lambda同名导致编译失败，修复局部测试变量后完整45项通过。前端最初误把按chunkId排序的evidence首项当原文首片，现以实际locator选择并严格核对内容。旧资料owner起初误设developer，404后根据权威记录改为workbench-user；原探针资料保留，为真实工作台另入两份资料，费用纳入上述账本。

长段414px首次从宽屏连续resize后截图出现空白尾部；fresh路由DOM实测2327px，改为每视口reload/等待正文后最终截图同为414×2327，实际查看没有尾部空白或水平溢出。正文和样式未为截图改写。QA脚本Ruff修复导入顺序、异步子进程包装；首次Code Hygiene传LINT而非STATIC_ANALYSIS导致证据缺失，按已执行ESLint证据重新归一化通过。

49项Python条件集成测试本次未启用，不计PASS；既有第三方警告和Vite体积提示保留。U01/U02没有真实业务语料，两个自有文件仅证明机制与兼容，不能推导普遍召回提升；代码按原文字符分块，不声称AST代码解析。OCR/表格文件条件项未触发，没有容量或OS沙箱承诺。全局Runtime漂移状态不变，同一Agent执行分阶段审查。

SKILL_HANDOFF: implementation-validation，slice=S9D，status=COMPLETED，gate=PASS；更新进度后交task-git-delivery/ci-cd-gate执行两仓正常交付，随后继续S10B。
