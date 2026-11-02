# Docker 最新代码部署验收

结果：PASS。2026-11-02，desktop-linux/agentic-ai-app，http://localhost:28086。

| 验证 | 结果与证据 |
| --- | --- |
| 最新固定源码 | 后端7863ae3、前端c2d12fe；两仓origin/main及精确提交CI通过；[CI收据](ci-result.json) |
| 后端构建 | 固定快照mvn verify，210项测试、0失败/错误/跳过，6个后端镜像完成；[构建收据](build-evidence.json) |
| 前端制品 | 最新前端与已验证旧制品逐文件一致；复用旧image digest，新旧RootFS一致，仅变更追溯标签；[复用收据](frontend-reuse.json) |
| 实际运行 | release6b7bf063f040da7a的9容器全部healthy、重启0；imageID/containerID/指纹见[部署收据](deployment-result.json) |
| 身份与业务读取 | 两普通账号登录、8类只读API/账号、用量owner边界、旧租户隔离、CSRF、退出撤销、匿名及未授权成员拒绝全部通过；[浏览器结果](browser-result.json) |
| 页面 | 登录页、1440桌面及414窄屏截图已查看，无横向溢出；[登录](login.png)、[桌面](workspace-desktop.png)、[窄屏](workspace-mobile.png) |
| 数据与预算 | 45业务表2716行完整字段摘要及2账本摘要相同；Knowledge V19/Tasks V8/Conversations V3，无新迁移；[数据核对](data-result.json) |
| 配置与范围 | 现有派生runtime逐文件一致，原功能开关保留；没有业务写入、模型调用、清理或远程生产部署 |

首次完整build在Web的npm全局安装pnpm步骤持续无输出，因此中止；网络探测可访问registry，但包安装仍慢。前端代码、Dockerfile、Web配置及选项均未变化，采用已验证制品复用。按digest构建时BuildKit尝试访问未发布的远程仓库而失败，随后以已核对imageID的本地tag完成仅标签构建，并校验新旧文件层相同；未更换依赖、修改产品源码或放宽测试。原始日志与失败现场保留于.logs/docker-latest-20261102/。

三库SQL备份、原配置和旧镜像已保留；备份不代表恢复演练通过。现有功能开关未扩大，本次未验证新启用模式、真实模型生成质量、生产容量/SLO或灾备恢复。
