# S13A Docker部署进度

- 目标：将已交付S13A及其依赖部署到当前Docker Desktop desktop-linux/agentic-ai-app；用户于2026-11-01明确授权部署，采用既有本机28086入口与local-agentic个人身份。
- 当前：核对通过；旧运行01a95560586ee526仍9容器healthy，尚未切换。产品基线后端af86726、前端a394f58，原目录新分支feat/docker-identity-20261101。S13B草案/权威进度原改动保留，不混入部署源码快照或提交。
- 清单：①现状/数据/预算/回滚基线②OIDC容器配置与固定IAM回调增量③提交及CI、固定源码快照/镜像构建④正常停旧实例、备份三开发库、正向迁移及整体切换⑤真实登录/会话/隔离/业务只读/健康⑥证据/进度/Git交付。
- 必要配置：网页使用OIDC构建与代理，Gateway固定http://localhost:28086回调，Casdoor保留28196并追加28086登记；同namespace有界固定端口桥到既有Casdoor。新租户worker绑定local-agentic，旧local-dev数据不改归属。
- 数据：当前Knowledge V11/Tasks V4/Conversations V1，候选V12/V5/V3为已验证追加迁移；切换前备份并核对原业务字段。迁移后不能直接启动旧writer，失败优先修复相同新schema制品，不逆向数据恢复。
- 授权边界：仅本机现有Docker应用；不删除卷/数据、重置预算、扩大模型额度、发布远程生产、部署S13B或启动子Agent。smoke不执行付费模型调用。
- 日志.logs/docker-identity-20261101/；私密旧部署收据.local/docker-identity-20261101/before-*（0600），实际运行旧版本仍以上述容器为准。无本轮运行中的部署/迁移。
- 规范：runtime-and-deploy/deployment-execution/task-git-delivery/ci-cd-gate及进度规范；架构业务建模/接口/持久化/并发安全/发布治理。沿已选拓扑补配置，不重新选型。

- 实现检查：OIDC固定桥/前端模式/worker租户/固定提交快照已实现；语法、Compose config、固定提交排除脏文件、OIDC代理7项PASS。旧镜像内旧前端缺新增路由，首次local代理回归403；绑定当前前端源码后13项PASS，非产品缺陷。专属IAM精确回调增量已读回PASS，其他字段保留。尚未构建/切换；准备记录仅候选，不代表运行版本。
