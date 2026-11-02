# S15B 共享预算验收

结论：本片实现与机制验收通过，Git交付及CI状态另见delivery-result.json（交付完成时归档）。生产未启用中央预算，未增加付费额度。完整51项未完成，按用户要求S15B交付后停止，不启动S15C。

基线后端1e41545、前端6a18e72；核心提交5531976，模型出口提交d157947。最终源码/验证日志摘要见[final-source-evidence.json](final-source-evidence.json)。历史批次摘要保持原revision；后续出口改动的受影响验证覆盖旧摘要差异。同一Agent执行实现与独立验收pass，没有声称独立人员/子Agent审查或用户视觉签收。

| 验收 | 结果 | 直接证据与范围 |
| --- | --- | --- |
| UA01 最后一份共享额度 | PASS | `UsageBudgetIT.differentTasksCompeteForOneSharedAccountWithoutOverIssue`真实MySQL账户/operation/attempt/审计合计；`deploy/usage_browser_smoke.py`两个独立Tasks进程、8个任务争0.030000合成额度，只3个得到许可、5个拒绝。[联合收据](browser-joint-result.json) |
| UA02 重复预留与恢复 | PASS | UsageBudgetIT并发同attempt只占一次、同键异输入409、lookup稳定operation、SETTLED结果丢失另建retryOf；Python `test_recovery_uses_new_attempt_and_only_new_dispatch_true_can_send`及scope不含epoch测试。Tasks60IT、Python120受影响检查 |
| UA03 一次dispatch及响应丢失 | PASS | UsageBudgetIT并发8请求仅一次true；Python `test_unknown_permission_never_yields_or_retries_model`覆盖丢响应/异常/false，模型发送数0；新的实际尝试保留前次占额 |
| UA04 取消/超时/退出/失租 | PASS | Python取消、断流、计量缺失与异常出口；真实Task租约失效拒绝旧worker新准入。独立worker在许可后以17退出、取消任务、重启两个Tasks后原attempt仍DISPATCHED且未结算；不以退出/取消推断免费 |
| UA05 release与dispatch竞争 | PASS | `UsageBudgetIT.releaseAndDispatchRaceCannotBothWin`真实事务竞争、重复释放和已发拒绝释放；接管者只能释放数据库证明未发送的预留 |
| UA06 一次结算/迟到/乱序 | PASS | UsageBudgetIT UNKNOWN→SETTLED、重复payload幂等、异payload409、SETTLED不后退；实际Python→Java→MySQL迟到12token结算0.000024；报告失败保留占额、原payload重报测试 |
| UA07 计量错误与超预留 | PASS | Java UsagePriceTest与Python严格Tokens校验负值/浮点/NaN/缺维度/溢出；HTTP超body拒绝；不挑选冲突中的有利计量。真实DB合法超预留记录成本并冻结，累计DECIMAL(40,6)不丢有效值 |
| UA08 金额/价格/单位 | PASS | Java/Python共享usage-golden、中文请求字节摘要、向上6位舍入；不可变priceVersion、各用途独立；React金额保持字符串，不汇总成供应商账单，不把unknown变0 |
| UA09 身份与共享归因 | PASS | 真实HTTP受众/scope/tenant/worker和Knowledge签名、30秒期限、委托/用途/profile校验；入库签发前重验原租约及活跃文档。Java Knowledge→Python→Tasks两用户同合成账户各自计量，第三人读取404；[联合收据](knowledge-joint-result.json) |
| UA10 全部现有出口 | PASS | Chat流式、Agent工具聊天、Embedding每批、Rerank均统一gate、实际请求字节计价；工厂缺配置失败且不打开旧文件；既有工具/研究/资料权限继续受原回归保护。Judge尚无实现/启用，本片验证未配置Judge请求在HTTP前拒绝，不冒称已启用裁判 |
| UA11 中央/DB故障 | PASS | 停Tasks后真实Knowledge503、提供方替身调用数保持2；另隔离Tasks配置不可达DB，不停止共用MySQL，约3.2秒返回USAGE_UNAVAILABLE、发送数0。[DB收据](db-outage-result.json)。报告异常保留held，HTTP5秒/4连接/32KiB、数据库短事务/超时有界 |
| UA12 旧账本导入 | PASS | UsageAdminIT及真实非Web CLI：原grant不可增、同源/同命令幂等、异参/篡改/合计不符拒绝、重复条目整事务回滚；冻结原文件字节与PID/锁验证。历史HTTP_200仍UNKNOWN，不猜用户，原账本未实际迁移 |
| UA13 重启/回滚防双写 | PASS | 合成冻结标记让旧Chat/Embedding/Rerank写入路径拒绝；CENTRAL工厂不加载旧预算；重启后DISPATCHED持久、恢复不清额。运行手册要求真实切换前停所有旧writer、冻结导入，不能回退到未冻结副本；未执行生产回滚 |
| UA14 本人视图及浏览器 | PASS | 真实MySQL浏览器4组：22记录分页20+2、刷新/折叠/键盘、1440/414px、空列表、跨用户404、退出后待计量；另3组契约浏览器验证问答/资料/评测正确context、loading/503/retry/404清除旧结果。React身份切换abort/迟到响应隔离、strict DTO测试。截图位置和实际查看见下 |
| UA15 数据/模块边界 | PASS | `ci/check_usage_boundaries.py`扫描292 Java源码、禁止usage跨域持久依赖及外部写usage表、5层无环、Mapper只读写自身表；8表94字段均有注释。真实MySQL元数据核验与V6/V7不可变摘要保留。Maven构建验证模块依赖；无新增跨库写权威 |
| UA16 机制与实际启用 | PASS | 全部新增数据仅agentic_tasks_test/既有知识test；真实模型调用0。Chat0.957092/1、Embedding0.781784/1 CNY及文件摘要未变；Rerank/Judge无新增授权，dev/生产未迁移。合成记录与所有诊断保留 |

## 命令与结果

- Java：52相关单元、Tasks60真实IT全部通过无跳过，`.logs/s15b-task-regression-it.log`；后续20usage IT复验通过`.logs/s15b-core-enums-it.log`。Knowledge126单元、Security10单元、35真实MySQL/HTTP/双索引IT全部通过，`.logs/s15b-knowledge-usage-it-r2.log`。
- Python：全量733 passed / 97条件跳过 / 34既有警告，`.logs/s15b-outlets-python-all.log`；后续保守计量/工厂120项通过，Ruff144文件与mypy88源码通过，`.logs/s15b-outlets-final-{python,mypy}.log`。跳过项需要显式外部环境/付费模型/历史fixture，不当作已跑的全链路测试；UA必需路径另有本报告对应证据。
- Web：全量246项通过`.logs/s15b-web-all-tests-r2.log`；身份桥枚举整改后16项通过`.logs/s15b-identity-final.log`；Prettier/Lint/TypeScript/production build通过。新用量包/测试41项窄测通过，未引入新依赖。
- 实际浏览器命令：`ai-runtime/.venv/bin/python deploy/usage_browser_smoke.py`；`database-outage`子命令只给临时Tasks设置不可达DB。读取真实前端构建dist；所有临时Java/桥进程在finally正常结束，不清理合成数据。
- 契约浏览器：Web预览28256、`pnpm exec playwright test tests/usage-consumers.spec.ts`，3项通过；使用正式契约夹具，明确与真实DB4项区分。
- 文档契约登记14项、共享报文15份及边界脚本通过。Java formatter/专用静态分析未配置的限制保留；不因此新增工具或改写已执行迁移。

## 可见界面验收

沿用现有Ant Design、紫白主题、字号和组件token；新增“本人用量”按需展开，在问答轮次、研究/助手任务、评测运行和资料当前处理任务出现。显示初始预留、已结算内部成本、未知/待计量、Token、价格版本和调用编号。无资金池余额、他人记录或正文；页面不是支付/账单页面。

已实际查看前端`docs/qa/s15b/screenshots/`中`usage-1440.png`、`usage-414.png`、`worker-exit.png`、`empty.png`、`chat-414.png`、`document-414.png`、`evaluation-414.png`。支持窄屏换行和复制长编号；未知与成本分开展示，未溢出视口。浏览器实际验证Enter展开/收起、分页、刷新、重新加载及刷新后折叠；没有从截图推断这些行为。

边界：资料显示当前处理job的本人用量，搜索无独立历史用量页面；当前片不增加账户管理/自助充值或全局统计。资料撤权不影响任务成本历史的归因，但没有可读主体上下文时拒绝访问；共享只读者不能查看owner入库用量。

## 保留的失败及修正

历史核心/出口失败见[IMPLEMENTATION_EVIDENCE](IMPLEMENTATION_EVIDENCE.md)，均保留原日志。前端首轮使用不存在的toggle工具、未cleanup及动画图标影响按钮名称，改为真实点击、清理和固定aria-label后通过。浏览器夹具首轮漏CENTRAL验签key、使用了超时限测试JWT，补正确配置/60秒token后通过；资料fixture漏metadata与路由写错已修正。一次并行Playwright共享输出目录使trace被覆盖，已为真实DB套件设置独立输出目录并串行复验，不将该次失败算通过。

边界工具首次误认组合根MapperScan和XML属性为跨域依赖，按精确装配字符串与XML解析修正，原生产规则不放宽；加入CI防回归。卫生检查发现本地身份桥裸TASK、验收脚本裸CHAT/吞掉就绪异常，均改为既有枚举及有界、可诊断等待。原全局卫生扫描误扫忽略venv问题仍按[范围修正](core-hygiene-review.json)处理，不改全局规则；最终限制和建议见[final-hygiene-review.json](final-hygiene-review.json)。

## 限制与停止点

真实提供方价格正确性、账单核对、生产容量和真实中央切换均不在本次启用范围；冻结工具只核对声明的writer/PID，不声称发现整机全部写入者。现有Vite大包、Vite未来native loader提示与Python durability警告保留，未借本片升级栈。临时故障只影响本轮已知进程，原预览/Docker不变。S15B完成正常Git交付后停止；后续从S15C恢复需用户新的继续要求。
