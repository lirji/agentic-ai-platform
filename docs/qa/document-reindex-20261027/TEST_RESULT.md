# S10D 验收结果

状态：本地实现与必需验收 **PASS**；Git/CI交付结果另见收据。源码指纹见[validation.json](validation.json)，实现与反例修正见[IMPLEMENTATION_EVIDENCE](IMPLEMENTATION_EVIDENCE.md)。

| 验收 | 结果 | 证据 |
| --- | --- | --- |
| LC-01/02/06/17 固定快照与独立代次 | PASS | KnowledgeLifecycleIT：失败v2保留发布v1；REINDEX取v1原文件/名称/hash，生成代次3，最新内容/失败状态不变；UPDATE/REINDEX真实MySQL竞争唯一赢家，无孤立代次 |
| LC-07/08 新旧profile与稳定身份 | PASS | 同文件PDF/DOCX V2→V3真实解析/S3/MySQL/双索引；两套离线LlamaIndex样本由Java/Python共同核验；同profile重建也换块ID，旧金标逐字节保留 |
| LC-09/18 恢复与发布核验 | PASS | 每路全量ID/身份/batchDigest核验，ES正文对账；最后一项、缺失、重复、错误owner/hash等负例；真实DONE关键词投影损坏拒绝发布，修复投影后原准备包恢复；原模型/解析计数不增长 |
| LC-10/17 当前发布与旧引用 | PASS | 真实HTTP先失败新稿，再两次重建同内容版本1至代次3/4；旧代引用及来源404，当前发布读取成功，名称来自原发布修订 |
| LC-19 有界扩展与失败语义 | PASS | 真实ES/Qdrant20条旧投影时扩到60找回有效块；100条旧候选耗尽503。原排名、20/60/100上限、一次查询Embedding、剩余预算递减单测通过 |
| LC-10 共享消费者 | PASS | 新代次发布后3类历史任务GET/SSE共6次404，K08拒绝原引用，Conversation旧上下文409；不改写历史结果。PG/评测守卫产品代码未变，沿S10B/C有效证据 |
| LC-21 默认关闭与幂等 | PASS | 关闭前后HTTP新请求503；已接受原键POST仍202同回执，变参409；重复JSON/浮点/字符串/越界/未知字段400、只读403、异owner/tenant404 |
| LC-24 前端恢复与视口 | PASS | 89单元；14项协议浏览器回归；真实1次POST响应丢失、刷新恢复原键、原内容版本新代发布；另2项只读重验。1440/1024/414及Escape/焦点；旧引用清空 |

Java最终143单元+116集成全部通过：`.logs/s10d-full-java.log`及严格JSON变更后`.logs/s10d-final-knowledge.log`。Python396通过/51显式环境跳过，Ruff/mypy按当前源码检查；跳过不计通过。前端类型、构建、ESLint、格式与89单元通过；日志为两仓`.logs/s10d-*`。HTTP分阶段探针[http_probe.py](http_probe.py)；固定编号及原键保存在忽略目录`.local/s10d-http-state.json`。

前端可视证据见[前端验收](../../../../agentic-ai-platform-web/docs/qa/document-reindex-20261027/TEST_RESULT.md)。最初未等待动画的弹层截图不作为布局通过证据；最终`current-confirm-*`已逐项查看，确认无横向溢出，发布记录清楚区分内容版本和代次。

限制：存在淘汰候选且扩至100仍不足请求topK时，按契约返回503；真实单文档topK=1成功、topK=5不足且有旧代返回503，不能当作无答案。当前不物理删除旧代次。新样本为受控机制对照，无语义质量/SLO/灾备承诺；U01/U03/U06/U07不扩展。无生产部署，S10E查重及S10F诊断仍后续范围。
