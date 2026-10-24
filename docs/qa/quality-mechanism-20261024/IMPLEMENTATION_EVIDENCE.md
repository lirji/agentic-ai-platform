# S8E 执行与证据映射

本片只增加真实资料、冻结标注和QA证据，产品代码保持后端aa355c5、前端5960531；详细源码摘要见[source-fingerprint](source-fingerprint.json)。

- 资料经Gateway K01上传、Java持久入库、真实Embedding与双索引发布，再由E01 evidence读取实际块，不直接写业务表。
- E01创建不可变定义 → E02受理唯一运行/中央任务 → 原qa-worker消费LangGraph评测图 → 逐题复用QaPipeline生产函数 → 中央终态确认清单 → E03保存评分与基线。
- 六个证据块逐段检查后标为relevance=2；来源说明块不计金标。样本、参考答案与rubric只在评测定义，不注入语料。
- 同一评测完成8次真实聊天、8次查询Embedding；入库另6批Embedding。恢复过程中没有重发历史探针、重置预算或自动恢复失败任务。
- 冻结基线保留M04已知语义缺陷；语义拒答、非空召回标志、引用可读及答案支撑分开记录。
- 本机Docker缺失恢复、原必要容器启动、Conversations连接恢复及qa-worker停止/恢复均为已授权本地环境维护；其他项目服务和故障夹具未启动或清理。

验收结论见[TEST_RESULT](TEST_RESULT.md)。没有委托子Agent、改变阈值或宣称独立人员审查。
