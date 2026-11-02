# S15A 设计审查

范围：usage目标模块、正式usage-v1、迁移/回退及UA01–UA16；本Agent以独立检查步骤执行人工专业pass，无子Agent，不冒称机器Architecture Gate。取证基线d9b0c79，产品未改。

已核对并修正：

- 资金来源唯一键排除可变资助方名称，避免同一来源改名复制额度；组织绑定共享原池、用途分离。
- reserve幂等不能授权重复外发：增加一次性dispatch CAS和丢许可响应的保守未知规则；旧worker/epoch不能dispatch旧预留。
- 重排查询按候选数重复计入估算，不能只用一次HTTP请求字节低估成对计量；价格/上限服务端固定，明确不是真实账单保证。
- 错误/超时不证明免费；UNKNOWN→SETTLED迟到报告合法，终态不被旧UNKNOWN覆盖；跨worker不能报告任意attempt。
- 旧文件无tenant时不猜归属；唯一来源/摘要/条目防改名二次导入；CENTRAL后禁止回退可写旧账本。
- Tasks和usage没有跨域Mapper依赖；同库task fence事务仍形成明确抽取债，Readiness LOW而非“已可抽取”。

未发现阻止S15A设计交接的问题。风险保留：价格及计量信任边界、保守占额造成可用额度降低、Tasks故障阻止新付费调用、实际账号绑定/预算启用需受控配置。生产容量、价格真实性、运行并发和UI均未在设计阶段验证。S15B必须按全部UA项执行，不能把本审查PASS当实现PASS。

交付前接入核对补正：现有scope实际为task:read/task:write（不是tasks复数），400基础错误属于TaskError.INPUT_INVALID而非PlatformError；正式契约已按真实目录修正并重验，不引入新权限别名或第二套错误体系。
