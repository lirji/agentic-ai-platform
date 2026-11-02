# S14C 有界实现审查

审查者：本次执行 Agent 的独立审查 pass，未使用独立子 Agent。不把人工审查替代真实数据库/浏览器验收。

- 批准由可信owner和knowledge:write限定，私有候选不承接共享读者的写权限。Controller严格有界JSON/no-store，Mapper逐条tenant/owner过滤；没有把请求中的身份或对象键当授权。
- CandidatePublicationService先在锁外读取真实Tasks终态/报告/评分，再按run→preview→original→candidate顺序在短READ_COMMITTED事务中重验。质量及生命周期嵌套入口使用MANDATORY，原UPDATE、批准审计和两份命令回执共同提交；没有事务内远程调用。迟到审计故障的真实IT验证完整回滚。
- V17为追加迁移，表和20字段均有中文注释，case-sensitive身份、正数约束与三条唯一约束兜底。同候选修订只有一次批准；原键含全部输入及说明的摘要，同键异意图冲突，失败事务退出后才尝试恢复赢家回执。
- source复用已持久化的完整原文件；原Lifecycle仍唯一创建内容版本/代次/作业，不另建发布队列。目标仍为原documentId，原共享ACL不复制或扩大。相同源hash的元数据变更遵守原UPDATE拒绝规则。
- 发布读模型使用原资料短锁和READY任务精确元组；受理不冒充PUBLISHED，新受理尚未替代发布指针时旧批准仍有效，新版本真实发布后SUPERSEDED，撤下/删除INACTIVE。FAILED重试沿原job和CAS，耗尽重试次数不展示可重试。
- 前端只依赖服务器资格/真实状态，关闭功能仍可恢复历史；确认显示完整修正稿标题/修订、原资料入口和说明。sessionStorage只保留原键与请求摘要，不保留批准说明或正文。轮询错误不继续展示旧成功。
- Runtime开关默认关闭，需显式依赖operations；未隐式启动worker、改模型预算或部署。测试桥固定loopback和夹具身份，只在隔离测试中丢弃真实已受理响应。
- 现有Java无规范格式器和独立静态分析器，沿周边代码风格手工检查。卫生工具对协议常量、短事务等建议逐项检查，无阻断项。前端执行既有Prettier/ESLint/TypeScript；旧Python Runtime Ruff提示与基线一致，不扩大格式化范围。

当前无已知阻断缺陷；发布后检索探测与错误发布补偿属于后续S14D，未在本片声称完成。确定性处理器/质量报告用于协议验证，不证明真实模型质量；付费调用与生产部署均为0。
