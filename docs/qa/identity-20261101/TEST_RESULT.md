# S13A 正式登录验收

2026-11-01。功能验收PASS，交付状态另见后续delivery-result.json。契约identity-session-v1，真实Casdoor、Gateway、Knowledge/MySQL/对象存储及React生产构建。无模型调用、原业务数据迁移或生产部署。

| 验收 | 结果与证据 |
| --- | --- |
| 专属登记 | local-agentic / agentic-workbench；两个人成员及无授权反例，非管理员，无自助注册。私密登记收据/凭据保留.local/identity/，不进入Git |
| 登录/可信身份 | 真实密码表单、授权码+PKCE、稳定sub绑定；服务器会话不向React返回Token；独立内部JWT并清理cookie与外来身份头 |
| 个人隔离 | 两成员通过Gateway上传真实测试库/桶，各自文档200、他人文档404；伪造tenant/user/internal头不能改变所有者 |
| 会话安全 | HttpOnly/SameSite=Lax，session no-store；缺CSRF写操作403；UI退出后旧cookie401；30秒空闲到期后重新登录恢复/chat，不重发业务写命令 |
| 回调/准入 | state/code/nonce篡改、无应用成员、回调重放、裸Bearer和开发session入口拒绝；6项真实浏览器测试PASS |
| 依赖故障 | 503/错误sub/空响应/过期Token拒绝；16并发、3秒超时及取消释放；503保留会话但不转发，401撤销会话。受控HTTP替身，未停止共享Casdoor |
| 当前权限 | 单测拒绝错误issuer/audience/组织/subject/tokenType/用户名及停用/删除/字段缺失；每次重验userinfo和get-user，不缓存ALLOW |
| 回归 | Gateway19/Security10/Contracts11单元PASS，前端188测试；格式/ESLint/类型/默认构建/OIDC生产构建PASS |
| UI | 1440×1000与414×896截图4张已查看；真实资料列表、本人和退出均可见，窄屏表格内部横向滚动，页面无横向溢出 |
| Runtime | py_compile与真实start/status通过；仅隔离测试库/桶，不执行迁移或模型worker；正式失败不回退开发身份 |

源码/日志摘要见validation.json。主要日志：后端.logs/s13a-backend-verified-r2.log；前端.logs/s13a-browser-final.log、s13a-web-final-unit.log及静态检查。前端tests/identity-real.spec.ts可重跑。

保留失败记录：测试实例误继承AI就绪依赖后改为本片所需配置；异常回调映射与JWKS受限客户端补齐；nonce负例改到真实授权API参数；截图等待加载/侧栏动画并保留窄屏本人展示；测试类型错误均已修。没有放宽安全断言。旧workbench全套误用于未开启开发桥的静态预览而失败，其首项在读取旧资料时即失败、未发生模型调用；改用不依赖该环境的work-contract三项回归，原s4真实环境不重跑。

局限：单网关易失会话，无共享会话/HA声明；loopback HTTP，HTTPS配置拒绝已测但未部署TLS。故障/停用删除为受控协议测试，没有修改共享IAM账号状态。隔离入口关闭模型worker，完整业务容器推广沿S17。Java未配置独立格式器，既有Vite提示/包大小警告保留。由同一Agent分别执行实施/审查/验收，不冒称独立人员或Runtime机器Gate。
