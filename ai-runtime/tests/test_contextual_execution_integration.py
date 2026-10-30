"""v3真实MySQL/PG/中央HTTP恢复；模型与知识正文为协议替身，不花费模型预算。"""

import asyncio
import json
import os
from contextlib import AsyncExitStack
from uuid import uuid4

import pytest
from langsmith import tracing_context
from test_contextual_pipeline import Chat
from test_generation_execution_integration import PersistentCase
from test_generation_execution_integration import services as services  # noqa: F401

from agentic_ai.qa.contextual_pipeline import ContextualQaPipeline
from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.models import TaskStatus
from agentic_ai.tasks.postgres import CheckpointConfig, PostgresCheckpoints

pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_GENERATION_PG_IT") != "1", reason="需显式启用隔离上下文PG/中央HTTP验收"
)


class ContextualCase(PersistentCase):
    def __init__(self, services, stack, previous):
        super().__init__(services, stack, "qa")
        self.kind, self.tenant = "contextual", previous.tenant
        self.graph = "knowledge-qa-v3"
        self.chat = Chat()
        self.input = {
            **previous.input,
            "schemaVersion": "knowledge-qa-input-v3",
            "turnId": str(uuid4()),
            "question": "它有哪些限制？",
            "contextTurnId": previous.input["turnId"],
            "contextTaskId": previous.task_id,
            "contextQuestion": previous.input["question"],
            "queryPolicy": "CONTEXT_REWRITE_V1",
        }
        services.local.private(
            services.directory / (self.task_id + ".json"),
            json.dumps(
                {
                    "taskId": self.task_id,
                    "tenant": self.tenant,
                    "graph": self.graph,
                    "contextTaskId": previous.task_id,
                }
            ),
        )

    def pipeline(self, client, lease):
        return ContextualQaPipeline(lease, client, self.knowledge, self.chat, self.use_case)

    async def pause(self, client, lease, stage):
        store = await self.stack.enter_async_context(
            PostgresCheckpoints.open(self.services.checkpoints)
        )
        saver = FencedPostgresSaver(lease, client, store)
        graph = self.pipeline(client, lease).build(
            saver, interrupt_before=[stage] if stage else None
        )
        await graph.ainvoke(lease.input, saver.config, durability="sync")
        snapshot = await graph.aget_state(saver.config)
        assert snapshot.values["query"]["mode"] == "REWRITTEN"
        assert snapshot.values["contextEvidence"]
        return snapshot


async def create(services, stack):
    previous = PersistentCase(services, stack, "qa")
    await previous.submit()
    client, lease = await previous.claim()
    result = await previous.execute(client, lease)
    assert result.status == TaskStatus.SUCCEEDED, result.error_code
    task = ContextualCase(services, stack, previous)
    await task.submit()
    return task


@pytest.mark.parametrize("stage", ["retrieve", "generate", "verify", None])
def test_contextual_pg_takeover_keeps_original_query_and_confirmed_calls(services, stage):
    async def scenario():
        with tracing_context(enabled=False):
            async with AsyncExitStack() as stack:
                task = await create(services, stack)
                first, lease = await task.claim()
                await task.pause(first, lease, stage)
                await task.expire()
                second, resumed = await task.claim()
                assert resumed.epoch == lease.epoch + 1 and resumed.checkpoint is not None
                result = await task.execute(second, resumed)
                assert result.status == TaskStatus.SUCCEEDED, result.error_code
                assert len(task.embedding.calls) == 1 and len(task.chat.calls) == 2
                assert result.result["query"]["originalQuestion"] == task.input["question"]
                assert result.result["query"]["retrievalQuestion"] != task.input["question"]
                inherited = await second.generation_manifest(task.task_id)
                assert len(inherited.references) > len(result.result["references"])
                replay = await task.execute(
                    second,
                    resumed,
                    checkpoints=CheckpointConfig(dsn="postgresql://127.0.0.1:1/unreachable"),
                )
                assert replay == result and len(task.chat.calls) == 2

    asyncio.run(scenario())


@pytest.mark.parametrize("failure", ["revoke", "cancel"])
def test_contextual_pre_retrieval_checkpoint_rechecks_history_and_cancellation(services, failure):
    async def scenario():
        with tracing_context(enabled=False):
            async with AsyncExitStack() as stack:
                task = await create(services, stack)
                first, lease = await task.claim()
                await task.pause(first, lease, "retrieve")
                if failure == "cancel":
                    current = await first.inspect(task.task_id)
                    await task.cancel(current.task.revision)
                    result = await task.execute(first, lease)
                    assert result.status == TaskStatus.CANCELLED
                else:
                    await task.expire()
                    second, resumed = await task.claim()
                    services.denied.add(task.tenant)
                    result = await task.execute(second, resumed)
                    assert result.status == TaskStatus.FAILED
                assert not task.embedding.calls and len(task.chat.calls) == 1

    asyncio.run(scenario())
