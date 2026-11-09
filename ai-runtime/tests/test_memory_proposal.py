"""受控提议的原生图、未知回执、撤销和身份测试；不会调用真实模型。"""

import asyncio
import json
from uuid import uuid4

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric.rsa import generate_private_key
from langgraph.checkpoint.memory import InMemorySaver
from langsmith import tracing_context
from pydantic import ValidationError
from test_qa_pipeline import Central, lease

from agentic_ai.memory.client import MemoryClient
from agentic_ai.memory.errors import MemoryCode, MemoryError
from agentic_ai.memory.models import CallPermit, CandidateBatch, ProposalResult, ProposalSource
from agentic_ai.memory.proposal_pipeline import MemoryProposalPipeline, parse_batch
from agentic_ai.qa.errors import failure_code
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import TaskFailureCode, TaskLease

QUESTION = "我偏好中文简洁回答，星河项目使用关系数据库。"
BATCH = {
    "candidates": [
        {"category": "PREFERENCE", "content": "偏好中文简洁回答", "quote": "我偏好中文简洁回答"}
    ]
}


def memory_lease():
    value = lease().model_dump(mode="json", by_alias=True)
    value["task"]["kind"] = "MEMORY_PROPOSAL"
    value["graphVersion"] = "memory-proposal-v1"
    value["input"] = {
        "schemaVersion": "memory-proposal-input-v1",
        "proposalId": str(uuid4()),
        "conversationId": str(uuid4()),
        "turnId": str(uuid4()),
        "generation": 0,
    }
    return TaskLease.model_validate_json(json.dumps(value))


class Memory:
    def __init__(self, binding):
        self.binding, self.receipt = binding, None
        self.calls, self.applies = [], 0
        self.revoked, self.drop, self.revoke_after_permit = False, False, False

    async def source(self):
        if self.revoked and self.receipt is None:
            raise MemoryError(MemoryCode.STALE)
        return ProposalSource.model_validate_json(
            json.dumps(
                {
                    "schemaVersion": "memory-proposal-source-v1",
                    "proposalId": self.binding.input["proposalId"],
                    "taskId": self.binding.task.task_id,
                    "generation": 0,
                    "question": None if self.receipt else QUESTION,
                    "receipt": self.receipt.model_dump(mode="json", by_alias=True)
                    if self.receipt
                    else None,
                }
            )
        )

    async def permit(self, call):
        self.calls.append(call)
        if self.revoke_after_permit:
            self.revoked = True
        return CallPermit.model_validate_json(
            '{"schemaVersion":"memory-call-permit-v1","ordinal":1}'
        )

    async def apply(self, call, batch):
        if self.revoked:
            raise MemoryError(MemoryCode.STALE)
        assert call in self.calls and batch == CandidateBatch.model_validate_json(json.dumps(BATCH))
        self.applies += 1
        self.receipt = ProposalResult.model_validate_json(
            json.dumps(
                {
                    "schemaVersion": "memory-proposal-result-v1",
                    "proposalId": self.binding.input["proposalId"],
                    "memoryIds": [str(uuid4())],
                }
            )
        )
        if self.drop:
            raise MemoryError(MemoryCode.UNAVAILABLE)
        return self.receipt


class Chat:
    def __init__(self, output=None, callback=None):
        self.output = json.dumps(BATCH, ensure_ascii=False) if output is None else output
        self.callback, self.calls, self.messages = callback, 0, []

    async def stream(self, messages, operation):
        self.calls += 1
        self.messages = messages
        assert ":memory:" in operation
        if self.callback:
            self.callback()
        yield self.output


def setup(chat=None):
    binding = memory_lease()
    central, memory, chat = Central(binding), Memory(binding), chat or Chat()
    return MemoryProposalPipeline(binding, central, memory, chat), central, memory, chat


def test_native_graph_checkpoints_never_contain_question_or_candidates_and_recovery_uses_receipt():
    async def scenario():
        pipeline, central, memory, chat = setup()
        memory.drop = True
        saver = InMemorySaver()
        graph = pipeline.build(saver)
        config = {"configurable": {"thread_id": pipeline.binding.task.task_id}}
        with tracing_context(enabled=False):
            result = await graph.ainvoke(pipeline.binding.input, config)
            await pipeline.before_complete(result)
            async for checkpoint in graph.aget_state_history(config):
                encoded = json.dumps(checkpoint.values, ensure_ascii=False)
                assert QUESTION not in encoded
                assert (
                    "偏好中文简洁回答" not in encoded
                    and "candidates" not in encoded
                    and "quote" not in encoded
                )
            snapshot = await graph.aget_state(config)
            await pipeline.before_restore(snapshot.values)
            recovered = await pipeline.propose(pipeline.binding.input)
        assert recovered == result and chat.calls == memory.applies == 1
        assert len(memory.calls) == 1
        assert all(
            kind == "PROGRESS" and set(payload) == {"stage"}
            for kind, payload in central.events.values()
        )
        assert len(chat.messages) == 2 and QUESTION in chat.messages[1].content
        assert "不可信数据" in chat.messages[0].content
        memory.revoked = True  # 已应用后遗忘，元数据仍可查；不会重新写入正文。
        await pipeline.before_complete(result)
        assert chat.calls == memory.applies == 1

    asyncio.run(scenario())


@pytest.mark.parametrize("during_model", [False, True])
def test_revoke_before_send_or_during_model_prevents_application(during_model):
    async def scenario():
        pipeline, _, memory, chat = setup()
        if during_model:
            chat.callback = lambda: setattr(memory, "revoked", True)
        else:
            memory.revoke_after_permit = True
        with pytest.raises(MemoryError, match="MEMORY_STALE"):
            await pipeline.propose(pipeline.binding.input)
        assert chat.calls == int(during_model) and memory.applies == 0

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "raw",
    [
        '{"candidates":[],"confirm":true}',
        '{"candidates":[],"candidates":[]}',
        '{"candidates":[{"category":"SECRET","content":"x","quote":"x"}]}',
        '{"candidates":[{"category":"PREFERENCE","content":"x","quote":"x","status":"ACTIVE"}]}',
        json.dumps({"candidates": BATCH["candidates"] * 4}),
        json.dumps({"candidates": BATCH["candidates"] * 2}),
        json.dumps(
            {"candidates": [{"category": "PREFERENCE", "content": "x" * 501, "quote": "x"}]}
        ),
        json.dumps(
            {"candidates": [{"category": "PREFERENCE", "content": "x\u0001", "quote": "x"}]}
        ),
        '```json\n{"candidates":[]}\n```',
    ],
)
def test_model_cannot_add_control_fields_or_override_json_keys(raw):
    with pytest.raises(MemoryError, match="MEMORY_PROTOCOL_INVALID"):
        parse_batch(raw)


def test_unsupported_quote_overflow_and_cancel_never_apply():
    async def scenario():
        invalid = {
            "candidates": [{"category": "PREFERENCE", "content": "偏好", "quote": "没有原文支持"}]
        }
        for output in (json.dumps(invalid), "x" * 4097):
            pipeline, _, memory, _ = setup(Chat(output))
            with pytest.raises(MemoryError, match="MEMORY_PROTOCOL_INVALID"):
                await pipeline.propose(pipeline.binding.input)
            assert memory.applies == 0
        pipeline, central, memory, chat = setup()
        central.cancelled = True
        with pytest.raises(TaskRuntimeError, match=TaskRuntimeCode.LEASE_LOST):
            await pipeline.propose(pipeline.binding.input)
        assert not memory.calls and chat.calls == 0

    asyncio.run(scenario())


def test_internal_client_has_independent_audience_and_owner_and_rejects_bound_id_drift():
    async def scenario():
        binding = memory_lease()
        memory = Memory(binding)
        key = generate_private_key(public_exponent=65537, key_size=2048)
        drift = False

        def respond(request):
            claims = jwt.decode(
                request.headers["X-Internal-Token"],
                key.public_key(),
                algorithms=["RS256"],
                audience="agentic-ai-memory",
                issuer="urn:agentic-ai:runtime-memory",
            )
            assert claims["scope"] == "memory:execute" and claims["sub"] == binding.owner_id
            assert claims["tenant_id"] == binding.tenant_id and claims["exp"] - claims["iat"] == 60
            assert (
                request.url.path
                == f"/internal/v1/memory/proposals/{binding.input['proposalId']}/source"
            )
            assert request.url.params["taskId"] == binding.task.task_id
            return httpx.Response(
                200,
                json={
                    "schemaVersion": "memory-proposal-source-v1",
                    "proposalId": binding.input["proposalId"],
                    "taskId": str(uuid4()) if drift else binding.task.task_id,
                    "generation": 0,
                    "question": QUESTION,
                    "receipt": None,
                },
            )

        client = MemoryClient(
            "http://127.0.0.1:1", binding, key, transport=httpx.MockTransport(respond)
        )
        try:
            assert await client.source() == await memory.source()
            drift = True
            with pytest.raises(MemoryError, match="MEMORY_PROTOCOL_INVALID"):
                await client.source()
        finally:
            await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "endpoint",
    [
        "http://example.com",
        "http://localhost:0",
        "https://x/path",
        "https://u:p@x",
        "https://x?y=1",
        "https://x#f",
    ],
)
def test_unsafe_endpoint_is_rejected_before_claim(endpoint):
    with pytest.raises(MemoryError, match="MEMORY_CONFIG_INVALID"):
        MemoryClient.validate_endpoint(endpoint)


def test_wire_closure_and_safe_failure_codes():
    assert failure_code(MemoryError(MemoryCode.STALE)) == (TaskFailureCode.MEMORY_STALE, False)
    assert failure_code(MemoryError(MemoryCode.CALL_LIMIT)) == (
        TaskFailureCode.MEMORY_CALL_LIMIT,
        False,
    )
    assert failure_code(MemoryError(MemoryCode.UNAVAILABLE)) == (
        TaskFailureCode.AI_UNAVAILABLE,
        True,
    )
    with pytest.raises(ValidationError):
        ProposalResult.model_validate(
            {
                "schema_version": "memory-proposal-result-v1",
                "proposalId": str(uuid4()),
                "memoryIds": [],
            }
        )
