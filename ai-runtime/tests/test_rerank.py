"""重排协议、原生后处理及独立持久预算；所有提供方调用均为协议替身。"""

import asyncio
import fcntl
import hashlib
import json
import threading
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import UUID

import httpx
import pytest
from pydantic import ValidationError

from agentic_ai.rag.ports import EmbeddingResult
from agentic_ai.retrieval.deadline import RetrievalCapacity, RetrievalDeadline
from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.models import CandidatesReply, RetrievalInput, RetrievalStrategy
from agentic_ai.retrieval.pipeline import RrfRetrievalPipeline
from agentic_ai.retrieval.rerank_budget import RerankBudget
from agentic_ai.retrieval.rerank_client import RerankClient
from agentic_ai.retrieval.rerank_config import RerankConfig
from agentic_ai.retrieval.rerank_models import (
    RERANK_BUDGET_SCOPE,
    RERANK_PATH,
    RERANK_RESPONSE_BYTES,
    ModelScores,
    RerankResult,
    RerankUsage,
    ScoredIndex,
)
from agentic_ai.retrieval.rerank_pipeline import RerankRetrievalPipeline

TEXT = "授权正文仅发给受控重排协议替身。"


def request():
    return RetrievalInput(
        query="核验内容", document_ids=None, top_k=2, strategy=RetrievalStrategy.RERANK
    )


def candidates(count=60):
    values = []
    for index in range(count):
        values.append(
            {
                "documentId": str(UUID(int=index + 1)),
                "version": 1,
                "indexGeneration": 1,
                "jobId": str(UUID(int=index + 1)),
                "processorVersion": "text-v1",
                "chunkId": hashlib.sha256(str(index).encode()).hexdigest(),
                "batchDigest": "b" * 64,
                "textHash": hashlib.sha256(TEXT.encode()).hexdigest(),
                "text": TEXT,
                "source": {"displayName": "资料", "locator": "utf8-bytes:0-1"},
                "sourceRank": index + 1,
                "sourceScore": 0.25,
            }
        )
    return CandidatesReply.from_wire(
        json.dumps(
            {
                "schemaVersion": "knowledge-retrieval-v1",
                "candidateLimit": 60,
                "vectorCandidates": values,
                "lexicalCandidates": [],
            }
        ).encode()
    )


class Embedding:
    def __init__(self):
        self.calls = 0

    async def embed(self, texts):
        self.calls += 1
        return EmbeddingResult(((0.125,) * 1024,), "text-embedding-v4", 1024)


class Knowledge:
    def __init__(self, count=60, reject=0):
        self.reply = candidates(count)
        self.resolved = []
        self.requests = []
        self.reject = reject

    async def candidates(self, query, vector, timeout_ms):
        self.requests.append((query, timeout_ms))
        return self.reply

    async def resolve(self, frozen, document_ids, timeout_ms):
        self.resolved.append(frozen)
        if self.reject == len(self.resolved):
            raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
        return frozen


class Scorer:
    def __init__(self, failure=None, gate=None, equal=False):
        self.calls = []
        self.failure, self.gate, self.equal = failure, gate, equal
        self.cancelled = False
        self.started = asyncio.Event()

    async def check_available(self):
        if self.failure == RetrievalCode.RERANK_DISABLED:
            raise RetrievalError(self.failure)

    async def score(self, query, documents, deadline):
        self.calls.append((query, documents, deadline))
        self.started.set()
        try:
            if self.gate is not None:
                await self.gate.wait()
        except asyncio.CancelledError:
            self.cancelled = True
            raise
        if self.failure:
            raise RetrievalError(self.failure)
        return ModelScores(
            scores=tuple(
                ScoredIndex(index=i, score=0.5 if self.equal else i / len(documents))
                for i in range(len(documents))
            ),
            usage=RerankUsage(
                model="qwen3-rerank",
                total_tokens=10,
                reserved_cny="0.010000",
                reservation_id=str(UUID(int=1)),
                elapsed_ms=1,
            ),
        )


def config_and_ledger(tmp_path, *, limit="1", enabled=True):
    config = RerankConfig(
        enabled=enabled,
        endpoint="https://protocol.cn-beijing.maas.aliyuncs.com",
        api_key="protocol-secret",
        model="qwen3-rerank",
        region="cn-beijing",
        budget_file=tmp_path / "rerank.json",
        budget_limit_cny=Decimal(limit),
        input_price_cny_per_million=Decimal("1"),
        pricing_checked_at=datetime.now(UTC).date(),
        pricing_reference="https://help.aliyun.com/zh/model-studio/model-pricing",
    )
    ledger = {
        "version": 1,
        "scope": RERANK_BUDGET_SCOPE,
        "currency": "CNY",
        "configFingerprint": config.budget_fingerprint(),
        "limitCny": limit,
        "reservedCny": "0",
        "calls": [],
    }
    config.budget_file.write_text(json.dumps(ledger))
    config.budget_file.chmod(0o600)
    return config


def provider_reply(count=2):
    return {
        "request_id": "protocol-request",
        "output": {"results": [{"index": i, "relevance_score": i / count} for i in range(count)]},
        "usage": {"total_tokens": 10},
    }


def test_full_pipeline_scores_50_before_topk_and_preserves_json_evidence():
    async def scenario():
        embedding, knowledge, scorer = Embedding(), Knowledge(), Scorer()
        result = await RerankRetrievalPipeline(embedding, RetrievalCapacity(), scorer).execute(
            request(), knowledge
        )
        assert result.mode == "RERANKED" and result.degraded is False
        assert [len(v) for v in knowledge.resolved] == [50, 2]
        assert knowledge.requests[0][0].candidate_target == 50
        assert embedding.calls == len(knowledge.requests) == len(scorer.calls) == 1
        assert len(scorer.calls[0][1]) == 50
        assert [v.document_id for v in result.hits] == [str(UUID(int=50)), str(UUID(int=49))]
        assert result.hits[0].rrf_score == 1 / 110 and result.hits[0].rerank_score == 49 / 50
        assert result.hits[0].contributions[0].source_score == 0.25
        assert RerankResult.model_validate_json(result.model_dump_json()) == result

    asyncio.run(scenario())


def test_equal_scores_follow_rrf_then_chunk_id_and_empty_does_not_call_model():
    async def scenario():
        scorer, knowledge = Scorer(equal=True), Knowledge()
        result = await RerankRetrievalPipeline(Embedding(), RetrievalCapacity(), scorer).execute(
            request(), knowledge
        )
        assert [v.document_id for v in result.hits] == [str(UUID(int=1)), str(UUID(int=2))]
        empty, scorer = Knowledge(0), Scorer()
        result = await RerankRetrievalPipeline(Embedding(), RetrievalCapacity(), scorer).execute(
            request(), empty
        )
        assert result.mode == "EMPTY" and result.usage is None and not result.hits
        assert not scorer.calls and not empty.resolved

    asyncio.run(scenario())


@pytest.mark.parametrize("reject", [1, 2])
def test_revoke_before_model_blocks_transmission_and_after_model_blocks_output(reject):
    async def scenario():
        scorer, knowledge = Scorer(), Knowledge(reject=reject)
        with pytest.raises(RetrievalError) as error:
            await RerankRetrievalPipeline(Embedding(), RetrievalCapacity(), scorer).execute(
                request(), knowledge
            )
        assert error.value.code == RetrievalCode.EVIDENCE_REJECTED
        assert len(scorer.calls) == reject - 1

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "failure",
    [
        RetrievalCode.RERANK_DISABLED,
        RetrievalCode.RERANK_BUDGET_REJECTED,
        RetrievalCode.RERANK_UNAVAILABLE,
        RetrievalCode.RERANK_PROTOCOL_INVALID,
        RetrievalCode.DEADLINE_EXCEEDED,
    ],
)
def test_fail_policy_never_silently_returns_rrf(failure):
    async def scenario():
        embedding, knowledge = Embedding(), Knowledge()
        with pytest.raises(RetrievalError) as error:
            await RerankRetrievalPipeline(embedding, RetrievalCapacity(), Scorer(failure)).execute(
                request(), knowledge
            )
        assert error.value.code == failure
        assert len(knowledge.resolved) <= 1
        if failure == RetrievalCode.RERANK_DISABLED:
            assert embedding.calls == 0

    asyncio.run(scenario())


def test_cancel_and_root_deadline_stop_final_resolution_and_release_shared_capacity():
    async def scenario():
        capacity, gate = RetrievalCapacity(), asyncio.Event()
        scorer, knowledge = Scorer(gate=gate), Knowledge()
        pipeline = RerankRetrievalPipeline(Embedding(), capacity, scorer)
        task = asyncio.create_task(pipeline.execute(request(), knowledge))
        await scorer.started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert scorer.cancelled and len(knowledge.resolved) == 1
        scorer, knowledge = Scorer(gate=gate), Knowledge()
        with pytest.raises(RetrievalError) as error:
            await RerankRetrievalPipeline(Embedding(), capacity, scorer).execute(
                request(), knowledge, root_deadline=asyncio.get_running_loop().time() + 0.05
            )
        assert error.value.code == RetrievalCode.DEADLINE_EXCEEDED and scorer.cancelled
        assert len(knowledge.resolved) == 1
        await RerankRetrievalPipeline(Embedding(), capacity, Scorer()).execute(
            request(), Knowledge()
        )
        with pytest.raises(RetrievalError):
            await RrfRetrievalPipeline(Embedding(), capacity).execute(request(), Knowledge())

    asyncio.run(scenario())


def test_provider_exact_body_private_ledger_and_restart_limit(tmp_path):
    async def scenario():
        config = config_and_ledger(tmp_path, limit="0.01")
        calls = []

        async def handler(req):
            calls.append(req)
            assert (
                req.url.path == RERANK_PATH
                and req.headers["authorization"] == "Bearer protocol-secret"
            )
            body = json.loads(req.content)
            assert body == {
                "model": "qwen3-rerank",
                "input": {"query": "问题", "documents": [TEXT, TEXT]},
                "parameters": {"top_n": 2, "return_documents": False},
            }
            assert len(json.loads(config.budget_file.read_text())["calls"]) == 1
            return httpx.Response(200, json=provider_reply())

        client = RerankClient(config, transport=httpx.MockTransport(handler))
        await client.check_available()
        result = await client.score("问题", (TEXT, TEXT), RetrievalDeadline.start(None))
        assert result.usage.reserved_cny == "0.010000" and result.usage.total_tokens == 10
        await client.aclose()
        restarted = RerankClient(config, transport=httpx.MockTransport(handler))
        with pytest.raises(RetrievalError) as error:
            await restarted.score("问题", (TEXT, TEXT), RetrievalDeadline.start(None))
        assert error.value.code == RetrievalCode.RERANK_BUDGET_REJECTED and len(calls) == 1
        await restarted.aclose()
        ledger = config.budget_file.read_text()
        assert TEXT not in ledger and "protocol-secret" not in ledger and "问题" not in ledger

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "mutation",
    [
        "duplicate",
        "missing",
        "out_of_range",
        "bool_index",
        "float_index",
        "nan",
        "negative",
        "above_one",
        "shape",
        "model",
        "body",
        "usage",
        "duplicate_json",
        "oversized",
    ],
)
def test_invalid_provider_response_keeps_reservation_and_has_no_retry(tmp_path, mutation):
    async def scenario():
        config, calls = config_and_ledger(tmp_path), []
        data = provider_reply()
        row = data["output"]["results"][0]
        if mutation == "duplicate":
            data["output"]["results"][1]["index"] = 0
        if mutation == "missing":
            data["output"]["results"].pop()
        if mutation == "out_of_range":
            row["index"] = 2
        if mutation == "bool_index":
            row["index"] = True
        if mutation == "float_index":
            row["index"] = 0.0
        if mutation == "nan":
            row["relevance_score"] = float("nan")
        if mutation == "negative":
            row["relevance_score"] = -1
        if mutation == "above_one":
            row["relevance_score"] = 1.1
        if mutation == "shape":
            data["output"] = []
        if mutation == "model":
            data["model"] = "wrong-model"
        if mutation == "body":
            row["document"] = {"text": "未经授权的替换正文"}
        if mutation == "usage":
            data["usage"]["total_tokens"] = 999999999
        raw = json.dumps(data).encode()
        if mutation == "duplicate_json":
            raw = raw[:-1] + b',"usage":{"total_tokens":1}}'
        if mutation == "oversized":
            raw = b" " * (RERANK_RESPONSE_BYTES + 1)

        async def handler(req):
            calls.append(req)
            return httpx.Response(200, content=raw)

        client = RerankClient(config, transport=httpx.MockTransport(handler))
        with pytest.raises(RetrievalError) as error:
            await client.score("问题", (TEXT, TEXT), RetrievalDeadline.start(None))
        assert error.value.code == RetrievalCode.RERANK_PROTOCOL_INVALID
        assert error.value.__context__ is None and len(calls) == 1
        assert len(json.loads(config.budget_file.read_text())["calls"]) == 1
        await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize("status", [301, 401, 403, 429, 503])
def test_remote_failure_no_redirect_retry_or_refund(tmp_path, status):
    async def scenario():
        config, calls = config_and_ledger(tmp_path), []

        async def handler(req):
            calls.append(req)
            return httpx.Response(
                status, headers={"Location": "https://untrusted.invalid"}, text="secret-body"
            )

        client = RerankClient(config, transport=httpx.MockTransport(handler))
        with pytest.raises(RetrievalError) as error:
            await client.score("问题", (TEXT,), RetrievalDeadline.start(None))
        assert error.value.code == RetrievalCode.RERANK_UNAVAILABLE and len(calls) == 1
        assert "secret-body" not in str(error.value) and error.value.__context__ is None
        assert len(json.loads(config.budget_file.read_text())["calls"]) == 1
        await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "problem",
    ["scope", "fingerprint", "sum", "duplicate_json", "permission", "missing", "symlink", "lock"],
)
def test_ledger_corruption_or_wrong_scope_never_creates_or_sends(tmp_path, problem):
    async def scenario():
        config = config_and_ledger(tmp_path)
        path, original = config.budget_file, config.budget_file.read_text()
        ledger = json.loads(original)
        locked = None
        if problem == "scope":
            ledger["scope"] = "chat-integration-20261020"
        if problem == "fingerprint":
            ledger["configFingerprint"] = "0" * 64
        if problem == "sum":
            ledger["reservedCny"] = "0.01"
        path.write_text(json.dumps(ledger))
        if problem == "duplicate_json":
            path.write_text(original[:-1] + ',"reservedCny":"0"}')
        if problem == "permission":
            path.chmod(0o644)
        if problem == "missing":
            path.rename(tmp_path / "saved.json")
        if problem == "symlink":
            path.rename(tmp_path / "saved.json")
            path.symlink_to(tmp_path / "saved.json")
        if problem == "lock":
            locked = path.open("r+")
            fcntl.flock(locked, fcntl.LOCK_EX | fcntl.LOCK_NB)
        calls = []

        async def handler(req):
            calls.append(req)
            return httpx.Response(200, json=provider_reply(1))

        client = RerankClient(config, transport=httpx.MockTransport(handler))
        try:
            with pytest.raises(RetrievalError) as error:
                await client.score("问题", (TEXT,), RetrievalDeadline.start(None))
            expected = (
                RetrievalCode.CAPACITY_EXCEEDED
                if problem == "lock"
                else RetrievalCode.RERANK_BUDGET_REJECTED
            )
            assert error.value.code == expected and not calls
            if problem == "missing":
                assert not path.exists()
        finally:
            if locked:
                locked.close()
            await client.aclose()

    asyncio.run(scenario())


def test_cancel_during_durable_reservation_keeps_bounded_slot_and_never_sends(tmp_path):
    async def scenario():
        config = config_and_ledger(tmp_path)
        client = RerankClient(
            config, transport=httpx.MockTransport(lambda req: pytest.fail("取消后不得发送"))
        )
        started, release = asyncio.Event(), threading.Event()
        loop = asyncio.get_running_loop()
        original = client._budget._access

        def delayed(upper, check_only):
            loop.call_soon_threadsafe(started.set)
            assert release.wait(3)
            return original(upper, check_only)

        client._budget._access = delayed
        task = asyncio.create_task(client.score("问题", (TEXT,), RetrievalDeadline.start(None)))
        await started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        with pytest.raises(RetrievalError) as error:
            await client.check_available()
        assert error.value.code == RetrievalCode.CAPACITY_EXCEEDED
        release.set()
        for _ in range(100):
            if not client._budget._slot.locked():
                break
            await asyncio.sleep(0.01)
        assert len(json.loads(config.budget_file.read_text())["calls"]) == 1
        await client.aclose()

    asyncio.run(scenario())


def test_configuration_disabled_expired_and_private_loading(tmp_path):
    async def scenario():
        config = config_and_ledger(tmp_path, enabled=False)
        budget = RerankBudget(config)
        with pytest.raises(RetrievalError) as error:
            await budget.check_available()
        assert error.value.code == RetrievalCode.RERANK_DISABLED
        budget.close()
        expired = config.model_copy(
            update={
                "enabled": True,
                "pricing_checked_at": datetime.now(UTC).date() - timedelta(days=8),
            }
        )
        with pytest.raises(RetrievalError):
            expired.check_enabled()
        for endpoint in (
            "http://localhost",
            "https://a.cn-beijing.maas.aliyuncs.com/",
            "https://evil.invalid",
            "https://a.cn-beijing.maas.aliyuncs.com@evil.invalid",
        ):
            with pytest.raises(ValidationError):
                RerankConfig(**{**config.model_dump(), "api_key": "fixture", "endpoint": endpoint})
        data = config.model_dump(mode="json")
        data["api_key"] = "fixture"
        path = tmp_path / "private.json"
        path.write_text(json.dumps(data))
        path.chmod(0o600)
        loaded = RerankConfig.from_file(path)
        assert loaded.enabled is False and "fixture" not in repr(loaded)
        path.chmod(0o644)
        with pytest.raises(RetrievalError) as error:
            RerankConfig.from_file(path)
        assert error.value.__context__ is None

    asyncio.run(scenario())


def test_native_postprocessor_avoids_settings_global_callbacks_events_and_thread_bridge(
    monkeypatch,
):
    import llama_index.core as core
    import llama_index.core.settings as settings
    from llama_index_instrumentation import root_dispatcher
    from llama_index_instrumentation.event_handlers import BaseEventHandler

    from agentic_ai.retrieval.rerank_postprocessor import AuthorizedReranker
    from agentic_ai.retrieval.retriever import AuthorizedFusionRetriever, _ConstructionGuard

    events = []

    class Trap:
        def __getattribute__(self, name):
            raise AssertionError("不得访问全局Settings")

    class EventSpy(BaseEventHandler):
        def handle(self, event, **kwargs):
            events.append(event)

    class CallbackSpy(_ConstructionGuard):
        """与内部种子不同类型，模拟应用安装的全局回调。"""

    async def no_thread(*args, **kwargs):
        pytest.fail("原生后处理不能落入同步线程桥接")

    monkeypatch.setattr(settings, "Settings", Trap())
    monkeypatch.setattr(core, "global_handler", CallbackSpy())
    monkeypatch.setattr(root_dispatcher, "event_handlers", [EventSpy()])
    monkeypatch.setattr(asyncio, "to_thread", no_thread)

    async def scenario():
        deadline = RetrievalDeadline.start(None)
        native = AuthorizedFusionRetriever(Knowledge(2), request(), (0.125,) * 1024, deadline)
        nodes = await native.aretrieve(request().query)
        processor = AuthorizedReranker(Scorer(), deadline, request().query)
        ranked = await processor.apostprocess_nodes(nodes, query_str=request().query)
        assert len(ranked) == 2 and not processor.callback_manager.handlers and not events
        assert "正文" not in processor.model_dump_json()
        with pytest.raises(RetrievalError):
            processor.postprocess_nodes(nodes, query_str=request().query)
        with pytest.raises(RetrievalError):
            await processor.apostprocess_nodes(nodes, query_str="另一个问题")

    asyncio.run(scenario())


@pytest.mark.parametrize("failure", ["duplicate", "missing", "wrong_model"])
def test_postprocessor_rechecks_untrusted_port_scores(failure):
    class InvalidScorer(Scorer):
        async def score(self, query, documents, deadline):
            result = await super().score(query, documents, deadline)
            if failure == "wrong_model":
                return result.model_copy(
                    update={"usage": result.usage.model_copy(update={"model": "wrong"})}
                )
            scores = result.scores[:-1]
            if failure == "duplicate":
                scores += (result.scores[0],)
            return result.model_copy(update={"scores": scores})

    async def scenario():
        knowledge = Knowledge(2)
        with pytest.raises(RetrievalError) as error:
            await RerankRetrievalPipeline(
                Embedding(), RetrievalCapacity(), InvalidScorer()
            ).execute(request(), knowledge)
        assert error.value.code == RetrievalCode.RERANK_PROTOCOL_INVALID
        assert len(knowledge.resolved) == 1

    asyncio.run(scenario())


def test_candidate_http_target_depends_on_frozen_strategy():
    from cryptography.hazmat.primitives.asymmetric import rsa

    from agentic_ai.retrieval.client import RetrievalClient

    async def scenario():
        targets = []

        async def handler(req):
            targets.append(json.loads(req.content)["candidateTarget"])
            return httpx.Response(200, content=candidates(2).model_dump_json(by_alias=True))

        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        client = RetrievalClient(
            "http://localhost:28084",
            "local-dev",
            "developer",
            key,
            transport=httpx.MockTransport(handler),
        )
        await client.candidates(request(), (0.125,) * 1024, 1000)
        await client.candidates(
            request().model_copy(update={"strategy": RetrievalStrategy.RRF}), (0.125,) * 1024, 1000
        )
        assert targets == [50, 2]
        await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize("mode", ["timeout", "cancel"])
def test_http_timeout_cancel_keep_unknown_reservation_and_stop(tmp_path, mode):
    async def scenario():
        config, gate, entered, calls = (
            config_and_ledger(tmp_path),
            asyncio.Event(),
            asyncio.Event(),
            [],
        )

        async def handler(req):
            calls.append(req)
            entered.set()
            await gate.wait()
            return httpx.Response(200, json=provider_reply(1))

        client = RerankClient(config, transport=httpx.MockTransport(handler))
        deadline = RetrievalDeadline.start(
            asyncio.get_running_loop().time() + 0.05 if mode == "timeout" else None
        )
        task = asyncio.create_task(client.score("问题", (TEXT,), deadline))
        await entered.wait()
        if mode == "cancel":
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        else:
            with pytest.raises(RetrievalError) as error:
                await task
            assert (
                error.value.code == RetrievalCode.DEADLINE_EXCEEDED
                and error.value.__context__ is None
            )
        assert len(calls) == len(json.loads(config.budget_file.read_text())["calls"]) == 1
        await client.aclose()

    asyncio.run(scenario())


def test_two_inflight_shared_with_rrf_and_third_rejected_then_cancel_recovers():
    async def scenario():
        capacity, gate, scorer = RetrievalCapacity(), asyncio.Event(), Scorer(gate=asyncio.Event())
        scorer.gate = gate
        pipeline = RerankRetrievalPipeline(Embedding(), capacity, scorer)
        tasks = [asyncio.create_task(pipeline.execute(request(), Knowledge())) for _ in range(2)]
        await scorer.started.wait()
        await asyncio.sleep(0)
        with pytest.raises(RetrievalError) as error:
            await RrfRetrievalPipeline(Embedding(), capacity).execute(
                request().model_copy(update={"strategy": RetrievalStrategy.RRF}), Knowledge()
            )
        assert error.value.code == RetrievalCode.CAPACITY_EXCEEDED
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        assert not capacity._slots.locked()

    asyncio.run(scenario())


@pytest.mark.parametrize("problem", ["empty", "too_many", "long_text", "long_query"])
def test_invalid_provider_input_has_no_budget_or_network_side_effect(tmp_path, problem):
    async def scenario():
        config = config_and_ledger(tmp_path)
        before = config.budget_file.read_bytes()
        client = RerankClient(
            config, transport=httpx.MockTransport(lambda req: pytest.fail("输入失败不得发送"))
        )
        query, documents = "问题", (TEXT,)
        if problem == "empty":
            documents = ()
        if problem == "too_many":
            documents = (TEXT,) * 51
        if problem == "long_text":
            documents = ("文" * 513,)
        if problem == "long_query":
            query = "问" * 2001
        with pytest.raises(RetrievalError) as error:
            await client.score(query, documents, RetrievalDeadline.start(None))
        assert error.value.code == RetrievalCode.INPUT_INVALID
        assert config.budget_file.read_bytes() == before
        await client.aclose()

    asyncio.run(scenario())
