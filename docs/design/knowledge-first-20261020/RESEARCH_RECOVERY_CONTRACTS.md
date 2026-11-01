# C-WORK2 / S12D：研究根图分阶段恢复

2026-11-01，协议`research-recovery-v1`。基于已批准S12D补充S12C与CHECKPOINT_CONTRACTS；不改变服务边界、外部工具或金额预算。

## 兼容与受理

新增显式POST `/api/v4/tasks`和GET `/api/v4/tasks/capabilities`，请求仍是S12C六字段，能力响应沿research-capabilities-v1。仅RESEARCH/REPORT、显式LLAMA策略；输入knowledge-work-input-v4，根图knowledge-work-v4。结果复用knowledge-work-result-v3/research-report-v1，报告形状未变。新增TASK_RESEARCH_STAGED_ENABLED默认false，新v4受理还需原generation/research开关；禁用返回TASK_RESEARCH_DISABLED。关闭不阻止已有v4重放/领取/历史读取。

v1/v2/v3受理及持久图保持原语义，同一taskId或幂等键跨API改变版本仍冲突。前端新分问题研究读取v4能力并冻结apiVersion=v4；旧未知v3提交按原taskId核对，不改键重发。网关、身份桥、代理按公开路径映射Tasks受众，不增加新身份或权限。

## 已确认的根阶段

新图retrieve → plan → analyze → verify → END。每个阶段完成后以既有FencedPostgresSaver同步durability提交完整JSON快照及中央凭据，下一阶段只在确认后执行。

- retrieve：初始完整授权证据/实际检索诊断，仍保守预留一次初始搜索。
- plan：最多3问，只输出ResearchPlan；不发工具给计划模型，不执行工具/权限指令。空证据直接生成S12C固定问题，不调用模型。
- analyze：读取已确认计划；不再次生成计划。沿原Deep Agents有界只读工具循环生成覆盖草稿，工具/证据/诊断与草稿一起提交。内部仍checkpointer=False，未完成阶段可重新执行，不能声明逐工具持久恢复。
- verify：重验计划、原文、所有完整依赖和实际累计额度，形成原v3结果。图已完成但中央complete回复未知时先按中央事实/已确认快照恢复，不再次调用模型。

旧v1/v2/v3仍由各自图执行，不重写历史快照。v4恢复先校验输入、计划、证据/权限再运行；无计划不能进入分析，最终报告不能改写已确认计划。原45秒/32步通用界限及工作任务120秒执行预算、PG大小/池/命名空间/指纹栅栏均不扩大。

## 恢复进度与费用边界

新增安全PROGRESS载荷：`{stage:"RESEARCH_RESUMING",nextStage:"RETRIEVING|PLANNING|ANALYZING|VERIFYING|SUBMITTING",usage:{modelCalls,toolCalls,searchCalls}}`。只用于v4实际恢复，nextStage从已确认根快照内容推导（无evidence/有evidence/有plan/有draft/有report）；不公开正文、内部凭据或思维链。字段精确白名单，usage边界沿S12C。前端展示最新恢复位置与累计预留，并明确未确认阶段可能重跑、次数不代表账单。

跨epoch累计模型3/工具5/搜索3不变；失败/未知结果预留不返还。已确认计划不重跑；未确认模型响应可能需要在剩余额度内重做，额度耗尽明确失败。中央已取消或失租不得继续发模型或提交有效新阶段；可能存在旧epoch孤立PG写，不声称跨库原子或模型exactly-once。

## 必需验收与范围

真实PG+MySQL/HTTP在plan/analyze/verify/完成后接管；已确认计划复用；阶段提交响应丢失后中央对账；未确认阶段失败后恢复的额外模型预留可见；取消/失租/依赖撤权停止；旧v1和v3恢复回归；前端真实进度/恢复位置/累计值、刷新与取消，未知/多字段事件不展示。无新表/迁移/依赖/调度器/子Agent/shell/部署/付费调用。
