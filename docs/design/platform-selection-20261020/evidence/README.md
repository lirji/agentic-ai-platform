# 选型证据

这些文件来自 2026-10-20 选型核对，随文档从 `langchain4j-platform` 迁入。它们记录元数据与检查结果，**不表示新项目已安装依赖、运行框架、连接数据库或通过业务验收**。

| 文件 | 记录范围 |
| --- | --- |
| [package-metadata.json](package-metadata.json) | 8 个候选包当时的 PyPI 版本、Python 约束、直接依赖与官方来源；其中 Qdrant 插件只查元数据 |
| [candidate-requirements.in](candidate-requirements.in) | 7 个直接 AI 依赖与服务基础依赖的试验输入 |
| [candidate-requirements.txt](candidate-requirements.txt) | Python 3.12 / Linux x86_64 解析出的 113 包；无哈希，不作为生产锁文件 |
| [dependency-summary.json](dependency-summary.json)、[dependency-resolution.log](dependency-resolution.log) | `uv 0.11.3` 解析结果；没有安装或运行验证 |
| [source-validation.json](source-validation.json) | Java/Python 参考项目的声明版本与关键配置事实；固定提交见[来源映射](../../../doc-map.md) |
| [validation-summary.json](validation-summary.json) | 迁入后的文档、链接、源码引用、图语法及迁移完整性检查；不含运行或渲染验收 |

复现方式见[技术选型](../TECH_SELECTION.md)。实际实施仍需独立锁文件、许可证与漏洞核对，以及目标环境中的运行、恢复、隔离和模型接口验证。
