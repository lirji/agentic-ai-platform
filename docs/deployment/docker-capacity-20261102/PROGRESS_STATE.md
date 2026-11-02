# 最新代码Docker部署进度

- 状态：DONE / DEPLOYMENT_RESULT=PASS。desktop-linux/agentic-ai-app，http://localhost:28086，9容器全部healthy、restartCount=0。真实身份/只读业务/用量owner边界/桌面窄屏与旧字段/账本核对全部PASS。[部署收据](deployment-result.json)、[验收](TEST_RESULT.md)。
- 版本：release6b6adccd4f4f997f；后端ea339ee853958f37783476201d212b91f37f1984、前端c2d12feac3ad5f6d58f1bfd13a8a16476f6c8980；固定已提交快照，includesUncommitted=false。后端分支41409113451/main41409300564与前端main41388446325全部CI PASS。当前后续归档提交只含部署/进度证据，不改变应用制品。
- 过程：正常停旧9应用→三库私密SQL备份/23表2714行旧字段及两账本摘要→固定快照全Java/前端42组246项/静态/镜像→整体up→9健康与版本/实际登录只读smoke→旧字段和预算摘要一致。Knowledge V12→19、Tasks V5→8、Conversations保持V3；未做逆向恢复或数据清理。
- 运行模式：OIDC/PDF/DOCX/STRUCTURE_V3及原local-agentic worker绑定保留；sharing/feedback/operations/publication/recovery未启用，资金LEGACY_LOCAL保留；两域容量策略均NULL，新容量模式默认关闭。原local-dev资料及排队/过期任务保留原归属，未新增模型费用或复制额度。
- Git：原目录feat/docker-capacity-20261102，基线ea339ee；本轮待归档的仅部署文档、截图和进度/交付收据，前端无改动。按正常branch CI→ff main/push→main CI交付，最终精确归档ref/CI见.local/docker-capacity-20261102/delivery-final.json。原main工作树复用，无新worktree。
- 运行中：新9应用容器；本轮build/up/browser/数据核对全部退出，无在途迁移/模型调用。原28196等隔离验证/静态预览保留，不停止其他服务。
- 保留：当前.local/docker-runtime/releases/6b6adccd4f4f997f/runtime为活跃挂载；原预算/身份密钥、旧快照/镜像、.local/docker-capacity-20261102的三库备份及previous-*、.logs/docker-capacity-20261102和旧worktree均保留。备份未做恢复演练；迁移后旧writer不能直接回退。缓存/旧快照需不再用于恢复且另获授权才能清理，本次无清理。
- 下一步：完成归档Git/CI并结束本次部署目标。S16C/D及完整51项仍未完成，不自动把当前成功解释为生产容量或恢复演练通过；能力计划入口仍为docs/design/knowledge-first-20261020/PROGRESS_STATE.md。
- 规范：deployment-execution、runtime-and-deploy、task-git-delivery、ci-cd-gate、update-progress-docs及架构持久化/并发/发布治理；用户已明确授权本地部署与正常Git，不授权远程生产/删卷/新增费用。
