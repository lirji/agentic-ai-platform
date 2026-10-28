# S10F 实施证据

状态：实现与验证PASS，交付状态见收据。S10F契约为LIFECYCLE_CONTRACTS第9节；K/W范围限定Knowledge诊断读模型/Mapper/GET及前端知识页面/身份桥，无新DDL/框架/批处理引擎。

后端新增ProcessingDiagnostics、ProcessingDiagnosticsController，扩充LifecycleService/Repository/Mapper；GET当前任务由DB筛选/分页，名称取任务不可变修订。GET原重试受理由现有事务RETRY事件提供证明，失败不伪装未受理。新增4项真实MySQL/S3/HTTP IT、读失败单元及既有重索引名称断言。

前端新增诊断协议、批次状态模块和ProcessingTasks；知识资料视图切换、失败阶段/建议、来源及历史语义与原详情联动。身份桥仅放行两个合法GET；UploadModal最小无障碍修正稳定提交按钮名称。协议浏览器fixture补全本地会话，截图写入本片目录，历史S10E图片保持原字节。

验证发现与闭环：

- 后端首轮既有手写Repository测试适配器缺新增方法，补委托保留原断言；第二轮测试误引用未安装Mockito，改JDK代理，不加依赖。日志`.logs/s10f-backend-{narrow,recheck}.log`保留；recheck2与完整Java回归通过。
- 前端首次命令误用不存在tsconfig.app，改项目tsc --noEmit；透明checkbox原生input不可见，改验证真实label和输入可操作。组件/协议6项复验通过。
- Ant加载图标改变继续/上传按钮的无障碍名称，固定aria-label并保留busy/disabled。历史协议fixture缺local-session导致空页面，补会话fixture；10项浏览器通过。
- 代码卫生HF-001空catch：改为显式持久化UNKNOWN并停止后续发送。8项恢复单元、3视口协议与最终容器114测试通过，前端卫生完整PASS。

首个容器快照2d06075e0297afba仅构建、未运行，已由最终22dff8dd5e6ac896取代。通过既有Docker runtime prepare、匹配已验证后端JAR、compose build --print/bake精确目录读取构建，顺序stop/up/smoke；没有第二套writer。完整日志`.logs/s10f-docker-final-build.log`、`.logs/s10f-docker-up.log`。

最终全部验收及明确限制见[TEST_RESULT](TEST_RESULT.md)；产品源摘要见[validation](validation.json)。本片没有新增付费调用或清理。

交付期CI补证：分支CI通过而frontend main40664569514的ProcessingTasks组件测试偶发提前断言。修正测试等待第三项可见UNKNOWN和处理结束，再检查第四项未发；全部114项及格式/类型/lint通过。产品源码没有改变，容器/真实UI证据继续有效；测试hash变化已单列记录，远程修复CI另记。
