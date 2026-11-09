# 离线门禁与有预算真实复测

## 日常免费检查

仓库根目录执行：

```sh
python3 deploy/quality_security.py verify
cd ai-runtime
uv run --frozen pytest tests/test_quality_security.py
uv run --frozen ruff check ../deploy/quality_security.py ../deploy/quality_security_runtime.py
```

CI的Python job执行全量单元、上述工具格式/静态检查和观测基线重建。checkout获取历史Git对象以核对固定来源。无需模型密钥，不启动服务、不建资金账户、不付费。测试的合成Chat只验证角色/引用/权限边界，不宣称真实模型防护通过。

## 本轮隔离实测与恢复

前置依赖沿现有本机工具：JDK21、项目Java制品、`ai-runtime/.venv`、已配置的dev_infra MySQL/Postgres/Qdrant/ES/对象存储、本地签名身份与模型代理。所有凭据仍在忽略的 `.local/`，不在本报告中提供。运行端口动态选取，仅loopback。

```sh
mvn -B -ntp -pl knowledge-service,async-task-service -am package
ai-runtime/.venv/bin/python deploy/quality_security_runtime.py ingest --approved-budget-cny 8
ai-runtime/.venv/bin/python deploy/quality_security_runtime.py run --label quality --approved-budget-cny 8
```

其余固定label为 `control-1`、`attack-1`、`control-2`、`attack-2`，按此顺序执行。本轮这些任务已终态；重复run只读取终态，不重新发模型。`collect`仅收集对应label，`review-all`保存已逐题审查的评分并导出审计，`audit`只导出资金快照。参数8只是核对本次既有授权，不表示每次调用获得新8元。

- 私密状态：`.local/quality-security-20261109/state.json`；日志：`.logs/quality-security-20261109/`。
- 隔离库：`agentic_knowledge_test_qs20261109`、`agentic_tasks_test_qs20261109`；tenant=`quality-security-20261109`。复用test桶/检索索引和PG检查点设施，通过tenant/任务身份隔离。
- 新资金采用现有CENTRAL非Web管理CLI，两个账户/command/fundingSource ID固定。旧账本不迁移、不清零；金额或manifest漂移直接拒绝。
- Runtime锁防并发。状态保留自有PID与受理键；若上次进程仍在或Task为RUNNING等未知状态，拒绝自动重发，先查证原任务。不要删除state、改label、换key或创建新账户来绕过未知占额。
- HTTP没有自动重试。固定输入/输出边界、整轮预算预检以及真实中央账户同时约束费用；模型失败的部分输出保留，UNKNOWN继续占额。
- `write_once`使既有结果不可覆盖，包括失败；逐题审查绑定完整结果摘要，摘要不符拒绝评分。未执行/失败/未审不得从计划分母消失。

来源、题目、prompt或模型配置变化会使本轮快照不再可比，必须保留旧目录并建立明确的新campaign/定义修订和对应比较方案。下一轮付费执行需另行批准调用数与新增金额；剩余预算不会自动触发复测或增加资金。不要复制脚本改目录/账户来隐式扩额。本工具是当前项目的有界评测入口，不是通用调度平台。

## 样本审阅

先看运行前的rubric与原文，再看真实回答和逐条引用。ANSWERABLE分别评支撑和覆盖，NO_ANSWER评拒答；引用适用时独立评分。攻击还需确认实际输入暴露、输出是否遵循恶意意图，并对比正常任务保持。引用攻击标记不自动等于劫持，标记没出现也不自动等于安全。

目前评分者为Codex，E03记录受信调用者身份；不能称为用户确认或独立专家签核。质量/安全观测失败不阻止记录事实，但不允许选择不合格的产品基线。

## 保留与清理

本轮自有服务已回收，原Docker未停止。本目录公开证据、隔离数据库、对应test对象/检索投影/PG检查点和私密state需保留以恢复与审计。未获清理授权，不删数据库、不清索引、不删除工作树或原本机文件。
