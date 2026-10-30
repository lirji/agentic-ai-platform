# AI 运行时

已交付 S0/S2/S5B：Python 3.12、存活端点、真实 Embedding、鉴权知识准备/查询向量，以及受控 PG 检查点和中央恢复。S5D 问答图、累计预算和 worker 已通过受控恢复与真实 deepseek-flash 问答验收。契约权威为 [R01/H01](../docs/design/knowledge-first-20261020/CONTRACTS.md)、[P01/P02](../docs/design/knowledge-first-20261020/PROCESSING_CONTRACTS.md)、[S5B](../docs/design/knowledge-first-20261020/CHECKPOINT_CONTRACTS.md)与 [Q 执行](../docs/design/knowledge-first-20261020/QA_EXECUTION_CONTRACTS.md)。

## 本地启动

在本目录运行，使用已安装的 uv：

```bash
uv sync --frozen --python 3.12
uv run --frozen agentic-ai serve --host 127.0.0.1 --port 8000
```

`GET http://127.0.0.1:8000/health/live` 返回：

```json
{"status":"alive","service":"agentic-ai-runtime"}
```

该端点只证明进程可响应，无需模型凭据。不存在声明业务可用的 readiness 端点。三框架已正式锁定并安装：LangGraph 1.2.16 编排准备图、LlamaIndex Core 0.14.25 分块；Deep Agents 0.7.28 已通过隔离工具/流式循环验证，业务研究 Agent 尚未交付。PG 完整快照已通过真实 LangGraph 和中央任务联合恢复验收；本机问答 worker 另由显式入口启动，见下文。

## 启用知识准备

`AI_PROCESSING_ENABLED=true` 时必须同时提供独立 Knowledge 服务公钥、合法 R01 配置，以及固定 `text-embedding-v4 / 1024`。无效配置启动失败；默认关闭时内部业务路由不存在。

- `POST /internal/v1/knowledge/prepare`：受限原文 → LangGraph/LlamaIndex 分块 → 真实向量 → 有来源定位与摘要的准备包。
- `POST /internal/v1/knowledge/embed-query`：同处理版本的查询向量。
- 两者均验证独立 RS256 服务 JWT、调用服务、scope 与租户/所有者，不对外网关开放。
- 默认处理并发 2、总期限 40 秒，忙时 429；取消/HTTP 断开停止后续模型等待。Python 不写入业务数据库、S3 或检索索引。

本项目本地配置与启动统一使用 [deploy/local.py](../deploy/local.py) 的 `init-ai/start-ai/stop-ai`，见[运行说明](../deploy/README.md)。S2 已用少量合成语料验证真实北京地域接口；证据见 [真实模型验收](../docs/qa/knowledge-processing-20261020/real-model-smoke.json)。准备成功不代表资料已经发布。

## 模型配置

配置键与无密钥样例见 [.env.example](.env.example)，程序只读取环境变量，不自动加载 `.env` 文件。通过本地环境或密钥管理工具注入 `RAG_EMBEDDING_BASE_URL`、`RAG_EMBEDDING_API_KEY` 后运行：

```bash
uv run --frozen agentic-ai check-config
```

默认 `provider=openai` 代表兼容协议，模型为百炼 `text-embedding-v4`，维度为 `1024`。基础地址应是账号所属地域的 HTTPS API 根地址（通常含兼容接口版本路径），客户端追加 `/embeddings`；账号、地域和网络可用性需单独核对。配置检查仅输出协议、模型和维度，不请求模型，也不证明凭据有效。

`hash`、未实现的提供方、缺失密钥和非法参数会明确失败。修改模型或维度需要另建匹配的索引配置，不能混用旧向量。真实模型调用需要对应任务授权及预算。本轮 Embedding 上限 1 元，聊天另限 1 元。AI 启动入口注入 `AI_EMBEDDING_BUDGET_FILE` 与 `AI_EMBEDDING_BUDGET_LIMIT_CNY=1`，每个实际 HTTP 批次发送前加锁、预留、落盘；文件缺失/损坏/争锁/耗尽拒绝发送，未知结果不返还额度。通用直接装配可以不启用本机护栏，此时由宿主提供预算控制；本项目付费验收不得绕过。账单金额未读取。

## 内部调用

```python
from agentic_ai.adapters.openai_embedding import OpenAIEmbeddingAdapter
from agentic_ai.runtime.embedding_config import EmbeddingConfig

async def embed_authorized_texts(texts: list[str]):
    # 应用用例先完成资料判权与预算检查，再进入此内部端口。
    async with OpenAIEmbeddingAdapter(EmbeddingConfig.from_environment()) as adapter:
        return await adapter.embed(texts)
```

正式应用装配时复用一个适配器并在生命周期结束时 `aclose()`，不要为每条文本创建连接池。每次调用按至多 10 条拆批，并共享总时限；响应按索引还原顺序，严格检查数量、维度和有限数值。失败时只抛出安全的 `EmbeddingError`，不自动重试或降级，不返回部分成功；取消继续向上传播。

## 目录与验证

- `src/agentic_ai/rag/`：无 HTTP 框架依赖的领域端口、不可变结果与稳定错误。
- `src/agentic_ai/adapters/`：OpenAI 兼容 HTTP 实现。
- `src/agentic_ai/processing/`：准备契约、摘要、服务身份及固定处理图。
- `src/agentic_ai/tasks/`：中央任务客户端、受控 PG saver、严格序列化和终态优先执行入口。
- `src/agentic_ai/qa/`：固定身份知识检索、LlamaIndex retriever、LangGraph 问答图、引用重验、累计聊天预算与单在途 worker。
- `src/agentic_ai/runtime/`：有界配置；`api/` 与 `cli.py`：当前运行入口。
- `tests/`：配置、协议成功/失败、分批、时限、取消及真实本地 HTTP 进程测试。模型响应使用 HTTPX transport 替身，不能作为真实语义质量证据。

```bash
uv run --frozen ruff format --check src tests
uv run --frozen ruff check src tests
uv run --frozen mypy
uv run --frozen pytest -q
uv build --no-sources
```

`uv.lock` 锁定运行及开发依赖；构建后端由 `pyproject.toml` 声明。默认测试不调用模型或真实业务存储。S5B 真实测试须先按[运行说明](../deploy/README.md)初始化 PG 并启动中央任务，再显式运行：

```bash
AGENTIC_CHECKPOINT_IT=1 uv run --frozen pytest -q
```

S5B 当时 150 项验证通过（含 11 项真实 PG/MySQL/中央 HTTP），见 [S5B 验收](../docs/qa/ai-checkpoint-recovery-20261021/TEST_RESULT.md)。测试只新增本项目合成数据，保留 `.local/s5b-*.json` 归属记录，不调用任何真实模型。执行入口未暴露为用户 API；不接受用户 threadId，不支持子图、历史回放或删除。

## 问答执行与当前限制

S5D 图为 `retrieve → generate → verify`。每次读取/模型调用/事件追加前检查中央租约；恢复后的证据和最终引用重新经 Java 判权。无证据直接返回统一说明，不调用聊天。完整快照恢复也执行 `before_complete`；中央已经终态时不重跑图。上下文只取同 owner/会话/知识范围的一个前序成功回答。

聊天客户端固定 `chat-default`、1 在途、25 秒、64 KiB 请求、256 KiB 响应、32 KiB SSE 事件、1024 输出 token；无重试/回退/工具调用。独立 0600 账本在发送前锁定、保守预留并 fsync，未知响应不退款；重启、日期变化或显式恢复均不重置。实际出口已锁定 LiteLLM 1.106.0 → deepseek-flash，关闭思考模式；真实流与问答已验证。代理进程使用独立依赖环境、最小日志和专用密钥，不持有其他服务凭据。

只有显式运行下列命令才会启动 worker，普通 `serve` 不会自动领取问答任务：

```bash
uv run --frozen agentic-ai check-qa-worker --config /absolute/path/to/private-worker.json
uv run --frozen agentic-ai qa-worker --config /absolute/path/to/private-worker.json
```

预检读取固定私钥、聊天配置/账本和 PG 迁移/权限，不生成内容或预留费用；不证明代理/提供方可达。进程日志区分配置有效、READY、执行、中央不可达、结果未确认和停止；任务 ID 用于关联，正文与凭据不入日志。PID 存活与配置/依赖就绪是不同事实。SIGTERM 停止领取并取消有界在途等待，不把关闭误报为业务失败；未完成任务由中央租约接管。

私密 `WorkerConfig` 字段为 `tenant_id,tasks_endpoint,knowledge_endpoint,task_private_key,knowledge_private_key,chat_config,checkpoints`，最后一项为含 `dsn` 的 PG 配置。`ChatConfig` 字段见 [chat_config.py](src/agentic_ai/qa/chat_config.py)：必须明确获准型号、当前价格/核验日期、代理密钥和预算绝对路径，无默认提供方。本机配置已在忽略的 `.local/` 建立，使用 2026-10-21 官方峰值价格与累计账本；初始化幂等保留所有现存文件，缺失账本时拒绝重建。运行命令见[独立模型出口](../deploy/README.md#问答执行s5d真实聊天已验收)。

```bash
AGENTIC_QA_EXECUTION_IT=1 AGENTIC_CHECKPOINT_IT=1 uv run --frozen pytest -q
```

当前执行记录为 263 passed / 20 skipped：包含 5 项真实 PG/Tasks/网关与本机模型 TCP 联合测试、11 项旧恢复回归。20 项真实知识接口测试在前一单独阶段已通过，其中唯一的付费检索未重跑；随后真实三条业务路径使正式累计 Embedding 预留达 0.20 元/20 批，聊天累计预留 0.092600 元/4 次（含 2 次提供方探针）；均为独立 1 元上限内的保守预留，非账单。新增独立代理边界、真实业务路径与重启重放证据分别列示。证据及未验证项见 [S5D 验证](../docs/qa/knowledge-qa-execution-20261021/TEST_RESULT.md)。

## 研究与只读助手（S6）

`src/agentic_ai/agents/` 提供固定知识范围的工具模型适配与 `retrieve → analyze → verify`。analyze 使用真实 Deep Agents；仅搜索/读证据，无子 Agent、外部写工具或宿主文件访问。其内部不持久 checkpoint，根图继续使用原 FencedPostgresSaver，失败可能重做 analyze 并再次计费。原问答文本客户端及45秒限制不变；工作图120秒，每阶段最多3次模型、5次工具、2次额外搜索。

现有 `agentic-qa-worker` 顺序轮询两个 graph，一次一个任务；相同私密配置、模型路由和累计预算。真实 deepseek-flash 已验证 REPORT/EXPLAIN/CHECKLIST/DRAFT；空证据零聊天调用。界面不展示思维链，只展示实际阶段、最终分节结果与工具元数据。[契约](../docs/design/knowledge-first-20261020/WORK_TASK_CONTRACTS.md)和[验收](../docs/qa/research-readonly-20261021/TEST_RESULT.md)。

```bash
AGENTIC_WORK_IT=1 AGENTIC_QA_EXECUTION_IT=1 AGENTIC_CHECKPOINT_IT=1 uv run --frozen pytest -q
```

真实恢复测试使用隔离合成身份和受控模型，不产生模型费用。Deep Agents 无内部 saver 时显式设置 durability=exit，以阻止继承父图 sync 持久设置；锁定版本会提示 durability 无效，属于已知无害警告，未放宽恢复器或序列化限制。


## 持久质量评测（S8C）

`evaluation/` 通过独立服务身份读取冻结的评测定义、保存不可变候选及报告清单；Java仍拥有权限、发布版本和正式报告。LangGraph根图按题调用既有QaPipeline的检索、生成和引用复核；标准答案/评分标准不会进入模型提示。每题成功后保存候选引用到原PG检查点，恢复仅重做未提交的题，中央终态凭据决定正式报告。

LlamaIndex提供Recall/MRR适配；分级nDCG按已冻结公式计算。空召回应答题计0，无答案/执行失败不混入同一分母。真实费用未返回则记录未知，保守预留与供应商费用分开。每题事件/预算键包含题目身份，不复用第一题的操作键，也不向浏览器广播评测答案TEXT_DELTA。

新图只在配置 `evaluation_private_key` 后领取；初始化见 [本机运行](../deploy/README.md)。本图600秒/202步，最多100题；既有QA/work期限保持。`tests/test_evaluation_pipeline.py`为确定性图/公式验证，`test_evaluation_execution_integration.py`在显式本机开关下验证真实服务/PG，测试前暂停模型worker，结果不等于真实模型质量。

## 受控追问新图（S12A，接入中）

`contextual_pipeline.py`的独立`knowledge-qa-v3`在原检索前新增`prepare_query`，只读取指定成功前文的原问题及最多2000码点答案。一次改写最多等待5秒，预算、配置、权限与取消不允许回退；可回退的模型失败使用原问题。原问题保持不变，实际查询和原因保存在v3结果的`query`。完整依赖并集最多10条，当前引用仍最多5条；v1/v2图保留旧语义。

worker新增`contextual_qa_enabled=false`，开启还需`retrieval_enabled=true`。复用现有ChatPort、累计账本、45秒/32步和PG/中央fencing，无新增模型、密钥或预算。`tests/test_contextual_execution_integration.py`使用`AGENTIC_GENERATION_PG_IT=1`验证真实测试库与HTTP，包含改写/检索/生成后接管、失权和取消，模型仍为协议替身。当前未切换运行容器；正式边界见[C-QA2](../docs/design/knowledge-first-20261020/CONTEXTUAL_QA_CONTRACTS.md)。
