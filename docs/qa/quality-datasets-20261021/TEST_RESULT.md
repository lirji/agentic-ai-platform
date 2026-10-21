# S8B 评测定义与标注验收

2026-10-21；必需验收 **PASS**，S8B 可进入 DONE。后端基线 `39af9f6`，前端基线 `d2b4dc6`，实际源码摘要见 [validation.json](validation.json)。本记录由当前 Agent 在实现修正后执行验证与截图审查，不声称用户签收或独立 Agent 复核。

| 验收 | 方法、观察与结果 |
| --- | --- |
| E01-DB-01：并发幂等 | 真实 MySQL `EvaluationPersistenceIT`：6 个并发同键创建、4 个同键编辑得到同一回执；同键异参被拒绝，PASS |
| E01-DB-02：不可变修订 | 两个同版本编辑只接受一个；旧正文/摘要保持不变，失败写入没有命令残留；浏览器历史修订 1 仍是原说明，PASS |
| E01-ACL-01：当前权限 | 跨 owner/tenant 的集合、详情、历史、回执、证据与写入被拒绝；真实发布变化后隐藏定义，NO_ANSWER 也固定范围；HTTP 401/403/404 验证，PASS |
| E01 边界与发布来源 | 严格 JSON、Unicode、浮点/重复属性/未知字段、错误证据版本、游标/用途作用域及 chunked 超 256 KiB；共 11 新单元和 2 HTTP 组，PASS |
| E01-UI：保存和恢复 | 首次真实创建两题并标注；服务端接受后丢响应，以原键重试仅增加一个修订；再丢响应/刷新后只查询回执；真实竞争编辑保留草稿并人工采用新版本，PASS |
| E01-UI：返回与授权状态 | 返回恢复用途筛选；本页内存草稿可恢复；旧修订只读；绑定失效/403 受控响应不展示上次正文，PASS。真实后端权限语义由 DB/HTTP 测试独立支持 |
| E01-UI：三屏宽和键盘 | 1440/1024/414px 下详情、编辑、历史、列表、范围选择、证据标注、引用；Escape 后焦点返回入口，窄屏表格在容器内横滚，表单与侧栏无页面横溢出，PASS |
| 构建和回归 | Java Knowledge 26 单元 + 21 DB 集成，security 10 单元；额外 2 HTTP 组全部成功。前端 44 单元、TypeScript/Vite、ESLint、Prettier PASS；8 个浏览器组最终均 PASS |
| 代码卫生 / 规范 / diff | 前端 COMPLETE；后端 COMPLETE_WITH_LIMITATIONS，仅缺格式化工具，专用 Java 静态分析未配置。事务/批量/常量及中文 SQL 注释人工复核通过；限制保留，不冒称工具已执行 |

真实 DB 日志 `.logs/s8b-java-integration.log`、`.logs/s8b-java-http.log`。前端日志在前端仓库：`.logs/s8b-unit-final.log`、`s8b-build-final.log`、`s8b-lint-verified.log`、`s8b-typecheck-verified.log`、`s8b-format-verified.log`；浏览器最终证据为 `s8b-e2e-final.log` 前四组 PASS，以及 `s8b-e2e-views-verified.log` 后四组 PASS。最后只调整虚拟列表测试的键盘导航和操作超时，前四组代码与产品源码未改变；未重复制造额外修订。

截图实际查看记录见 [前端验收](https://github.com/lirji/agentic-ai-platform-web/blob/main/docs/qa/s8b/TEST_RESULT.md)。原始失败保留：首轮焦点与加载图标导致 4 项失败，修正后原失败组 4 PASS；补查选择面板焦点再修正后 4 PASS。表单双提示匹配和虚拟列表旧修订不在 DOM 属测试定位问题，改用 alert 和当前组件支持的 ArrowUp/Enter；一次 End 键试验因组件不支持而中断。没有放宽保存、权限或历史内容断言。

V4 已应用独立 dev/test；旧 V1–V3 未改写，Knowledge 新制品 readiness UP。隔离 DB 测试使用确定性发布夹具；浏览器读现有通过正式接口发布的操作资料。它们不代表 S8E 的完整固定来源语料或业务模型质量。新模型调用为零，原累计预算和既有运行数据保留。

限制：没有专门 Java formatter/静态分析；前端原共享分块约 626 KB 的构建警告保留。没有本片性能 SLA/负载验收要求，不虚构容量数字。评测执行、逐题结果、基线和退化比较仍由 S8C/D/E 接续，整个 51 项计划尚未完成。

Handoff：`implementation-validation / S8B / COMPLETED / PASS`；下一 Owner 为 update-progress-docs、task-git-delivery，然后连续进入 S8C。
