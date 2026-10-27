# 本机 Docker 部署结果

- 目标：Docker Desktop `desktop-linux`，原项目本机环境；源码快照 `5a313f0bd92dec9b`（双仓完整 SHA-256 见原工作区 `.local/docker-runtime/current.json`），包含用户指定的未提交 S10E 后端代码。基线为后端 `0eeea79`、前端 `56b3f3b`。部署配置为单独的 `feat/docker-deployment-20261027` 任务分支，未提交 S10E 业务代码。
- 结果：8 个应用容器及 1 个本地搜索端口转发容器全部 healthy。Gateway 仅映射 `127.0.0.1:28080` 和工作台 `127.0.0.1:28086`。内部 Java readiness、AI live、LiteLLM 鉴权 models、worker 配置/PG/预算、Web 本地会话全部通过；容器镜像 ID 与源码摘要已逐一核对，私密逐容器记录在 `.local/docker-deployment-20261027/deployment-result.json`。
- 数据：旧宿主进程正常退出后，项目三套开发数据库完成私密备份。Knowledge 开发库从 V10 正常升级 V11，独立 test 库原已 V11。升级前后 61 条资料的业务字段逐行 SHA-256 一致；Embedding 和聊天预算文件逐字节一致。未回放任何付费调用。备份存于被忽略的 `.local/docker-deployment-20261027/pre-v11-development.sql`（0600），未做恢复演练。
- 构建/测试：Maven 143 项无数据库单元/协议检查通过；Python Ruff、mypy、396 项测试通过，51 项需要显式条件的集成检查跳过；前端格式/lint/类型、21 文件 89 项单元、Vite 构建通过。S10E 原 30 项有界真实数据库检查所对应的 10 个源码摘要与此快照一致；整片仍未完成。
- 运行验收：10 项只读页面/列表检查、五个工作台页面的真实浏览器渲染均通过，没有浏览器异常或失败的 API 请求。新增查重接口返回空匹配形状、非法 hash 400、未认证 401、无范围 403、本地身份桥跨站 403 均通过。Knowledge readiness 在最终搜索转发镜像重建后再次为 `UP`。
- 首轮启动发现并修复 tmpfs YAML 分隔格式，以及 Knowledge 搜索 HTTP 只接受 loopback 的运行约束。修复均在部署配置/固定目标转发内完成，未改业务校验、重放任务或逆向迁移。原失败日志与最终日志保留在 `.logs/docker-deployment-20261027/`。
- 使用边界：仅本机开发身份/明文依赖。V11 迁移后旧版 Knowledge JAR 不兼容，失败时保留数据并修复/重启同版镜像；没有自动数据库回滚。完整启停与恢复见 [运行手册](../../../deploy/docker/README.md)。
- Git / CI：待完成任务分支提交、正常合并推送 `main` 与提交 SHA 对应的远程 CI 检查；私密部署收据会同步记录实际结果。
