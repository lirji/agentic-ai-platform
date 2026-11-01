# S13A Docker部署进度

- 状态：DONE，DEPLOYMENT_RESULT=PASS；本机desktop-linux/agentic-ai-app，正式入口http://localhost:28086/。9容器全部healthy、restartCount=0，真实浏览器与原字段核对PASS。权威证据：[部署收据](deployment-result.json)、[验收](TEST_RESULT.md)。
- 目标/授权：用户明确要求将最新已交付版本部署到Docker；仅当前本机应用，不扩大到远程生产/S13B共享，不删除卷或数据，不新增模型额度。
- 部署版本：release `aaec73cb5cfc0fff`；后端`682d8cdb4b0e24e1338f982d50bfb40763c1dcfa`、前端`a394f583024049b7e0d2b458fdbeabd68e47a0ec`；固定提交快照、模式参数与逐文件摘要在`.local/docker-runtime/releases/aaec73cb5cfc0fff/manifest.json`。includesUncommitted=false；S13B脏文档未进入制品。
- 已完成：OIDC前端构建/代理、固定Casdoor loopback桥、专属应用追加28086回调并保留28196、派生worker绑定local-agentic、三库备份、整体切换、追加迁移、9健康、真实账号/CSRF/退出/旧租户隔离/只读接口及桌面窄屏验收。
- 数据：Knowledge V12 / Tasks V5 / Conversations V3；23业务表共2714行原字段摘要保持一致，两个预算文件SHA不变，本次付费调用0。原local-dev资料保留原归属，不自动迁移给正式账号。
- IAM：复用已登记个人账号与原client secret；密码只在`.local/identity/ACCESS.md`（0600）。管理凭据未注入应用；正式Web不挂载测试签名私钥。
- 构建/验证：Java183、前端188、镜像代理OIDC7/local13、真实账号两组各8条业务API全部PASS；3张截图已查看。首次构建因Docker内存/swap耗尽导致1项5000ms超时，正常停旧应用释放资源后，相同源码/测试/时限重跑通过，未绕过检查。worker冷启动探针曾超时，随后自动健康；完整预检也PASS，当前9项健康复核通过。
- 交付：实现分支feat/docker-identity-20261101；产品682d8cd已正常ff合并并推main，分支CI41314134393/main41314310124全部PASS。后续验收文档归档提交和CI精确收据在`.local/docker-identity-20261101/delivery-final.json`，不改变运行制品版本。
- 当前修改归属：部署文件/验收记录属于本任务；原CODEX/主计划的S13B文档修改与SHARING_READINESS草案保留，不混入部署交付。前端无本轮改动。沿用现有main工作树，无新增worktree，不做清理。
- 恢复：日志`.logs/docker-identity-20261101/`；备份/原字段/旧部署收据`.local/docker-identity-20261101/`（0600）；当前配置/快照`.local/docker-runtime/`。迁移后不得直接启动旧writer；故障优先修复兼容新schema的版本，不自动逆向恢复。备份未做恢复演练。
- 运行中：9个Docker应用容器；原28196隔离验证进程仍保留（test库、模型worker关闭），PID入口`.local/identity/runtime-processes.json`。无未结束构建、部署或迁移命令。
- 下一步：本次Docker部署无待办。原完整能力交付仍由设计PROGRESS_STATE维护；S13B仍等待业务矩阵，不因部署完成宣称51项全部完成。
- 采用规范：runtime-and-deploy、deployment-execution、project-documentation、update-progress-docs、task-git-delivery、ci-cd-gate；架构建模/接口/持久化/并发安全/发布治理。
