# 独立知识平台准备进度

## 任务目标

- 任务：`knowledge-first-baseline-20261020`；固化用户最新确认的独立项目、全新 React 前端、知识库优先、技能代码规范及参考资产边界。
- 初始范围：更新准备与设计文档、核对参考资产、验证并交付文档。
- 用户最新授权与活动目标：完成本部分后继续后续实施准备并开始 Coding。按既有架构推进 React 设计、正式契约、一致性审查、实施切片及首个可验证代码切片；无需再次询问是否继续。完整知识业务的后续切片仍分别记录完成状态，不以开始编码宣称全平台完成。
- 权威入口：本目录 `BRIEF.md`；架构与组件继续原位维护于 `../platform-selection-20261020/`，不建立第二套架构/选型。
- 用户已确认：按需从 `langchain4j-platform` 迁入所需能力；React 前端从零建设；知识入库、知识问答、研究分析和业务助手为优先业务；后续业务参考 AgentScope；模型与数据参考原 langchain 项目。
- 最新补充：不使用默认 HashEmbedding，允许采用参考项目中的其他真实 Embedding。已核对标准 Compose 为百炼 `text-embedding-v4` / 1024 维 / OpenAI 兼容协议，作为新项目设计默认；配置缺失和调用失败不得回退 hash。
- Git 授权：沿用户持续授权，以任务分支提交，验证后正常合并并推送 `origin/main`；不包含生产部署。

## 当前状态

- `IN_PROGRESS`：`D0` 需求基线及架构/选型同步验证通过，准备 Git 交付；下一阶段为设计/契约与首片编码，整体活动目标未完成。
- 工作目录：`/Users/liruijun/personal/LLM/agentic-ai-platform`。
- 分支：`docs/knowledge-first-baseline-20261020`；起点 `a284e8f`；原有 `.idea/` 未跟踪，保留并排除交付。
- 参考基线：`langchain4j-platform@468cec425dd160e80cfc3ab378d3440a8767e4d5`、`platform-agentscope@7738cb7bedd1a31fd4260aa66b74572ca48d9ade`；只读参考，不修改源项目。

## 任务清单

1. 已完成：核对用户最新决定、历史进度、现有架构、选型与适用技能。
2. 验证通过：固化业务范围、独立运行标准、代码组织规则、资产来源及后续准备顺序；默认百炼 text-embedding-v4 / 1024 维，禁用 hash 业务默认及回退。
3. 验证通过：更新架构、选型、文档索引与来源映射，消除旧前端复用和依赖旧业务部署的表述。
4. 验证通过：11 份 Markdown、66 处本地链接/锚点、357 行表格、39 个不同固定源码对象及历史证据字节一致；修改的 Mermaid 图语法通过，未渲染。结果见 [baseline-validation.json](evidence/baseline-validation.json)；计数对应正文验收快照，进度收尾另查链接及 diff。
5. 待完成：按本任务显式文件清单提交，正常合并与推送，核对远程结果。
6. 待完成：按 `public-engineering-workflow` 承接 React 架构、首条路径契约、一致性审查与纵向切片。
7. 待完成：选择依赖满足的首个代码切片，按实现/验证技能编码、检查、更新进度并交付。

## 恢复与边界

- 已选框架和组件沿用；独立性通过迁入所需代码、项目级数据/配置隔离和独立启动验证实现；dev_infra 等共享基础设施按既有约定接入。
- 模型配置与样本只登记来源和安全的结构信息，不读取私密凭据、不把旧运行数据自动导入新库。
- 正式 CONTRACTS 尚未形成；先记录业务验收与契约需求，随后依据正式契约生成实施切片，不把阶段路线冒充可执行切片。
- 已读规范：`~/.codex/architecture-standards.md` 的业务建模、持久化、契约、并发安全、中间件、容量与发布章节；`~/.codex/personal-tech-stack.md` 的优先级、Java 路线、前后端分仓与 React 配套。
- 技能：`project-design`、`backend-architecture-design`、`frontend-architecture-design`、`project-documentation`；已核对 `implementation-slicing` 的契约前提；进度与交付分别依 `update-progress-docs`、`task-git-delivery`。
- 运行中服务、模型调用、数据迁移和部署：无。
- 验证调整：首次 Mermaid 解析遇到 DOMPurify 缺 DOM 环境，在任务临时目录补 JSDOM 后重新解析通过；没有更改项目依赖或跳过语法验证。
- 公共 Runtime：`entry.py resolve` 返回 `MULTI_CLIENT_DRIFT_BLOCKED`（`frontend-design/SKILL.md` CORE_DRIFT 和 scan-input MANIFEST_DRIFT），未创建受治理 Run、未改全局技能/指纹；依据 public-engineering-workflow 的手工路由兼容规则，按已读取专业技能和原有用户授权逐项执行，保留人工 Gate/验证，不能宣称机器 Runtime 通过。
- 下一步：交付 D0 文档，随后直接补 React 架构、CONTRACTS、一致性审查、IMPLEMENTATION_SLICES，再执行首个可验证代码切片。

## 历史入口

- [选型与文档迁移记录](../platform-selection-20261020/PROGRESS_STATE.md)：上一任务已完成；其中当时未决范围由本轮用户确认更新，历史验证证据保持原样。
