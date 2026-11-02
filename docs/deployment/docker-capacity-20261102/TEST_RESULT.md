# 最新代码Docker更新验收

Status: PASS。目标为本机desktop-linux/agentic-ai-app，入口http://localhost:28086；不涉及远程生产、卷删除或新增模型额度。

- 固定release `6b6adccd4f4f997f`，后端`ea339ee853958f37783476201d212b91f37f1984`、前端`c2d12feac3ad5f6d58f1bfd13a8a16476f6c8980`，`includesUncommitted=false`。后端分支CI41409113451/main41409300564以及前端main41388446325全部PASS。
- 固定快照的完整Java reactor verify、前端Prettier/ESLint/严格构建及42组246项单元通过，锁文件和制品构建通过。详细日志`.logs/docker-capacity-20261102/build.log`；没有复用未验证的宿主JAR，没有跳过镜像内检查。
- 保留OIDC、PDF/DOCX、STRUCTURE_V3和原租户绑定；sharing/feedback/operations/publication/recovery仍关闭，资金仍LEGACY_LOCAL，新的租户容量策略未登记且开关关闭。本次更新代码与追加schema，不自动给租户新增配额、费用或改变长期并发。
- 正常停止旧9应用容器后备份Knowledge/Tasks/Conversations三库，保留23张业务表2714行原字段摘要及原Chat/Embedding账本摘要。停止前有效任务/入库租约均0，local-agentic无在途任务；旧local-dev排队/过期记录保留。SQL备份在`.local/docker-capacity-20261102/`，0600权限；没有恢复演练或逆向覆盖。

## 验收范围

上述检查全部PASS：9容器healthy/restartCount=0，镜像指纹精确匹配，Knowledge V19/Tasks V8/Conversations V3；23业务表2714行原字段及两预算账本摘要完全相同。真实两账号各8条只读API及2条用量owner拒绝、未分配成员/匿名/伪造旧身份、Cookie/CSRF/退出撤销和旧租户隔离均通过；登录、桌面和414px三张截图已查看，无溢出或遮挡。浏览器无业务写入、无trace、无付费模型调用，仅登录/退出产生身份会话；截图不含账号密码。

复验以`deploy/docker/tests/identity-browser-smoke.mjs`为基线，私密运行副本`.local/docker-capacity-20261102/browser-smoke.mjs`仅改证据输出目录并追加新用量API所有者拒绝检查，源码SHA留在build-evidence中；沿用原legacy-document记录，不覆盖20261101证据。[固定构建与日志摘要](build-evidence.json)。最终证据为[部署收据](deployment-result.json)、[浏览器结果](browser-result.json)、[数据核对](data-result.json)。

## 保留和恢复

当前release runtime为容器只读挂载，必须保留；原预算文件仍挂原路径，不复制额度。旧镜像/快照、三库备份、原current/compose/build收据、测试数据、私密身份与工作树保留。构建缓存和旧快照只有不再需要复现并另获清理授权后才可清理；本次未清理任何目录、数据或卷。

追加迁移后不能直接启动不兼容旧schema的writer；若失败优先修复兼容新schema的制品，不自动逆向恢复数据。S16C混合容量和生产SLO、S17恢复演练仍未完成。本部署验收不替代这些任务。
