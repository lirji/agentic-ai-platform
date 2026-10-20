"""验证跨语言准备边界；测试向量只存在测试中，不代表真实模型质量。"""

import asyncio
import json
import time
from pathlib import Path
from uuid import uuid4

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import FastAPI
from pydantic import ValidationError

from agentic_ai.api.processing import processing_router
from agentic_ai.processing.errors import ProcessingCode, ProcessingError
from agentic_ai.processing.identity import (
    INTERNAL_HEADER,
    PREPARE_SCOPE,
    QUERY_SCOPE,
    SERVICE_AUDIENCE,
    SERVICE_ISSUER,
    SERVICE_PARTY,
    ServiceVerifier,
)
from agentic_ai.processing.models import (
    EMBEDDING_DIMENSIONS,
    EMBEDDING_MODEL,
    MAX_BODY_BYTES,
    PrepareRequest,
    digest,
)
from agentic_ai.processing.pipeline import PreparationPipeline
from agentic_ai.rag.ports import EmbeddingResult
from agentic_ai.runtime.processing_config import ProcessingConfig


class TestEmbedding:
    """仅用来观察校验、顺序、取消；产品入口始终装配真实 HTTP 适配器。"""

    __test__ = False

    def __init__(self):
        self.inputs = []
        self.result = None
        self.gate = None
        self.entered = None
        self.cancelled = False

    async def embed(self, texts):
        self.inputs.append(tuple(texts))
        if self.entered:
            self.entered.set()
        if self.gate:
            try:
                await self.gate.wait()
            except asyncio.CancelledError:
                self.cancelled = True
                raise
        return self.result or EmbeddingResult(
            tuple((0.125,) * EMBEDDING_DIMENSIONS for _ in texts),
            EMBEDDING_MODEL,
            EMBEDDING_DIMENSIONS,
        )


def command(text="知识平台将原文与发布版本分开管理。"):
    return PrepareRequest(
        tenant_id="tenant-a",
        owner_id="owner-a",
        document_id=str(uuid4()),
        job_id=str(uuid4()),
        document_version=1,
        source_hash=digest(text.encode()),
        content_type="text/plain",
        source_text=text,
    )


def test_java_golden_package_matches_actual_llamaindex():
    """同一固定协议包同时由 Java 读取和真实分块器重算，防止两端 Unicode/位置规则漂移。"""
    fixture = (
        Path(__file__).parents[2] / "knowledge-service/src/test/resources/processing-golden.json"
    )
    golden = json.loads(fixture.read_text())
    request = PrepareRequest(
        tenant_id=golden["prepared"]["tenantId"],
        owner_id=golden["prepared"]["ownerId"],
        document_id=golden["prepared"]["documentId"],
        job_id=golden["prepared"]["jobId"],
        document_version=1,
        source_hash=digest(golden["sourceText"].encode()),
        content_type="text/plain",
        source_text=golden["sourceText"],
    )
    prepared = asyncio.run(PreparationPipeline(TestEmbedding()).prepare(request))
    assert prepared.model_dump(mode="json", by_alias=True) == golden["prepared"]


@pytest.fixture(scope="module")
def signing_key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def token(key, **changes):
    now = int(time.time())
    claims = {
        "iss": SERVICE_ISSUER,
        "aud": SERVICE_AUDIENCE,
        "azp": SERVICE_PARTY,
        "iat": now,
        "nbf": now,
        "exp": now + 60,
        "sub": "owner-a",
        "tenant_id": "tenant-a",
        "scope": f"{PREPARE_SCOPE} {QUERY_SCOPE}",
        **changes,
    }
    return jwt.encode(claims, key, algorithm="RS256")


def api(embedding, signing_key, *, deadline=40):
    app = FastAPI()
    app.include_router(
        processing_router(
            PreparationPipeline(embedding),
            ServiceVerifier(signing_key.public_key()),
            ProcessingConfig(
                public_key_path=Path("/unused-public.pem"),
                max_concurrent=1,
                deadline_seconds=deadline,
            ),
        )
    )
    return app


@pytest.mark.parametrize(
    "text",
    [
        " 中文开头。\r\n第二行带 emoji 🐋 和 e\u0301。 \n" * 30,
        "相同段落。" * 250,
        "甲" * 1200,
        "one two three. Four five.\n\n" * 80,
        "\ufeff资料第一行。\n后续资料。",
    ],
)
def test_positions_and_stable_package(text):
    async def scenario():
        embedding = TestEmbedding()
        pipeline = PreparationPipeline(embedding)
        request = command(text)
        first, second = await pipeline.prepare(request), await pipeline.prepare(request)
        assert first == second
        assert 1 <= len(first.chunks) <= 100
        previous = -1
        for chunk in first.chunks:
            assert text.encode()[chunk.start_byte : chunk.end_byte].decode() == chunk.text
            assert chunk.start_byte > previous
            previous = chunk.start_byte
            assert len(chunk.vector) == 1024
            assert digest(chunk.text.encode()) == chunk.text_hash
        other = request.model_copy(update={"tenant_id": "tenant-b"})
        assert (await pipeline.prepare(other)).chunks[0].chunk_id != first.chunks[0].chunk_id

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "bad",
    [
        EmbeddingResult((), EMBEDDING_MODEL, 1024),
        EmbeddingResult(((1.0,) * 1023,), EMBEDDING_MODEL, 1024),
        EmbeddingResult(((float("nan"),) * 1024,), EMBEDDING_MODEL, 1024),
        EmbeddingResult(((True,) * 1024,), EMBEDDING_MODEL, 1024),
        EmbeddingResult(((0.1,) * 1024,), "wrong-model", 1024),
        EmbeddingResult(((0.1,) * 1024,), EMBEDDING_MODEL, 3),
    ],
)
def test_invalid_vectors_cannot_cross_preparation_boundary(bad):
    async def scenario():
        embedding = TestEmbedding()
        embedding.result = bad
        with pytest.raises(ProcessingError) as caught:
            await PreparationPipeline(embedding).prepare(command())
        assert caught.value.code == ProcessingCode.RESULT_INVALID

    asyncio.run(scenario())


def test_too_many_chunks_never_reach_model():
    async def scenario():
        embedding = TestEmbedding()
        with pytest.raises(ProcessingError) as caught:
            await PreparationPipeline(embedding).prepare(command("文" * 60000))
        assert caught.value.code == ProcessingCode.INPUT_TOO_LARGE
        assert not embedding.inputs

    asyncio.run(scenario())


@pytest.mark.parametrize(
    ("claim", "value", "expected"),
    [
        ("iss", "urn:untrusted", 401),
        ("aud", "agentic-ai-knowledge", 401),
        ("azp", "edge", 401),
        ("exp", 1, 401),
        ("exp", int(time.time()) + 3600, 401),
        ("iat", True, 401),
        ("tenant_id", "../other", 401),
        ("scope", "knowledge:read", 403),
        ("sub", "owner-b", 403),
        ("tenant_id", "tenant-b", 403),
    ],
)
def test_identity_is_checked_before_model(signing_key, claim, value, expected):
    async def scenario():
        embedding = TestEmbedding()
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=api(embedding, signing_key)), base_url="http://test"
        ) as client:
            response = await client.post(
                "/internal/v1/knowledge/prepare",
                headers={INTERNAL_HEADER: token(signing_key, **{claim: value})},
                json=command().model_dump(by_alias=True),
            )
            assert response.status_code == expected
            assert not embedding.inputs
            assert set(response.json()) == {"code", "message", "traceId"}

    asyncio.run(scenario())


def test_missing_and_forged_signatures(signing_key):
    async def scenario():
        embedding = TestEmbedding()
        forged = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=api(embedding, signing_key)), base_url="http://test"
        ) as client:
            for headers in ({}, {INTERNAL_HEADER: token(forged)}):
                response = await client.post(
                    "/internal/v1/knowledge/prepare",
                    headers=headers,
                    json=command().model_dump(by_alias=True),
                )
                assert response.status_code == 401
        assert not embedding.inputs

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "change",
    [
        {"sourceHash": "0" * 64},
        {"documentVersion": True},
        {"documentVersion": "1"},
        {"sourceText": "私密资料：\x00 不得显示"},
        {"sourceText": "\ud800"},
        {"unused": "secret-should-not-appear"},
        {"documentId": "not-a-uuid"},
    ],
)
def test_bad_requests_have_safe_errors(signing_key, change):
    async def scenario():
        embedding = TestEmbedding()
        data = {**command().model_dump(by_alias=True), **change}
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=api(embedding, signing_key)), base_url="http://test"
        ) as client:
            response = await client.post(
                "/internal/v1/knowledge/prepare",
                headers={
                    INTERNAL_HEADER: token(signing_key),
                    "Content-Type": "application/json",
                },
                content=json.dumps(data),
            )
            assert response.status_code == 400
            assert "sourceText" not in response.text
            assert "secret-should-not-appear" not in response.text
            assert not embedding.inputs

    asyncio.run(scenario())


def test_success_query_and_prepare_and_actual_body_limit(signing_key):
    async def scenario():
        embedding = TestEmbedding()
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=api(embedding, signing_key)), base_url="http://test"
        ) as client:
            headers = {INTERNAL_HEADER: token(signing_key)}
            result = await client.post(
                "/internal/v1/knowledge/prepare",
                headers=headers,
                json=command().model_dump(by_alias=True),
            )
            assert result.status_code == 200
            assert len(result.json()["chunks"][0]["vector"]) == 1024
            result = await client.post(
                "/internal/v1/knowledge/embed-query",
                headers=headers,
                json={"tenantId": "tenant-a", "ownerId": "owner-a", "query": "版本是什么？"},
            )
            assert result.status_code == 200
            assert len(result.json()["vector"]) == 1024
            result = await client.post(
                "/internal/v1/knowledge/prepare",
                headers={**headers, "Content-Type": "application/json"},
                content=b" " * (MAX_BODY_BYTES + 1),
            )
            assert result.status_code == 413

    asyncio.run(scenario())


def test_capacity_timeout_and_cancellation_release_slots(signing_key):
    async def scenario():
        embedding = TestEmbedding()
        embedding.gate, embedding.entered = asyncio.Event(), asyncio.Event()
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=api(embedding, signing_key, deadline=1)),
            base_url="http://test",
        ) as client:

            async def request():
                return await client.post(
                    "/internal/v1/knowledge/prepare",
                    headers={INTERNAL_HEADER: token(signing_key)},
                    json=command().model_dump(by_alias=True),
                )

            first = asyncio.create_task(request())
            await asyncio.wait_for(embedding.entered.wait(), 1)
            assert (await request()).status_code == 429
            assert (await first).status_code == 503
            assert embedding.cancelled
            embedding.entered.clear()
            second = asyncio.create_task(request())
            await asyncio.wait_for(embedding.entered.wait(), 1)
            second.cancel()
            with pytest.raises(asyncio.CancelledError):
                await second
            embedding.gate.set()
            assert (await request()).status_code == 200

    asyncio.run(scenario())


def test_processing_config_fails_closed():
    assert ProcessingConfig.from_environment({}) is None
    for environment in (
        {"AI_PROCESSING_ENABLED": "typo"},
        {"AI_PROCESSING_ENABLED": "true"},
        {
            "AI_PROCESSING_ENABLED": "true",
            "AI_SERVICE_PUBLIC_KEY": "/key",
            "AI_PROCESSING_MAX_CONCURRENT": "0",
        },
    ):
        with pytest.raises(ProcessingError) as caught:
            ProcessingConfig.from_environment(environment)
        assert caught.value.code == ProcessingCode.CONFIGURATION_INVALID
    with pytest.raises(ValidationError):
        command().model_copy().model_validate({**command().model_dump(), "document_version": False})


def test_http_disconnect_cancels_inflight_model(signing_key):
    async def scenario():
        embedding = TestEmbedding()
        embedding.gate, embedding.entered = asyncio.Event(), asyncio.Event()
        app = api(embedding, signing_key)
        incoming = asyncio.Queue()
        incoming.put_nowait(
            {
                "type": "http.request",
                "body": command().model_dump_json(by_alias=True).encode(),
                "more_body": False,
            }
        )
        sent = []

        async def send(message):
            sent.append(message)

        scope = {
            "type": "http",
            "asgi": {"version": "3.0"},
            "method": "POST",
            "scheme": "http",
            "path": "/internal/v1/knowledge/prepare",
            "raw_path": b"/internal/v1/knowledge/prepare",
            "query_string": b"",
            "headers": [
                (b"content-type", b"application/json"),
                (b"x-internal-token", token(signing_key).encode()),
            ],
            "client": ("127.0.0.1", 1234),
            "server": ("127.0.0.1", 8000),
        }
        running = asyncio.create_task(app(scope, incoming.get, send))
        await asyncio.wait_for(embedding.entered.wait(), 1)
        incoming.put_nowait({"type": "http.disconnect"})
        await asyncio.wait_for(running, 1)
        assert embedding.cancelled
        assert sent[0]["status"] == 400

    asyncio.run(scenario())
