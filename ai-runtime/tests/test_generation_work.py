"""新研究图的完整代次与原生恢复，离线端口不代表真实模型质量。"""

import asyncio
import copy
import json

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from langsmith import tracing_context
from test_qa_pipeline import Central, lease
from test_retrieval import Embedding, Knowledge, parsed
from test_work_pipeline import ToolsChat, run, work_input

from agentic_ai.agents.generation_pipeline import GenerationWorkPipeline
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.deadline import RetrievalCapacity
from agentic_ai.retrieval.errors import RetrievalError
from agentic_ai.retrieval.task_models import GenerationResult


class GenerationCentral(Central):
    async def generation_evidence(self, task_id, epoch, references):
        assert task_id == self.binding.task.task_id and epoch == self.binding.epoch
        current = set(references)
        assert not self.manifests or current.issuperset(self.manifests[-1])
        self.manifests.append(current)

    async def knowledge_evidence(self, *args):
        raise AssertionError("v2 cannot register legacy evidence")


def setup(*, chat=None, knowledge=None):
    value = {
        **work_input(),
        "schemaVersion": "knowledge-work-input-v2",
        "documentIds": [],
        "retrievalStrategy": "LLAMA_RRF_V1",
    }
    binding = lease(input_value=value, graphVersion="knowledge-work-v2")
    binding = binding.model_copy(
        update={"task": binding.task.model_copy(update={"kind": "RESEARCH"})}
    )
    central, embedding, candidates, chat = (
        GenerationCentral(binding),
        Embedding(),
        knowledge or Knowledge(),
        chat or ToolsChat(),
    )
    use_case = RetrievalUseCase(embedding, RetrievalCapacity(1))
    pipeline = GenerationWorkPipeline(binding, central, candidates, chat, use_case)
    return pipeline, central, embedding, candidates, chat, use_case


def test_generation_graph_keeps_original_content_and_full_evidence():
    async def scenario():
        pipeline, central, embedding, knowledge, chat, _ = setup()
        result = GenerationResult.from_wire(json.dumps((await run(pipeline))["report"]).encode())
        await pipeline.before_complete(result.model_dump(mode="json", by_alias=True))
        assert result.schema_version == "knowledge-work-result-v2"
        assert result.retrieval[0].mode == "RRF"
        assert len(embedding.calls) == 1 and chat.calls == 2
        assert central.manifests and len(central.manifests[-1]) >= len(result.references)
        assert set(result.references) <= central.manifests[-1]
        assert all(ref.index_generation >= 1 and ref.job_id for ref in central.manifests[-1])
        assert knowledge.resolutions

    asyncio.run(scenario())


@pytest.mark.parametrize("stage", ["analyze", "verify"])
def test_restore_in_new_instance_preserves_full_identity_without_retrieval_replay(stage):
    async def scenario():
        pipeline, central, embedding, knowledge, chat, use_case = setup()
        saver = InMemorySaver()
        await run(pipeline, saver=saver, before=[stage])
        config = {"configurable": {"thread_id": pipeline.binding.physical_thread}}
        restored = GenerationWorkPipeline(pipeline.binding, central, knowledge, chat, use_case)
        graph = restored.build(saver)
        snapshot = await graph.aget_state(config)
        assert snapshot.values["generationEvidence"]
        calls = chat.calls
        await restored.before_restore(snapshot.values)
        with tracing_context(enabled=False):
            report = (await graph.ainvoke(None, config, durability="sync"))["report"]
        await restored.before_complete(report)
        assert len(embedding.calls) == 1
        assert chat.calls == (2 if stage == "analyze" else calls)

    asyncio.run(scenario())


@pytest.mark.parametrize("field", ["indexGeneration", "jobId", "batchDigest"])
def test_drift_after_retrieval_never_replays_model_or_text(field):
    async def scenario():
        pipeline, central, embedding, knowledge, chat, use_case = setup()
        saver = InMemorySaver()
        await run(pipeline, saver=saver, before=["analyze"])
        original = knowledge.resolve

        async def changed(frozen, documents, timeout_ms):
            current = await original(frozen, documents, timeout_ms)
            key = {
                "indexGeneration": "index_generation",
                "jobId": "job_id",
                "batchDigest": "batch_digest",
            }[field]
            value = (
                99
                if field == "indexGeneration"
                else "f" * 64
                if field == "batchDigest"
                else "00000000-0000-4000-8000-000000000099"
            )
            return tuple(h.model_copy(update={key: value}) for h in current)

        knowledge.resolve = changed
        restored = GenerationWorkPipeline(pipeline.binding, central, knowledge, chat, use_case)
        snapshot = await restored.build(saver).aget_state(
            {"configurable": {"thread_id": pipeline.binding.physical_thread}}
        )
        with pytest.raises(RetrievalError):
            await restored.before_restore(snapshot.values)
        assert chat.calls == 0 and len(embedding.calls) == 1

    asyncio.run(scenario())


def test_snapshot_missing_or_modified_generation_is_rejected():
    async def scenario():
        pipeline, _, _, _, _, _ = setup()
        saver = InMemorySaver()
        await run(pipeline, saver=saver, before=["analyze"])
        state = (
            await pipeline.build(saver).aget_state(
                {"configurable": {"thread_id": pipeline.binding.physical_thread}}
            )
        ).values
        for key in ("generationEvidence", "retrievalDiagnostics"):
            value = copy.deepcopy(state)
            del value[key]
            with pytest.raises(RetrievalError):
                await pipeline.before_restore(value)
        value = copy.deepcopy(state)
        value["evidence"][0]["text"] = "replaced"
        with pytest.raises(RetrievalError):
            await pipeline.before_restore(value)

    asyncio.run(scenario())


def test_empty_v2_does_not_call_model_and_remains_truthful():
    async def scenario():
        knowledge = Knowledge(
            parsed(
                {
                    "schemaVersion": "knowledge-retrieval-v1",
                    "candidateLimit": 20,
                    "vectorCandidates": [],
                    "lexicalCandidates": [],
                }
            )
        )
        pipeline, _, _, _, chat, _ = setup(knowledge=knowledge)
        report = (await run(pipeline))["report"]
        await pipeline.before_complete(report)
        assert report["retrieval"][0]["mode"] == "EMPTY" and not report["references"]
        assert chat.calls == 0

    asyncio.run(scenario())
