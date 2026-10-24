# C-RELEASE 最小持续集成

版本 `ci-v1`，2026-10-24，S8F。只冻结当前两仓的构建/协议回归入口；S17的制品发布、环境门禁、迁移与回退仍独立实施。

## 检查与边界

| 仓库 / 必需检查 | 可观察结果 |
| --- | --- |
| backend / `java` | JDK21、现有POM依赖与Maven插件构建；全部无数据库单元/本机协议测试通过；P01与E02金标拒绝DTO字段/类型漂移 |
| backend / `python` | Python3.12按uv.lock安装（locked检查后冻结运行）；Ruff格式/lint、mypy、全部默认pytest通过；P01与E02读取同一份金标 |
| backend / `documents` | 当前CI登记的契约文件/版本标记与金标路径存在，JSON可解析；差异无空白错误。只验证登记的契约，不冒称所有历史Markdown链接均有效 |
| frontend / `web` | Node取.nvmrc，pnpm取packageManager，pnpm-lock.yaml冻结安装；格式、lint、类型、全部单元及Vite构建通过 |

两仓分别在push（main、feat/fix/refactor分支）、pull_request与workflow_dispatch运行。所有必需job成功才算当前ref通过；远程结果绑定完整SHA，分支后来变化不能沿用旧结论。没有跨仓私密checkout，也不自动部署或合并。正常合并推送仍依用户既有授权，CI不替代业务验收。

## 跨语言约束

P01沿用 `knowledge-service/src/test/resources/processing-golden.json` 及原Java/Python双端测试，不复制第二份。E02新增同目录 `evaluation-wire-golden.json`，是[E02](EVALUATION_RUN_CONTRACTS.md)与[quality-v1](QUALITY_CONTRACTS.md)的可执行投影：冻结输入、模型profile、含定义/绑定的open、逐题成功/无答案/失败、候选与完成凭据、任务状态编码。

Java通过现有EvaluationJson/RunModels/RunRules读取校验再序列化；Python通过现有Pydantic模型读取再按wire别名序列化。两端输出须与金标JSON结构和值一致；新增/遗漏/改名字段不会被静默吞掉。负例覆盖未知字段、字符串数字、数值金额、失败带成功结果等关键语义。更改协议时先更新正式契约及金标，再同步生产者/消费者，不自动重生成金标来迁就代码。

这不是完整OpenAPI生成平台，也不覆盖所有已有T/K/Q/A字段；现有单元与集成验收保持各自边界。新增边界按后续slice扩展共享样本，不宣称一份样本证明所有兼容场景。

## 无费用与隔离

CI不注入真实模型、开发者私钥或本地账本，不调用deploy初始化/付费脚本。Python原条件集成测试缺显式开关时跳过；Java数据库IT只在原integration profile及隔离配置下执行，本工作流不自动开启。跳过项不计真实DB、PG、模型或端到端PASS；这些继续由各业务slice本地/隔离验收提供。

checkout关闭持久凭据，GITHUB_TOKEN仅contents:read；Action固定完整提交SHA，job有明确timeout，并发取消只作用于同工作流/ref。日志不打印环境或凭据；不从PR标题等不可信字符串拼shell，不使用pull_request_target执行PR代码。缓存不是必需前置，首次安装可在干净runner复现。

## 工具与依据

复用JDK21、Python3.12、uv0.11.3、前端Node24.22.0/pnpm12.14.0，不升级业务依赖。Java采用现有POM固定依赖/BOM（无另造Maven锁文件）；Python与前端分别使用原uv.lock和pnpm-lock.yaml。原生数据库与模型在此范围外。

[GitHub权限与SHA固定说明](https://docs.github.com/en/actions/reference/security/secure-use)、[uv官方CI用法](https://docs.astral.sh/uv/guides/integration/github/)、[setup-java](https://github.com/actions/setup-java)、[setup-node](https://github.com/actions/setup-node)。Action提交已通过其官方仓库tag核对；具体SHA是工作流里的执行输入。

验收包含：本地同等命令；隔离测试中引入字段漂移使两端测试确实失败；推送工作流后查具体SHA的真实远程结果。远程无法启动或失败时保持S8F未完成，不以本地通过冒充远程通过。
