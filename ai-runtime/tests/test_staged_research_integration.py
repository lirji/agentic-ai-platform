"""真实PG/Tasks行锁验证阶段恢复、取消及中央提交未知结果；无付费调用。"""

import asyncio
import os
from contextlib import AsyncExitStack, asynccontextmanager

import pytest
from langsmith import tracing_context
from test_generation_execution_integration import services as services  # noqa: F401
from test_research_execution_integration import ResearchCase
from test_staged_research import StagedChat

from agentic_ai.agents.staged_research_pipeline import StagedResearchPipeline
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import TaskStatus

pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_STAGED_RESEARCH_PG_IT") != "1", reason="需显式启用隔离阶段恢复验收"
)


class StagedCase(ResearchCase):
    """保持父夹具的隔离库/任务记录，执行真正v4图。"""

    def __init__(self, services, stack):
        super().__init__(services, stack)
        self.graph = "knowledge-work-v4"
        self.input = {**self.input, "schemaVersion": "knowledge-work-input-v4"}
        self.chat = StagedChat()

    def pipeline(self, client, lease):
        return StagedResearchPipeline(lease, client, self.knowledge, self.chat, self.use_case)


@asynccontextmanager
async def case(services):
    with tracing_context(enabled=False):
        async with AsyncExitStack() as stack:
            task = StagedCase(services, stack)
            await task.submit()
            yield task


@pytest.mark.parametrize("stage,calls", [("plan", 0), ("analyze", 1), ("verify", 2), (None, 2)])
def test_real_pg_takeover_resumes_confirmed_stage_and_preserves_plan(services, stage, calls):
    async def scenario():
        async with case(services) as task:
            first, lease = await task.claim()
            await task.pause(first, lease, stage)
            assert task.chat.calls == calls
            await task.expire()
            second, resumed = await task.claim()
            result = await task.execute(second, resumed)
            assert result.status == TaskStatus.SUCCEEDED, result.error_code
            assert task.chat.calls == 2 and task.chat.plan_calls == 1
            assert result.result["content"]["usage"]["modelCalls"] == 2
            assert await task.execute(second, resumed) == result
            assert task.chat.calls == 2

    asyncio.run(scenario())


@pytest.mark.parametrize("committed", [True, False])
def test_stage_commit_unknown_reconciles_or_counts_repeated_unconfirmed_model(services, committed):
    async def scenario():
        async with case(services) as task:
            first, lease = await task.claim()
            original = first.checkpoint
            lost = False

            async def uncertain(*args):
                nonlocal lost
                if task.chat.analysis_calls and not lost:
                    lost = True
                    if committed:
                        await original(*args)
                    raise TaskRuntimeError(TaskRuntimeCode.UNAVAILABLE)
                return await original(*args)

            first.checkpoint = uncertain
            if committed:
                await task.pause(first, lease, None)
            else:
                with pytest.raises(TaskRuntimeError) as failure:
                    await task.pause(first, lease, None)
                assert failure.value.code == TaskRuntimeCode.UNAVAILABLE
            assert lost and task.chat.calls == 2
            await task.expire()
            second, resumed = await task.claim()
            result = await task.execute(second, resumed)
            assert result.status == TaskStatus.SUCCEEDED, result.error_code
            expected = 2 if committed else 3
            assert task.chat.calls == expected and task.chat.plan_calls == 1
            assert result.result["content"]["usage"]["modelCalls"] == expected

    asyncio.run(scenario())


def test_cancelled_confirmed_plan_never_starts_report(services):
    async def scenario():
        async with case(services) as task:
            client, lease = await task.claim()
            await task.pause(client, lease, "analyze")
            current = await client.inspect(task.task_id)
            await task.cancel(current.task.revision)
            result = await task.execute(client, lease)
            assert result.status == TaskStatus.CANCELLED and task.chat.calls == 1

    asyncio.run(scenario())


def test_expired_worker_cannot_continue_confirmed_plan(services):
    async def scenario():
        async with case(services) as task:
            first, lease = await task.claim()
            await task.pause(first, lease, "analyze")
            await task.expire()
            second, resumed = await task.claim()
            with pytest.raises(TaskRuntimeError) as failure:
                await task.execute(first, lease)
            assert failure.value.code == TaskRuntimeCode.LEASE_LOST and task.chat.calls == 1
            assert (await task.execute(second, resumed)).status == TaskStatus.SUCCEEDED

    asyncio.run(scenario())


def test_revoked_evidence_after_confirmed_plan_stops_before_model(services):
    async def scenario():
        async with case(services) as task:
            first, lease = await task.claim()
            await task.pause(first, lease, "analyze")
            await task.expire()

            async def revoked(*args):
                return ()

            task.knowledge.resolve = revoked
            second, resumed = await task.claim()
            result = await task.execute(second, resumed)
            assert result.status == TaskStatus.FAILED and result.error_code == "INPUT_REJECTED"
            assert task.chat.calls == 1

    asyncio.run(scenario())


@pytest.mark.parametrize("stage", ["analyze", "verify", None])
def test_legacy_v1_keeps_original_graph_and_restores_with_new_worker(services, stage):
    async def scenario():
        from test_generation_execution_integration import PersistentCase
        from test_qa_pipeline import Knowledge
        from test_work_pipeline import ToolsChat, work_input

        from agentic_ai.agents.pipeline import WorkPipeline
        from agentic_ai.tasks.checkpointer import FencedPostgresSaver
        from agentic_ai.tasks.postgres import PostgresCheckpoints

        with tracing_context(enabled=False):
            async with AsyncExitStack() as stack:
                task = PersistentCase(services, stack, "work")
                task.input, task.graph = work_input(), "knowledge-work-v1"
                task.chat = ToolsChat()
                await task.submit()
                first, lease = await task.claim()
                store = await stack.enter_async_context(
                    PostgresCheckpoints.open(services.checkpoints)
                )
                saver = FencedPostgresSaver(lease, first, store)
                pipeline = WorkPipeline(lease, first, Knowledge(), task.chat)
                graph = pipeline.build(saver, interrupt_before=[stage] if stage else None)
                await graph.ainvoke(lease.input, saver.config, durability="sync")
                await task.expire()
                second, resumed = await task.claim()
                result = await task.execute(second, resumed)
                assert result.status == TaskStatus.SUCCEEDED, result.error_code
                assert result.result["schemaVersion"] == "knowledge-work-result-v1"
                assert task.chat.calls == 2

    asyncio.run(scenario())
