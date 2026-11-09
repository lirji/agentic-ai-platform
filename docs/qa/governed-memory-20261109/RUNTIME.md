# S18B 运行与回退边界

Conversation V4追加三表，test库已从V3实际迁移到V4；dev与现有Docker尚未更新。配置`CONVERSATION_MEMORY_ENABLED`默认false，Docker prepare的`--memory`才启用新建/确认。关闭仍可读取、编辑、停用和遗忘，不自动删除数据。

复用既有MySQL、6连接Hikari池与5秒短事务；每owner行锁串行化命令。只读快照显式REPEATABLE_READ，不依赖进程内缓存/定时器或Deep Agents Store。最多100条、每条500码点；过期使用数据库UTC时间立即排除。审计无正文，幂等账本没有自动清理策略。

旧应用能忽略新增表，不能消费记忆；代码回退不能撤销已确认/遗忘的业务效果。回退前关闭新受理，保留表和账本，不反向执行DROP/重建。S18C后统一本地Docker更新由用户授权，不在本片提前修改既有运行容器。

隔离验收：`python3 docs/qa/governed-memory-20261109/run_integration.py`只读取已有test私密配置；`serve_test.py`在28388运行无worker测试服务，供前端`tests/memory-real.spec.ts`联合验证。签名材料来自忽略目录、只注入测试进程，源码和公开收据不保存令牌。失败的Playwright trace可能记录短效测试委托头，仅保留于忽略目录，不上传；重跑已关闭trace。浏览器身份代理只转发到固定测试端口；业务操作由真实Java/MySQL执行。
