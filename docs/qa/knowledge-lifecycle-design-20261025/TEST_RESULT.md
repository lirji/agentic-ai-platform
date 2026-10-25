# S10A 生命周期设计验证

2026-10-25。仅设计/契约/计划影响路径和文档检查，不执行产品测试、迁移、模型调用或部署。后端取证基线`88dcf6cd4ccf340dd0c53b6bd2cb480146904205`，前端只读基线`cdf7e471ff418261e594d9c232bc880da0243c12`。完整51项计划仍在进行。

## 产物与必需验收

| 需求 | 产物与结论 | 本片结果 |
| --- | --- | --- |
| 区分内容、发布、索引代次、profile | [原架构S10A增量](../../design/platform-selection-20261020/BACKEND_ARCHITECTURE.md)、[生命周期契约](../../design/knowledge-first-20261020/LIFECYCLE_CONTRACTS.md)五种身份，6条不变量、8项决策与状态表/图 | 设计PASS |
| 更新失败旧版可用、旧重试不覆盖新版 | activeJobId、发布基线与lease共同CAS；分离job失败与文档可见性；统一document→job锁序 | 设计PASS；真实DB验收归S10B/C/D |
| 当前v1兼容影响 | 固定version=1、旧CHECK/唯一索引、MIME单profile、当前publishedScope、旧ID/批次和Tasks历史result路径逐项映射 | 设计PASS；迁移未执行 |
| 更新/重索引/撤下/删除不混淆 | K07显式命令/原键查询、K08引用判权、P05代次身份；清理独立关闭 | 设计PASS；API未实现 |
| 历史正文不绕过当前发布授权 | Tasks公开读取和事件、Conversation上下文、评测/前端回收；旧流式事件无manifest不回放；NO_EVIDENCE安全说明例外明确 | 设计PASS；真实跨服务拒绝待实现 |
| 迁移/回退与保留未知项 | 追加DDL、旧generation=1且不改hash、启用顺序、不能回退到不识别墓碑的旧代码；U07具体期限单列 | 设计PASS；生产/清理保持未授权 |
| 后续可执行范围 | [LC-01–24矩阵](../../design/knowledge-first-20261020/LIFECYCLE_ACCEPTANCE.md)；S9D继续原P04新profile，S10B/C补齐T/Q/G读取影响路径，稳定51个ID/Needs不变 | 设计PASS |

## 证据与第二轮审查

[源码证据](source-evidence.json)记录实际读取文件、基线和SHA-256；[文档验证](validation.json)记录检查范围/数量/结构/边界。没有用历史QA或模板默认代替当前源码事实。

同一Agent完成第二轮反例审查，没有独立子Agent或跨模型审查声明。发现并在本片修正：

- 原幂等键规范实际是16..128而非初稿8..128，已按UploadRules统一。
- publishedGeneration+1会复用失败编号，改为last_generation单调分配。
- 重索引当前发布v1且存在失败新稿v2时，旧K02.version及ingestionJobId不能倒退；与K07.activeJobId分开。
- EvaluationMapper当前使用d.content_hash；新版失败时它会指向新稿，迁移要求改读发布内容修订hash。
- 只保护引用点击/最终答案不足以保护历史TEXT_DELTA；明确证据清单、旧无清单流事件禁止回放，以及服务端读取判权。
- 同内容同profile重复重建会复用原ID，P05显式把generation加入chunk/batch身份，旧ID不回填。
- job→document现有锁序会与新管理命令反序，统一document→job；心跳不反向取得document锁。
- 旧代候选挤占时，不把授权过滤后的不足伪装成无答案，采用有界扩展并保留原排名。

文档检查首次收尾因未将根README登记为允许文档路径而失败；已补登记这一实际索引变更并重新执行完整检查，未放宽产品代码限制。

应用源码/依赖/运行配置未改；前端工作树及HEAD保持基线。Mermaid状态图仅作围栏、边与状态结构检查，本机无mmdc，未宣称已渲染。没有为了文字变化新增应用测试或重跑已验证付费链路；两仓S9C结果继续有效。

## 限制与交付

U01真实业务语料、U03正式共享身份、U06规模/SLO、U07保留/灾备仍未决；原计划允许本地/隔离机制先行，本片不补造业务天数、容量、质量提升或RTO/RPO。后续产品验收均明确待执行。

SKILL_HANDOFF: backend-architecture-design和Contracts阶段，slice=S10A，status=COMPLETED，gate=PASS_WITH_ASSUMPTIONS（上述已显式限定的未知项不阻断S9D）；project-documentation/implementation-slicing已同步原文档和Owner路径。交update-progress-docs记录DONE，再按task-git-delivery/ci-cd-gate完成设计文档交付；随后直接继续S9D。设计提交`14300f1`已正常合并推送main，分支和main CI均通过；见[交付收据](delivery-result.json)与[CI结果](ci-gate.json)。
