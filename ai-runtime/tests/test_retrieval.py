"""授权原语、原生LlamaIndex与一次RRF的行为验证；不调用付费模型。"""

import asyncio
import copy
import hashlib
import json
import time
from pathlib import Path

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from pydantic import ValidationError

from agentic_ai.rag.errors import EmbeddingError, EmbeddingErrorCode
from agentic_ai.rag.ports import EmbeddingResult
from agentic_ai.retrieval.client import RetrievalClient
from agentic_ai.retrieval.deadline import RetrievalCapacity, RetrievalDeadline
from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.models import (
    MAX_RESPONSE,
    SCHEMA,
    CandidatesReply,
    FusedHit,
    RetrievalHit,
    RetrievalInput,
)
from agentic_ai.retrieval.pipeline import RrfRetrievalPipeline, frozen_hits
from agentic_ai.retrieval.retriever import AuthorizedFusionRetriever

ROOT = Path(__file__).resolve().parents[2]
GOLDEN = json.loads(
    (ROOT / "docs/design/knowledge-first-20261020/retrieval-golden-v1.json").read_text()
)
TEXT = "同一正文不能合并不同资料。"
VECTOR = (0.125,) * 1024


def hit(alias):
    identity = GOLDEN["identityAliases"][alias]
    return {
        **identity,
        "jobId": identity["documentId"],
        "processorVersion": "text-v1-char512-overlap64-embedding-v4-1024",
        "text": TEXT,
        "textHash": hashlib.sha256(TEXT.encode()).hexdigest(),
        "source": {"displayName": "资料" + alias, "locator": f"utf8-bytes:0-{len(TEXT.encode())}"},
    }


def response(case=None):
    case = case or GOLDEN["cases"][0]
    return {
        "schemaVersion": SCHEMA,
        "candidateLimit": 20,
        "vectorCandidates": [
            {**hit(v["id"]), "sourceRank": v["rank"], "sourceScore": -0.5} for v in case["vector"]
        ],
        "lexicalCandidates": [
            {**hit(v["id"]), "sourceRank": v["rank"], "sourceScore": 3.5} for v in case["lexical"]
        ],
    }


def parsed(data=None):
    return CandidatesReply.from_wire(json.dumps(response() if data is None else data).encode())


def query():
    return RetrievalInput(query="核验当前资料", document_ids=None, top_k=2)


class Embedding:
    def __init__(self, gate=None):
        self.calls = []
        self.gate = gate
        self.cancelled = False

    async def embed(self, texts):
        self.calls.append(tuple(texts))
        try:
            if self.gate is not None:
                await self.gate.wait()
        except asyncio.CancelledError:
            self.cancelled = True
            raise
        return EmbeddingResult((VECTOR,), "text-embedding-v4", 1024)


class Knowledge:
    def __init__(self, candidates=None):
        self.reply = parsed() if candidates is None else candidates
        self.calls = []
        self.resolutions = []
        self.drift = False
        self.failure = None
        self.wait = None
        self.cancelled = False

    async def candidates(self, request, vector, timeout_ms):
        self.calls.append((request, vector, timeout_ms))
        try:
            if self.wait is not None:
                await self.wait.wait()
        except asyncio.CancelledError:
            self.cancelled = True
            raise
        if self.failure:
            raise RetrievalError(self.failure)
        return self.reply

    async def resolve(self, frozen, document_ids, timeout_ms):
        self.resolutions.append((frozen, document_ids, timeout_ms))
        if self.failure:
            raise RetrievalError(self.failure)
        if self.drift:
            return tuple(v.model_copy(update={"text": "不能替换冻结原文"}) for v in frozen)
        return frozen


@pytest.mark.parametrize("case", GOLDEN["cases"], ids=lambda case: case["id"])
def test_shared_golden_native_nodes_preserve_rank_gaps_identity_and_raw_scores(case):
    async def scenario():
        knowledge, embedding = Knowledge(parsed(response(case))), Embedding()
        result = await RrfRetrievalPipeline(embedding, RetrievalCapacity()).execute(
            query(), knowledge
        )
        expected = [GOLDEN["identityAliases"][name]["chunkId"] for name in case["expectedOrder"]]
        assert [v.chunk_id for v in result] == expected
        for value, alias in zip(result, case["expectedOrder"], strict=True):
            assert value.rrf_score == case["expectedScore"][alias]
            assert all(
                c.source_score == (-0.5 if c.route == "VECTOR" else 3.5)
                for c in value.contributions
            )
        assert (
            embedding.calls == [(query().query,)]
            and len(knowledge.calls) == len(knowledge.resolutions) == 1
        )
        assert knowledge.calls[0][1] is VECTOR
        assert len({v.identity().node_id() for v in result}) == 2
        encoded = json.dumps([v.model_dump(mode="json", by_alias=True) for v in result])
        assert [FusedHit.model_validate_json(json.dumps(v)) for v in json.loads(encoded)] == list(
            result
        )

    asyncio.run(scenario())


def test_framework_does_not_inherit_settings_llm_callbacks_or_global_content_events(monkeypatch):
    import llama_index.core as core
    import llama_index.core.settings as settings
    from llama_index.core.callbacks.base_handler import BaseCallbackHandler
    from llama_index_instrumentation import root_dispatcher
    from llama_index_instrumentation.event_handlers import BaseEventHandler

    events = []

    class Trap:
        def __getattribute__(self, name):
            raise AssertionError("不得读取全局Settings")

    class CallbackSpy(BaseCallbackHandler):
        def __init__(self):
            super().__init__([], [])

        def on_event_start(self, *args, **kwargs):
            raise AssertionError("全局回调不能收到正文")

        def on_event_end(self, *args, **kwargs):
            raise AssertionError("全局回调不能收到正文")

        def start_trace(self, *args, **kwargs):
            raise AssertionError("全局trace不能启动")

        def end_trace(self, *args, **kwargs):
            raise AssertionError("全局trace不能结束")

    class EventSpy(BaseEventHandler):
        def handle(self, event, **kwargs):
            events.append(event)

    monkeypatch.setattr(settings, "Settings", Trap())
    monkeypatch.setattr(core, "global_handler", CallbackSpy())
    monkeypatch.setattr(root_dispatcher, "event_handlers", [EventSpy()])

    async def scenario():
        retriever = AuthorizedFusionRetriever(
            Knowledge(), query(), VECTOR, RetrievalDeadline.start(None)
        )
        assert not retriever.callback_manager.handlers
        nodes = await retriever.aretrieve(query().query)
        assert len(nodes) == 2 and not events
        with pytest.raises(RetrievalError) as error:
            retriever.retrieve(query().query)
        assert error.value.code == RetrievalCode.CONFIG_INVALID
        nodes[0].node.metadata["rrfScore"] = 1.0
        with pytest.raises(RetrievalError):
            frozen_hits(nodes)

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "code",
    [
        RetrievalCode.KNOWLEDGE_UNAVAILABLE,
        RetrievalCode.AUTH_REJECTED,
        RetrievalCode.EVIDENCE_REJECTED,
    ],
)
def test_candidate_failure_is_not_empty_or_a_fallback(code):
    async def scenario():
        knowledge, embedding = Knowledge(), Embedding()
        knowledge.failure = code
        with pytest.raises(RetrievalError) as error:
            await RrfRetrievalPipeline(embedding, RetrievalCapacity()).execute(query(), knowledge)
        assert error.value.code == code and len(embedding.calls) == len(knowledge.calls) == 1
        assert not knowledge.resolutions

    asyncio.run(scenario())


def test_empty_candidates_and_final_drift_are_distinct():
    async def scenario():
        empty = response()
        empty["vectorCandidates"] = []
        empty["lexicalCandidates"] = []
        knowledge = Knowledge(parsed(empty))
        assert (
            await RrfRetrievalPipeline(Embedding(), RetrievalCapacity()).execute(query(), knowledge)
            == ()
        )
        assert not knowledge.resolutions
        changed = Knowledge()
        changed.drift = True
        with pytest.raises(RetrievalError) as error:
            await RrfRetrievalPipeline(Embedding(), RetrievalCapacity()).execute(query(), changed)
        assert error.value.code == RetrievalCode.EVIDENCE_REJECTED and len(changed.resolutions) == 1

    asyncio.run(scenario())


@pytest.mark.parametrize("phase", ["embedding", "candidate"])
def test_stage_timeout_cancels_dependency_and_stops_later_steps(monkeypatch, phase):
    import agentic_ai.retrieval.pipeline as pipeline

    monkeypatch.setattr(
        pipeline, "EMBEDDING_SECONDS" if phase == "embedding" else "CANDIDATE_SECONDS", 0.02
    )

    async def scenario():
        gate = asyncio.Event()
        embedding = Embedding(gate if phase == "embedding" else None)
        knowledge = Knowledge()
        knowledge.wait = gate if phase == "candidate" else None
        with pytest.raises(RetrievalError) as error:
            await RrfRetrievalPipeline(embedding, RetrievalCapacity()).execute(query(), knowledge)
        assert error.value.code == RetrievalCode.DEADLINE_EXCEEDED
        assert not knowledge.resolutions
        assert embedding.cancelled if phase == "embedding" else knowledge.cancelled

    asyncio.run(scenario())


def test_root_remaining_cannot_be_refilled_and_expired_root_never_embeds():
    async def scenario():
        embedding, knowledge = Embedding(), Knowledge()
        pipeline = RrfRetrievalPipeline(embedding, RetrievalCapacity())
        with pytest.raises(RetrievalError):
            await pipeline.execute(
                query(), knowledge, root_deadline=asyncio.get_running_loop().time() - 1
            )
        assert not embedding.calls and not knowledge.calls
        knowledge.wait = asyncio.Event()
        with pytest.raises(RetrievalError) as error:
            await pipeline.execute(
                query(), knowledge, root_deadline=asyncio.get_running_loop().time() + 0.04
            )
        assert error.value.code == RetrievalCode.DEADLINE_EXCEEDED
        assert 0 < knowledge.calls[0][2] < 40 and knowledge.cancelled and not knowledge.resolutions

    asyncio.run(scenario())


def test_two_application_permits_reject_without_queue_and_cancel_releases():
    async def scenario():
        gate = asyncio.Event()
        embedding = Embedding(gate)
        capacity = RetrievalCapacity()
        pipeline = RrfRetrievalPipeline(embedding, capacity)
        one = asyncio.create_task(pipeline.execute(query(), Knowledge()))
        two = asyncio.create_task(pipeline.execute(query(), Knowledge()))
        for _ in range(20):
            if len(embedding.calls) == 2:
                break
            await asyncio.sleep(0)
        assert len(embedding.calls) == 2
        try:
            with pytest.raises(RetrievalError) as error:
                await pipeline.execute(query(), Knowledge())
            assert error.value.code == RetrievalCode.CAPACITY_EXCEEDED and len(embedding.calls) == 2
            one.cancel()
            with pytest.raises(asyncio.CancelledError):
                await one
            assert (
                len(await RrfRetrievalPipeline(Embedding(), capacity).execute(query(), Knowledge()))
                == 2
            )
        finally:
            gate.set()
            await asyncio.gather(one, two, return_exceptions=True)

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "value",
    [
        EmbeddingResult((VECTOR,), "wrong", 1024),
        EmbeddingResult((VECTOR,), "text-embedding-v4", 1),
        EmbeddingResult(((0.0,) * 1024,), "text-embedding-v4", 1024),
        EmbeddingResult(((float("nan"),) * 1024,), "text-embedding-v4", 1024),
    ],
)
def test_embedding_response_must_match_indices_before_candidate_http(value):
    class Invalid:
        async def embed(self, texts):
            return value

    async def scenario():
        knowledge = Knowledge()
        with pytest.raises(EmbeddingError) as error:
            await RrfRetrievalPipeline(Invalid(), RetrievalCapacity()).execute(query(), knowledge)
        assert error.value.code == EmbeddingErrorCode.RESPONSE_INVALID and not knowledge.calls

    asyncio.run(scenario())


@pytest.fixture(scope="module")
def key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def test_client_signs_fixed_identity_and_resolves_only_full_identities(key):
    async def scenario():
        calls = []

        async def handle(request):
            claims = jwt.decode(
                request.headers["X-Internal-Token"],
                key.public_key(),
                algorithms=["RS256"],
                audience="agentic-ai-knowledge",
                issuer="urn:agentic-ai:runtime-knowledge",
            )
            assert claims["tenant_id"] == "tenant" and claims["sub"] == "owner"
            assert claims["azp"] == "ai-runtime-worker" and claims["scope"] == "knowledge:read"
            assert claims["exp"] - claims["iat"] == 60 and claims["iat"] <= int(time.time())
            body = json.loads(request.content)
            calls.append((request.url.path, body))
            assert "tenantId" not in body and "ownerId" not in body
            if request.url.path.endswith("/candidates"):
                assert (
                    len(body) == 8
                    and body["vector"] == list(VECTOR)
                    and body["candidateTarget"] == 2
                )
                return httpx.Response(200, json=response())
            assert len(body) == 4 and all(
                len(value) == 8 and "text" not in value for value in body["candidates"]
            )
            return httpx.Response(200, json={"schemaVersion": SCHEMA, "hits": [hit("a"), hit("b")]})

        client = RetrievalClient(
            "http://127.0.0.1:28084", "tenant", "owner", key, transport=httpx.MockTransport(handle)
        )
        try:
            result = await RrfRetrievalPipeline(Embedding(), RetrievalCapacity()).execute(
                query(), client
            )
            assert len(result) == 2
            assert [path for path, _ in calls] == [
                "/internal/v2/knowledge/retrieval/candidates",
                "/internal/v2/knowledge/retrieval/resolve",
            ]
        finally:
            await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "status,code",
    [
        (401, RetrievalCode.AUTH_REJECTED),
        (403, RetrievalCode.AUTH_REJECTED),
        (404, RetrievalCode.EVIDENCE_REJECTED),
        (429, RetrievalCode.CAPACITY_EXCEEDED),
        (503, RetrievalCode.KNOWLEDGE_UNAVAILABLE),
    ],
)
def test_client_http_failures_do_not_retry_or_preserve_private_response(key, status, code):
    async def scenario():
        calls = []

        async def handle(request):
            calls.append(request)
            return httpx.Response(status, text="SECRET-response")

        client = RetrievalClient(
            "http://127.0.0.1:28084", "tenant", "owner", key, transport=httpx.MockTransport(handle)
        )
        try:
            with pytest.raises(RetrievalError) as error:
                await client.candidates(query(), VECTOR, 1000)
            assert error.value.code == code and len(calls) == 1
            assert "SECRET" not in str(error.value) and error.value.__context__ is None
        finally:
            await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "kind",
    [
        "missing",
        "unknown",
        "duplicate-json",
        "snake-case",
        "float-limit",
        "duplicate-hit",
        "reordered-rank",
        "cross-source-drift",
        "hash",
        "score-string",
        "score-infinite",
        "oversize",
    ],
)
def test_untrusted_candidate_response_is_strict_and_bounded(key, kind):
    data = response()
    if kind == "missing":
        del data["schemaVersion"]
    if kind == "unknown":
        data["tenantId"] = "other"
    if kind == "snake-case":
        data["candidate_limit"] = data.pop("candidateLimit")
    if kind == "float-limit":
        data["candidateLimit"] = 20.0
    if kind == "duplicate-hit":
        data["vectorCandidates"][1] = copy.deepcopy(data["vectorCandidates"][0])
    if kind == "reordered-rank":
        data["vectorCandidates"].reverse()
    if kind == "cross-source-drift":
        data["lexicalCandidates"][1]["source"]["displayName"] = "漂移"
    if kind == "hash":
        data["vectorCandidates"][0]["textHash"] = "f" * 64
    if kind == "score-string":
        data["vectorCandidates"][0]["sourceScore"] = "1.0"
    if kind == "score-infinite":
        data["vectorCandidates"][0]["sourceScore"] = float("inf")
    body = json.dumps(data)
    if kind == "duplicate-json":
        body = '{"schemaVersion":"knowledge-retrieval-v1",' + body[1:]
    if kind == "oversize":
        body = " " * (MAX_RESPONSE + 1)

    async def scenario():
        async def handle(request):
            return httpx.Response(200, content=body)

        client = RetrievalClient(
            "http://127.0.0.1:28084", "tenant", "owner", key, transport=httpx.MockTransport(handle)
        )
        try:
            with pytest.raises(RetrievalError) as error:
                await client.candidates(query(), VECTOR, 1000)
            assert (
                error.value.code == RetrievalCode.EVIDENCE_REJECTED
                and error.value.__context__ is None
            )
        finally:
            await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize("kind", ["missing", "reordered", "text", "source", "generation"])
def test_final_client_resolution_rejects_drift_and_partial_success(key, kind):
    values = [hit("a"), hit("b")]
    frozen = tuple(RetrievalHit.model_validate(value) for value in values)
    if kind == "missing":
        values.pop()
    if kind == "reordered":
        values.reverse()
    if kind == "text":
        values[0]["text"] = "新正文"
        values[0]["textHash"] = hashlib.sha256("新正文".encode()).hexdigest()
    if kind == "source":
        values[0]["source"]["displayName"] = "新名称"
    if kind == "generation":
        values[0]["indexGeneration"] += 1

    async def scenario():
        async def handle(request):
            return httpx.Response(200, json={"schemaVersion": SCHEMA, "hits": values})

        client = RetrievalClient(
            "http://127.0.0.1:28084", "tenant", "owner", key, transport=httpx.MockTransport(handle)
        )
        try:
            with pytest.raises(RetrievalError) as error:
                await client.resolve(frozen, None, 1000)
            assert error.value.code == RetrievalCode.EVIDENCE_REJECTED
        finally:
            await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "updates",
    [
        {"query": " "},
        {"query": "\ud800"},
        {"query": "x" * 2001},
        {"document_ids": ()},
        {"document_ids": (GOLDEN["identityAliases"]["a"]["documentId"],) * 2},
        {"top_k": True},
        {"top_k": 21},
        {"tenantId": "forged"},
    ],
)
def test_input_rejects_ambiguous_scope_and_ownership(updates):
    with pytest.raises(ValidationError):
        RetrievalInput.model_validate({**query().model_dump(), **updates})


def test_same_text_different_versions_keep_full_node_identity():
    async def scenario():
        data = response(GOLDEN["cases"][1])
        data["vectorCandidates"][1]["documentId"] = data["vectorCandidates"][0]["documentId"]
        data["vectorCandidates"][1]["version"] = 2
        data["vectorCandidates"][1]["indexGeneration"] = 2
        result = await RrfRetrievalPipeline(Embedding(), RetrievalCapacity()).execute(
            query(), Knowledge(parsed(data))
        )
        assert [item.version for item in result] == [1, 2]
        assert len({item.identity().node_id() for item in result}) == 2

    asyncio.run(scenario())


def test_http_timeout_cancels_stream_and_does_not_retry(key):
    async def scenario():
        calls = []
        cancelled = asyncio.Event()

        async def handle(request):
            calls.append(request)
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()

        client = RetrievalClient(
            "http://127.0.0.1:28084", "tenant", "owner", key, transport=httpx.MockTransport(handle)
        )
        try:
            with pytest.raises(RetrievalError) as error:
                await client.candidates(query(), VECTOR, 20)
            assert error.value.code == RetrievalCode.DEADLINE_EXCEEDED
            assert len(calls) == 1 and cancelled.is_set() and error.value.__context__ is None
        finally:
            await client.aclose()

    asyncio.run(scenario())
