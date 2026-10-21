"""真实中央/MySQL/PG 下的研究阶段恢复、取消和撤权；不调用付费模型。"""

import asyncio
import os
from contextlib import AsyncExitStack, asynccontextmanager

import httpx
import pytest
from langsmith import tracing_context
from test_qa_pipeline import Chat, Knowledge
from test_task_checkpoints_integration import CENTRAL, RealTask
from test_work_pipeline import ToolsChat, work_input

from agentic_ai.agents.models import WORK_GRAPH
from agentic_ai.agents.pipeline import WorkPipeline
from agentic_ai.qa.worker import execute_qa_task
from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.models import TaskStatus
from agentic_ai.tasks.postgres import CheckpointConfig

pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_WORK_IT") != "1", reason="需显式启用本项目研究恢复验收"
)


@asynccontextmanager
async def work_task():
    with tracing_context(enabled=False):
        async with AsyncExitStack() as stack:
            task = RealTask(stack)
            task.input = work_input()
            task.chat, task.knowledge = ToolsChat(), Knowledge()
            async with httpx.AsyncClient(trust_env=False, timeout=5) as http:
                result = await http.post(
                    CENTRAL + "/internal/v1/tasks",
                    headers={
                        "X-Internal-Token": task.sign("submit", task.tenant, task.owner),
                        "Idempotency-Key": task.task_id,
                    },
                    json={
                        "taskId": task.task_id,
                        "kind": "RESEARCH",
                        "runtimeVersion": "langgraph-v1",
                        "graphVersion": WORK_GRAPH,
                        "input": task.input,
                    },
                )
                assert result.status_code == 202
            yield task


async def execute(task, client, lease, *, config=None):
    return await execute_qa_task(
        lease, client, config or task.config, task.knowledge, Chat(), task.chat
    )


@pytest.mark.parametrize("stage", ["analyze", "verify"])
def test_real_takeover_recovers_only_root_and_reuses_completed_stages(stage):
    async def scenario():
        async with work_task() as task:
            first = await task.client()
            lease = await first.claim(WORK_GRAPH)
            storage = await task.storage()
            saver = FencedPostgresSaver(lease, first, storage)
            pipeline = WorkPipeline(lease, first, task.knowledge, task.chat)
            await pipeline.build(saver, interrupt_before=[stage]).ainvoke(
                lease.input, saver.config, durability="sync"
            )
            assert task.chat.calls == (2 if stage == "verify" else 0)
            assert task.knowledge.searches == 1
            await task.expire()
            second = await task.client()
            resumed = await second.claim(WORK_GRAPH)
            assert resumed.epoch == lease.epoch + 1
            result = await execute(task, second, resumed)
            assert result.status == TaskStatus.SUCCEEDED
            assert result.result["schemaVersion"] == "knowledge-work-result-v1"
            assert task.chat.calls == 2 and task.knowledge.searches == 1
            repeat = await execute(
                task,
                second,
                resumed,
                config=CheckpointConfig(dsn="postgresql://127.0.0.1:1/unavailable"),
            )
            assert repeat == result and task.chat.calls == 2
            latest = await second.inspect(task.task_id)
            snapshot = await storage.saver.aget_tuple(
                {
                    "configurable": {
                        "thread_id": latest.checkpoint.thread_id,
                        "checkpoint_ns": "",
                        "checkpoint_id": latest.checkpoint.checkpoint_id,
                    }
                }
            )
            assert "messages" not in snapshot.checkpoint["channel_values"]

    asyncio.run(scenario())


def test_final_snapshot_rechecks_revoked_sources():
    async def scenario():
        async with work_task() as task:
            first = await task.client()
            lease = await first.claim(WORK_GRAPH)
            saver = FencedPostgresSaver(lease, first, await task.storage())
            await (
                WorkPipeline(lease, first, task.knowledge, task.chat)
                .build(saver)
                .ainvoke(lease.input, saver.config, durability="sync")
            )
            await task.expire()
            second = await task.client()
            resumed = await second.claim(WORK_GRAPH)
            task.knowledge.revoked = True
            result = await execute(task, second, resumed)
            assert result.status == TaskStatus.FAILED and result.error_code == "INPUT_REJECTED"
            assert task.chat.calls == 2

    asyncio.run(scenario())


def test_cancellation_during_model_stops_tool_and_final_submission():
    async def scenario():
        async with work_task() as task:
            first = await task.client()
            lease = await first.claim(WORK_GRAPH)
            original = task.chat.complete

            async def cancel_then_reply(messages, tools, operation_id):
                current = await first.inspect(task.task_id)
                async with httpx.AsyncClient(trust_env=False, timeout=5) as http:
                    response = await http.post(
                        f"http://127.0.0.1:28080/api/v1/tasks/{task.task_id}/cancel",
                        headers={
                            "Authorization": "Bearer "
                            + task.local.token(task.tenant, task.owner, "task:read task:write")
                        },
                        json={"expectedRevision": current.task.revision},
                    )
                    assert response.status_code == 200
                return await original(messages, tools, operation_id)

            task.chat.complete = cancel_then_reply
            result = await execute(task, first, lease)
            assert result.status == TaskStatus.CANCELLED and result.result is None
            assert task.chat.calls == 1

    asyncio.run(scenario())
