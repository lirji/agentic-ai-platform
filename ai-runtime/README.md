# AI 运行时

当前实现 [S0](../docs/design/knowledge-first-20261020/IMPLEMENTATION_SLICES.md)：Python 3.12 包、存活端点、配置检查及内部真实 Embedding 客户端。契约权威为 [R01/H01](../docs/design/knowledge-first-20261020/CONTRACTS.md)。知识入库、问答、持久化任务和 React 页面尚未接通。

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

该端点只证明进程可响应，无需模型凭据。不存在声明业务可用的 readiness 端点。当前版本不安装尚未使用的 LangGraph、Deep Agents 或 LlamaIndex；三框架联合运行验证仍属于后续切片。

## 模型配置

配置键与无密钥样例见 [.env.example](.env.example)，程序只读取环境变量，不自动加载 `.env` 文件。通过本地环境或密钥管理工具注入 `RAG_EMBEDDING_BASE_URL`、`RAG_EMBEDDING_API_KEY` 后运行：

```bash
uv run --frozen agentic-ai check-config
```

默认 `provider=openai` 代表兼容协议，模型为百炼 `text-embedding-v4`，维度为 `1024`。基础地址应是账号所属地域的 HTTPS API 根地址（通常含兼容接口版本路径），客户端追加 `/embeddings`；账号、地域和网络可用性需单独核对。配置检查仅输出协议、模型和维度，不请求模型，也不证明凭据有效。

`hash`、未实现的提供方、缺失密钥和非法参数会明确失败。修改模型或维度需要另建匹配的索引配置，不能混用旧向量。真实模型调用和费用由后续集成任务授权及预算管理。

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
- `src/agentic_ai/runtime/`：有界配置；`api/` 与 `cli.py`：当前运行入口。
- `tests/`：配置、协议成功/失败、分批、时限、取消及真实本地 HTTP 进程测试。模型响应使用 HTTPX transport 替身，不能作为真实语义质量证据。

```bash
uv run --frozen ruff format --check src tests
uv run --frozen ruff check src tests
uv run --frozen mypy
uv run --frozen pytest -q
uv build --no-sources
```

`uv.lock` 锁定运行及开发依赖；构建后端由 `pyproject.toml` 声明。测试只绑定本机临时端口，不访问真实模型或旧业务服务。
