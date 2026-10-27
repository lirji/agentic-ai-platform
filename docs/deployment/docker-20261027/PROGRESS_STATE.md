# Docker 部署进度

- 任务：`docker-deployment-20261027`，目标为本机 Docker Desktop `desktop-linux`。用户明确选择包含当前未提交代码；不继续实现 S10E 前端。
- 验收：8 个既有应用进程容器化，复用 dev-infra/既有搜索与原数据；页面、鉴权、只读业务及健康检查通过；源码快照和镜像可追溯。
- 原工作区：`/Users/liruijun/personal/LLM/agentic-ai-platform`，分支 `feat/document-dedup-20261027`，HEAD `0eeea79`；S10E 未提交内容保持原样。前端原仓 HEAD `56b3f3b`，只有原进度修改。
- 部署工作树：`~/.local/share/git-worktrees/agentic-ai-platform/docker-deployment-20261027`，分支 `feat/docker-deployment-20261027`；仅交付部署脚本/文档，不把 S10E 内容提交或推送。
- 清单：①环境/源码核对 PASS；②源码快照/Compose/入口 PASS；③构建与必要回归 PASS；④旧服务正常停止、数据库备份、dev V11、Docker 切换 PASS；⑤健康/页面/只读冒烟 PASS；⑥部署文档已同步，Git 交付/CI PENDING。详细证据见 [部署结果](DEPLOYMENT_RESULT.md)。
- 当前制品：双仓快照 `5a313f0bd92dec9b`，完整摘要在原工作区 `.local/docker-runtime/current.json`；Java 143 项全部通过，Python 格式/lint/mypy、396 passed/51 条条件集成跳过、6 契约/10 共享金标登记通过。S10E 原 30 项有界数据库验证的 10 个源码摘要全部一致，复用该证据，不宣称整片完成。
- 关键决定：现有 Python/Knowledge 搜索客户端只允许 loopback HTTP；本地容器共享 gateway 网络命名空间，保留该校验。8 个应用容器加 1 个固定搜索转发容器，将 loopback 26333/29200 连接至同机 Qdrant/ES；DB/S3/PG 使用 dev-infra 容器 DNS；只发布本机 28080/28086。非生产、不能独立扩副本。
- 数据：dev/test Knowledge 均 V11；旧 writer 已停，61 条业务资料字段逐行一致。旧版 Knowledge 不允许直接启动；保留迁移前私密备份，失败优先使用相同 V11 镜像修复配置。
- 启动修正：首轮 tmpfs YAML 列表逗号被拆为多个挂载，已加引号；随后 Knowledge 完成 dev V11 后因搜索 DNS HTTP 校验拒绝启动，已通过固定端口转发修复运行配置，未修改业务校验。两次失败均未恢复旧 writer、未逆向迁移/删数据。最终镜像重建后 9 个容器全部 healthy，Knowledge readiness 为 UP。
- 原凭据与模型账本继续沿用；只读冒烟不发付费模型调用；不删卷、不清理历史数据、不新增模型预算。模型价格核验 2026-10-21，目前仍在已有 7 天期限内。
- 日志/恢复：原工作区 `.logs/docker-deployment-20261027/`、`.local/docker-deployment-20261027/`。构建/运行会话均成功退出，实际容器镜像 ID 与源码摘要逐一一致，浏览器结果见 `browser-result.json`。原 61 条文档与预算摘要保存在 `cutover.json`；备份为 `pre-v11-development.sql`（0600）。当前无运行中的本轮迁移、构建或测试。
- 采用：runtime-and-deploy、deployment-execution、task-git-delivery、project-documentation、update-progress-docs；架构规范“业务建模与代码组织”“并发、安全与验证”“发布与数据治理”。
- 下一步：只交付本任务部署配置与结果，正常提交、合并、推送 main 并核对远程 CI；原开发进度入口仍为 `docs/design/knowledge-first-20261020/PROGRESS_STATE.md`，S10E 保持 IN_PROGRESS。
