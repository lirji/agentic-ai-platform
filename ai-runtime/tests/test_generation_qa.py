"""问答v2的新旧图、上下文完整依赖与取消/恢复验证。"""

import asyncio
import json
from uuid import uuid4

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from langsmith import tracing_context
from test_generation_work import GenerationCentral
from test_qa_pipeline import Chat, invoke, lease, question
from test_retrieval import Embedding, Knowledge

from agentic_ai.qa.errors import QaError
from agentic_ai.qa.generation_pipeline import GenerationQaPipeline
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.deadline import RetrievalCapacity
from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.evidence import GenerationManifest, generation_digest
from agentic_ai.retrieval.task_models import GenerationResult
from agentic_ai.tasks.models import TaskStatus


def setup(*, input_value=None, knowledge=None):
    value = input_value or question(
        schemaVersion="knowledge-qa-input-v2", documentIds=[], retrievalStrategy="LLAMA_RRF_V1"
    )
    binding = lease(input_value=value, graphVersion="knowledge-qa-v2")
    central, embedding, candidates, chat = (
        GenerationCentral(binding),
        Embedding(),
        knowledge or Knowledge(),
        Chat(),
    )
    use_case = RetrievalUseCase(embedding, RetrievalCapacity(1))
    return (
        GenerationQaPipeline(binding, central, candidates, chat, use_case),
        central,
        embedding,
        candidates,
        chat,
        use_case,
    )


def test_v2_qa_uses_same_outcome_and_final_envelope():
    async def scenario():
        pipeline, central, embedding, knowledge, chat, _ = setup()
        result = await invoke(pipeline)
        envelope = GenerationResult.from_wire(json.dumps(result).encode())
        await pipeline.before_complete(result)
        assert envelope.content.answer.endswith("[1]")
        assert envelope.retrieval[0].mode == "RRF"
        assert len(embedding.calls) == chat.calls == 1
        assert set(envelope.references) <= central.manifests[-1]
        assert len(central.manifests[-1]) > len(envelope.references)
        assert knowledge.resolutions

    asyncio.run(scenario())


@pytest.mark.parametrize("stage", ["generate", "verify", None])
def test_new_instance_restore_keeps_full_dependencies_and_no_duplicate_generation(stage):
    async def scenario():
        pipeline, central, embedding, candidates, chat, use_case = setup()
        saver = InMemorySaver()
        config = {"configurable": {"thread_id": pipeline.binding.physical_thread}}
        with tracing_context(enabled=False):
            await pipeline.build(saver, interrupt_before=[stage] if stage else None).ainvoke(
                pipeline.binding.input, config, durability="sync"
            )
        restored = GenerationQaPipeline(pipeline.binding, central, candidates, chat, use_case)
        graph = restored.build(saver)
        snapshot = await graph.aget_state(config)
        await restored.before_restore(snapshot.values)
        with tracing_context(enabled=False):
            result = await graph.ainvoke(None, config, durability="sync")
        await restored.before_complete(result)
        assert len(embedding.calls) == chat.calls == 1

    asyncio.run(scenario())


def test_unreferenced_generation_drift_blocks_completed_snapshot():
    async def scenario():
        pipeline, central, _, candidates, chat, use_case = setup()
        saver = InMemorySaver()
        config = {"configurable": {"thread_id": pipeline.binding.physical_thread}}
        with tracing_context(enabled=False):
            result = await pipeline.build(saver).ainvoke(pipeline.binding.input, config)
        cited = {r["chunkId"] for r in result["references"]}
        original = candidates.resolve

        async def changed(frozen, documents, timeout_ms):
            values = await original(frozen, documents, timeout_ms)
            return tuple(
                h.model_copy(update={"index_generation": 99}) if h.chunk_id not in cited else h
                for h in values
            )

        candidates.resolve = changed
        restored = GenerationQaPipeline(pipeline.binding, central, candidates, chat, use_case)
        snapshot = await restored.build(saver).aget_state(config)
        with pytest.raises(RetrievalError):
            await restored.before_restore(snapshot.values)
        assert chat.calls == 1

    asyncio.run(scenario())


def test_context_inherits_all_previous_dependencies_and_rechecks_before_complete():
    async def scenario():
        previous, previous_central, _, _, _, _ = setup()
        previous_result = await invoke(previous)
        full = tuple(previous_central.manifests[-1])
        old = previous.binding.model_copy(
            update={
                "task": previous.binding.task.model_copy(
                    update={"status": TaskStatus.SUCCEEDED, "result": previous_result}
                )
            }
        )
        value = {
            **old.input,
            "turnId": str(uuid4()),
            "question": "进一步解释",
            "contextTurnId": old.input["turnId"],
            "contextTaskId": old.task.task_id,
            "contextQuestion": old.input["question"],
        }
        pipeline, central, _, _, chat, _ = setup(input_value=value)
        central.previous = old
        checks = []

        async def manifest(task_id):
            checks.append(task_id)
            return GenerationManifest(
                task_id=task_id,
                epoch=old.epoch,
                schema_version="knowledge-evidence-v2",
                evidence_digest=generation_digest(full),
                references=full,
            )

        central.generation_manifest = manifest
        result = await invoke(pipeline)
        await pipeline.before_complete(result)
        assert set(full) <= central.manifests[-1] and len(checks) >= 3
        assert (
            json.loads(chat.messages[1].content)["context"]["answer"]
            == previous_result["content"]["answer"]
        )

        async def revoked(task_id):
            raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)

        central.generation_manifest = revoked
        with pytest.raises(RetrievalError):
            await pipeline.before_complete(result)

    asyncio.run(scenario())


def test_legacy_context_cannot_be_upgraded_with_invented_generation():
    async def scenario():
        old = lease()
        value = question(
            schemaVersion="knowledge-qa-input-v2",
            retrievalStrategy="LLAMA_RRF_V1",
            documentIds=[],
            contextTurnId=old.input["turnId"],
            contextTaskId=old.task.task_id,
            contextQuestion=old.input["question"],
        )
        pipeline, central, _, _, chat, _ = setup(input_value=value)
        central.previous = old
        with pytest.raises(QaError):
            await invoke(pipeline)
        assert chat.calls == 0

    asyncio.run(scenario())
