# S10C 验收结果

状态：**本地实现与必需验收PASS；Git/CI交付状态另由收据记录**。本次唯一切片S10C，源码指纹见[validation.json](validation.json)。复核由同一Agent分阶段执行，不冒称独立Agent或机器Runtime认证。

| 验收 | 结果 | 证据与观察 |
| --- | --- | --- |
| LC-12 发布/更新/撤下/删除竞争 | PASS | KnowledgeLifecycleIT真实MySQL锁/CAS；任意赢家不复活资料；实际迟到ES/Qdrant写不能确认或发布 |
| LC-13 owner管理、墓碑、原键恢复 | PASS | K07真实401/403/400/404矩阵；WITHDRAWN管理信息可读，普通K02/K03/引用拒绝；DELETED列表隐藏/详情404；同键重放与跨owner/tenant拒绝 |
| LC-14 历史正文与全部消费者 | PASS | 每种墓碑3类完成任务的GET/SSE均404，存储摘要不变；K04/K05/K06/K08拒绝、实际检索无命中；Conversation上下文409且不创建新轮次 |
| LC-14 PG恢复和模型外发 | PASS | 6个真实PG/MySQL/HTTP场景，QA/研究/助手在generate/analyze之前暂停；真实撤下或删除后恢复均INPUT_REJECTED，真实K05重验，模型0调用 |
| LC-14 评测资料/报告/基线 | PASS | EvaluationDecisionIT实际WITHDRAW/DELETE后定义正文、绑定、报告、问题、评审与基线展示均关闭；原基线记录不改写 |
| LC-15 权威不可用 | PASS | Knowledge实际停机，三类历史任务GET/SSE全部503；浏览器404/503清除已显示正文；恢复后不借旧索引或结果继续输出 |
| LC-16 锁序与重复请求 | PASS | document→job统一锁序、并发真实DB测试有界完成；定向同键DELETE查询窗口红绿测试，最终只有一条命令审计 |
| LC-21 关闭命令/兼容回退 | PASS | 六项开关全false仍识别墓碑、原owner回执/POST重放；S10B v3仍可读；错误修订409、陌生身份404、新命令拒绝 |
| LC-22 仅逻辑删除 | PASS | 无S3/ES/Qdrant/备份删除路径；计数端口0调用；真实S3原文、索引候选、块ID和READY历史保留，公开读取拒绝 |
| LC-23 现有唯一约束边界 | PASS（本片范围） | 已逻辑删除的同内容重新上传仍明确409，无静默复活；S10E查重/约束演进待实施 |
| LC-24 UI、未知响应、键盘/窄屏 | PASS | 83前端单元、14浏览器协议回归、真实撤下/删除和修复后再次真实删除；1440/1024/414，Escape恢复焦点、Enter打开、回执恢复只发一次POST |

## 必需检查

- Java：最终 `mvn -B -ntp -Pintegration verify`，138单元+108集成全部通过，日志`.logs/s10c-java-final.log`。定向竞态修复前失败见`s10c-delete-race-before.log`，修复后11项通过见`s10c-delete-race-after.log`。
- 前端：类型、ESLint、生产构建、83单元全部通过；14项协议浏览器回归通过。首次2项真实端到端通过后，针对截图发现的问题另用第3份隔离资料验证真实删除/丢响应/404恢复通过；最终已接受操作只读重验，不重复修改原样本。
- Python：本片产品与依赖未改，沿用S10B版本匹配的单元/静态证据；新增6个真实墓碑PG恢复场景全部通过，未调用聊天模型。显式跳过的其他环境测试不计通过。
- Runtime：原四字段升级默认关闭、测试隔离、错误类型/未知字段/回填互斥检查通过；真实HTTP输入/身份/历史、关闭开关和依赖故障通过；服务与qa-worker已恢复，固定键/账本保留。
- 文档与代码卫生：契约登记、共享金标、Markdown链接/围栏、两仓diff与源码摘要检查登记于validation.json；无新迁移，不重写V10或旧金标。

前端截图及逐项视觉观察见其[验收记录](../../../../agentic-ai-platform-web/docs/qa/document-visibility-20261026/TEST_RESULT.md)。两仓日志均在忽略目录：`s10c-http-*`、`s10c-pg-*`、`s10c-authority-outage.log`、`s10c-writes-closed-recheck.log`、`s10c-browser-*`及`s10c-ui-final-*`。原始运行编号保存在`.local/s10c-http-state.json`、`.local/s10c-privacy-state.json`。

限制：未部署生产；S10D重索引、S10E查重、恢复上架、物理删除/清理不在本片。U01真实业务语料、U03共享身份、U06容量/SLO、U07保留/灾备要求仍未决。Vite原有大块提示和LangGraph测试警告保留。原MULTI_CLIENT_DRIFT_BLOCKED现状未改，沿批准手工专业pass执行。
