# S16C验收结果

Status: PASS。最终实验`9ad83207`，产品基线`a52c94f`，测试源码/原始日志SHA256见[source-evidence](source-evidence.json)。验收由同一Agent在测试建设后做独立核对，无子Agent、跨模型或人工签收声明。Git/远程CI另按交付收据；生产容量和真实模型性能未验证。

| 验收 | 结果 | 证据 |
| --- | --- | --- |
| LC01 真实混合负载 | PASS | 单次完整实验96.29秒；30上传READY、21QA/22研究SUCCEEDED，真实HTTP/MySQL/PG/S3/双索引/图；成功引用限定本租户指定资料 |
| LC02 资源和容量特征 | PASS | 常规32次、恢复16次操作无拒绝/异常；突发54次含32次429，有界排队；分位数、计数、资源、调用和费用见[容量报告](CAPACITY_REPORT.md)及[JSON](capacity-results.json) |
| LC03 数据库慢 | PASS | 独立schema容量行持锁2秒，实际请求2131ms，释放后受理完成；没有操作共享数据库服务 |
| LC04 索引故障 | PASS | 双索引本轮代理503；资料FAILED且未发布；同job/revision重试完成，查询恢复 |
| LC05 模型超时 | PASS | 替身延迟40秒，真实期限触发503/FAILED；原job可恢复，在线许可无泄漏；付费0 |
| LC06 worker退出 | PASS | 独立进程真实PG确认计划后退出，15秒租约自然过期，15.941秒内接管；attempt=2、modelCalls=2，旧epoch心跳拒绝，B继续推进 |
| LC07 最终完整性 | PASS | 两库全量数量与逐一受理ID一致；总30READY/43SUCCEEDED，无额外记录或非终态，在线许可0；原账本摘要不变，全部本轮进程已退出 |

## 必需验证

- Knowledge/Tasks及依赖Maven package（含单元）PASS，`.logs/s16c-baseline-build.log`。产品Java未改，构建与Jar证据适用于本轮测试。
- 显式真实实验PASS，`.logs/s16c-9ad83207/pytest.log`。默认Python全量771 passed / 101 skipped / 34既有warnings，`.logs/s16c-python-full.log`；新增条件跳过为S16C，已单独显式通过，不能用默认跳过替代该证据。
- Python155文件Ruff format/check、92源码mypy PASS；入口py_compile PASS。15登记契约/15共享报文及usage分层/表归属检查PASS。没有新增生产Java、前端、契约字段或迁移，不新建Java格式器/前端验收。
- 本片源码卫生：新增测试与入口Ruff检查PASS，差异空白检查PASS；无新依赖、数据库建表、凭据、外部模型或日志正文输出。真实SQL只用于本轮隔离测试库建库/授权/状态核对/故障锁，无产品SQL修改。
- 新增和变更文档的本地链接、表格及Git范围在交付前核对；精确结果见document-validation.json。远程CI须绑定最终commit，不能用本地验证代替。

## 失败、复核与边界

五轮前期夹具问题均保留原运行：`0b92865c`默认索引地址未传入；`2b2c7912`依赖端点带路径被正式配置校验拒绝；`74e67f51`误把入库READY当PUBLISHED而主动停止校准；`40df82b9`公开搜索提交内部timeoutMs被400拒绝；`1920e1ea`QA资料ID未排序被400拒绝。都修正测试以符合既有协议，没有放宽产品验证或弱化必需断言。失败库与日志全部保留，尚未产生的业务动作不宣称成功。

首个完整运行`d863f7b3`机制通过，但复核发现HTTP检索入口仍为默认1，常规搜索有50%拒绝。最终夹具显式maximum=2，与计划一致，并加强常规/恢复零拒绝、结果引用归属、全表数量、许可归零及DB池上限断言；`9ad83207`全量重新通过。对照数据不作为最终配置的容量证据。

本次没有发现需修改产品代码的阻断缺陷。仅覆盖小文本、两租户与两worker的受控闭环负载；大规模资料、长期稳定性、真实模型计费/质量/限流和生产SLO保持UNVERIFIED，验收本身允许U06未定先报观测。UI无变化，视觉验收N/A。S16D/S17/完整51项仍未完成。
