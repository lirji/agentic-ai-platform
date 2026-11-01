"""真实MySQL中央额度和PG检查点接管；知识/模型为协议替身，未调用付费模型。"""

import asyncio
import json
import os
from contextlib import AsyncExitStack, asynccontextmanager

import pytest
from langsmith import tracing_context
from test_generation_execution_integration import PersistentCase
from test_generation_execution_integration import services as services  # noqa: F401
from test_research_coverage import setup

from agentic_ai.agents.research_pipeline import ResearchPipeline
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import TaskStatus
from agentic_ai.tasks.research import ResearchCall, ResearchUsage

pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_RESEARCH_PG_IT") != "1", reason="需显式启用隔离研究PG/中央HTTP验收"
)


class ResearchCase(PersistentCase):
    """复用既有隔离服务与失租注入，只替换本片正式输入和实际图。"""

    def __init__(self, services, stack):
        super().__init__(services, stack, "work")
        pipeline, _, self.embedding, self.knowledge, self.chat, self.use_case = setup()
        self.input, self.graph = pipeline.binding.input, pipeline.binding.graph_version
        services.local.private(
            services.directory / (self.task_id + ".json"),
            json.dumps({"taskId": self.task_id, "tenant": self.tenant, "graph": self.graph}),
        )

    def pipeline(self, client, lease):
        return ResearchPipeline(lease, client, self.knowledge, self.chat, self.use_case)


@asynccontextmanager
async def case(services):
    with tracing_context(enabled=False):
        async with AsyncExitStack() as stack:
            task = ResearchCase(services, stack)
            await task.submit()
            yield task


@pytest.mark.parametrize("stage", ["analyze", "verify", None])
def test_takeover_keeps_plan_and_full_evidence_without_replaying_confirmed_calls(services, stage):
    async def scenario():
        async with case(services) as task:
            first, lease = await task.claim()
            dependencies, snapshot = await task.pause(first, lease, stage)
            if stage != "analyze":
                assert snapshot.values["plan"]["items"][0]["id"] == "Q1"
            await task.expire()
            second, resumed = await task.claim()
            assert resumed.epoch == lease.epoch + 1 and resumed.checkpoint is not None
            result = await task.execute(second, resumed)
            assert result.status == TaskStatus.SUCCEEDED, result.error_code
            assert result.result["content"]["outcome"] == "PARTIAL"
            assert result.result["content"]["usage"] == {
                "modelCalls": 2,
                "toolCalls": 0,
                "searchCalls": 1,
            }
            assert task.chat.calls == 2 and len(task.embedding.calls) == 1
            assert set((await second.generation_manifest(task.task_id)).references) == set(
                dependencies
            )
            assert await task.execute(second, resumed) == result
            assert task.chat.calls == 2

    asyncio.run(scenario())


def test_prior_epoch_model_reservations_cannot_be_reset_by_recovery(services):
    async def scenario():
        async with case(services) as task:
            first, lease = await task.claim()
            await task.pause(first, lease, "analyze")
            for index in range(3):
                await first.reserve_research(
                    task.task_id, lease.epoch, f"unknown-{index}", ResearchCall.MODEL
                )
            await task.expire()
            second, resumed = await task.claim()
            assert await second.research_usage(task.task_id, resumed.epoch) == ResearchUsage(
                model_calls=3, tool_calls=0, search_calls=1
            )
            result = await task.execute(second, resumed)
            assert result.status == TaskStatus.FAILED and result.error_code == "INPUT_REJECTED"
            assert not result.retryable and task.chat.calls == 0 and len(task.embedding.calls) == 1

    asyncio.run(scenario())


def test_initial_reservation_without_confirmed_retrieval_is_not_reissued(services):
    async def scenario():
        async with case(services) as task:
            first, lease = await task.claim()
            await first.reserve_research(
                task.task_id, lease.epoch, "initial-unknown", ResearchCall.INITIAL_SEARCH
            )
            await task.expire()
            second, resumed = await task.claim()
            result = await task.execute(second, resumed)
            assert result.status == TaskStatus.FAILED and result.error_code == "INPUT_REJECTED"
            assert not task.embedding.calls and task.chat.calls == 0

    asyncio.run(scenario())


def test_lost_reservation_response_never_sends_model_request_and_remains_counted(services):
    async def scenario():
        async with case(services) as task:
            client, lease = await task.claim()
            reserve = client.reserve_research

            async def lose_reply(task_id, epoch, key, kind):
                receipt = await reserve(task_id, epoch, key, kind)
                if kind == ResearchCall.MODEL:
                    raise TaskRuntimeError(TaskRuntimeCode.UNAVAILABLE)
                return receipt

            client.reserve_research = lose_reply
            result = await task.execute(client, lease)
            assert result.status == TaskStatus.FAILED and result.error_code == "AI_UNAVAILABLE"
            assert task.chat.calls == 0 and len(task.embedding.calls) == 1
            # 持久事件读取保留预留；失败终态不绕过租约去调用额度接口。
            rows = await asyncio.to_thread(
                services.local.mysql,
                f"SELECT payload_json FROM {services.database}.ai_task_event "
                f"WHERE task_id='{task.task_id}' "
                "AND operation_key LIKE 'b:%' ORDER BY sequence DESC LIMIT 1;",
            )
            assert json.loads(rows.strip())["reservation"]["usage"]["modelCalls"] == 1

    asyncio.run(scenario())


def test_revoked_dependency_before_resume_does_not_generate_or_expose_report(services):
    async def scenario():
        async with case(services) as task:
            first, lease = await task.claim()
            await task.pause(first, lease, "verify")
            await task.expire()

            async def revoked(*args):
                return ()

            task.knowledge.resolve = revoked
            second, resumed = await task.claim()
            result = await task.execute(second, resumed)
            assert result.status == TaskStatus.FAILED and result.error_code == "INPUT_REJECTED"
            assert task.chat.calls == 2

    asyncio.run(scenario())
