"""原生新图验证原问题分离、指定上下文与确认快照恢复；模型为协议替身。"""

import asyncio
import json
from copy import deepcopy
from uuid import uuid4

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from langsmith import tracing_context
from test_generation_qa import setup as legacy_setup
from test_generation_work import GenerationCentral
from test_qa_pipeline import invoke, lease, question
from test_retrieval import Embedding, Knowledge

from agentic_ai.qa.contextual_models import ContextualQaResult, QueryMode
from agentic_ai.qa.contextual_pipeline import ContextualQaPipeline
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.deadline import RetrievalCapacity
from agentic_ai.retrieval.evidence import GenerationManifest, generation_digest
from agentic_ai.tasks.models import TaskStatus


class Chat:
    def __init__(self):
        self.calls = []

    async def stream(self, messages, operation):
        self.calls.append((operation, messages))
        yield (
            '{"query":"资料中助手的操作限制有哪些？"}'
            if operation.startswith("qa-rewrite:")
            else "只能提供只读解释。[1]"
        )


async def setup(*, history=True, previous_v3=False):
    value = question(
        schemaVersion="knowledge-qa-input-v3",
        documentIds=[],
        retrievalStrategy="LLAMA_RRF_V1",
        queryPolicy="CONTEXT_REWRITE_V1",
    )
    old = full = None
    if history:
        if previous_v3:
            previous, previous_central, *_ = await setup(history=False)
        else:
            previous, previous_central, *_ = legacy_setup()
        result = await invoke(previous)
        old = previous.binding.model_copy(
            update={
                "task": previous.binding.task.model_copy(
                    update={"status": TaskStatus.SUCCEEDED, "result": result}
                )
            }
        )
        full = tuple(previous_central.manifests[-1])
        value.update(
            conversationId=previous.question.conversation_id,
            turnId=str(uuid4()),
            question="它有哪些限制？",
            contextTurnId=previous.question.turn_id,
            contextTaskId=old.task.task_id,
            contextQuestion=previous.question.question,
        )
    binding = lease(input_value=value, graphVersion="knowledge-qa-v3")
    central, embedding, candidates, chat = (
        GenerationCentral(binding),
        Embedding(),
        Knowledge(),
        Chat(),
    )
    central.previous = old
    central.context_checks = 0

    async def manifest(task_id):
        central.context_checks += 1
        assert old and task_id == old.task.task_id
        return GenerationManifest(
            task_id=task_id,
            epoch=old.epoch,
            schema_version="knowledge-evidence-v2",
            evidence_digest=generation_digest(full),
            references=full,
        )

    central.generation_manifest = manifest
    use_case = RetrievalUseCase(embedding, RetrievalCapacity(1))
    pipeline = ContextualQaPipeline(binding, central, candidates, chat, use_case)
    return pipeline, central, embedding, candidates, chat, use_case


@pytest.mark.parametrize("previous_v3", [False, True])
def test_one_rewrite_retrieval_and_answer_preserve_original_and_full_dependencies(previous_v3):
    async def scenario():
        pipeline, central, embedding, _, chat, _ = await setup(previous_v3=previous_v3)
        original = deepcopy(pipeline.binding.input)
        result = await invoke(pipeline)
        await pipeline.before_complete(result)
        envelope = ContextualQaResult.from_wire(json.dumps(result).encode())
        assert envelope.query.mode == QueryMode.REWRITTEN
        assert envelope.query.original_question == original["question"]
        assert embedding.calls == [(envelope.query.retrieval_question,)]
        assert len(chat.calls) == 2 and central.context_checks >= 3
        assert json.loads(chat.calls[-1][1][1].content)["question"] == original["question"]
        assert pipeline.binding.input == original
        assert set(envelope.references) < central.manifests[-1]

    asyncio.run(scenario())


def test_without_history_skips_rewrite_and_keeps_original_retrieval():
    async def scenario():
        pipeline, _, embedding, _, chat, _ = await setup(history=False)
        result = await invoke(pipeline)
        await pipeline.before_complete(result)
        assert result["query"]["mode"] == "ORIGINAL"
        assert len(chat.calls) == 1 and embedding.calls == [(pipeline.question.question,)]

    asyncio.run(scenario())


@pytest.mark.parametrize("stage", ["retrieve", "generate", "verify", None])
def test_new_instance_resume_does_not_repeat_confirmed_rewrite_retrieval_or_answer(stage):
    async def scenario():
        pipeline, central, embedding, candidates, chat, use_case = await setup()
        saver = InMemorySaver()
        config = {"configurable": {"thread_id": pipeline.binding.physical_thread}}
        with tracing_context(enabled=False):
            await pipeline.build(saver, interrupt_before=[stage] if stage else None).ainvoke(
                pipeline.binding.input, config, durability="sync"
            )
        restored = ContextualQaPipeline(pipeline.binding, central, candidates, chat, use_case)
        graph = restored.build(saver)
        snapshot = await graph.aget_state(config)
        await restored.before_restore(snapshot.values)
        with tracing_context(enabled=False):
            result = await graph.ainvoke(None, config, durability="sync")
        await restored.before_complete(result)
        assert len(embedding.calls) == 1 and len(chat.calls) == 2
        assert result["query"]["mode"] == "REWRITTEN"

    asyncio.run(scenario())


def test_context_revocation_after_rewrite_blocks_resume_before_retrieval_or_model():
    async def scenario():
        pipeline, central, embedding, candidates, chat, use_case = await setup()
        saver = InMemorySaver()
        config = {"configurable": {"thread_id": pipeline.binding.physical_thread}}
        with tracing_context(enabled=False):
            await pipeline.build(saver, interrupt_before=["retrieve"]).ainvoke(
                pipeline.binding.input, config, durability="sync"
            )

        async def revoked(task_id):
            raise QaError(QaCode.EVIDENCE_REJECTED)

        central.generation_manifest = revoked
        restored = ContextualQaPipeline(pipeline.binding, central, candidates, chat, use_case)
        snapshot = await restored.build(saver).aget_state(config)
        with pytest.raises(QaError):
            await restored.before_restore(snapshot.values)
        assert not embedding.calls and len(chat.calls) == 1

    asyncio.run(scenario())


def test_invalid_context_owner_or_scope_fails_before_any_model_request():
    async def scenario():
        pipeline, central, embedding, _, chat, _ = await setup()
        central.previous = central.previous.model_copy(update={"owner_id": "other-owner"})
        with pytest.raises(QaError):
            await invoke(pipeline)
        assert not chat.calls and not embedding.calls

    asyncio.run(scenario())
