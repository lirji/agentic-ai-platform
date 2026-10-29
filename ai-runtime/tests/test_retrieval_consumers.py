"""S11E共用用例、公开边界与跨语言完整证据；不执行付费模型。"""

import asyncio
import json
from pathlib import Path

import pytest
from pydantic import ValidationError
from test_retrieval import Embedding, Knowledge, parsed, query

from agentic_ai.retrieval.consumer import PublicRetrievalResult, RetrievalOutcome, RetrievalUseCase
from agentic_ai.retrieval.deadline import RetrievalCapacity
from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.evidence import GenerationCheck, GenerationReference, generation_digest
from agentic_ai.retrieval.models import RetrievalInput, RetrievalStrategy

GOLDEN = json.loads(
    (
        Path(__file__).resolve().parents[2]
        / "knowledge-service/src/test/resources/generation-evidence-golden.json"
    ).read_text()
)


def test_shared_generation_digest_and_wire():
    for case in GOLDEN["digests"]:
        refs = tuple(
            GenerationReference.model_validate_json(json.dumps(r)) for r in case["references"]
        )
        assert generation_digest(refs) == case["digest"]
    assert GenerationCheck.from_wire(json.dumps(GOLDEN["valid"]).encode()).references
    for case in GOLDEN["invalid"]:
        with pytest.raises((ValidationError, ValueError)):
            GenerationCheck.from_wire(json.dumps(case["body"]).encode())
    duplicate_key = json.dumps(GOLDEN["valid"]).replace(
        '"version": 1', '"version": 1, "version": 2'
    )
    with pytest.raises(ValueError):
        GenerationCheck.from_wire(duplicate_key.encode())


def test_unified_rrf_roundtrip_and_public_projection():
    async def run():
        embedding, knowledge = Embedding(), Knowledge()
        result = await RetrievalUseCase(embedding, RetrievalCapacity(1)).execute(query(), knowledge)
        assert result.mode == "RRF"
        restored = RetrievalOutcome.from_wire(result.model_dump_json(by_alias=True).encode())
        assert restored == result
        public = restored.public()
        assert [h.rank for h in public.hits] == list(range(1, len(public.hits) + 1))
        assert all(h.rerank_score is None for h in public.hits)
        body = public.model_dump_json(by_alias=True)
        for forbidden in (
            "sourceRank",
            "sourceScore",
            "contributions",
            "candidateLimit",
            "reservedCny",
        ):
            assert forbidden not in body
        assert PublicRetrievalResult.from_wire(body.encode()) == public
        assert [h.identity for h in public.hits] == [h.identity() for h in result.hits]

    asyncio.run(run())


def test_disabled_rerank_never_embeds_or_silently_falls_back():
    async def run():
        embedding = Embedding()
        request = RetrievalInput(
            query="问题", document_ids=None, top_k=5, strategy=RetrievalStrategy.RERANK
        )
        with pytest.raises(RetrievalError) as failure:
            await RetrievalUseCase(embedding, RetrievalCapacity(1)).execute(request, Knowledge())
        assert failure.value.code == RetrievalCode.RERANK_DISABLED
        assert embedding.calls == []

    asyncio.run(run())


def test_empty_is_observed_and_not_reranked():
    async def run():
        knowledge = Knowledge()
        knowledge.reply = parsed(
            {
                "schemaVersion": "knowledge-retrieval-v1",
                "candidateLimit": 20,
                "vectorCandidates": [],
                "lexicalCandidates": [],
            }
        )
        result = await RetrievalUseCase(Embedding(), RetrievalCapacity(1)).execute(
            query(), knowledge
        )
        assert result.mode == "EMPTY" and not result.hits
        assert result.public().mode == "EMPTY"

    asyncio.run(run())


@pytest.mark.parametrize("maximum", [0, 3, True, 1.0])
def test_capacity_configuration_cannot_expand_platform_limit(maximum):
    with pytest.raises(RetrievalError):
        RetrievalCapacity(maximum)


def test_single_process_admission_releases_after_cancellation():
    async def run():
        capacity = RetrievalCapacity(1)
        started = asyncio.Event()

        async def occupy():
            async with capacity.enter():
                started.set()
                await asyncio.Event().wait()

        task = asyncio.create_task(occupy())
        await started.wait()
        with pytest.raises(RetrievalError):
            async with capacity.enter():
                pytest.fail("capacity exceeded")
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        async with capacity.enter():
            pass

    asyncio.run(run())


def test_rerank_uses_one_pipeline_and_retains_private_usage():
    from test_rerank import Embedding as RerankEmbedding
    from test_rerank import Knowledge as RerankKnowledge
    from test_rerank import Scorer, request

    async def run():
        embedding, knowledge, scorer = RerankEmbedding(), RerankKnowledge(), Scorer()
        result = await RetrievalUseCase(embedding, RetrievalCapacity(1), scorer).execute(
            request(), knowledge
        )
        assert result.mode == "RERANKED" and result.usage is not None
        assert len(scorer.calls) == 1 and embedding.calls == 1
        assert all(hit.rerank_score is not None for hit in result.public().hits)
        assert "reservedCny" not in result.public().model_dump_json(by_alias=True)
        assert RetrievalOutcome.from_wire(result.model_dump_json(by_alias=True).encode()) == result

    asyncio.run(run())


def test_public_result_rejects_tampered_content_rank_and_mode():
    async def run():
        result = await RetrievalUseCase(Embedding(), RetrievalCapacity(1)).execute(
            query(), Knowledge()
        )
        body = result.public().model_dump(mode="json", by_alias=True)
        for target, field, value in [
            ("root", "mode", "RERANKED"),
            ("root", "degraded", 0),
            ("hit", "rank", 2),
            ("hit", "text", "替换正文"),
            ("hit", "rerankScore", 0.5),
            ("hit", "sourceRank", 20),
        ]:
            changed = json.loads(json.dumps(body))
            destination = changed if target == "root" else changed["hits"][0]
            destination[field] = value
            with pytest.raises(ValidationError):
                PublicRetrievalResult.from_wire(json.dumps(changed).encode())

    asyncio.run(run())
