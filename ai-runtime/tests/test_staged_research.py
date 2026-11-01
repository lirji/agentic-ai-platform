"""v4根阶段与恢复位置的实际框架验证；模型为无费用协议替身。"""

import asyncio

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from langsmith import tracing_context
from test_research_coverage import DRAFT, PLAN, reply, setup

from agentic_ai.agents.staged_research_pipeline import StagedResearchPipeline
from agentic_ai.qa.errors import QaError


class StagedChat:
    """计划明确没有工具，分析仍只允许两种只读工具；记录真实调用次数。"""

    def __init__(self):
        self.calls = self.plan_calls = self.analysis_calls = 0

    async def complete(self, messages, tools, operation_id):
        self.calls += 1
        if operation_id.startswith("research-plan:"):
            assert not tools
            self.plan_calls += 1
            return reply(PLAN)
        assert {tool["function"]["name"] for tool in tools} == {"search_knowledge", "read_evidence"}
        self.analysis_calls += 1
        return reply(DRAFT)


def staged():
    pipeline, central, embedding, knowledge, chat, use_case = setup()
    binding = pipeline.binding.model_copy(
        update={
            "graph_version": "knowledge-work-v4",
            "input": {**pipeline.binding.input, "schemaVersion": "knowledge-work-input-v4"},
        }
    )
    central.binding = binding
    chat = StagedChat()
    return StagedResearchPipeline(binding, central, knowledge, chat, use_case), central, chat


@pytest.mark.parametrize(
    "pause,expected,calls",
    [
        ("plan", "PLANNING", 0),
        ("analyze", "ANALYZING", 1),
        ("verify", "VERIFYING", 2),
        (None, "SUBMITTING", 2),
    ],
)
def test_confirmed_stage_restores_plan_without_replaying_it(pause, expected, calls):
    async def scenario():
        pipeline, central, chat = staged()
        saver = InMemorySaver()
        config = {"configurable": {"thread_id": "staged"}}
        first = pipeline.build(saver, interrupt_before=[pause] if pause else None)
        await first.ainvoke(pipeline.binding.input, config, durability="sync")
        snapshot = await first.aget_state(config)
        assert chat.calls == calls
        restored = StagedResearchPipeline(
            pipeline.binding,
            central,
            pipeline.generation.candidates,
            chat,
            pipeline.generation.use_case,
        )
        await restored.before_restore(snapshot.values)
        result = await restored.build(saver).ainvoke(None, config, durability="sync")
        await restored.before_complete(result["report"])
        assert chat.calls == 2
        events = [
            payload
            for _, payload in central.events.values()
            if payload.get("stage") == "RESEARCH_RESUMING"
        ]
        assert events[-1]["nextStage"] == expected
        assert events[-1]["usage"]["modelCalls"] == calls

    with tracing_context(enabled=False):
        asyncio.run(scenario())


def test_missing_plan_cannot_enter_analysis_or_claim_resume_completed():
    async def scenario():
        pipeline, _, chat = staged()
        with pytest.raises(QaError):
            await pipeline.analyze({"evidence": []})
        with pytest.raises(QaError):
            await pipeline.before_restore({"draft": {}})
        assert chat.calls == 0

    asyncio.run(scenario())


def test_restore_cannot_replace_centrally_frozen_question():
    async def scenario():
        pipeline, _, chat = staged()
        with pytest.raises(QaError):
            await pipeline.before_restore({**pipeline.binding.input, "question": "替换后的目标"})
        assert chat.calls == 0

    asyncio.run(scenario())
