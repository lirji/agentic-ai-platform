# S9A 结构化解析设计验收

2026-10-24，后端基线82eb31c，前端83c0bc6只读。**设计及候选探针PASS**；PDF/DOCX在线受理、持久解析、准备发布和前端尚未实现，不计产品链路通过。正式入口：[解析设计](../../design/knowledge-first-20261020/PARSING_DESIGN.md)、[P-v2](../../design/knowledge-first-20261020/STRUCTURED_PROCESSING_CONTRACTS.md)、[后续验收矩阵](../../design/knowledge-first-20261020/PARSING_ACCEPTANCE.md)。

| 验收项 | 结果 |
| --- | --- |
| 当前事实 | 核对原TXT/MD受理、v1字节校验、持久准备/逐sink发布、单profile搜索过滤、评测来源生成、Python仅utf8-bytes正则；新设计逐项对应 |
| 候选与唯一Owner | Java PDFBox/POI、Python pypdf/python-docx同组样本通过；Tika宽格式方案仅文档比较，未伪称执行过。选定Knowledge管理的Java子进程，Python仅转换/分块 |
| 实际样本 | 3页含空页/旋转PDF、加密/损坏PDF、中文DOCX标题/空段/有序表格/横向合并/外链文字；4个固定原文件及hash见fixture-manifest，观察见observed-results |
| 制品与费用 | 实际JDK21编译/读取PDFBox3.0.8/POI6.0.0，17个JAR约23.87MB；直接许可证、官方来源与单次资源观察有证据；无模型调用和业务依赖变更 |
| 协议设计 | 原文件/提取物/块/来源/向量摘要分开；UTF-8提取片段来源、空结构、合并格、告警、闭合枚举/错误、HTTP/子进程边界明确 |
| 兼容与失败 | P-v1保持；固定schema/profile与已接受指针恢复；新locator严格消费者和多profile检索先升级；失租/未知提交/回退/资源边界及验证任务明确 |
| 范围控制 | 未改产品POM/uv.lock、应用代码、业务数据或运行进程；未新建服务/数据库/生产部署；U02未明按机制样本处理 |

本次由当前Agent完成设计后再按事实/契约/故障清单审阅，没有独立Agent或用户签核。修正了“字段名不变即可透明兼容”的初稿风险：真实Python源码限制locator值，正式方案要求消费者先升级；原公共字段准确保持displayName/locator。

探针错误与环境限制如实保留：PyPI TLS失败后使用固定官方源码和离线缓存；缺失DOCX tblGrid是生成器错误，修正夹具后两个候选通过，不记为Python能力失败。Java的日志provider告警只在隔离探针，已列为产品包装必须解决项。一次小样本性能不是SLA，JVM堆不等于RSS硬隔离；生产OS边界及大/恶意文件仍未验证。

设计文档验证、最终探针复核与源码指纹见validation.json。后续必须按PAR-01..15补完整S9B/C/D验收，特别是中文PDF、纵向合并、ZIP/XML/外链、堆/输出/期限故障、真实权限/数据库/索引/界面；当前不冒称这些通过。

Handoff：`backend-architecture-design / S9A / COMPLETED / PASS`，`implementation-validation / S9A-DESIGN / PASS`；正常Git/CI交付后继续S9B，不进入OCR或其它条件格式。
