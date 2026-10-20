"""以真实 HTTPX 请求/响应边界验证协议，测试向量不作为业务数据或质量证据。"""

import asyncio
import json

import httpx
import pytest

from agentic_ai.adapters.openai_embedding import OpenAIEmbeddingAdapter
from agentic_ai.rag.errors import EmbeddingError, EmbeddingErrorCode
from agentic_ai.runtime.embedding_config import EmbeddingConfig


def run_embed(config, handler, texts):
    async def invoke():
        async with OpenAIEmbeddingAdapter(
            config, transport=httpx.MockTransport(handler)
        ) as adapter:
            return await adapter.embed(texts)

    return asyncio.run(invoke())


def successful_response(count, dimensions=3):
    return httpx.Response(
        200,
        json={
            "model": "text-embedding-v4",
            "data": [{"index": i, "embedding": [0.25] * dimensions} for i in range(count)],
        },
    )


def test_batches_and_reorders_vectors(embedding_config):
    requests = []

    def handler(request):
        payload = json.loads(request.content)
        requests.append(payload)
        assert str(request.url) == "https://models.example.invalid/v1/embeddings"
        assert request.headers["authorization"] == "Bearer test-only-token"
        assert payload["model"] == "text-embedding-v4"
        assert payload["dimensions"] == 3
        assert payload["encoding_format"] == "float"
        return httpx.Response(
            200,
            json={
                "data": [
                    {"index": i, "embedding": [float(text)] * 3}
                    for i, text in reversed(list(enumerate(payload["input"])))
                ]
            },
        )

    result = run_embed(embedding_config, handler, [str(i) for i in range(21)])
    assert [len(request["input"]) for request in requests] == [10, 10, 1]
    assert [text for request in requests for text in request["input"]] == [
        str(i) for i in range(21)
    ]
    assert result.vectors == tuple((float(i),) * 3 for i in range(21))
    assert (result.model, result.dimensions) == ("text-embedding-v4", 3)


def test_default_1024_dimension_contract():
    config = EmbeddingConfig(
        base_url="https://models.example.invalid/v1", api_key="test-only-token"
    )
    result = run_embed(config, lambda request: successful_response(1, 1024), ["文档文本"])
    assert len(result.vectors[0]) == 1024


@pytest.mark.parametrize(
    "body",
    [
        b"not-json",
        b"[" * 2000 + b"]" * 2000,
        b"\xff",
        b"[]",
        b'{"data":[]}',
        b'{"data":[{"index":0,"embedding":[0,0,0]}]}',
        b'{"data":[{"index":0,"embedding":[0,0,0]},{"index":0,"embedding":[0,0,0]}]}',
        b'{"data":[{"index":0,"embedding":[0,0,0]},{"index":2,"embedding":[0,0,0]}]}',
        b'{"data":[{"index":false,"embedding":[0,0,0]},{"index":1,"embedding":[0,0,0]}]}',
        b'{"data":[{"index":0,"embedding":[0,0]},{"index":1,"embedding":[0,0,0]}]}',
        b'{"data":[{"index":0,"embedding":[true,0,0]},{"index":1,"embedding":[0,0,0]}]}',
        b'{"data":[{"index":0,"embedding":[NaN,0,0]},{"index":1,"embedding":[0,0,0]}]}',
        b'{"data":[{"index":0,"embedding":[1e400,0,0]},{"index":1,"embedding":[0,0,0]}]}',
        b'{"model":"wrong-model","data":[{"index":0,"embedding":[0,0,0]},'
        b'{"index":1,"embedding":[0,0,0]}]}',
    ],
)
def test_invalid_responses_fail_closed(embedding_config, body):
    with pytest.raises(EmbeddingError) as caught:
        run_embed(embedding_config, lambda request: httpx.Response(200, content=body), ["a", "b"])
    assert caught.value.code == EmbeddingErrorCode.RESPONSE_INVALID
    assert not caught.value.retryable
    assert caught.value.__context__ is None


@pytest.mark.parametrize("status", [302, 400, 401, 403, 429, 503])
def test_upstream_errors_do_not_leak_or_retry(embedding_config, status):
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(
            status,
            headers={"location": "https://elsewhere.invalid"},
            content=b"test-only-token private document content",
        )

    with pytest.raises(EmbeddingError) as caught:
        run_embed(embedding_config, handler, ["sensitive input"])
    expected = (
        EmbeddingErrorCode.UPSTREAM_UNAVAILABLE
        if status in (429, 503)
        else EmbeddingErrorCode.UPSTREAM_REJECTED
    )
    assert caught.value.code == expected
    assert caught.value.retryable == (status in (429, 503))
    assert len(calls) == 1
    assert "test-only-token" not in str(caught.value)
    assert "sensitive input" not in str(caught.value)
    assert "private document" not in str(caught.value)


def test_failed_second_batch_never_returns_partial_or_starts_third(embedding_config):
    calls = []

    def handler(request):
        calls.append(request)
        return successful_response(10) if len(calls) == 1 else httpx.Response(503)

    with pytest.raises(EmbeddingError) as caught:
        run_embed(embedding_config, handler, ["text"] * 21)
    assert caught.value.code == EmbeddingErrorCode.UPSTREAM_UNAVAILABLE
    assert len(calls) == 2


@pytest.mark.parametrize(
    "texts", [None, {}, [], "text", [""], ["  "], [None], ["\ud800"], ["a"] * 101]
)
def test_invalid_input_never_sends_request(embedding_config, texts):
    def forbidden(request):
        pytest.fail("invalid input must not reach the model")

    with pytest.raises(EmbeddingError) as caught:
        run_embed(embedding_config, forbidden, texts)
    assert caught.value.code == EmbeddingErrorCode.INPUT_INVALID
    assert caught.value.__context__ is None


def test_utf8_input_budget(embedding_config):
    config = embedding_config.model_copy(update={"max_input_bytes": 5})
    with pytest.raises(EmbeddingError) as caught:
        run_embed(config, lambda request: pytest.fail("input exceeded bytes"), ["中文"])
    assert caught.value.code == EmbeddingErrorCode.INPUT_INVALID


def test_response_budget_uses_actual_bytes(embedding_config):
    config = embedding_config.model_copy(update={"max_response_bytes": 10})
    with pytest.raises(EmbeddingError) as caught:
        run_embed(
            config,
            lambda request: httpx.Response(200, content=b"x" * 11, headers={"content-length": "1"}),
            ["text"],
        )
    assert caught.value.code == EmbeddingErrorCode.RESPONSE_INVALID


def test_transport_error_is_safe(embedding_config):
    def handler(request):
        raise httpx.ConnectError("private connection detail test-only-token", request=request)

    with pytest.raises(EmbeddingError) as caught:
        run_embed(embedding_config, handler, ["text"])
    assert caught.value.code == EmbeddingErrorCode.UPSTREAM_UNAVAILABLE
    assert caught.value.retryable
    assert "test-only-token" not in str(caught.value)
    assert caught.value.__context__ is None


def test_one_deadline_covers_all_batches(embedding_config):
    calls = []
    config = embedding_config.model_copy(update={"deadline_seconds": 0.25})

    async def handler(request):
        calls.append(request)
        await asyncio.sleep(0.15)
        return successful_response(10)

    with pytest.raises(EmbeddingError) as caught:
        run_embed(config, handler, ["text"] * 20)
    assert caught.value.code == EmbeddingErrorCode.UPSTREAM_UNAVAILABLE
    assert caught.value.retryable
    assert len(calls) == 2


def test_cancellation_propagates_and_stops_next_batch(embedding_config):
    async def scenario():
        started = asyncio.Event()
        calls = []

        async def handler(request):
            calls.append(request)
            started.set()
            await asyncio.Event().wait()
            raise AssertionError("unreachable after cancellation")

        async with OpenAIEmbeddingAdapter(
            embedding_config, transport=httpx.MockTransport(handler)
        ) as adapter:
            task = asyncio.create_task(adapter.embed(["text"] * 20))
            await asyncio.wait_for(started.wait(), timeout=1)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
            assert len(calls) == 1

    asyncio.run(scenario())
