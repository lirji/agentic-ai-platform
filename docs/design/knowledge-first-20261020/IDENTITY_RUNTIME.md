# S13A 本机正式身份验证入口

本入口使用专属Casdoor组织/客户端和真实服务器会话，复用已有Knowledge测试数据库与桶；模型worker全部关闭。它用于身份与个人资料隔离验收，尚不是完整业务生产部署。旧容器与开发身份模式不自动切换。

1. 登记：`python3 deploy/identity_local.py`输出不含秘密的精确计划；`--apply --management <已有0600管理配置路径>`仅创建缺失的专属对象。同名安全配置冲突拒绝，密码不重置；已成功登记无需重跑。
2. 后端：使用JDK21，`mvn -B -ntp -pl edge-gateway,knowledge-service -am package`。复用现有已迁移的测试库，不执行初始化/数据库清理。
3. 前端仓库使用已锁定Node24.22/pnpm12.14：`VITE_IDENTITY_MODE=oidc pnpm exec vite build --outDir .local/identity-dist`。正式模式必须在构建时指定，服务端代理也显式指定；禁止同时开启本地身份桥。
4. 后端原目录：`python3 deploy/identity_runtime.py start`。测试到期使用`start --idle 30s`；默认15分钟。启动前拒绝端口冲突；中断后先`status`核对，不能盲目重复启动。
5. 入口 http://localhost:28196/；账号密码仅位于`.local/identity/ACCESS.md`（0600），不写入Git或聊天。前两账号是个人成员，第三账号只作拒绝访问反例；均非管理员。
6. 浏览器验收：前端`AGENTIC_IDENTITY_BROWSER=1 pnpm exec playwright test tests/identity-real.spec.ts --reporter=list`。该测试要求30秒空闲实例；真实密码登录，不使用mock session。不记录认证trace，截图不含凭据。
7. `python3 deploy/identity_runtime.py stop`仅SIGTERM本工具拥有且命令标识仍匹配的三个进程；保留账号、资料、日志和凭据。状态在`.local/identity/runtime-processes.json`，日志`.logs/s13a-runtime-*.log`。

固定本机端口：Gateway28190/管理29190，Knowledge28194/管理29194，网页28196。回调固定`http://localhost:28196/login/oauth2/code/workbench`；其他域名需合法HTTPS+Secure与IdP登记，不随浏览器输入改变。所有正式模式`BROWSER_IDENTITY_*`键见网关application.yml，秘密由受控环境注入。前端`VITE_IDENTITY_MODE=local`仍为原开发模式；401/503不会触发模式回退。

会话有界且易失，当前单网关最多1000会话；无共享会话/多实例HA承诺。退出仅撤销工作台会话，下次要求重新验证账号。旧local-dev资料没有归属迁移，新成员通过API创建自己资料。完整容器制品/推广仍沿S17计划，不代表本次已部署。
