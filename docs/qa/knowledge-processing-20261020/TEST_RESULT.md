# S2 验证结果

日期：2026-10-20。对象：[S2 验收](../../design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md#s2-有限验收)与 [P01/P02](../../design/knowledge-first-20261020/PROCESSING_CONTRACTS.md)。结论：**PASS（有限范围，保留以下依赖/环境限制）**。

源码绑定见 [validation.json](validation.json)。实现过程见 [IMPLEMENTATION_EVIDENCE](IMPLEMENTATION_EVIDENCE.md)。本验证是同一 Agent 在实现后执行的单独验证 pass，不冒充外部人员或多 Agent 审查。

| 验收 | 实际证据 | 结果 |
| --- | --- | --- |
| S2-AC1 三框架运行 | 正式 uv.lock 安装/导入；实际 LangGraph/LlamaIndex 准备图；Deep Agents 的工具调用、ToolMessage、流式更新及内存检查点状态测试 | PASS |
| S2-AC2 身份与边界 | 伪签名、缺令牌、过期、issuer/audience/azp/scope、租户/所有者不匹配、未知字段/类型/哈希/Unicode、实际正文超限测试；真实 HTTP 401/403 | PASS |
| S2-AC3 可验证准备包 | 中文/英文/CRLF/重复段落/emoji/组合字符/BOM 原文定位与稳定结果；跨租户 ID 隔离；坏模型/维度/数量/NaN/布尔拒绝；真实 binary64 摘要独立重算 | PASS |
| S2-AC4 资源与取消 | 并发满时 429；期限 503；Task 取消与 ASGI disconnect 取消在途模型；失败后名额归还；外部 LangSmith tracing 禁用 | PASS |
| S2-AC5 真实模型与 HTTP | 原北京百炼账号，text-embedding-v4 / 1024；115 字节合成资料准备和查询向量通过；两次请求保守预留 0.02 元/上限 1 元 | PASS |
| S2-AC6 工程与文档 | 103 项 pytest；Ruff format/check、mypy、构建、精确依赖许可下的 Code Hygiene Gate；源码摘要及本地文档链接 | PASS |

## 环境与命令

macOS ARM64 / Python 3.12.13。`uv run --frozen ruff format --check src tests`、`ruff check`（另覆盖 3 份变更 deploy 脚本）、`mypy src`、`pytest -q`、`uv build --no-sources`。详细日志在项目 `.logs/s2-*`；机器摘要保存在 validation.json。

真实模型验收使用 `ai-runtime/.venv/bin/python deploy/processing_smoke.py`；仅发送代码中可审查的固定合成文本，不迁入业务语料。私密配置/服务密钥不进入证据，费用是预留上界，不是供应商账单。

## 依赖与代码卫生

- [dependency-audit.json](dependency-audit.json)：118 个本机运行/开发包均查版本公告，无跳过；OSV 直接连接不可用，使用 PyPI 发布元数据公告源。不能宣称零漏洞。
- NLTK 3.10.3 命中 `GHSA-8mgp-746c-j5xp / CVE-2026-81726`：调用方可控模型文件路径下，部分训练/模型导入导出 API 可绕过 NLTK pathsec。发布元数据尚无补丁版本。当前处理代码只用显式本地分句函数和 SentenceSplitter，不调用 `TransitionParser`、`AveragedPerceptron` 或 tagger/model 保存/加载，也不接收模型路径；对**当前路径**评估不触发。启用新的 reader/训练/模型文件接口前重新评估；这是有依据的范围例外，不是忽略公告或整个库的安全认证。
- [license-metadata.json](license-metadata.json)：118 个包许可证元数据已记录，无缺项；未进行法律审查。
- [code-hygiene.json](code-hygiene.json)：`IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS`。工具仅在仓库根自动发现格式化配置，保留 `FORMAT_TOOL_NOT_AVAILABLE` 限制；实际已运行 ai-runtime 的 Ruff 与 mypy，以执行证据补入 FORMAT/STATIC_ANALYSIS。语义常量被标为魔法值 advisory，已人工核对其均有协议/配置归属。

## 未验证与下游

Java 持久 worker、MySQL lease/epoch、准备结果持久化、Qdrant/ES 提交与发布、检索质量和 React 尚未完成。P01 的短图无持久 checkpoint；Deep Agents 内存测试不能证明 PG 重启恢复。Linux/容器、真实聊天模型、研究 Agent 工具治理、生产 IAM 与生产容量未验证。

下一步继续 S3：冻结 Java 提交/检索契约，创建本项目独立检索资源，落实带 epoch 的领取和逐 sink 发布，再验证真实完整知识路径。
