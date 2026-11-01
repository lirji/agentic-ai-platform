# C-IDENTITY / S13A：个人空间可信身份

2026-11-01，identity-session-v1。用户明确选择local-agentic组织、agentic-workbench客户端，先个人空间；沿Casdoor/auth-platform方向及Spring Security，不创建第二个IAM。S13B再接团队角色/共享治理，本片不继承其他应用成员或Grant。

## 身份与登记

本机既有Casdoor http://localhost:18090；专属组织local-agentic、应用/客户端agentic-workbench，关闭自助注册与共享应用。只新增agentic-user（首个个人账号）、agentic-peer（隔离验收）、agentic-unassigned（无应用访问授权反例）三个普通非管理员账号；随机凭据只保存在本项目.local/identity/，不发送邀请、不复制旧成员。前两者稳定sub显式列入本项目允许成员配置，最后一个不授权。现存同名对象需完整核对，冲突拒绝，不能重置密码、恢复停用或覆盖旧配置。先写私密幂等状态再调用API，超时先GET核对；不直接写IAM数据库。

身份归属固定issuer+audience+organization，tenant映射为local-agentic，userId为IdP稳定sub；浏览器、展示名、用户名、owner请求头或角色文字不决定归属。业务scope仅来自服务端已登记个人成员策略，复用knowledge/conversation/task各read/write；不授予管理/其他用户资料访问权。Casdoor仍持有用户、密码和停用权威；本片显式成员配置是应用准入名单，不是新用户目录。以后中央角色接入沿auth-platform，不建立第二套动态角色服务。

## 浏览器会话

Gateway提供BFF，Spring Security OAuth2/OIDC授权码+S256 PKCE/state/nonce校验；固定注册回调，不接受客户端URL。新增BOM管理的spring-boot-starter-oauth2-client，与现有6.5.11一致，不新增服务、DB或Redis。正式模式gateway.browser.enabled=true只接受服务器会话，不开放原开发Bearer入口；false保留旧受信JWT链且不提供正式登录。前端显式VITE_IDENTITY_MODE=oidc选择正式协议，local仅用于原显式开发桥；不得遇到正式失败后自动回退。

- GET /oauth2/authorization/workbench：开始登录，prompt=login，每次明确验证账号；回调/login/oauth2/code/workbench。回调成功只跳工作台根路径，客户端可用本地相对路径恢复导航；失败仅稳定状态，不回显IdP异常/code/token。
- GET /api/v1/session：返回{tenantId,userId,displayName,environment:"authenticated",scopes}；无有效会话401 AUTH_REQUIRED；未知/停用/错误组织403 AUTH_FORBIDDEN；IdP不可用503 AUTH_UNAVAILABLE。响应no-store。
- POST /api/v1/auth/logout：CSRF保护，撤销当前服务器会话，204。退出含义仅当前工作台会话，不替其他已登录应用执行全局退出；下次prompt=login，旧cookie拒绝。IdP原token不能绕过会话直接访问正式Gateway。
- 会话cookie AGENTIC_SESSION为HttpOnly/SameSite=Lax/Path=/；非loopback必须HTTPS+Secure，部署配置冲突启动拒绝。CSRF使用独立AGENTIC_XSRF cookie与X-AGENTIC-XSRF header，所有会话写操作均验证。
- 令牌只保存在服务器WebSession，不返回React、不进local/sessionStorage或日志。会话为有界易失认证状态，最多1000，默认15分钟空闲；重启失效后重新登录，不影响持久业务任务。没有多网关共享会话/高可用声明。
- 到期不静默刷新旧身份；重新走授权码流程完成续期，恢复原页面但不重发业务写命令。每次业务/session读取在转发前用真实userinfo确认Token/sub，再以本应用client凭据读取固定组织下当前get-user记录核对稳定id/organization/停用/删除及允许成员（本机UserInfo不含停用字段），令牌过期立即拒绝。检查开始时通过的在途请求不承诺可逆撤销；S13C补齐业务阶段撤权语义。
- IdP客户端无自动重试，连接1秒/总请求3秒、最大16并发及有限响应体；过载/故障503，禁止使用旧ALLOW结果放行。OAuth回调失败不得保存已认证上下文。成功/失败不记录令牌、code、密码或cookie。

## 界面与兼容

复用现有React壳层和唯一HTTP错误目录；正式未登录显示登录，连接故障显示重试，权限不足明确拒绝。登录后显示本人、个人空间和退出；收到401停止受保护页面/订阅并引导登录，退出或换身份清理业务缓存/内存草稿。保留相对导航路径；既有未知写入仍按原taskId/幂等键查询，登录不当作写入重试信号。

旧local-dev/workbench-user资料不自动转给新成员；新成员初始空个人空间，通过正式API创建自己的资料。开发mode继续既有协议测试。内部JWT仍独立签名/受众/短TTL，清除外来身份提示，服务保留tenant+owner谓词。

## 验收与运行

真实Casdoor注册/浏览器密码登录+PKCE，真实Gateway与Knowledge持久HTTP；两成员独立创建/读取及跨用户404，错组织/未授权成员/伪造头/裸Bearer拒绝，退出旧cookie失效、到期恢复、CSRF拒绝、IdP故障不放行、回调state/nonce/code负例。既有JWT/网关/前端回归；1440/414px实际截图。无付费模型；不更新长期运行容器或生产部署。秘密文件0600/目录0700，不删除旧测试数据。

依据：[Spring Security 6.5 OAuth2](https://docs.spring.io/spring-security/reference/6.5/reactive/oauth2/login/advanced.html)、[WebFlux CSRF](https://docs.spring.io/spring-security/reference/6.5/reactive/exploits/csrf.html)、[Casdoor OIDC](https://casdoor.ai/docs/how-to-connect/oidc-client/)，并以本机实际协议验证。外部文档不证明本项目验收通过。
