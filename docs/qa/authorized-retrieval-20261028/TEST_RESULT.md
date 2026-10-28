# S11B 验收结果

状态：实现与本地必需验收 **PASS**；Git/CI另见交付收据。[正式契约](../../design/knowledge-first-20261020/RETRIEVAL_CONTRACTS.md)，源码/运行版本见[validation.json](validation.json)。

| 验收 | 结果 | 证据 |
| --- | --- | --- |
| RT-01 内部身份 | PASS | AuthorizedRetrievalIT：独立RSA、issuer/audience/azp/TTL/scope、重复头、精确方法与路由 |
| RT-02 严格输入 | PASS | 缺失/未知/重复字段、禁止标量强转、模型/维度/向量/范围/数值限额与128KiB HTTP拒绝；成功响应字段精确计数 |
| RT-03 发布权威 | PASS | 真实MySQL/S3/ES/Qdrant污染其他tenant/owner索引、失败新稿、撤下/删除/重索引；来源始终取发布修订 |
| RT-04 原排名/score | PASS（Java） | AuthorizedRetrievalTest原排名70/90与负原score保留、跨源去重；IndexReadbackTest拒绝重复/缺失/非数字score；Python部分属S11C |
| RT-05 扩展 | PASS | 两路20/60/100，足量停止、干净短缺、污染不足503；同一向量、不同源不合并原score |
| RT-06 最终解析 | PASS | 8分量匹配/顺序/整体404、真实SQL与连接拒绝503、正文hash拒绝；PDF/DOCX现时来源与旧权威一致 |
| RT-08 原语边界 | PASS（Java） | 生产服务没有模型/Python依赖，输入固定向量；LlamaIndex接管属S11C |
| RT-09 容量/期限 | PASS（Java） | 旧搜索/候选/解析共享2在途立即BUSY；单调剩余预算与取消停止；外层Python取消属S11C |
| 兼容/运行 | PASS | 固定快照69248f47394fb22b，9容器健康/10项smoke；29项新旧接口与网关隔离，[HTTP](http-result.json)、[运行](runtime-result.json) |

全量`.logs/s11b-full-java.log`：153单元+133集成，0失败/错误/跳过。窄14+6通过，最后PDF/DOCX补充由全回归覆盖。所有测试模型向量为明确协议夹具；业务dev数据、旧账本未变。没有新增真实模型费用、生产部署或数据清理。

验证由同一执行者分阶段执行，未使用独立Agent。UI没有变更；前端保持4a9a280，容器重建已通过114测试、格式/类型/lint/生产构建。代码卫生与边界见[审查](REVIEW.md)、[实施证据](IMPLEMENTATION_EVIDENCE.md)。SQL/网络采用既有有界I/O并在边界检查总期限；没有宣称跨进程取消可瞬时停止数据库或已发送请求，也未开展生产容量SLO验收。
