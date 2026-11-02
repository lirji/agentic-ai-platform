# S16B租户公平与后台隔离验收

Status: PASS（本地契约/实现/真实依赖验收）。验证由同一Agent执行独立检查pass，无子Agent或人工签收；Git/CI见后续交付收据，生产容量/SLO未验证。

| 验收 | 结果 | 证据与范围 |
| --- | --- | --- |
| CI01 策略一致性 | PASS | 两域不可变配置摘要、份额求和/未知字段/YAML重复租户拒绝；首次启用拒绝未分配旧租户及有效租约超额，保留已受理旧队列；已运行disabled实例不能绕过新登记策略 |
| CI02 Tasks公平 | PASS | 两真实Spring实例、8路竞争，仅1个A交互有效租约；B和后台份额可用，队列并发仅剩余3条受理，幂等/失败resume有界；停止实例后新epoch恢复，旧心跳拒绝 |
| CI03 入库公平 | PASS | 两真实实例；满额A有25条更早候选，B仍能领取；上传/update/reindex/retry/候选副本队列共用限额；实际独立入库循环两租户同时阻塞并保留各自租约；双解析JVM过载拒绝及全部回收 |
| CI04 在线/回调 | PASS | SEARCH/CANDIDATE跨实例不同槽位，A占满不阻塞B；双重关闭不扩容，过期旧token不能释放继任者；实际慢HTTP超时后恢复、本地关闭异常仍归还信号量；Python慢A期间B返回200，取消恢复请求scope |
| CI05 上下文 | PASS | 实际Micrometer与IngestionWorker线程复用、独立root trace、嵌套span恢复、异常及取消；Caller显式不可变传参，无业务ThreadLocal因此未引入TTL；Python diagnostics上下文复用/嵌套/取消随80相关项及771全量验证 |
| CI06 停机/故障 | PASS | 停止实际入库worker及所属第二Spring实例，持久PROCESSING不误标失败，租约过期后原job新epoch续办；旧心跳拒绝。隔离不可达MySQL连接在约257ms拒绝，未进入模型；该配置为250ms本地试验，生产仍沿原连接超时 |

## 验证与证据

- Tasks21单元、4双实例IT、1首次启用IT及29原持久/研究兼容IT通过；Knowledge130单元、1首次启用IT、最终10双实例/容量IT通过。最后10项日志`.logs/s16b-knowledge-it-34cef60b-retry1.log`。
- Knowledge既有真实MySQL/S3/双索引/解析回归92项：87通过，5既有条件跳过（真实浏览器/完整中央联调开关）；覆盖入库、结构化解析、生命周期、候选和共享。候选最后补丁由全reactor中KnowledgeCandidateIT再次验证（17通过/3既有条件跳过），8个Maven模块verify通过；最后两个常量命名整理另做受影响单元复验。
- Python全量771通过、100条件跳过、34既有LangGraph durability警告；其中本轮必需Python容量/上下文用例没有跳过。真实Java容量用例均显式执行，无跳过。Ruff153文件格式/静态、mypy92源码、15契约/15共享报文及usage边界检查通过。
- 无付费模型调用；原Chat/Embedding账本摘要与S15B相同，资金实现未改。所有模型/候选端口为明确的确定性替身；数据库、对象存储、索引、线程、HTTP和解析进程为真实运行。
- [源码/日志摘要](source-evidence.json)、[预算核对](budget-check.json)、[代码卫生](hygiene-result.json)。Java无专用格式器/static analyzer，卫生为IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；Python工具另行实际执行。事务advisory已逐条审查，无未解决阻断项。

## 审查、失败与修复

准入在短事务中采用capacity→task及capacity→document→job；候选批准先capacity再run→document，避免嵌套生命周期更新与普通更新反序。网络/模型等待在事务外；计数后创建/领取同事务，重放先返回原记录，唯一键失败沿原回滚读取。许可按token和有效期限释放，DB不可用不转本地后门。SQL集中Mapper XML，3个新增表共13列均有中文注释，索引按tenant/status/lease过滤；V8/V19已应用，未改写。策略/租户名不作为普通指标标签，不记录业务正文/凭据。

复核修复了四个会破坏份额的入口：固定单循环入库、单槽解析、固定检索转发池、候选副本直接入库；候选批准补齐外层容量锁。启用模式按对应份额求和，关闭模式保留旧并发。Python新增显式本地检索并发1–8，原默认1不变；它不承担跨实例资金/配额权威。

首次Bootstrap新增单元误用未安装Mockito，改为JDK端口替身，未增加依赖；首次第10个IT使用了错误的Reference包名导致testCompile失败，确认空schema后修正并复用同一库通过。Python首次全量770通过/1失败（旧固定最大2断言），依据新增显式最大8契约更新边界后771通过；默认单并发过载/取消测试保留。原失败日志均保留，无放宽期限或跳过必需失败。一次静态检查工作目录错误导致重定向失败，纠正路径后三个工具通过。

## 运行限制

静态预留份额不借用，可能降低空闲利用率；不承诺同租户不同owner的权重公平。提供方、Rerank账本/连接、共享CPU/索引及资金账户仍可拒绝，未宣称生产吞吐、尾延迟或完整混合负载达标；S16C/D尚未实施。首轮配额与连接/解析内存核算见[运行说明](../../design/knowledge-first-20261020/CAPACITY_RUNTIME.md)。

新模式默认关闭，不能与旧二进制writer混启；已登记策略不可通过关闭开关或另一配置绕过。首次启用/调整均需核对实际租户和所有实例配置，不能删除策略、重置槽位或复制本地资金账本。任务未知费用保持占额。测试schema和全部合成资料、日志、私密配置保留，不清理旧数据；所有本轮临时Spring/HTTP/worker/解析进程已正常退出。Docker最新代码部署是用户后续明确要求，另有DEPLOYMENT_RESULT；本报告不冒充部署成功。
