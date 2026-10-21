# 候选建设项与不建议现在做的事项

2026-10-21。基线及事实见 [能力缺口](CAPABILITY_GAPS.md) 和 [证据索引](EVIDENCE_INDEX.md)。当前可靠执行主链路可复用；优先考虑质量、资料管理和知识运营。不得把候选清单视为已经批准的实施切片，五步实施仍暂停。

复杂度与风险为工程估计 `INFERRED`，不是工期或预算承诺。每项状态均表示分析建议，不表示开发完成。

## Must Fix

### O01　质量基线与退化可见性

- **capability / currentState：** 业务质量评测；已有功能/故障测试，缺产品化评测集及固定对照。
- **problem / evidence：** 无法证明检索/模型调整有效；G01/G08，[E09、E12、E24](EVIDENCE_INDEX.md)。
- **whyNeeded / proposedCapability：** 后续优化需要可比证据；建立标注、版本、基线、逐题结果、拒答/权限题及失败运行管理，先覆盖当前两路链路。
- **value：** 业务上能定位错答原因；工程上防止总体均值掩盖关键题退化。
- **complexity / risk：** 中；标注不可靠、样本泄漏和评分口径偏差比界面实现更关键。
- **dependency：** 真实代表性资料及标注规则；复用现有知识、身份、任务和引用能力。
- **class / priority / triggerCondition / status：** `MUST_FIX` / P1 / 任何检索或回答质量改造之前 / `RECOMMENDED`。
- **验收：** 同定义、版本和权限可重复对照；失败不算通过；关键题退化可定位；答案质量与检索质量分开。

### O02　文档更新、撤下与引用失效

- **capability / currentState：** 文档生命周期；有发布指针和引用重验，无公开更新/删除/重索引。
- **problem / evidence：** 上传替代版本会留下独立旧资料；G04，[E05、E06](EVIDENCE_INDEX.md)。
- **whyNeeded / proposedCapability：** 资料一定会变化；建立明确的版本、撤下/删除、重索引、失败恢复和历史引用规则。
- **value：** 业务上能维护知识有效性；工程上避免旧索引和历史结果成为旁路。
- **complexity / risk：** 高；双索引、原文、任务、会话引用的一致性和并发冲突。
- **dependency：** O01 的版本/失效样本；沿用现有权威数据与准备包协议，新增语义需后续冻结。
- **class / priority / triggerCondition / status：** `MUST_FIX` / P1 / 开始维护重复修订或需撤下的正式资料 / `RECOMMENDED`。
- **验收：** 新版失败不损坏旧发布版；撤下后各读取路径一致；重试不造多版本；旧引用有真实解释。

### O03　多人开放的身份、共享和撤权

- **capability / currentState：** 正式身份与协作；后端隔离已实现，UI 使用本地身份桥。
- **problem / evidence：** 无正式登录/组织权限操作；G10，[E16、E17](EVIDENCE_INDEX.md)。
- **whyNeeded / proposedCapability：** 让真实多人使用；确定角色、分享范围与撤权规则，接通身份生命周期和共享界面。
- **value：** 业务上可协作；工程上将静态 owner 隔离扩成可验证的授权业务。
- **complexity / risk：** 高；撤权、在途任务、历史引用、缓存和外部模型输入需同时约束。
- **dependency：** 明确身份来源、组织模型和资料所有权；O02 的文档状态。
- **class / priority / triggerCondition / status：** `MUST_FIX` / P1 / 对团队或远程用户开放之前 / `RECOMMENDED`。
- **验收：** 登录/退出/过期可恢复；跨用户/租户及撤权后访问拒绝；不能直接照搬参考项目 disabled 的 ReBAC 配置。

### O04　可重复交付与能力事实说明

- **capability / currentState：** CI、契约验证、供应链及文档；已有手工证据，缺两仓流水线。
- **problem / evidence：** 后续多人改动难以持续复现，旧状态描述易误导；G16/G19，[E20、E24–E26](EVIDENCE_INDEX.md)。
- **whyNeeded / proposedCapability：** 每次交付能证明交付了什么；按变更范围自动构建/验证、记录依赖门禁和当前能力状态。
- **value：** 业务上交付可预期；工程上减少环境漂移和口头完成。
- **complexity / risk：** 中；CI 外部组件条件、密钥与模型费用需要明确边界。
- **dependency：** 当前锁文件/构建说明及已验证测试；不要求先扩业务。
- **class / priority / triggerCondition / status：** `MUST_FIX` / P1 / 持续开发和协作交付 / `RECOMMENDED`。
- **验收：** 干净环境构建、受影响契约回归、失败如实暴露；报告区分实现/验证/启用/部署。本轮仅修正文档入口，未创建 CI。

## Natural Evolution

### O05　结构化资料、分块与来源定位

- **capability / currentState：** 多格式知识处理；当前严格 TXT/MD 和字符切分/字节定位。
- **problem / evidence：** 文档转文本丢失结构和来源；G02/G03，[E03、E04、E25](EVIDENCE_INDEX.md)。
- **whyNeeded / proposedCapability：** 直接处理真实业务资料；按资料比例先 PDF/DOCX，保留结构/告警/页段定位并使用合理分块；扫描件再触发 OCR。
- **value：** 业务上减少人工整理且便于核对；工程上使解析错误和分块损失可观测。
- **complexity / risk：** 高；复杂格式、OCR 错误、表格跨页、资源输入和来源映射。
- **dependency：** O01 格式样本；现有原文/准备包绑定，格式扩展需明确新契约。
- **class / priority / triggerCondition / status：** `NATURAL_EVOLUTION` / P1 / 真实知识资料超出 TXT/MD / `RECOMMENDED`。
- **验收：** 实际文件结构保真、异常分类、资源边界、引用展示、同集检索影响；不仅安装 parser。

### O06　内容去重与入库运营

- **capability / currentState：** 上传管理；已有同键幂等和失败重试，缺跨键同内容查重/管理诊断。
- **problem / evidence：** 重复资料与重复费用；G05/G18，[E02、E17、E25](EVIDENCE_INDEX.md)。
- **whyNeeded / proposedCapability：** 让用户知道已有资料和未完成任务；安全查重、明确结果、处理告警；批量操作按实际需求启用。
- **value：** 业务上减少重复/误操作；工程上减少无效索引和模型调用。
- **complexity / risk：** 中；去重不能泄露他人资料，删除/历史版本/并发请求不能误判。
- **dependency：** O02 版本规则；O05 解析状态与告警模型。
- **class / priority / triggerCondition / status：** `NATURAL_EVOLUTION` / P1 / 持续整理或多人上传资料 / `RECOMMENDED`。
- **验收：** 同内容改名/新键/并发、无权资料、旧版删除、失败恢复、未知提交均覆盖。

### O07　评测驱动的检索与答案改善

- **capability / currentState：** 查询编排/独立精排/语义支撑；当前两路 RRF 已可用。
- **problem / evidence：** 缺相关性精排和可解释对照，引用有效不等于答案正确；G07/G08，[E07、E08、E12](EVIDENCE_INDEX.md)。
- **whyNeeded / proposedCapability：** 修复实际错误题；独立 Reranker、候选池/失败规则、来源与排序诊断；按错误类型决定查询扩展/指代消解/答案验证。
- **value：** 业务上减少相关证据遗漏与不受支持的回答；工程上能量化每项改动的效果和费用。
- **complexity / risk：** 中至高；外发正文边界、延迟、重复收费、阈值误伤和过度拒答。
- **dependency：** O01；O05/O02 使代表性资料和版本稳定。O03 在共享场景下先明确授权规则。
- **class / priority / triggerCondition / status：** `NATURAL_EVOLUTION` / P1 / 基线显示排序、追问或支撑度缺陷 / `RECOMMENDED`。
- **验收：** RRF 基线与精排消融、逐题退化、成本及失败行为；授权后精排、最终复核；不预设增加图谱召回。

### O08　反馈到发布的知识运营

- **capability / currentState：** 知识纠错；当前没有运营状态流。
- **problem / evidence：** 错答不能持续转成知识改进；G09/G18，[E10、E17、E25](EVIDENCE_INDEX.md)。
- **whyNeeded / proposedCapability：** 形成可追责的改进流程；反馈、候选编辑、内容绑定评估、审核、发布状态同步、发布后探测和补偿版本。
- **value：** 业务上运营者能处理问题；工程上变更有证据和审计。
- **complexity / risk：** 高；审批/内容版本错配、重复发布、失效评估和伪成功。
- **dependency：** O01 及 O02；多人审批依赖 O03；反馈收集可以先于完整审批发布落地。
- **class / priority / triggerCondition / status：** `NATURAL_EVOLUTION` / P1 / 知识库持续使用并有内容责任人 / `RECOMMENDED`。
- **验收：** 一条错误反馈最终对应实际可检索的修正版本；编辑使旧审批失效；失败恢复和补偿不绕权限。

### O09　一个有治理的跨会话或业务助手场景

- **capability / currentState：** 长期记忆/业务工具；当前只读知识助手，无通用执行权限。
- **problem / evidence：** 尚不能支撑依赖个人上下文或外部业务操作的任务；G11/G12/G20，[E13、E14、E22、E23](EVIDENCE_INDEX.md)。
- **whyNeeded / proposedCapability：** 由真实业务决定：需要长期记忆则提供确认/遗忘；需要业务数据则先接一个只读工具，写操作再加入确认与幂等。
- **value：** 业务上解决一个完整工作任务；工程上验证最小工具治理边界。
- **complexity / risk：** 高；隐私、旧任务回填、跨系统权限与副作用。
- **dependency：** 明确用户、数据所有者和成功条件；O03/O01；写入能力另需正式契约。
- **class / priority / triggerCondition / status：** `NATURAL_EVOLUTION` / P2 / 明确跨会话或外部业务场景 / `NEEDS_MORE_EVIDENCE`。
- **验收：** 用实际流程验证数据来源、权限、纠正/撤销及失败可恢复；不以框架创建 Agent 成功为验收。

## Platformization Opportunities

### O10　可持续知识来源

- **capability / currentState：** 来源同步；当前只有手工上传。
- **problem / evidence：** 动态知识依赖人工维护；G06，[E11](EVIDENCE_INDEX.md)。
- **whyNeeded / proposedCapability：** 先治理一个真实来源的绑定身份、游标、删除/变更、运行历史和恢复。
- **value：** 业务上保持时效；工程上可对账和重放。
- **complexity / risk：** 中至高；来源权限、删除传播、重复/乱序事件及游标丢失。
- **dependency：** O02/O06/O03；明确一个外部来源协议。
- **class / priority / triggerCondition / status：** `PLATFORMIZATION` / P2 / 首个持续更新来源出现 / `DISCOVERED`。
- **验收：** 中断后续传不重复发布；变化/删除可追溯；来源不能被客户端任意改 URL/身份。

### O11　多人多实例运行与费用治理

- **capability / currentState：** 公平调度、容量、预算与诊断；已有单机限制和可靠任务底座。
- **problem / evidence：** 尚无多 worker/多租户整链路证明；G13–G15，[E15、E18、E19、E24](EVIDENCE_INDEX.md)。
- **whyNeeded / proposedCapability：** 从单人试用扩展到稳定协作；分步验证队列公平/资源边界，形成共享预算预留结算、跨语言诊断及告警。
- **value：** 业务上服务可预期、费用可归因；工程上可控制过载与定位故障。
- **complexity / risk：** 高；租约竞态、重复模型费用、配额不一致及慢下游扩散。
- **dependency：** O03；目标负载/费用主体；保留现有 T 契约和预算连续性。
- **class / priority / triggerCondition / status：** `PLATFORMIZATION` / P1（扩容前） / 多 worker、多租户或共享预算 / `RECOMMENDED`。
- **验收：** 抢占/取消/恢复不越权；预算不超发、不重复结算；排队公平和容量结果有报告。

### O12　可交付部署与可恢复数据

- **capability / currentState：** 应用制品/配置/迁移/恢复；当前本地运行已验证。
- **problem / evidence：** 缺完整应用交付与恢复验收；G17，[E21](EVIDENCE_INDEX.md)。
- **whyNeeded / proposedCapability：** 在明确环境中交付和恢复；固定制品、环境检查、数据备份及独立还原演练，记录索引重建关系。
- **value：** 业务上服务可恢复；工程上部署与数据状态可追溯。
- **complexity / risk：** 高；多存储一致性、敏感配置、不可逆迁移和恢复覆盖。
- **dependency：** O04/O03/O11，明确目标环境及恢复要求；生产部署需独立授权。
- **class / priority / triggerCondition / status：** `PLATFORMIZATION` / P1（对外运行前） / 从本地验证走向长期运行 / `RECOMMENDED`。
- **验收：** 独立环境还原数据与权限/任务/引用/预算连续性；不以备份文件存在为成功。

## Exploration Opportunities

| 候选能力 | currentState / problem / evidence | whyNeeded / proposedCapability / value | complexity / risk / dependency | class / priority / triggerCondition / status |
| --- | --- | --- | --- | --- |
| 图谱/更多召回 | 当前两路，参考可组合更多来源；G07/E07 | 仅当关系型问题被基线确认时，验证额外来源的增量价值 | 高；图抽取噪声/更新一致性；O01/O02/O07 | EXPLORATION / P3 / 关系问题占比及失败证据明确 / NEEDS_MORE_EVIDENCE |
| 多模态知识/语音渠道 | 当前文本资料；参考有相应服务入口；G20/E23 | 存在图片信息或语音交互需求时验证一种完整任务 | 高；数据/模型费用/权限；O03/O05/场景资料 | EXPLORATION / P3 / 文本路径无法解决真实任务 / DISCOVERED |
| 多 Agent/开放工具 | 当前两类只读工具；G12/E14 | 仅当单图有可量化瓶颈时检验分工收益 | 高；权限、费用、恢复放大；O01/O09/O11 | EXPLORATION / P3 / 有单 Agent 基线及明确分工收益假设 / NEEDS_MORE_EVIDENCE |

## Not Recommended Now

| 事项 | 不建议的原因 | 重新考虑的条件 |
| --- | --- | --- |
| 按参考模块列表逐个新建服务 | 会扩大范围与运维面，无法证明用户任务改善 | 有明确业务所有权、负载或独立交付需求 |
| 将召回从两路机械补到四路 | 现有融合已实现，更多来源可能增噪声和成本 | 固定评测显示新增来源能解决既有失败题 |
| 一次引入全部 splitter/重排模型 | 参数组合膨胀，无法归因收益 | 单一变更对照明确不足，再追加候选 |
| 直接照搬旧项目默认配置 | 应用/Compose、ReBAC、预算、外部 Agent 路由并不一致 | 逐能力确认当前身份/数据/运行契约 |
| 建设没有真实来源的连接器市场 | 没有同步语义和可验收样本 | 至少一个持续知识来源及负责人 |
| 默认记录长期用户画像或开放写工具 | 当前知识业务不必需，新增隐私/副作用边界 | 明确用户收益、治理和执行授权 |
| 先加缓存/MQ/额外框架再找需求 | 当前已有可靠状态和有界执行；组件数量不是深度 | 基线发现瓶颈，并有可观测目标与恢复规则 |
| 直接照搬参考 200 条合成问题当黄金集 | 覆盖参考技术语料，不能代表本项目业务正确答案 | 人工核验并补齐真实资料、拒答、权限、版本题 |
| 用本次文档作为生产上线批准 | 本次没有重新验证运行环境或部署 | 后续明确目标环境并完成对应准入与恢复验收 |
