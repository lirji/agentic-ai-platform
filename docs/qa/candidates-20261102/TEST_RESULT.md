# S14B 候选修订与内容绑定评估验收

本地验收 PASS；Git/CI 门禁在交付收据中单独确认。没有原资料发布、生产部署或付费模型调用。完整51项计划未完成，后续 S14C/D 继续审批发布与探测补偿。

| 验收 | 结果与证据 |
| --- | --- |
| C01 所有权/幂等/CAS/历史 | PASS。KnowledgeCandidateIT覆盖实际MySQL短事务、同键并发、双写CAS、不可变历史、跨owner/tenant/大小写拒绝、原资料变化；浏览器发生真实409仍保留完整草稿，显式采用新基线后保存。 |
| C02 完整源/准备/恢复 | PASS。真实S3持久化TXT及PDF/DOCX完整字节，附件走实际解析子进程与代次/双索引。浏览器真实PDF multipart；已受理响应丢失后刷新只查原键，不产生第二候选。前后端中文摘要golden一致，不保存正文到sessionStorage。 |
| C03 私有副本边界 | PASS。同修订并发准备唯一，首次插入与关联原子；普通列表/查重/默认v1/v2双索引及最终SQL排除，显式owner检索可用，陌生人拒绝。共享、通用生命周期写与副本反馈拒绝，标准13字段兼容保持。 |
| C04 精确质量关联 | PASS。实际S8定义/报告/评分服务校验旧原文、精确定义修订、manifest/review摘要、BUSINESS/MECHANISM与依赖不可用；真实Tasks worker协议完成并由中央终态确认。浏览器关联后显示审批前置，编辑后历史STALE，新修订不沿用。确定性报告仅证明机制，不证明模型质量。 |
| C05 页面/恢复/窄屏 | PASS。211单元；真实浏览器2条完整路径涵盖反馈入口、完整编辑、冲突、附件、准备、只读详情、原质量编辑器固定副本范围与证据、报告返回上下文、历史失效。1440与414截图7张已检查，无横向溢出；副本已就绪与原资料生效文案明确区分。 |
| C06 工程门禁 | 本地PASS。130项Java单元（Knowledge120+Security10）、27项核心真实IT、10项共享执行/2联合按配置跳过、最新实际Tasks联合1项+浏览器2项；Gateway限流路径回归PASS。格式/类型/静态/构建、13份登记契约/14份报文、V16注释与默认关闭配置检查通过。远程CI全部PASS，见交付收据。 |

主要日志：`.logs/s14b-candidate-attachments-receipts-it.log`（27项）、`.logs/s14b-candidate-quality-http-it.log`（共享/HTTP/质量）、`.logs/s14b-joint-browser-final.log`、`.logs/s14b-joint-4473254010354186463/{joint,browser}.log`。前端`.logs/s14b-final-unit.log`、`s14b-final-lint.log`、`s14b-final-build-r2.log`及当前QA截图；摘要见validation.json。

失败与修正：初期测试开关未同时启用generation、旧代理方法名与断言期望已修正后复验。前端静态检查的组件辅助函数导出/渲染读ref及测试ResizeObserver已修。首次浏览器精确名称受必填星号影响，增加稳定可访问名称；原route.fetch附件转发得到400，改用固定本机目标的字节流桥后完整附件验收通过。首轮超时的本轮Playwright进程已正常中断，未停止长期服务。最后联合已完成，测试Tasks与桥退出。

卫生结果：Java COMPLETE_WITH_LIMITATIONS（仓库无Java格式器；协议数字/短事务人工审查见REVIEW）；前端格式与静态证据经卫生门禁单独登记。新Python helper Ruff通过；3个旧Runtime文件的5条Ruff提示与HEAD一致，无新增发现。没有借本片整体格式化旧Runtime。

保留：隔离test库V16及随机租户数据/源/索引/未启动的副本作业、`.local`凭据与账本、`.logs`、旧main工作树及`.idea`均保留。新增28226候选预览；28216/28206/28196和28086原Docker不变。关闭入口可查询历史与原回执，不能恢复被撤回的共享权限。
