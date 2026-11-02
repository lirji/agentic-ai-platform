# S16A验收结果

Status: PASS（本地契约与受控运行验收）；生产容量/SLO未验证。Git/CI状态以PROGRESS_STATE及后续交付收据为准。验证方式为同一Agent的独立检查pass，无子Agent或人工签收。

| 验收 | 结果 | 证据与范围 |
| --- | --- | --- |
| MW01 配置/领取 | PASS | 默认顺序及真实轮询保持兼容；显式角色只领取指定图。未知/空/重复/能力不匹配拒绝；错误tenant/worker/graph响应拒绝；沿原WireModel拒绝错误runtime |
| MW02 进程竞争 | PASS | 3场景各2个独立Python进程同时竞争真实Tasks/MySQL，仅1个有效领取；未领取进程模型计数0 |
| MW03 确认点接管 | PASS | 真PG确认计划后终止拥有租约的子进程，后继epoch加1；不重做计划；旧epoch心跳/完成/PG快照及pending写入拒绝，迟到快照不存在 |
| MW04 取消/未知/预算 | PASS | 取消后新进程不能领取且模型0；完成已提交响应丢失读取中央SUCCEEDED；研究累计模型次数2不清零。19项已有真实DB恢复复验覆盖未确认阶段重复计数、模型/搜索预留不重置、失租和撤权 |
| MW05 兼容/运行 | PASS | 旧v1三个中断位置恢复通过；新v4角色可选。组配置拒绝重复身份/第三实例/LEGACY_LOCAL/不同服务或PG/非私密文件；总连接上限显式；原worker关闭取消图/心跳单元通过；本轮子进程均退出 |

## 实测

- Python完整768 passed / 100条件skipped / 34既有durability警告（10.64秒）；最终追加真实轮询用例后37相关项passed，完整CI随后复核。跳过包含此次真实多进程3项，已在显式启用运行中全部通过，不能以默认跳过充当验收。
- 真实双进程3 passed（25.63秒），阶段/旧图/跨attempt预算19 passed（32.90秒、15既有durability警告）；全部使用登记test MySQL/PG、临时Tasks和模型替身，无付费提供方请求。
- Ruff check、153文件format check、mypy92源码全部PASS；15登记契约/15共享报文文件PASS。Java/前端源码未变，Java在CI做常规构建；无本地新Java全量测试或前端浏览器验收的声明。
- [源码与日志摘要](source-evidence.json)、[预算核对](budget-check.json)、[卫生结果](hygiene-result.json)。卫生COMPLETE_WITH_LIMITATIONS：工具没有识别Python专用格式/静态命令，已单独运行上述真实工具；本片无Java源变更。

## 失败与修复

首次双进程竞争成功后，测试将JSON字典传入严格TaskLease.model_validate，时间与枚举反序列化失败；改用model_validate_json后3项通过。初次Ruff报告测试脚本的异步文件读/屏障，改线程读与有超时的跨进程屏障；导入顺序自动整理，最终零错误。未改变产品严格协议或放宽断言。

检查发现历史worker注释把单进程许可描述成平台总量，已纠正为逐实例累加。组配置进一步拒绝不同checkpoint DSN，防止接管读取另一库。无未修复必需失败。

## 边界

S15B共享资金准入/未知占额的双Tasks证据仍适用，相关资金实现未变；本轮进程实验使用直接模型替身，只证明任务/研究调用次数预算，不宣称实际模型计费或全平台并发许可。U06未指定，不推断吞吐、生产延迟或费用达标。没有新增迁移、依赖、基础设施、长期worker、生产部署或清理。原Chat/Embedding账本字节摘要与S15B收据一致。

运行入口见[CAPACITY_RUNTIME](../../design/knowledge-first-20261020/CAPACITY_RUNTIME.md)。日志保留.local/s16a-*和.logs/s11e-generation-pg-*/workers-*；私密清单留在忽略目录，不提交。原服务与旧工作树保留。
