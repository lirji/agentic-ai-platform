"""受限HTTP实际ASGI路由验证，使用模型/候选替身而不消费提供方额度。"""

import asyncio
import json
from contextlib import asynccontextmanager

import httpx
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import FastAPI
from test_processing import token
from test_retrieval import Embedding, Knowledge

from agentic_ai.api.retrieval import MAX_SEARCH_BODY, RETRIEVE_SCOPE, retrieval_router
from agentic_ai.processing.identity import INTERNAL_HEADER, ServiceVerifier
from agentic_ai.retrieval.consumer import PublicRetrievalResult, RetrievalUseCase
from agentic_ai.retrieval.deadline import RetrievalCapacity
from agentic_ai.retrieval.errors import RetrievalError
from agentic_ai.runtime.retrieval_config import RetrievalHttpConfig

SEARCH = "/internal/v2/knowledge/search"
CAPABILITIES = "/internal/v2/knowledge/retrieval-capabilities"


@pytest.fixture(scope="module")
def key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def command(**updates):
    return {
        "schemaVersion": "knowledge-search-input-v2",
        "query": "核验当前资料",
        "documentIds": None,
        "topK": 5,
        "strategy": "LLAMA_RRF_V1",
        "timeoutMs": 30000,
        **updates,
    }


def api(key, embedding=None):
    embedding = embedding or Embedding()
    knowledge, owners, closed = Knowledge(), [], []

    @asynccontextmanager
    async def factory(owner):
        owners.append(owner)
        try:
            yield knowledge
        finally:
            closed.append(owner)

    app = FastAPI()
    app.include_router(
        retrieval_router(
            RetrievalUseCase(embedding, RetrievalCapacity(1)),
            ServiceVerifier(key.public_key()),
            factory,
        )
    )
    return app, embedding, knowledge, owners, closed


def headers(key, **claims):
    return {INTERNAL_HEADER: token(key, scope=RETRIEVE_SCOPE, **claims)}


def test_authenticated_search_projects_only_public_fields_and_scopes_each_request(key):
    async def scenario():
        app, embedding, _, owners, closed = api(key)
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            capabilities = await client.get(CAPABILITIES, headers=headers(key))
            assert capabilities.status_code == 200
            assert capabilities.json()["strategies"] == [
                {"strategy": "LLAMA_RRF_V1", "available": True, "reasonCode": None},
                {
                    "strategy": "LLAMA_RERANK_V1",
                    "available": False,
                    "reasonCode": "RETRIEVAL_RERANK_DISABLED",
                },
            ]
            assert not embedding.calls and not owners
            for owner in ("alice", "bob"):
                response = await client.post(
                    SEARCH, headers=headers(key, sub=owner), json=command()
                )
                assert response.status_code == 200, response.text
                result = PublicRetrievalResult.from_wire(response.content)
                assert result.mode == "RRF" and result.hits
                assert "contributions" not in response.text and "sourceRank" not in response.text
            assert [v.owner_id for v in owners] == ["alice", "bob"] and owners == closed
            count = len(embedding.calls)
            unavailable = await client.post(
                SEARCH, headers=headers(key), json=command(strategy="LLAMA_RERANK_V1")
            )
            assert (
                unavailable.status_code == 503
                and unavailable.json()["code"] == "RETRIEVAL_RERANK_DISABLED"
            )
            assert len(embedding.calls) == count

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "auth", ["missing", "prepare-role", "wrong-issuer", "wrong-audience", "duplicate"]
)
def test_retrieval_scope_cannot_be_replaced_by_other_roles_or_duplicate_headers(key, auth):
    async def scenario():
        app, embedding, _, owners, _ = api(key)
        selected = headers(key)
        if auth == "missing":
            selected = {}
        elif auth == "prepare-role":
            selected = {INTERNAL_HEADER: token(key)}
        elif auth == "wrong-issuer":
            selected = headers(key, iss="urn:agentic-ai:runtime-knowledge")
        elif auth == "wrong-audience":
            selected = headers(key, aud="agentic-ai-knowledge")
        else:
            selected = [(INTERNAL_HEADER, selected[INTERNAL_HEADER])] * 2
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            response = await client.post(SEARCH, headers=selected, json=command())
            assert response.status_code in (401, 403)
            assert not embedding.calls and not owners

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "mutation", ["missing-strategy", "float-topk", "owner", "duplicate", "too-large", "empty-range"]
)
def test_strict_input_rejects_ambiguity_before_any_model_call(key, mutation):
    async def scenario():
        app, embedding, _, owners, _ = api(key)
        body = command()
        if mutation == "missing-strategy":
            del body["strategy"]
        elif mutation == "float-topk":
            body["topK"] = 5.0
        elif mutation == "owner":
            body["ownerId"] = "someone-else"
        elif mutation == "empty-range":
            body["documentIds"] = []
        raw = json.dumps(body).encode()
        if mutation == "duplicate":
            raw = raw[:-1] + b',"strategy":"LLAMA_RERANK_V1"}'
        elif mutation == "too-large":
            raw = b" " * (MAX_SEARCH_BODY + 1)
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            response = await client.post(
                SEARCH, headers={**headers(key), "Content-Type": "application/json"}, content=raw
            )
            assert response.status_code == (413 if mutation == "too-large" else 400), response.text
            assert not embedding.calls and not owners

    asyncio.run(scenario())


def test_single_capacity_timeout_and_cancellation_release_request_scopes(key):
    async def scenario():
        gate, entered = asyncio.Event(), asyncio.Event()

        class Slow(Embedding):
            async def embed(self, texts):
                entered.set()
                return await super().embed(texts)

        app, embedding, _, owners, closed = api(key, Slow(gate))
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:

            async def request(budget_ms=30000):
                return await client.post(
                    SEARCH, headers=headers(key), json=command(timeoutMs=budget_ms)
                )

            pending = asyncio.create_task(request())
            await asyncio.wait_for(entered.wait(), 1)
            busy = await request()
            assert busy.status_code == 429 and len(embedding.calls) == 1
            pending.cancel()
            with pytest.raises(asyncio.CancelledError):
                await pending
            assert embedding.cancelled and owners == closed
            timeout = await request(10)
            assert timeout.status_code == 504 and owners == closed
            gate.set()
            assert (await request()).status_code == 200
            assert owners == closed

    asyncio.run(scenario())


def test_runtime_config_is_explicit_and_has_no_per_process_capacity_override():
    assert RetrievalHttpConfig.from_environment({}) is None
    valid = {
        "AI_RETRIEVAL_ENABLED": "true",
        "AI_SERVICE_PUBLIC_KEY": "/private/public.pem",
        "AI_RETRIEVAL_KNOWLEDGE_ENDPOINT": "http://127.0.0.1:28084",
        "AI_RETRIEVAL_PRIVATE_KEY": "/private/runtime.pem",
    }
    assert RetrievalHttpConfig.from_environment(valid).rerank_config is None
    for change in (
        {"AI_RETRIEVAL_ENABLED": "yes"},
        {"AI_RETRIEVAL_PRIVATE_KEY": "relative.pem"},
        {"AI_RETRIEVAL_KNOWLEDGE_ENDPOINT": "https://host/path"},
        {"AI_RETRIEVAL_KNOWLEDGE_ENDPOINT": "http://host"},
    ):
        with pytest.raises(RetrievalError):
            RetrievalHttpConfig.from_environment({**valid, **change})


def test_app_factory_shares_one_use_case_and_closes_owner_clients_and_embedding(
    monkeypatch, tmp_path, key
):
    from types import SimpleNamespace

    from cryptography.hazmat.primitives import serialization

    import agentic_ai.api.app as entrypoint
    import agentic_ai.retrieval.client as client_module
    import agentic_ai.retrieval.consumer as consumer_module
    from agentic_ai.runtime.processing_config import ProcessingConfig

    public = tmp_path / "public.pem"
    private = tmp_path / "private.pem"
    public.write_bytes(
        key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )
    )
    private.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    private.chmod(0o600)
    config = RetrievalHttpConfig(
        public_key_path=public,
        knowledge_private_key=private,
        knowledge_endpoint="http://127.0.0.1:28084",
    )
    monkeypatch.setattr(entrypoint.RetrievalHttpConfig, "from_environment", lambda: config)
    monkeypatch.setattr(
        entrypoint.ProcessingConfig,
        "from_environment",
        lambda: ProcessingConfig(public_key_path=public),
    )
    monkeypatch.setattr(
        entrypoint.EmbeddingConfig,
        "from_environment",
        lambda: SimpleNamespace(model="text-embedding-v4", dimensions=1024),
    )
    marker = object()
    monkeypatch.setattr(entrypoint.EmbeddingBudget, "from_environment", lambda: marker)
    embedding = Embedding()
    embedding.closed = False

    async def close():
        embedding.closed = True

    embedding.aclose = close

    def build_embedding(config, *, budget):
        assert budget is marker
        return embedding

    monkeypatch.setattr(entrypoint, "OpenAIEmbeddingAdapter", build_embedding)
    scopes, closed, instances = [], [], []

    class Scoped(Knowledge):
        def __init__(self, endpoint, tenant, owner, private_key):
            super().__init__()
            assert private_key.public_key().public_numbers() == key.public_key().public_numbers()
            self.owner = owner
            scopes.append(owner)

        async def aclose(self):
            closed.append(self.owner)

    monkeypatch.setattr(client_module, "RetrievalClient", Scoped)

    def use_case(*args):
        value = RetrievalUseCase(*args)
        instances.append(value)
        return value

    monkeypatch.setattr(consumer_module, "RetrievalUseCase", use_case)
    app = entrypoint.create_app()
    assert len(instances) == 1

    async def scenario():
        async with (
            app.router.lifespan_context(app),
            httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://test"
            ) as client,
        ):
            for owner in ("alice", "bob"):
                response = await client.post(
                    SEARCH, headers=headers(key, sub=owner), json=command()
                )
                assert response.status_code == 200, response.text
        assert embedding.closed and scopes == closed == ["alice", "bob"] and len(instances) == 1

    asyncio.run(scenario())


def test_enabled_http_cannot_start_without_existing_embedding_budget(monkeypatch):
    from types import SimpleNamespace

    import agentic_ai.api.app as entrypoint
    from agentic_ai.processing.errors import ProcessingError

    monkeypatch.setattr(entrypoint.ProcessingConfig, "from_environment", lambda: None)
    monkeypatch.setattr(entrypoint.RetrievalHttpConfig, "from_environment", lambda: object())
    monkeypatch.setattr(
        entrypoint.EmbeddingConfig,
        "from_environment",
        lambda: SimpleNamespace(model="text-embedding-v4", dimensions=1024),
    )
    monkeypatch.setattr(entrypoint.EmbeddingBudget, "from_environment", lambda: None)
    with pytest.raises(ProcessingError):
        entrypoint.create_app()


def test_shared_search_golden_comes_from_http_use_case_and_public_projection(key):
    from pathlib import Path

    fixture = json.loads(
        (
            Path(__file__).resolve().parents[2]
            / "knowledge-service/src/test/resources/search-consumer-golden.json"
        ).read_text()
    )

    async def scenario():
        app, _, _, _, _ = api(key)
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            response = await client.post(
                SEARCH, headers=headers(key), json={**fixture["input"], "timeoutMs": 30000}
            )
            assert response.status_code == 200
            actual = response.json()
            actual["elapsedMs"] = fixture["result"]["elapsedMs"]
            assert actual == fixture["result"]
            capabilities = await client.get(CAPABILITIES, headers=headers(key))
            assert capabilities.json() == fixture["capabilities"]

    asyncio.run(scenario())


def test_body_read_time_consumes_callers_remaining_budget_before_embedding(key):
    async def scenario():
        app, embedding, _, owners, closed = api(key)

        async def slow_body():
            yield b" "
            await asyncio.sleep(0.03)
            yield json.dumps(command(timeoutMs=10)).encode()

        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            response = await client.post(
                SEARCH,
                headers={**headers(key), "Content-Type": "application/json"},
                content=slow_body(),
            )
            assert response.status_code == 504
            assert response.json()["code"] == "RETRIEVAL_DEADLINE_EXCEEDED"
            assert not embedding.calls and owners == closed

    asyncio.run(scenario())
