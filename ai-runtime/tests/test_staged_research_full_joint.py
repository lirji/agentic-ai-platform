"""真实发布、PG接管与浏览器恢复位置；所有模型为确定性测试端口。"""

import asyncio
import os
from contextlib import AsyncExitStack

import pytest
from langsmith import tracing_context
from test_evaluation_full_joint import Joint
from test_evaluation_full_joint import joint as joint  # noqa: F401
from test_research_full_joint import ResearchChat, browser, execute, submit
from test_work_pipeline import ToolsChat

from agentic_ai.agents.staged_research_pipeline import StagedResearchPipeline
from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.postgres import PostgresCheckpoints

pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_STAGED_RESEARCH_JOINT_IT") != "1", reason="由真实Java发布夹具启动"
)


def test_real_staged_report_and_recovery_progress_survive_refresh_and_cancel(joint):
    async def scenario():
        with tracing_context(enabled=False):
            async with AsyncExitStack() as stack:
                run = Joint(joint, stack)
                assert (await run.request("GET", "/api/v4/tasks/capabilities", task=True))[
                    "enabled"
                ]
                report_id = await submit(run, staged=True)
                chat = ResearchChat()
                result = await execute(run, report_id, chat, staged=True, restore=True)
                assert result.result["content"]["usage"]["modelCalls"] == chat.calls == 2
                legacy_id = await submit(run, legacy=True)
                await execute(run, legacy_id, ToolsChat(), legacy=True)
                empty = Joint(joint, stack)
                empty.owner, empty.documents = "staged-empty-owner", []
                empty_id = await submit(empty, staged=True)
                empty_chat = ResearchChat()
                assert (await execute(empty, empty_id, empty_chat, staged=True)).result["content"][
                    "outcome"
                ] == "INSUFFICIENT"
                assert empty_chat.calls == 0
                # 在真实已确认计划后接管，发布实际恢复事件；浏览器负责取消，不再启动报告模型。
                recovery_id = await submit(run, staged=True)
                first, lease = await run.client("knowledge-work-v4", recovery_id)
                candidates = await run.candidates()
                store = await stack.enter_async_context(PostgresCheckpoints.open(run.checkpoints))
                saver = FencedPostgresSaver(lease, first, store)
                recovery_chat = ResearchChat()
                pipeline = StagedResearchPipeline(
                    lease, first, candidates, recovery_chat, run.use_case
                )
                graph = pipeline.build(saver, interrupt_before=["analyze"])
                await graph.ainvoke(lease.input, saver.config, durability="sync")
                await run.expire(recovery_id)
                second, resumed = await run.client("knowledge-work-v4", recovery_id)
                restored = StagedResearchPipeline(
                    resumed, second, candidates, recovery_chat, run.use_case
                )
                resumed_saver = FencedPostgresSaver(resumed, second, store)
                snapshot = await restored.build(resumed_saver).aget_state(resumed_saver.config)
                await restored.before_restore(snapshot.values)
                values = {
                    "researchTaskId": report_id,
                    "legacyTaskId": legacy_id,
                    "emptyTaskId": empty_id,
                    "emptyOwner": empty.owner,
                    "recoveryTaskId": recovery_id,
                }
                run.state.update(values)
                run.save()
                await browser(run, values)
                if os.environ.get("AGENTIC_JOINT_BROWSER") == "1":
                    assert (await run.request("GET", f"/api/v1/tasks/{recovery_id}", task=True))[
                        "status"
                    ] == "CANCELLED"
                assert recovery_chat.calls == 1

    asyncio.run(scenario())
