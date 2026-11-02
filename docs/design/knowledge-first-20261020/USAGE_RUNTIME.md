# S15B 用量运行说明

S15B实现及UA01–UA16机制验收已通过，详见[TEST_RESULT](../../qa/shared-usage-budget-20261102/TEST_RESULT.md)。Tasks预算API、受控非Web CLI/冻结导入、模型出口、Task/Knowledge归因及React本人用量均已接入；双Tasks争用、worker退出/重启、真实浏览器和DB不可达均完成隔离验证。默认LEGACY_LOCAL，不能据本页直接切换真实付费调用。沿用[设计](USAGE_DESIGN.md)和[契约](USAGE_CONTRACTS.md)，不引入新服务、数据库或费用授权。

## 运行模式与归因

Tasks设置`USAGE_MODE=CENTRAL`，并以`usage.knowledge-public-key`引用Knowledge既有`knowledge-ai-public.pem`公钥（URI形式）；其worker公钥沿用原Task worker角色。Knowledge设置`USAGE_MODE=CENTRAL`、`USAGE_EMBEDDING_PROFILE`，只有已明确启用重排时提供独立`USAGE_RERANK_PROFILE`；两类profile不能相同。所有引用必须预先由受控CLI登记，不会自动创建或加额。关闭模式下配置profile也拒绝，防止以为已切换但仍使用旧账本。

每个Python HTTP/worker进程设置`AI_USAGE_MODE=CENTRAL`及`AI_USAGE_CONFIG=/absolute/private/usage.json`。文件必须0600、普通文件、绝对路径，示例只表达字段形状：

```json
{
  "schemaVersion": "usage-runtime-v1",
  "endpoint": "http://127.0.0.1:28087",
  "workerId": "operator-assigned-instance-id",
  "privateKey": "/absolute/private/task-worker-private.pem",
  "profiles": {
    "CHAT": {"profileId": "approved-chat-profile", "model": "approved-provider-model"},
    "EMBEDDING": {"profileId": "approved-embedding-profile", "model": "text-embedding-v4"}
  }
}
```

只登记该进程实际批准的种类；启动时核对所有启用模型与profile映射。CHAT的model对应`ChatConfig.approved_model`，不填LiteLLM路由别名。HTTP进程与任务worker使用不同的配置/workerId；每个并行实例必须有独立执行身份。任务worker领取与usage写入使用同一workerId，仍由epoch限制旧领取；stepKey不含epoch。CENTRAL不会读取或写入旧Chat/Embedding/Rerank预算文件，任何配置/准入错误均不回退。现有单次输出、价格有效期、工具次数、研究上限和资料权限仍生效。Rerank能力查询在CENTRAL只报告显式配置，实际发送前还须中央原子准入，不对外提供共享资金余额。

Task模型调用从真实租约绑定租户、taskId、epoch和用途，Tasks在事务中解析原用户。题目、阶段、补充搜索序号及Embedding批号参与稳定步骤身份；同一步骤恢复仍需新attempt，已发送的旧尝试保留计量或未知占额。

Knowledge使用原处理签名密钥签发独立audience/scope的30秒证明，Python及Tasks分别验签。入库上下文绑定原jobId/owner，签发前以原领取重验有效租约和当前文档意图；旧prepare方法不能在CENTRAL绕过此检查。搜索绑定已认证的实际查询用户，每次独立请求有独立contextId。共享读者仍归因读者本人。证明只含允许的kind/profile/步骤前缀，不含正文、资金池或价格。

没有用旧token换新token的接口。证明过期后禁止后续批次新的预留/dispatch；已获许可的尝试允许原worker迟到报告。需要恢复时由新一轮可信业务入口重新验权签发，不自动续签或重发模型。准备包仍由原发布流程独立验证。

## 受控管理命令

入口为现有Tasks可执行jar，使用目标环境的独立数据库/迁移配置；不开放管理HTTP。命令默认关闭，Web模式启用管理命令会失败。操作人记录为本机进程用户，manifest不接受actor或密钥。

```sh
java -jar async-task-service/target/async-task-service-0.1.0-SNAPSHOT.jar \
  --spring.main.web-application-type=none \
  --usage.admin.enabled=true \
  --usage.admin.manifest=/absolute/private/manifest.json
```

数据库凭据通过已有受控环境变量提供，不能写进命令行/manifest。manifest、旧账本、冻结标记均须规范绝对路径、普通文件、0600、无符号链接；单文件最大256KiB。CLI完成即关闭进程，输出一条`usage_admin_result`日志；金额为当前账户快照。失败不得以再次初始化或增加limit修复。

manifest固定`schemaVersion=usage-admin-v1`：

| 字段 | 约束 |
| --- | --- |
| commandId / accountId | 规范小写UUID，账户身份永久稳定 |
| action | BOOTSTRAP / CONFIGURE / IMPORT |
| sponsorRef / fundingSourceRef | 受控安全引用，无凭据；同资金来源+用途+LIFETIME唯一 |
| kind / limitAmount | CHAT/EMBEDDING/RERANK/JUDGE；原授权CNY六位小数，已有账户不可变 |
| state / expectedVersion | 初始化state显式，version=null；配置需要目标状态与当前version；IMPORT的state=null且需要当前version |
| profiles | 最多8项`{profileId,priceVersion,pricing}`；pricing沿UsagePrice：model、unit、inputRate/outputRate/totalRate及输入输出上限；未使用费率为null |
| bindings | 最多32项`{tenantId,profileId,priceVersion,enabled}`；必须显式，已有绑定不能跨资金池重映射 |
| source | 无导入为null；导入为`{path,proofPath,sourceLedgerId,sourceSha256,reservedAmount,entriesCount,freezeProofRef}` |

BOOTSTRAP允许同事务创建账户、价格、绑定并导入；CONFIGURE不能导入或改上限；IMPORT的profiles/bindings必须为空，源limit必须等于原授权。价格快照不能覆盖，只能登记新版本再显式切换绑定；已有attempt继续使用冻结价格。账户超额时仍记录可表示的历史占额并FROZEN，不因状态配置恢复超额准入。

同commandId同规范内容回放，不重复变更；异内容冲突。源唯一、原始字节摘要唯一、旧条目UUID全局唯一。改命令ID仍不得重复导入同源；已登记源改摘要/目标则冲突。移动文件不影响身份。导入后没有普通用户修改旧未知记录的接口。

## 单向冻结与迁移顺序

1. 明确目标资金身份和原授权，停用并核对全部旧模型写入者；保存进程/容器停止证据。未升级二进制不认识冻结标记，不能只创建标记就声称它们已停止。
2. 在原运行时环境执行下述冻结工具。`--stopped-writer-pid`须完整列出已停止写入进程，可重复传入；工具复核这些PID已不存在，但不自动证明未列出的进程/其他主机已停用。`stopEvidenceRef`指向运维保留的安全证据引用。
3. 工具取得原文件独占锁，验证原金额/条目总和，写入并fsync `<原账本>.frozen.json`，原账本字节不变。升级后的Chat/Embedding/Rerank和旧处理验收脚本在原锁内拒绝任何冻结标记，坏标记也拒绝。
4. 使用工具输出的source片段构建显式manifest，通过非Web CLI导入；最多100条。旧RESERVED及早期Embedding的HTTP状态都保持UNKNOWN、LEGACY_UNATTRIBUTED，不能把HTTP_200当供应商计量。
5. 核对原limit、中央held/booked、源条目和收据，验证所有付费出口均走CENTRAL后，才按已有授权启用调用。导入失败保留冻结，不自动解冻或回退；导入成功后也不能将旧文件恢复为可写资金权威。

```sh
cd ai-runtime
uv run --frozen python -m agentic_ai.runtime.ledger_freeze \
  /absolute/private/original-ledger.json \
  --kind EMBEDDING --source-id operator-approved-source \
  --proof-ref freeze-evidence-id --stop-evidence-ref stopped-writers-evidence-id \
  --stopped-writer-pid 12345
```

上面PID/引用是说明占位符，不是本机已验证的停写证据，也不是可直接执行的实际迁移。原Chat和Embedding账本本轮未冻结、未导入、未增加授权，真实Rerank/Judge未启用新预算。

## 当前验证边界

提供方计量的资料核对（2026-11-02）：[DeepSeek Chat](https://api-docs.deepseek.com/api/create-chat-completion/)使用输入/输出token字段，流式计量取完整末尾报告；[百炼Embedding兼容接口](https://help.aliyun.com/zh/model-studio/embedding-interfaces-compatible-with-openai)及[文本Rerank](https://help.aliyun.com/en/model-studio/text-rerank-api)提供total_tokens。这只支持解析规则，不证明当前路由每次都返回计量；缺失、部分、矛盾或无效维度继续UNKNOWN。没有据此次资料查询改变真实模型、费率或额度。

V6/V7只应用`agentic_tasks_test`；已应用迁移不可修改。最新核心验证52项相关Java单元、Tasks全量60项真实数据库/HTTP集成通过；枚举整改后的用量20IT复验通过。Python全量677通过/97条件跳过，受影响206项复验通过、mypy86源码通过。真实jar非Web CLI完成合成账本初始化+导入+重复重放，原付费账本摘要一致。中间日志在`.logs/s15b-admin-it.log`、`.logs/s15b-freeze-python.log`、`.logs/s15b-admin-cli-*.log`；当前证据不等同S15B整体验收完成。


模型接入批次验证：Knowledge 126项单元及35项真实MySQL/HTTP/搜索集成通过，无跳过（`.logs/s15b-knowledge-usage-it-r2.log`）；Python全量733通过/97条件跳过，新增保守计量与factory检查后120项受影响测试通过（`.logs/s15b-outlets-final-python.log`），mypy88源码通过。真实Java Knowledge→Python API→Tasks/MySQL联调证明两个用户独立归因、本人读取和中央不可用前置拒绝，模型仅本地传输替身（`.local/s15b-knowledge-joint.json`）；原付费账本摘要未变。


## 本人用量与复验入口

问答轮次、研究/助手详情、评测运行和资料当前处理任务提供“本人用量”，需task:read，仅展开时请求本人context。每页20条，可翻页/刷新；初始预留不等于已消费，未结算不补零，已结算金额仅为内部成本。没有全局账户余额、充值或管理HTTP入口。

已有本地test凭据与前端构建就绪后运行`ai-runtime/.venv/bin/python deploy/usage_browser_smoke.py`：只写随机agentic_tasks_test记录，启动两个临时Tasks并执行真实浏览器，模型发送仅合成计量，结束正常停止临时服务、保留记录/日志。运行`ai-runtime/.venv/bin/python deploy/usage_browser_smoke.py database-outage`只给新进程配置不可达DB，不停止共用MySQL。脚本默认工作目录为后端根目录；需先在相邻前端执行pnpm build，Node沿既有24.22工具链。`ci/check_usage_boundaries.py`已加入CI，检查usage持久化所有权、包依赖无环与迁移注释。
