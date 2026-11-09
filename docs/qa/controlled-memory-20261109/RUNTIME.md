# S18C运行与回退

- 沿既有本地Docker prepare入口新增`--memory-ai`，必须同时`--memory`；默认关闭。该开关配套启用Conversation/Tasks generation与追问、Knowledge检索、Python QA v4及提议图。没有新增服务或公共中间件。
- `deploy/memory_local.py`独立准备runtime-memory RSA角色，0600、拒绝符号链接/缺失私钥/不配对公钥；既有材料只复用不轮换。公钥只给Conversation，私钥只给worker。内部JWT不授予公开确认或其他服务写权限。
- Worker复用现存chat/embedding配置与账本，增加最小embedding环境和同一账本挂载；AI HTTP检索沿原runtime-knowledge只读角色。没有新额度或账本重置。
- Conversation V5/V6、Tasks V9为追加迁移；旧v1–v3问答与v1治理接口继续可读写。已有新图任务需保留本版本消费者；回退先停止新受理，保留新图worker排空，再关闭memory-ai。不能把新图交给旧worker或删除迁移。
- `--memory`关闭时仍可查看/编辑/停用/遗忘；AI开关关闭后不能新提议/应用/使用记忆，已有无正文提议收据仍可恢复。遗忘不是历史会话/供应商请求撤回。
- 本地原Chat限额1 CNY，部署前原保守预留0.957092 CNY/27调用；2026-11-09依据[DeepSeek官方价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)复核既有deepseek-flash高峰保守输入2/输出8 CNY每百万token，单价不变，仅更新私密配置核验日期。后续探针预留变化另见实际收据，不当作实际账单。

部署状态以PROGRESS_STATE和最终部署收据为准；本文件不声称已部署。
