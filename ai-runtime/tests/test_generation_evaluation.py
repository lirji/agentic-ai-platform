"""评测v2真实原生图与共享线格式验证，不使用提供方凭据。"""

import asyncio
import json
from copy import deepcopy
from pathlib import Path

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from langsmith import tracing_context
from pydantic import ValidationError
from test_evaluation_pipeline import PROFILE, EvaluationChat, Reports, evaluation_fixture
from test_qa_pipeline import Central
from test_retrieval import Embedding, Knowledge, parsed

from agentic_ai.evaluation.generation_pipeline import GenerationEvaluationPipeline
from agentic_ai.evaluation.models import GenerationEvaluationInput, GenerationQuestionReport, Opened
from agentic_ai.evaluation.pipeline import EvaluationFailed
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.deadline import RetrievalCapacity
from agentic_ai.retrieval.errors import RetrievalCode
from agentic_ai.tasks.errors import TaskRuntimeError

FIXTURE = (
    Path(__file__).parents[2]
    / "knowledge-service/src/test/resources/evaluation-generation-golden.json"
)


class GenerationReports(Reports):
    """复用候选幂等替身；完整权限读取的真实SQL另由Java集成证明。"""

    async def open(self, profile):
        assert profile.retrieval_profile == "LLAMA_RRF_V1"
        return await super().open(PROFILE)

    async def candidate(self, receipt):
        if self.revoked:
            raise QaError(QaCode.EVIDENCE_REJECTED)
        return await super().candidate(receipt)


def setup(*, count=2, empty=False):
    binding, opened = evaluation_fixture(count=count)
    value = {
        **binding.input,
        "schemaVersion": "evaluation-input-v2",
        "retrievalStrategy": "LLAMA_RRF_V1",
    }
    binding = binding.model_copy(
        update={"input": value, "graph_version": "knowledge-evaluation-v2"}
    )
    reply = parsed()
    hits = {h.document_id: h for h in (*reply.vector_candidates, *reply.lexical_candidates)}
    data = opened.model_dump(mode="json", by_alias=True)
    for q in data["definition"]["questions"]:
        q["documentIds"] = sorted(hits)
        first = next(iter(hits.values()))
        q["evidence"] = [
            {
                "documentId": first.document_id,
                "version": first.version,
                "chunkId": first.chunk_id,
                "relevance": 2,
            }
        ]
    data["bindings"] = [
        {
            "documentId": h.document_id,
            "version": h.version,
            "sourceHash": "b" * 64,
            "batchDigest": h.batch_digest,
            "processorVersion": h.processor_version,
        }
        for h in hits.values()
    ]
    opened = Opened.model_validate_json(json.dumps(data))
    if empty:
        reply = reply.model_copy(update={"vector_candidates": (), "lexical_candidates": ()})
    knowledge, embedding, chat = Knowledge(reply), Embedding(), EvaluationChat()
    central, reports = Central(binding), GenerationReports(binding, opened)
    profile = PROFILE.model_copy(update={"retrieval_profile": "LLAMA_RRF_V1"})
    use_case = RetrievalUseCase(embedding, RetrievalCapacity(1))
    pipeline = GenerationEvaluationPipeline(
        binding, central, knowledge, chat, reports, profile, opened, use_case
    )
    return pipeline, central, reports, embedding, chat, knowledge, use_case


def test_native_graph_persists_strategy_and_all_dependencies_then_restores_without_calls():
    async def scenario():
        pipeline, central, reports, embedding, chat, knowledge, use_case = setup()
        saver = InMemorySaver()
        config = {"configurable": {"thread_id": pipeline.binding.physical_thread}}
        with tracing_context(enabled=False):
            result = await pipeline.build(saver).ainvoke(
                pipeline.binding.input, config, durability="sync"
            )
        assert result["retrievalStrategy"] == "LLAMA_RRF_V1"
        assert len(embedding.calls) == chat.calls == 2
        for _, report in reports.reports.values():
            assert isinstance(report, GenerationQuestionReport)
            assert report.generation.retrieval.mode == "RRF"
            assert (
                len(report.generation.references)
                == len(report.retrieved)
                > len(report.result.citations)
            )
        restored = GenerationEvaluationPipeline(
            pipeline.binding,
            central,
            knowledge,
            chat,
            reports,
            pipeline.profile,
            pipeline.opened,
            use_case,
        )
        snapshot = await restored.build(saver).aget_state(config)
        await restored.before_restore(snapshot.values)
        await restored.before_complete(snapshot.values)
        assert len(embedding.calls) == chat.calls == 2
        reports.revoked = True
        with pytest.raises(QaError):
            await restored.before_restore(snapshot.values)
        assert chat.calls == 2

    asyncio.run(scenario())


@pytest.mark.parametrize("empty", [False, True])
def test_empty_and_dependency_failure_are_not_confused(empty):
    async def scenario():
        pipeline, _, reports, embedding, chat, knowledge, _ = setup(count=1, empty=empty)
        if not empty:
            knowledge.failure = RetrievalCode.KNOWLEDGE_UNAVAILABLE
        config = {"configurable": {"thread_id": pipeline.binding.physical_thread}}
        with tracing_context(enabled=False):
            if empty:
                await pipeline.build(InMemorySaver()).ainvoke(pipeline.binding.input, config)
            else:
                with pytest.raises(EvaluationFailed):
                    await pipeline.build(InMemorySaver()).ainvoke(pipeline.binding.input, config)
        report = next(iter(reports.reports.values()))[1]
        assert isinstance(report, GenerationQuestionReport)
        assert not report.generation.references and chat.calls == 0
        if empty:
            assert report.generation.retrieval.mode == "EMPTY"
        else:
            assert report.generation.retrieval is None and report.outcome == "FAILED"
        assert len(embedding.calls) == 1

    asyncio.run(scenario())


def test_shared_v2_fixture_and_missing_identity_modes_and_boolean_are_strict():
    golden = json.loads(FIXTURE.read_text())
    assert (
        GenerationEvaluationInput.model_validate_json(json.dumps(golden["input"])).model_dump(
            mode="json", by_alias=True
        )
        == golden["input"]
    )
    for report in golden["reports"].values():
        assert (
            GenerationQuestionReport.model_validate_json(json.dumps(report)).model_dump(
                mode="json", by_alias=True
            )
            == report
        )
    for mutation in (
        "missing-generation",
        "missing-component",
        "wrong-mode",
        "numeric-boolean",
        "wrong-hash",
        "missing-diagnostic",
    ):
        report = deepcopy(golden["reports"]["answerable"])
        if mutation == "missing-generation":
            del report["generation"]
        elif mutation == "missing-component":
            del report["generation"]["references"][0]["jobId"]
        elif mutation == "wrong-mode":
            report["generation"]["retrieval"]["mode"] = "RERANKED"
        elif mutation == "numeric-boolean":
            report["generation"]["retrieval"]["degraded"] = 0
        elif mutation == "wrong-hash":
            report["generation"]["references"][0]["textHash"] = "f" * 64
        else:
            report["generation"]["retrieval"] = None
        with pytest.raises(ValidationError):
            GenerationQuestionReport.model_validate_json(json.dumps(report))


def test_http_client_keeps_new_report_type_and_refuses_cross_strategy_profile():
    async def scenario():
        import httpx
        from cryptography.hazmat.primitives.asymmetric import rsa

        from agentic_ai.evaluation.client import EvaluationClient
        from agentic_ai.evaluation.models import CandidateReceipt

        pipeline, _, reports, _, _, _, _ = setup(count=1)
        golden = json.loads(FIXTURE.read_text())
        body = golden["reports"]["answerable"]
        body["questionId"] = pipeline.opened.definition.questions[0].question_id
        receipt = CandidateReceipt(question_id=body["questionId"], epoch=1, digest="a" * 64)
        requests = []

        def handler(request):
            requests.append(request)
            if request.url.path.endswith("/open"):
                assert json.loads(request.content)["profile"]["retrievalProfile"] == "LLAMA_RRF_V1"
                return httpx.Response(
                    200, json=pipeline.opened.model_dump(mode="json", by_alias=True)
                )
            return httpx.Response(200, json=body)

        client = EvaluationClient(
            "http://127.0.0.1:1",
            pipeline.binding,
            rsa.generate_private_key(public_exponent=65537, key_size=2048),
            transport=httpx.MockTransport(handler),
        )
        try:
            with pytest.raises(QaError):
                await client.open(PROFILE)
            assert not requests
            assert await client.open(pipeline.profile) == pipeline.opened
            report = await client.candidate(receipt)
            assert isinstance(report, GenerationQuestionReport)
            body["generation"]["strategy"] = "LLAMA_RERANK_V1"
            body["generation"]["retrieval"]["strategy"] = "LLAMA_RERANK_V1"
            body["generation"]["retrieval"]["mode"] = "RERANKED"
            with pytest.raises(QaError):
                await client.candidate(receipt)
        finally:
            await client.aclose()

    asyncio.run(scenario())


def test_initial_checkpoint_and_failed_prefix_restore_never_repeats_completed_question():
    async def scenario():
        pipeline, central, reports, embedding, chat, knowledge, use_case = setup(count=2)
        await pipeline.before_restore(pipeline.binding.input)
        chat.fail_at = 2
        saver = InMemorySaver()
        config = {"configurable": {"thread_id": pipeline.binding.physical_thread}}
        with tracing_context(enabled=False), pytest.raises(EvaluationFailed):
            await pipeline.build(saver).ainvoke(pipeline.binding.input, config, durability="sync")
        snapshot = await pipeline.build(saver).aget_state(config)
        assert snapshot.values["nextIndex"] == 1
        restored = GenerationEvaluationPipeline(
            pipeline.binding,
            central,
            knowledge,
            chat,
            reports,
            pipeline.profile,
            pipeline.opened,
            use_case,
        )
        await restored.before_restore(snapshot.values)
        assert len(embedding.calls) == chat.calls == 2
        bad = dict(snapshot.values, retrievalStrategy="LLAMA_RERANK_V1")
        with pytest.raises(TaskRuntimeError):
            await restored.before_restore(bad)
        assert chat.calls == 2

    asyncio.run(scenario())
