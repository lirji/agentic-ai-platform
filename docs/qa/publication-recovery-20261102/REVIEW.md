# S14D 有界实现审查

审查者：执行 Agent 的单独审查 pass，无子 Agent。人工检查与真实数据库、检索和浏览器证据分别记录。

- 探测复用 ConsumerKnowledgeSearch → Python/LlamaIndex → 授权候选/最终 SQL 校验；固定原资料与 RRF、topK=1，前后比较精确发布元组。旧命中不能冒充当前版本，依赖错误继续报错；没有新增自动轮询、重试、计费账本或静默回退。
- 补偿读取原 UPDATE 的 base_published_version/generation/job_id，关联 READY 作业与不可变资料修订。失败的中间内容版本不能被当成上次发布。S3 完整源读取在事务外，校验配置桶、字节数和内容摘要；PDF/DOCX 不通过提取正文重建附件。
- 短 READ_COMMITTED 事务按 original → candidate 顺序锁定并重新核对原资料修订、当前批准、最新候选及历史源。CandidateService.restoreStored 使用 MANDATORY 加入外层事务，新修订、回执与补偿审计共同提交；审计失败全回滚。不同命令冲突不得覆盖新稿，同原键恢复只返回原回执。
- 新候选修订不继承预览、文字要求、评估或批准，也不创建原资料 UPDATE。当前发布和原 ACL 保持不变；明确撤下通过既有资料生命周期 API/CAS/审计。没有新增第二条发布或撤下写路径。
- V18 为追加迁移，表与 11 字段注释齐全，身份二进制排序，source approval、candidate+approvedRevision、candidate+createdRevision、command 各有唯一约束。历史查询最多两条，覆盖选中修订的入向/出向补偿；记录只追加。
- HTTP 探测要求 read scope、补偿要求 write scope，应用层仍限制精确 tenant/owner；共享接收者不因只读资料授权而获得私有候选权限。JSON 有界且严格，响应 no-store，对外不暴露对象地址或签名。
- 前端按实际发布元组验证命中和正文摘要；失败不继续展示旧成功。补偿弹层说明只生成草稿、需重新评估批准；未知响应沿现有原键核对，不存说明/正文，不另发补偿命令。历史链接保留修订上下文，撤下入口指向原资料。
- Runtime 开关默认 false，要求 operations；与 publication 开关独立以允许暂停新审批后处置既有发布。没有扩大 worker、模型预算或部署范围。测试桥仅转发固定 loopback 服务与本轮隔离身份。

卫生限制：仓库仍未配置 Java 规范格式器/独立静态分析器，沿周边风格人工审查；不借本片重构或升级工具链。旧 Runtime Ruff 提示与基线相同，无新增；新 helper 静态检查通过。浏览器与最终回归状态以 TEST_RESULT 为准。

卫生工具4条建议已逐项复核：3条事务触碰由短事务、MANDATORY及真实回滚/并发IT覆盖；ProbeStatus与忽略的deploy/model-proxy/.venv中Redis State名称重合是第三方枚举误匹配，不是业务第二套状态。前端卫生无建议项。
