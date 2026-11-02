# S17A 固定制品与隔离发布验证

`slice=S17A`，`overall=PASS`，`gate=PASS`（本地验收）。运行ID `7f6a3c91`，隔离环境已停止，原九个应用已按原ID/镜像恢复并全部healthy。没有生产部署、付费模型调用或数据清理。Git交付状态以规范进度及后续交付收据为准。

证据：[运行/权限/重启结果](validation-result.json)、[源码与镜像摘要](source-evidence.json)、[浏览器结果](browser-result.json)；验收依据[C-RELEASE](../../design/knowledge-first-20261020/RELEASE_CONTRACTS.md)。

| 验收 | 状态 | 实测证据 |
| --- | --- | --- |
| RL01 固定制品与漂移拒绝 | PASS | 4项单元覆盖快照/额外源码/链接、清单/镜像与原环境身份保护；真实九镜像核对、专用tag换成另一镜像被拒绝、缺收据拒绝。原部署标签未变；up使用不可变ID |
| RL02 独立资源/迁移/权限 | PASS | 三MySQL新库完成Knowledge V1–19、Tasks V1–8、Conversations V1–3；PG原生及保护迁移完成。三DML身份可读本库、拒绝DDL及跨库查询；PG拒绝DDL，原开发库CONNECT权限为false且实际连接拒绝。S3桶/用户、ES索引/用户和Qdrant集合均独立 |
| RL03 完整拓扑与健康语义 | PASS | 九容器全部healthy、imageId一致；四Java服务live/ready均UP。只有28096/28090映射到loopback，管理/内部端口未发布；模型出口指向禁用loopback且无真实提供方凭据 |
| RL04 本地身份/页面/API | PASS | 10个页面或只读API检查，5个Host/Origin/fetch-site/请求头/匿名拒绝检查；真实Chromium访问知识列表、会话详情并刷新，页面与持久结果正确，无浏览器错误。1440px截图已实际查看 |
| RL05 stop/up连续性 | PASS | 正常停止再启动后合成会话、全部隔离MySQL行摘要和两合成预算一致；原48表2746行及两个真实账本摘要一致，原九容器ID/imageId/restartCount保持，全部恢复healthy |

[知识列表截图](knowledge.png)、[会话截图](conversation.png)来自最终Web制品；布局、页面组件和样式未改。本片验证实际新端口与本地身份桥，没有把模型存活检查当作生成质量、容量或灾备验收。AI只证明liveness；worker预检只证明配置/PG/预算可用。

验证汇总：Knowledge及依赖/Parser在冻结源码执行Maven verify，164项全过；前端lint/typecheck及249项单元全过；发布/原环境保护4项Python单元、无网络容器内15条本地代理/验签/拒绝回归（含可读资料分页与usage）、Ruff格式/静态、脚本编译、15份契约/15份报文、usage边界、diff检查通过。代码卫生首轮4项魔法值阻断已整改，最后无阻断；仓库级工具未发现Java专用formatter/static配置，Python与新增JS已使用项目工具单独格式校验，限制如实保留。

本片修复了实际启动/浏览器问题：Knowledge原来只允许固定dev/test索引名，现允许长度有界、前缀受限且双投影对应的项目命名空间，禁止路径/通配符/多目标；本地身份桥补齐可读资料分页白名单；Docker本地代理补齐usage路由，健康探针显式发送外部Host。命名空间校验不替代资源凭据授权。

第一次容器内Web编译在384MiB堆上限退出134；第二次1024MiB编译造成Docker查询与旧页面短暂超时，已取消本任务构建，原健康恢复且没有容器重启。改固定Node24.22宿主冻结源码编译、Docker无网络封装后成功；安装锁与pnpm锁末段一致。随后修复禁用Embedding端点需HTTPS、Web fetch探针Host未按预期发送、会话链接含时间导致测试定位失败；最终完整链路重新通过，旧日志/失败截图保留。

资源不足时已得到用户明确授权，仅正常暂停本项目原九应用；每轮用try/finally恢复，其他项目和共享中间件保持。当前原应用正常运行，隔离应用停止，资源/失败现场/漂移测试tag全部保留。没有新增worktree或清理操作。

验证镜像如实保留各自来源：Web `6c3f673eb43bff68`、Knowledge `a5bbe3eaa891f156`来自冻结工作树输入，其余七服务来自已验收原制品；`includesUncommittedValidationInputs=true`，不能冒称它们从最终Git提交构建。正式构建入口仍支持`--committed-only`。原Docker仍为`6b7bf063f040da7a`，并未部署本片新代码。

恢复入口：[PROGRESS_STATE](../../design/knowledge-first-20261020/PROGRESS_STATE.md)。私密状态/探针/构建日志在`.local/release-validation/7f6a3c91/`，末轮日志`.logs/s17a/final-validation.log`与`final-evidence.log`；前后失败快照为`*-before-readable-fix.json`和`web-inputs-attempt-*.json`。S17B–D未验收；用户代定的14天备份保留、每日完整备份、RTO60分钟/RPO24小时由S17C实测，本片不宣称灾备达标。
