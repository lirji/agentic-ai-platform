# S13A 有界审查

同一Agent独立切换审查pass；功能审查PASS，当前无阻断代码Finding，Git/CI另核。范围仅正式身份与个人空间，不提前开放共享角色。

- 身份来源：Spring OIDC签名/时间/issuer/audience/nonce/state/PKCE；应用再校验organization、稳定sub及显式成员。角色文案和外来头不能扩大scope，业务服务仍用tenant+owner持久谓词。
- 撤销与故障：每次业务/API前真实userinfo及当前get-user；不存在旧ALLOW缓存。Token过期/无效/停用/删除失败闭锁。依赖故障保留服务器会话供重试，但不转发请求；受控超时/并发测试与Guard测试覆盖。
- 会话与页面：令牌只留服务器，有界会话、固定回调、no-store、CSRF、退出/过期恢复及清缓存；生产构建浏览器完成真实登录/隔离/退出和回调篡改/重放。导航只保存白名单相对路径，不保存业务正文或自动重放写命令。
- Runtime：新增oauth2-client使用既有Boot3.5.16 BOM/Security6.5.11。新依赖为已冻结C-IDENTITY授权码流程直接所需，不升级框架或新增IAM/数据库/Redis。真实本机工具只接既有测试资源与本片端口，不更新长期容器。
- 已修：认证异常稳定投影；JWKS同用受限WebClient、禁止网络自动重试；nonce测试拦截实际授权参数；窄屏保留本人展示。源摘要和最终日志见validation.json。

保留限制见TEST_RESULT；生产TLS、多实例会话、用户停用操作/共享授权不以本片测试冒称已验收。匿名请求和已登录会话数据边界分别验证。无凭据/用户密码/code/cookie进入Git证据。
