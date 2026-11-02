# 最新代码 Docker 部署进度（S16C 后）

- 任务：docker-latest-20261102；用户明确要求将最新代码部署到 Docker 并启动。目标 desktop-linux/agentic-ai-app，入口 http://localhost:28086；仅本机部署与正常 Git 交付，parallel=no。
- 状态：部署 DONE / DEPLOYMENT_RESULT=PASS；9容器全部healthy、restartCount=0，真实身份/只读业务/数据完整性通过。[实际制品收据](deployment-result.json)、[验收](TEST_RESULT.md)。Git文档归档的精确提交、推送及CI状态以此收据为准：.local/docker-latest-20261102/delivery-final.json。
- 版本：release6b7bf063f040da7a，后端7863ae3ecc082a2807333bb55184467d262d7edb、前端c2d12feac3ad5f6d58f1bfd13a8a16476f6c8980。部署时为两仓最新origin/main，源码不含未提交内容；后续归档只改变文档。[源版本CI](ci-result.json)、[来源对比](source-evidence.json)。
- 构建：固定快照Maven210项全部通过，6个后端镜像构建完成。前端重新安装pnpm长时间停滞，已中止首次构建；逐文件核对前端源码、Dockerfile、Web配置和选项完全相同后，复用已验证旧镜像，仅更新追溯标签，新旧RootFS完全相同。精确前端main CI与本次登录smoke均PASS。[前端复用收据](frontend-reuse.json)、[构建收据](build-evidence.json)。
- 数据：正常停止旧9应用后保存三开发库0600 SQL备份；45业务表2716行完整字段摘要与两账本摘要前后一致。Knowledge V19、Tasks V8、Conversations V3，无新迁移。未做恢复演练、数据清理或新增付费调用。
- 运行配置：派生runtime全部文件与旧版字节一致，保留OIDC/PDF/DOCX/STRUCTURE_V3与local-agentic worker绑定；sharing/feedback/operations及容量模式维持原状态。其他项目容器、共享基础设施和宿主预览保持原状。
- Git：原目录/Users/liruijun/personal/LLM/agentic-ai-platform，feat/docker-latest-20261102，基线7863ae3；仅本轮部署证据、运行文档和进度入口。前端无改动；main复用既有干净工作树，无新worktree。交付恢复先读取.local/docker-latest-20261102/delivery-final.json；仅在对应阶段尚未完成时继续文档提交、正常合并推main或核对CI，部署已验收无须重跑。
- 运行中：本轮build/up/browser/data检查均已退出，临时网络检查容器已移除；新9应用持续运行。没有在途迁移/模型调用/业务写入。
- 保留：.local/docker-runtime/releases/6b7bf063f040da7a/runtime为活跃挂载；旧release6b6adccd4f4f997f、镜像、.local/docker-latest-20261102/私密备份及previous-*、.logs/docker-latest-20261102/均保留。没有清理授权，不移除旧工作树/缓存/恢复材料。旧新schema相同，回退须先正常停止全套新应用再复用previous配置和已验证旧制品；本次未回退。
- 恢复入口：先核对此文件、deployment-result.json、.local/docker-latest-20261102/state.json及Git；不要因原始build.log中存在取消而重复部署，当前制品已通过up和smoke。
- 规范：deployment-execution、ci-cd-gate、task-git-delivery、update-progress-docs；architecture-standards.md的业务建模、持久化、并发安全、发布数据治理。业务计划仍在docs/design/knowledge-first-20261020/PROGRESS_STATE.md，S16C已完成，本轮不启动S16D。
