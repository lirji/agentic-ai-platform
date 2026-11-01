# S14A 资料反馈验证

本地必需验收 PASS。产品分支/main精确提交CI全部PASS，见[交付收据](delivery-result.json)。当前源码摘要见 validation.json；S14B/C/D尚未实现，不把本片写成完整运营闭环。

| 验收 | 方法与实际结果 | 证据 |
| --- | --- | --- |
| F01 当前资料/完整引用授权 | PASS：owner和共享reader可提交；陌生人/跨tenant、代次变更、成员禁用及目录故障拒绝；owner不依赖远程目录 | KnowledgeFeedbackIT；.logs/s14a-joint-final.log |
| F02 幂等与未知恢复 | PASS：4路同键仅一条，异内容409；撤权/关闭后原回执仍可读；实际丢失POST响应后只GET原键，没有再次投递 | 同上及最终浏览器 |
| F03 隔离与协议 | PASS：mine/inbox/详情范围、稳定两页游标、严格JSON/码点上限、权限scope；owner响应taskId=null，反馈不改发布版本 | KnowledgeFeedbackIT共7项（含联合） |
| F04 任务事实 | PASS：仅本人SUCCEEDED任务实际引用可签发；隐藏检索依赖、运行中、他人、撤权拒绝；错用途/受众/主体/任务、过期/篡改拒绝；反馈证明作为HTTP身份401 | TaskPersistenceIT专项1项，.logs/s14a-task-proof.log；实际Tasks+Knowledge联合 |
| F05 前端与浏览器 | PASS：研究引用→填写→未知响应→离开原引用后恢复→本人详情→owner收件/详情→撤权后原文拒绝而记录保留；当前构建1440×1000及414×896截图实看，无横向溢出 | .logs/s14a-browser-final.log；前端tests/feedback-real.spec.ts及5张截图 |
| F06 工程验证 | PASS（本地）：202项前端单元，最后12项定向复验、Prettier、TypeScript、ESLint、Vite构建；两服务单元/真实DB通过；契约登记、链接、敏感值与diff检查 | validation.json；Git/CI独立记录 |
| Runtime | PASS：默认关闭、不完整K08配置拒绝、显式开启、Python语法；无新进程部署或模型调用 | .logs/s14a-runtime-check.log |

采用 implementation-validation、代码卫生、进度和Git交付技能。有实际界面的最终截图逐张打开：反馈Modal明确说明发送范围、主按钮目的明确，字段可读；详情区分反馈内容、任务关联与当前原文；窄屏导航折叠、文字换行；撤权显示错误及重新加载。最后补齐列表→详情→返回的筛选/游标参数。问答/搜索使用同一CitationContent与FeedbackAction；任务绑定由TurnCard/WorkPage传递，旧三元组不伪造完整引用。

失败与修复保留：首轮联合TXT夹具sourceLocator为空（原生产路径按字节范围生成定位），补夹具后通过；第二轮实际撤权已成功，但测试误找“重试”而现有按钮为“重新加载”，修正断言。截图首轮过渡未结束，最终关闭截图动画并等待详情标题/原文再取证；不以旧截图宣称视觉通过。代码卫生指出启动轮询吞连接异常，现记录错误类型并在超时明确失败。

代码卫生：后端IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS（未配置独立Java格式器，沿现有风格；事务与常量建议已人工复核），前端IMPLEMENTATION_COMPLETE。扫描器对PNG当UTF-8读取的既有限制通过仅排除二进制输入处理，全部变更文本仍扫描，截图独立实看。Vite既有构建体积/未来native config提示保留，未为本片升级依赖。

真实模型质量、性能容量和生产部署本片N/A；模型/向量调用0，原累计预算不变。测试只用隔离test库/桶/索引和随机tenant；V14应用后保留数据，不降级迁移。已有28086/28196/28206服务及凭据/日志/用户.idea保留，28216为独立构建预览。最终临时Tasks随验证结束正常终止。
