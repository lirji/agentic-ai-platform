# Docker正式身份验收

结论：PASS。对象为本机`agentic-ai-app`，固定制品`aaec73cb5cfc0fff`，不是远程生产发布。[部署收据](deployment-result.json)记录两仓提交、9个容器及镜像ID、CI和实际检查时间。

| 验收 | 证据与结果 |
| --- | --- |
| 固定源码与配置 | 两仓Git HEAD归档；未提交文件排除；模式/解析开关参与摘要，Compose来自快照；签名密钥不挂载正式Web |
| 代码与镜像 | Maven verify 183/183；前端format/lint/typecheck、188/188及OIDC生产构建PASS；7种镜像构建加载PASS |
| 代理边界 | 新Web镜像在network none中完成7条OIDC/13条local代理验证，Cookie/CSRF保持，正式模式无本地身份回退 |
| 启动 | Compose up --no-build --force-recreate --wait返回0；9个容器healthy、无重启；worker独立配置/PG/预算预检返回0 |
| 登录与退出 | 两个普通账号均实际通过Casdoor密码登录；身份匹配稳定UUID/local-agentic；Cookie HttpOnly/SameSite=Lax，session no-store；退出后旧cookie401 |
| 拒绝路径 | 匿名/伪造Bearer/旧local-session均401，未授权账号拒绝；缺CSRF退出403；伪造旧租户/owner头读取真实旧资料404 |
| 业务只读 | 每个账号8条真实API，包括资料、会话、研究/只读任务、评测列表、检索/任务/会话能力；5个业务页面可访问 |
| 数据和预算 | 三库备份完成；迁移至12/5/3；23表2714行原字段摘要一致，两累计预算SHA不变，付费调用0 |
| 界面 | 登录、1440桌面、414窄屏截图已实际查看；导航、空个人资料页、退出可用，无页面横向溢出 |

[浏览器结果](browser-result.json)、[备份元数据](backup-result.json)、[登录截图](login.png)、[桌面](workspace-desktop.png)、[窄屏](workspace-mobile.png)。私密SQL、原字段摘要与两账本摘要留在`.local/docker-identity-20261101/`；原始构建/部署日志在`.logs/docker-identity-20261101/`，不包含密码或会话令牌。

首次镜像构建在Docker仅余约500MiB可用内存、swap耗尽时出现1项5000ms测试超时（187项通过）。正常停止本项目旧应用后，同一固定源码、同一测试和时限重跑188项全部通过。未停止共享基础设施、关闭TLS或放宽测试。健康探针冷启动超时随后恢复；独立worker预检与持续健康核对均通过。

边界：本次不重新执行付费问答/上传/索引模型链路；此前功能验收不被本轮只读smoke替代。未做备份恢复演练，迁移后不自动启动旧schema writer。S13B共享未部署。
