# 授权检索与重排验收矩阵

依据[RETRIEVAL_CONTRACTS](RETRIEVAL_CONTRACTS.md)，S11A。DESIGN_CHECKED表示契约已明确；产品结果须由Owner片执行后逐项更新，不能由文字存在判PASS。

| ID | 场景与可观察断言 | Owner / 必要方法 | 当前 |
| --- | --- | --- | --- |
| RT-01 | 新内部两个POST只接受独立runtime签名；普通/edge签名、错误issuer/audience/azp/scope、过期/重复头拒绝 | S11B真实HTTP身份矩阵 | S11B PASS；见[验收](../../qa/authorized-retrieval-20261028/TEST_RESULT.md) |
| RT-02 | 未知/重复字段、非法UUID、空范围、模型/维度错配、非有限/零向量、越界限额、超大输入拒绝 | S11B严格DTO/HTTP | S11B PASS；见[验收](../../qa/authorized-retrieval-20261028/TEST_RESULT.md) |
| RT-03 | 伪造索引元数据、失效batch、撤下/删除、失败新稿及其他owner/tenant均不泄露正文；来源名称取发布修订 | S11B真实MySQL/索引污染 | S11B PASS；见[验收](../../qa/authorized-retrieval-20261028/TEST_RESULT.md) |
| RT-04 | 原始一基排名空位保留；同源重复/非法score拒绝；跨源相同身份不误计为剔除，不用正文hash合并 | S11B/C共享金标/真实索引 | S11B Java部分PASS；S11C部分待验 |
| RT-05 | 20/60/100有界扩展，同一向量复用；够目标/无剔除且耗尽/100仍受污染不足明确区分 | S11B受控索引与调用计数 | S11B PASS；见[验收](../../qa/authorized-retrieval-20261028/TEST_RESULT.md) |
| RT-06 | 批量解析完整身份/范围/textHash，任一失效整体404，不返回半列表；数据库失败503 | S11B真实SQL/HTTP/依赖故障 | S11B PASS；见[验收](../../qa/authorized-retrieval-20261028/TEST_RESULT.md) |
| RT-07 | 固定候选下Python RRF与Java一基RRF60、同分chunkId、身份一致，含原排名空位和同文异资料 | S11C共享固定输入金标 | DESIGN_CHECKED；产品待验 |
| RT-08 | 明确BaseRetriever/Node扩展，默认LLM/Settings/查询扩展不被调用；只融合一次，不反调RAG | S11B/C调用追踪/框架行为测试 | S11B Java部分PASS；S11C部分待验 |
| RT-09 | 一个总deadline不被每轮/阶段续期；并发2/无无界队列，满载立即拒绝，取消停止后续，无自动HTTP重试 | S11B/C隔离慢依赖/容量/取消 | S11B Java部分PASS；S11C部分待验 |
| RT-10 | 重排前候选最多50且未先裁最终K；原文外发前再次核权，提供方只收到查询/正文/必要参数 | S11D模型替身与撤权竞态 | DESIGN_CHECKED；产品待验 |
| RT-11 | 重排重复/漏项/越界/非有限分数/错误形状失败；同分按RRF与chunkId稳定排序，原score独立保留 | S11D协议替身 | DESIGN_CHECKED；产品待验 |
| RT-12 | 默认FAIL；显式可降级策略才标RRF_ONLY/degraded，授权/知识失败/取消/总超时不可降级 | S11D故障/预算矩阵 | DESIGN_CHECKED；产品待验 |
| RT-13 | 独立累计先行预算、未知计费保留、不自动重试，不改旧两本账本；价格/地域/账号条件未满足真实调用0 | S11D本地账本/HTTP计数；U04满足后真实核验 | DESIGN_CHECKED；真实模型条件未满足 |
| RT-14 | 最终解析失效拒绝；后续引用重新判权；内部原始排名/被过滤候选和查询正文不出现在公开诊断/日志 | S11C/E真实撤权与脱敏检查 | DESIGN_CHECKED；产品待验 |
| RT-15 | 新旧入口并存，旧任务缺策略固定legacy；新任务/评测固化策略与指纹，恢复不随默认漂移 | S11E真实任务/PG/幂等回归 | DESIGN_CHECKED；产品待验 |
| RT-16 | 搜索、问答、研究、评测共用同策略；逐题baseline/RRF/rerank可区分真实模型、协议替身和降级 | S11E/S8D固定集对照；U01/U04条件 | DESIGN_CHECKED；真实质量待验 |

S11A只验证源码/框架取证、边界与反例覆盖、契约/策略/依赖和文档一致性。预算/时限为明确上限，容量和模型效果不由设计自证。矩阵不要求为单纯文字变更运行应用或付费模型。
