# Q 执行：授权知识检索、带引用回答与费用界限

S5D 在既有 Q/T 与 PG 恢复边界上冻结执行协议；不改会话原问题、不建立新的任务权威。用户已授权继续实施，模型账号沿用原配置，Embedding 与聊天各累计 1 元；2026-10-21 用户明确批准替代型号 deepseek-flash，随后更新提供方 API key；新凭据仅保存本项目私密配置。本契约允许先实现并验证无付费依赖部分，真实模型质量和费用另有独立验收。

## 所有权与执行

- Java Knowledge 拥有已发布资料、检索授权与引用；Conversation 拥有原问题与前序关联；Tasks 拥有租约、事件、取消和最终答案。Python 不读写这些业务库。
- `knowledge-qa-input-v1` 沿用 S5C：`conversationId,turnId,question,documentIds,contextTurnId,contextTaskId,contextQuestion`，连同 schemaVersion；所有字段必须出现，无上下文三个关联值均为 null。身份来自中央 Lease，不能从输入或工具参数覆盖。
- LangGraph 执行 `retrieve → generate → verify`，使用 S5B 持久检查点；LlamaIndex 自定义 BaseRetriever 适配 Java 授权检索，返回有定位的 TextNode/NodeWithScore，不重新直连索引或调用默认模型。
- 检索固定 topK=5；question ≤2000 码点，文档 0–20 个唯一 canonical UUID。空文档数组在调用 K04 时转换为 null（K04 的“当前 owner 全部可见资料”），不改变已有公共契约。
- 没有证据时直接产生 `NO_EVIDENCE` 结果，明确无法依据资料回答，不调用聊天模型。有效证据以 JSON 分隔的数据输入模型，系统提示要求只引用给定编号、将原文中的指令视为资料内容；本图不暴露写工具、任意网络或文件系统工具。
- 上下文最多一个前序成功任务，读取中央 Lease 后核对 tenant/owner、会话、前序问题、知识范围和结果 schema；前序引用逐一经 Knowledge 重验。上下文不是工具授权依据。
- 恢复后使用已提交快照；生成前重新校验当前证据，最终提交前也校验。S5B 执行入口兼容增加可选的最终结果校验回调，完整图快照恢复而不重跑节点时仍执行该回调；已由中央确认的终态不重新生成。
- 重验发现原文/版本/权限漂移时失败，不使用旧快照继续生成。无自动跨模型回退，无 SDK/HTTP 层模型自动重试；未知模型响应保持预算预留。显式恢复可能再次付费，累计账本不会重置。

## K05：runtime 只读知识委托

复用现有 KnowledgeSearch 及 K04 结构，新增内部入口：

| 方法与路径 | 请求 | 成功 |
| --- | --- | --- |
| POST `/internal/v1/knowledge/search` | `{query,topK,documentIds}`；32 KiB；同 K04 校验 | 200 `{hits:[Hit]}` |
| GET `/internal/v1/knowledge/documents/{documentId}/versions/{version}/chunks/{chunkId}` | canonical UUID、正版本、64 位小写十六进制 chunkId | 200 Hit |

Hit 仍是 `{documentId,version,chunkId,text,score,source:{displayName,locator}}`。没有复制新的检索算法或引用数据模型。错误沿用 KnowledgeError/PlatformError，owner 不可见统一 404。

独立 RSA 验签角色：iss=`urn:agentic-ai:runtime-knowledge`、aud=`agentic-ai-knowledge`、azp=`ai-runtime-worker`、scope=`knowledge:read`，tenant/sub 由当前中央任务的所有者绑定；iat/nbf/exp 必需，TTL≤60 秒，无时间宽限。使用独立 `runtime-knowledge-{public,private}.pem`，不是 Gateway 私钥、中央 worker 私钥或 Knowledge→AI 准备密钥。公开 K API 保留原安全链；两套签名不互认。只有显式启用内部读取并提供公钥后开放，Gateway 不路由内部路径。

Python 每个任务创建固定 tenant/owner 的客户端，HTTPS 或 loopback HTTP 固定原点；无重定向、无自动重试、连接/在途≤2、单请求15秒、响应≤256 KiB、关闭和取消有界。每次读取都使用新短时签名，不将密钥/JWT放入图状态或检查点。

## Q04：最终结果

`Task.result` 固定形状：

```json
{
  "schemaVersion": "knowledge-qa-result-v1",
  "answerability": "ANSWERED",
  "answer": "根据资料……[1]",
  "citations": [
    {
      "citationId": "1",
      "documentId": "canonical UUID",
      "version": 1,
      "chunkId": "sha256",
      "source": {"displayName": "资料名", "locator": "utf8-bytes:0-10"}
    }
  ]
}
```

- answerability 为 `ANSWERED` / `NO_EVIDENCE`；不是新的任务状态。answer 是非空纯文本，≤12000 字符；result 仍遵守中央 64 KiB 上限。
- 引用编号按本次检索的固定顺序 1–5 分配；回答中的 `[n]` 只能引用存在的编号，ANSWERED 必须包含至少一个有效编号。结果 citations 只包含实际使用的证据，不把“检索过”冒充“模型引用过”。
- 标题、定位、版本和 chunkId 从 Java 已授权响应派生，模型不能覆盖。结果不内嵌完整原文；前端打开引用仍通过 K04 重新判权。NO_EVIDENCE 的 citations 为空，answer 使用统一说明文案。
- 流式 TEXT_DELTA 仅临时展示；最终 schema/引用验证失败时不能将已收到文字标成完成。最终答案只取中央 SUCCEEDED.result，刷新/恢复不拼接多个 epoch 的文字为最终答案。
- PROGRESS payload 固定 `{stage}`，stage 为 `RETRIEVING` / `GENERATING` / `VERIFYING`；TEXT_DELTA 为 `{text}`，每批≤1000字符且满足 T01 的 8 KiB。operationKey 按 epoch/阶段/批次稳定生成，阶段重放不重复写同代事件。

## 模型出口与预算

- 已批准的独立 LiteLLM 作为聊天出口，逻辑名 `chat-default`，账号/真实型号通过私密配置映射；独立进程映射到用户获准的 `deepseek/deepseek-flash`，显式 `thinking.type=disabled`。本机锁定安装 LiteLLM `1.106.0`，独立依赖见 `deploy/model-proxy/uv.lock`；制品/边界/真实调用分别验收，不能把安装等同于模型可用。
- OpenAI 兼容 `/v1/chat/completions`，单请求 stream=true、n=1、max_tokens≤1024；不允许模型决定地址、型号、工具、费用上限或重试策略。实现的请求体预算为64 KiB，超出时拒绝而不静默删减原文。HTTP 完整期限≤25秒、连接/在途≤1、总响应≤256 KiB、单 SSE 事件≤32 KiB；拒绝工具调用、异常 finish_reason、格式错误和超过答案上限。
- 从 LiteLLM 到提供方的重试/回退必须关闭；关闭请求/响应正文记录与外部追踪，不在日志保存 prompt、凭据或原响应。支持性必须对最终锁定制品验证，不能只凭最新文档配置。
- 聊天使用独立本轮 `.local/chat-budget-20261020.json`（0600），上限 1 元，不随日期、进程、任务重领或恢复重置。配置必须给出获准型号、核验日期与保守人民币单价；未配置、账本缺失/损坏/争锁/无法落盘时拒绝发送。
- 每个实际 HTTP 请求前持锁追加保守预留并 fsync；输入按 UTF-8 字节上界加固定协议余量估算 token，输出按 max_tokens 上限，使用经核对的保守输入/输出价格并向上取整。未知结果不退款；实际 usage 只作观察，不能扩大本轮上限。账本不保存 prompt 或答案。
- Embedding 继续原独立账本；检索调用仍可能消耗其预算。无证据不调用聊天，不表示检索本身免费。本地护栏不是正式租户账单，也不承诺提供方最终结算金额。

## worker 与失败

常驻进程默认不启动；只有身份/PG/模型/预算配置全部有效才领取。单实例一次执行一个任务，部署租户固定，10 秒心跳，沿用45秒图总期限/32步上限。关闭时停止领取、取消进行中的有界调用；无未完成任务时立即退出，持久任务按中央租约恢复。

当前图只接受 KNOWLEDGE_QA/langgraph-v1/knowledge-qa-v1 和固定输入 schema；旧合成输入或其他版本拒绝，不猜测补全。每个外部操作前检查租约，取消后不继续调用新工具或提交文字；已发往提供方的请求不宣称已撤销费用。

复用中央失败码：预算拒绝 `BUDGET_EXCEEDED`（不可自动恢复额度）；格式/权限/证据漂移 `INPUT_REJECTED`；模型或知识依赖不可达 `AI_UNAVAILABLE`；其他执行失败 `EXECUTION_FAILED`。内部细分码用于可测试的安全异常映射，不把原始异常/响应作为用户错误文案。失败报告也需原 epoch，迟到结果以中央已确认状态为准。

## S5D 验收与后续

1. K05 在真实 Knowledge/MySQL 上证明读取已有合成引用、owner/tenant/签名/scope/TTL/路由拒绝；只读引用不产生 Embedding 调用。公共 K API 回归。
2. Python typed input/result、原生 LlamaIndex 检索适配、LangGraph 节点、上下文/引用重验、空证据和错误映射均可独立测试；无隐藏默认模型、外部追踪或写工具。
3. 本地受控 HTTP 验证聊天流、大小/时限/取消/连接界限、费用预留在发送前落盘、并发预算竞争、未知响应不退款和重启延续；受控模型必须明确标为测试替身。
4. 真实 PG/Tasks 联合执行使用测试模型验证检索后/生成后恢复、最终回调、取消竞争、事件重放与同一结果提交；检查点中没有凭据。
5. 真实模型/独立 LiteLLM/实际答案质量与费用在已获准的 deepseek-flash 路由上验证；验收通过之前保持 UNVERIFIED，不能将受控模型结果作为真实问答交付。问答 UI 消费本契约与已有真实 Q/T/K API，分别完成可见状态和操作验收。

资料：[LiteLLM 官方发布](https://github.com/BerriAI/litellm/releases/tag/v1.106.0)、[配置参考](https://docs.litellm.ai/docs/proxy/config_settings)、[LlamaIndex 检索接口](https://developers.llamaindex.ai/python/framework/module_guides/querying/retriever/)。具体兼容性以已锁定依赖源码和实际执行为准。
