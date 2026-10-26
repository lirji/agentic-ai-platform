"""原生 LangGraph/LlamaIndex 编排测试；受控端口不等于真实模型质量验收。"""

import asyncio
import json
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from langsmith import tracing_context

from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.models import KnowledgeHit, QaResult
from agentic_ai.qa.pipeline import QaPipeline
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import TaskLease, TaskStatus

DOCUMENT = "00000000-0000-4000-8000-000000000001"
HIT = KnowledgeHit.model_validate(
    {
        "documentId": DOCUMENT,
        "version": 1,
        "chunkId": "a" * 64,
        "text": "这是一份合成说明，只读助手不执行外部写操作。",
        "score": 0.02,
        "source": {"displayName": "合成说明", "locator": "utf8-bytes:0-66"},
    }
)


def question(**updates):
    return {
        "schemaVersion": "knowledge-qa-input-v1",
        "conversationId": str(uuid4()),
        "turnId": str(uuid4()),
        "question": "助手可以执行什么？",
        "documentIds": [DOCUMENT],
        "contextTurnId": None,
        "contextTaskId": None,
        "contextQuestion": None,
        **updates,
    }


def lease(*, input_value=None, task_id=None, **updates):
    now = datetime.now(UTC)
    return TaskLease.model_validate_json(
        json.dumps(
            {
                "task": {
                    "taskId": task_id or str(uuid4()),
                    "kind": "KNOWLEDGE_QA",
                    "status": "RUNNING",
                    "revision": 1,
                    "attemptCount": 1,
                    "resumeCount": 0,
                    "retryable": False,
                    "errorCode": None,
                    "result": None,
                    "resultSha256": None,
                    "createdAt": now.isoformat(),
                    "updatedAt": now.isoformat(),
                },
                "tenantId": "synthetic-tenant",
                "ownerId": "synthetic-owner",
                "input": input_value or question(),
                "runtimeVersion": "langgraph-v1",
                "graphVersion": "knowledge-qa-v1",
                "epoch": 1,
                "workerId": "synthetic-worker",
                "leaseExpiresAt": (now + timedelta(seconds=30)).isoformat(),
                "checkpoint": None,
                **updates,
            }
        )
    )


class Central:
    def __init__(self, binding):
        self.binding = binding
        self.tenant_id, self.worker_id = binding.tenant_id, binding.worker_id
        self.events = {}
        self.previous = None
        self.cancelled = False
        self.beats = 0
        self.manifests = []

    async def knowledge_evidence(self, task_id, epoch, references):
        assert task_id == self.binding.task.task_id and epoch == self.binding.epoch
        current = {(item.document_id, item.version, item.chunk_id) for item in references}
        assert not self.manifests or current.issuperset(self.manifests[-1])
        self.manifests.append(current)

    async def heartbeat(self, task_id, epoch):
        assert task_id == self.binding.task.task_id and epoch == self.binding.epoch
        self.beats += 1
        if self.cancelled:
            raise TaskRuntimeError(TaskRuntimeCode.LEASE_LOST)
        return self.binding

    async def event(self, task_id, epoch, key, kind, payload):
        assert task_id == self.binding.task.task_id and epoch == self.binding.epoch
        value = (kind.value, payload)
        assert key not in self.events or self.events[key] == value
        self.events[key] = value

    async def inspect(self, task_id):
        if task_id == self.binding.task.task_id:
            return self.binding
        assert self.previous and self.previous.task.task_id == task_id
        return self.previous


class Knowledge:
    def __init__(self, hits=(HIT,)):
        self.hits = hits
        self.searches = 0
        self.reads = 0
        self.revoked = False
        self.changed = None

    async def search(self, query, document_ids):
        assert query and document_ids == [DOCUMENT]
        self.searches += 1
        return self.hits

    async def citation(self, value):
        self.reads += 1
        if self.revoked:
            raise QaError(QaCode.EVIDENCE_REJECTED)
        return self.changed or next(hit for hit in self.hits if hit.chunk_id == value.chunk_id)


class Chat:
    def __init__(self, parts=("只能提供只读解释。[1]",), before_yield=None):
        self.parts, self.before_yield = parts, before_yield
        self.calls = 0
        self.messages = None
        self.closed = False

    async def stream(self, messages, operation):
        self.calls += 1
        self.messages = messages
        assert operation.startswith("qa:")
        try:
            for part in self.parts:
                if self.before_yield:
                    self.before_yield()
                yield part
        finally:
            self.closed = True


def setup(*, input_value=None, knowledge=None, chat=None):
    binding = lease(input_value=input_value)
    central, knowledge, chat = Central(binding), knowledge or Knowledge(), chat or Chat()
    pipeline = QaPipeline(binding, central, knowledge, chat)
    return pipeline, central, knowledge, chat


async def invoke(pipeline):
    with tracing_context(enabled=False):
        return await pipeline.build(InMemorySaver()).ainvoke(
            pipeline.binding.input,
            {"configurable": {"thread_id": pipeline.binding.physical_thread}},
            durability="sync",
        )


def test_real_native_graph_filters_output_and_uses_authorized_metadata():
    async def scenario():
        pipeline, central, knowledge, chat = setup()
        result = await invoke(pipeline)
        assert set(result) == {"schemaVersion", "answerability", "answer", "citations"}
        parsed = QaResult.model_validate_json(json.dumps(result))
        assert parsed.citations[0].source == HIT.source
        await pipeline.before_complete(result)
        assert knowledge.searches == 1 and knowledge.reads == 3 and chat.calls == 1
        assert [kind for kind, _ in central.events.values()] == [
            "PROGRESS",
            "PROGRESS",
            "TEXT_DELTA",
            "PROGRESS",
        ]
        payload = json.loads(chat.messages[1].content)
        assert payload["evidence"][0]["citationId"] == "1"
        assert payload["evidence"][0]["text"] == HIT.text
        assert chat.closed and "tenant" not in chat.messages[1].content

    asyncio.run(scenario())


def test_no_evidence_finishes_truthfully_without_chat():
    async def scenario():
        pipeline, central, knowledge, chat = setup(knowledge=Knowledge(()))
        result = await invoke(pipeline)
        assert result["answerability"] == "NO_EVIDENCE" and result["citations"] == []
        assert chat.calls == 0 and knowledge.searches == 1
        assert all(kind != "TEXT_DELTA" for kind, _ in central.events.values())
        await pipeline.before_complete(result)

    asyncio.run(scenario())


@pytest.mark.parametrize("answer", ["无出处", "伪造出处[2]", "混入未知编号[1][999]", " "])
def test_invalid_model_citations_never_become_final_result(answer):
    async def scenario():
        pipeline, _, _, chat = setup(chat=Chat((answer,)))
        with pytest.raises(QaError, match=QaCode.ANSWER_INVALID):
            await invoke(pipeline)
        assert chat.closed

    asyncio.run(scenario())


@pytest.mark.parametrize("stage", ["generate", "verify"])
def test_revoked_evidence_stops_resumed_graph_before_further_model_use(stage):
    async def scenario():
        pipeline, central, knowledge, chat = setup()
        saver = InMemorySaver()
        config = {"configurable": {"thread_id": pipeline.binding.physical_thread}}
        with tracing_context(enabled=False):
            await pipeline.build(saver, interrupt_before=[stage]).ainvoke(
                pipeline.binding.input, config, durability="sync"
            )
            previous_calls = chat.calls
            knowledge.revoked = True
            with pytest.raises(QaError, match=QaCode.EVIDENCE_REJECTED):
                await pipeline.build(saver).ainvoke(None, config, durability="sync")
        assert knowledge.searches == 1
        assert chat.calls == previous_calls
        assert previous_calls == (1 if stage == "verify" else 0)

    asyncio.run(scenario())


def test_completed_snapshot_final_callback_checks_current_permission():
    async def scenario():
        pipeline, _, knowledge, _ = setup()
        result = await invoke(pipeline)
        knowledge.revoked = True
        with pytest.raises(QaError, match=QaCode.EVIDENCE_REJECTED):
            await pipeline.before_complete(result)

    asyncio.run(scenario())


def context_for(pipeline, central):
    previous_question = question(conversationId=pipeline.question.conversation_id)
    result = {
        "schemaVersion": "knowledge-qa-result-v1",
        "answerability": "ANSWERED",
        "answer": "旧回答只读。[1]",
        "citations": [
            {
                "citationId": "1",
                **HIT.model_dump(mode="json", by_alias=True, exclude={"text", "score"}),
            }
        ],
    }
    previous = lease(input_value=previous_question)
    previous = previous.model_copy(
        update={
            "task": previous.task.model_copy(
                update={"status": TaskStatus.SUCCEEDED, "result": result}
            )
        }
    )
    current_input = {
        **pipeline.binding.input,
        "contextTurnId": previous_question["turnId"],
        "contextTaskId": previous.task.task_id,
        "contextQuestion": previous_question["question"],
    }
    binding = pipeline.binding.model_copy(update={"input": current_input})
    central.binding, central.previous = binding, previous
    return QaPipeline(binding, central, pipeline.knowledge, pipeline.chat)


def test_context_requires_matching_central_fact_and_revalidates_old_citations():
    async def scenario():
        original, central, knowledge, chat = setup()
        pipeline = context_for(original, central)
        await invoke(pipeline)
        assert knowledge.reads == 4
        assert json.loads(chat.messages[1].content)["context"] == {
            "question": central.previous.input["question"],
            "answer": "旧回答只读。[1]",
        }

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "field",
    ["tenant", "owner", "conversation", "turn", "question", "scope", "status", "result"],
)
def test_context_mismatch_never_reaches_chat(field):
    async def scenario():
        original, central, _, chat = setup()
        pipeline = context_for(original, central)
        previous = central.previous
        if field in {"tenant", "owner"}:
            previous = previous.model_copy(update={field + "_id": "other"})
        elif field in {"conversation", "turn", "question", "scope"}:
            key = {
                "conversation": "conversationId",
                "turn": "turnId",
                "question": "question",
                "scope": "documentIds",
            }[field]
            value = [] if field == "scope" else str(uuid4())
            previous = previous.model_copy(update={"input": {**previous.input, key: value}})
        else:
            change = {"status": TaskStatus.CANCELLED} if field == "status" else {"result": {}}
            previous = previous.model_copy(update={"task": previous.task.model_copy(update=change)})
        central.previous = previous
        with pytest.raises(QaError, match=QaCode.EVIDENCE_REJECTED):
            await invoke(pipeline)
        assert chat.calls == 0

    asyncio.run(scenario())


def test_cancellation_closes_stream_without_appending_late_text():
    async def scenario():
        pipeline, central, _, chat = setup(chat=Chat(("x" * 128,)))
        chat.before_yield = lambda: setattr(central, "cancelled", True)
        with pytest.raises(TaskRuntimeError, match=TaskRuntimeCode.LEASE_LOST):
            await invoke(pipeline)
        assert chat.closed
        assert all(kind != "TEXT_DELTA" for kind, _ in central.events.values())

    asyncio.run(scenario())


def test_text_events_use_stable_batches_independent_of_network_splits():
    async def scenario():
        answer = "资料只读。" * 55 + "[1]"
        first, central, knowledge, _ = setup(chat=Chat((answer,)))
        result = await invoke(first)
        expected = dict(central.events)
        second = QaPipeline(first.binding, central, knowledge, Chat(tuple(answer)))
        replay = await invoke(second)
        assert result == replay and expected == central.events
        deltas = [
            payload["text"] for kind, payload in central.events.values() if kind == "TEXT_DELTA"
        ]
        assert "".join(deltas) == answer and all(len(part) <= 1000 for part in deltas)

    asyncio.run(scenario())
