# 本地Docker能力更新（2026-11-09）

- 状态：DEPLOYMENT PASS，9容器healthy/0重启，真实OIDC双账号、记忆API与页面、运行镜像解析及旧数据/预算核对通过。[部署收据](deployment-result.json)、[CI](ci-result.json)、[构建](build-evidence.json)、[浏览器](browser-result.json)、[解析](parser-result.json)、[数据](data-result.json)。
- 目标：既有desktop-linux / agentic-ai-app，入口http://localhost:28086。固定制品57540a96db9de87b，后端728ba9f629f750017219288357c1ff48d853cd08、前端967328a91e55b18828193e466af665dd6f8dc969；两仓分支/main CI通过。后续文档收据提交不改变此产品制品来源。
- 能力：保留OIDC、PDF/DOCX和既有生命周期；启用中英扫描/混合PDF OCR_V4、XLSX/CSV、个人记忆治理与受控提议/逐轮使用。独立记忆执行签名，复用原embedding/chat账本；共享/反馈/运营/常驻指标保持关闭。
- 数据：切换前正常停旧应用并保存三库私密SQL与旧字段摘要。Knowledge V19→V21、Tasks V8→V9、Conversation V3→V6追加迁移成功；45张原业务表2716行旧字段与两预算摘要保持。部署0付费模型调用、无业务写入；真实浏览器只登录/退出和读取API，实际运行解析器使用固定文件且不上传。
- 运行证据：Java253项（247通过/6条件跳过）、前端262项及格式/静态/类型/生产构建通过；实际运行容器PDF OCR/XLSX/CSV逐字段等于共享金标。OIDC两个普通账号、未分配成员/匿名拒绝、Cookie/CSRF、10项只读API、退出吊销和旧租户隔离通过；1440/414截图已查看。
- 限制：受控模型机制与真实恢复已在S18C联合验证；本次部署不再调用付费模型。真实提供方提议探针LIMITED，质量与业务收益未测量。Chat原1CNY限额保守预留0.971852，Embedding0.781784；不会重置或增加额度。
- 回退：未需要回退。保留前版6b7bf063f040da7a配置与三库备份；新schema/图需要兼容writer。若回退，应先关闭新受理、保留新worker排空，再按兼容策略处理，不自动逆向迁移或恢复数据库。
- 恢复入口：.local/docker-capabilities-20261109/state.json，.local/docker-runtime/current.json/artifacts.json；日志.logs/docker-capabilities-20261109/。构建/启动/验收进程均成功结束，9应用容器与原搜索持续运行。
- 保留：原目录与既有main工作树；私密备份/预算/密钥、构建缓存、旧镜像/停止容器和隔离测试数据。无清理授权，不删除；这些目录不能直接视为可清理。最终文档Git收据为.local/docker-capabilities-20261109/final-delivery.json。
