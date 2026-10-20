# S2 实现证据

- 范围：Python P01/P02；LangGraph 固定准备图、LlamaIndex 原文分块、Deep Agents 隔离兼容测试；专用服务身份与本机运行入口。
- 代码：`ai-runtime/src/agentic_ai/{processing,api,runtime}`、依赖/锁/样例、两份新测试；运行：`deploy/ai_local.py`、`processing_smoke.py`、既有 `local.py`。
- 关键不变量：身份与正文匹配；原文 SHA-256 一致；分块位置与原文一致；模型/维度/数量/有限值固定；所有摘要可跨语言重算；Python 不写知识/检索权威；没有 hash 或随机向量 fallback。
- 失败路径：过期/伪造/错误受众、scope、服务、所有者；非法 JSON/类型/哈希/Unicode；超过正文/分块/并发上限；总期限、调用取消和 HTTP disconnect。
- 本地验证：103 项 pytest、Ruff format/check、mypy、sdist/wheel 构建通过；`.logs/s2-{pytest,build}.log`。
- 实际模型：固定合成文本在原北京地域得到 1024 维向量；HTTP 返回准备结果的原文区间、分块/向量/批次摘要独立重算通过；查询向量成功；401/403 拒绝路径未调用模型。见 `real-model-smoke.json`。
- 本轮预算：两次调用合计预留 0.02 元，上限 1 元；预留记录在被忽略的 `.local/embedding-budget-20261020.json`；没有读取实际账单。
- 修正过程：首次导入排序/长行/断开监控静态检查已修正；代码卫生首次将错误转换中的 `except: pass` 及已选依赖标为阻断，改为显式失败状态，并按正式选型提供精确依赖许可参数后通过。没有降低测试断言。
- 依赖查询：OSV 直连 TLS EOF/timeout，改查全部 118 个本机发行包的 PyPI 版本公告；MarkupSafe 首次连接失败，定向补查成功，无跳过。NLTK 公告的适用范围及剩余风险进入最终 TEST_RESULT。
- 实现与验证由同一 Agent 分离 pass 完成，未声称多 Agent 独立审查。后续 Java worker/发布/检索、PG 长任务恢复、生产 IAM 与 React 不计入本片。
