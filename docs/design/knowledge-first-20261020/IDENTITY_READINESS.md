# S13A 正式身份接入前置核对

2026-11-01；READINESS_REVIEW，该次前置核对当时C-IDENTITY尚未冻结；最新实现状态见PROGRESS_STATE。只读取当前项目、已有Auth参考与Casdoor公开discovery，未写IAM、成员、授权、数据库或部署配置。正式多人接入仍受主方案U03约束。

## 已核实事实

- 当前网关已使用Spring Security Reactive Resource Server；验签固定外部公钥、issuer/audience/tenant/sub/scope格式。可信内部JWT按路径受众换发，删除外来身份提示。不能把旧选型材料中迁入前GlobalFilter描述当当前实现。
- InternalIdentityExchange直接拷贝已验签sub、配置tenant claim与scope；签名真实性不等于本项目成员/权限授予。需要明确组织到tenant、主体到userId和应用角色到业务scope的权威来源，不能直接信任浏览器租户字段，也不能照搬Casdoor owner为tenant。
- 前端唯一入口仍是/api/local-session，environment仅local，失败提示本地会话；未实现正式登录、回调、退出、续期。现有开发桥不能充当正式失败回退。
- 本机Auth参考仓库auth-platform@763ca23e0f0cc521b5f605375ab999198cf001db。既有WMS契约应用wms、组织local-wms、客户端wms-central，仅能作为实现参考，不能给新工作台继承其成员/管理权限。
- 本机Casdoor公开discovery 2026-11-01实测可达：issuer http://localhost:18090；authorize /login/oauth/authorize，token /api/login/oauth/access_token，JWKS /.well-known/jwks，logout /api/logout，PKCE S256。这证明端点公布能力，不证明新客户端登记、真实登录或退出后旧token已失效。
- 在已检查Auth deploy/docs/server资源中未找到agentic-ai/knowledge-workbench登记证据；未通过读取用户目录或授权表来推断成员许可。项目IAM配置模板仍待真实提供方值。

## 需要用户确定的业务输入

已异步询问：新专属local-agentic/agentic-workbench且限明确成员的个人空间，还是复用指定组织/成员/角色，或本轮仅做隔离协议验证。此项决定身份与数据归属，不能用超时默认选择。确定新组织也不等于允许批量导入旧成员或赋予管理员；后续须以明确成员来源绑定。

## 契约冻结与实施清单

1. 核对选定组织/客户端的实际登记、回调及允许退出URL、issuer/audience/公钥轮换、主体稳定标识、成员/租户映射和角色/scope来源。区分认证身份与业务授权，未知成员失败关闭。
2. 复用既定Casdoor/auth-platform，按真实支持能力冻结登录/回调、session读取、续期并发、退出与旧会话失效边界；不能新造IAM或默认扩大令牌权限。回调state/PKCE/重放、返回地址白名单、同源请求与敏感材料存储都进入契约。
3. API错误沿唯一错误目录，401引导重新登录、403解释权限；前端恢复原导航/未提交输入不隐式重放业务写入，退出/换用户清除业务查询缓存及订阅。开发桥独立显式开关，正式路径绝不自动回退。
4. 真实登录/到期/退出、错误issuer/audience/组织、伪造头、跨租户、撤销成员、并发续期、依赖不可用与未知结果测试；JWT与浏览器测试不替代真实提供方验收。新迁移仅在确需持久会话时由Owner追加并带注释，不预先新增数据库或Redis。
5. 本项目代码/配置在独立任务分支实施；Auth参考只读，若需新增组织/客户端则先形成可审阅的精确配置与成员清单，在用户已确定范围内执行，不能借用其他项目管理员权限推导授权。

以上清单是前置分析，不是已批准的最终身份契约或完成证据。源文件摘要保存于[evidence/identity-readiness-20261101.json](evidence/identity-readiness-20261101.json)；公开端点检查未发送凭据。

用户于2026-11-01已确认专属local-agentic/agentic-workbench及个人空间。组织选择阻塞解除，当前正式增量见[IDENTITY_CONTRACTS](IDENTITY_CONTRACTS.md)。
