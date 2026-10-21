"""真实 Deep Agents 循环与 LangGraph 阶段恢复；模型替身仅用于验证控制边界。"""

import asyncio
import json

import pytest
from langchain_core.messages import AIMessage
from langgraph.checkpoint.memory import InMemorySaver
from langsmith import tracing_context
from test_qa_pipeline import DOCUMENT, Central, Knowledge, lease

from agentic_ai.agents.models import WorkResult
from agentic_ai.agents.pipeline import WorkPipeline
from agentic_ai.qa.errors import QaCode, QaError

DRAFT = {
    "title": "助手范围",
    "facts": "只读助手不执行外部写操作。[1]",
    "analysis": "据资料推断应人工确认执行。",
    "output": "核对资料后准备草稿。[1]",
    "limitations": "只覆盖合成资料。",
}


def work_input(kind="RESEARCH", mode="REPORT"):
    return {
        "schemaVersion": "knowledge-work-input-v1",
        "kind": kind,
        "mode": mode,
        "question": "分析助手的边界",
        "documentIds": [DOCUMENT],
    }


def work_lease(kind="RESEARCH", mode="REPORT"):
    binding = lease(input_value=work_input(kind, mode), graphVersion="knowledge-work-v1")
    return binding.model_copy(update={"task": binding.task.model_copy(update={"kind": kind})})


class ToolsChat:
    def __init__(self, replies=None, hook=None):
        self.calls = 0
        self.replies = replies or [
            AIMessage(
                content="",
                tool_calls=[
                    {
                        "name": "read_evidence",
                        "args": {"citation_id": "1"},
                        "id": "call-1",
                        "type": "tool_call",
                    }
                ],
            ),
            AIMessage(content=json.dumps(DRAFT, ensure_ascii=False)),
        ]
        self.hook = hook
        self.requests = []

    async def complete(self, messages, tools, operation_id):
        assert {tool["function"]["name"] for tool in tools} == {"search_knowledge", "read_evidence"}
        self.requests.append(messages)
        self.calls += 1
        if self.hook:
            self.hook()
        return self.replies[min(self.calls - 1, len(self.replies) - 1)]


async def run(pipeline, *, saver=None, before=None):
    with tracing_context(enabled=False):
        return await pipeline.build(saver or InMemorySaver(), interrupt_before=before).ainvoke(
            pipeline.binding.input,
            {"configurable": {"thread_id": pipeline.binding.physical_thread}},
            durability="sync",
        )


def setup(chat=None, knowledge=None, kind="RESEARCH", mode="REPORT"):
    binding = work_lease(kind, mode)
    central, knowledge, chat = Central(binding), knowledge or Knowledge(), chat or ToolsChat()
    return WorkPipeline(binding, central, knowledge, chat), central, knowledge, chat


@pytest.mark.parametrize(
    "kind,mode",
    [
        ("RESEARCH", "REPORT"),
        ("READONLY_ASSISTANT", "EXPLAIN"),
        ("READONLY_ASSISTANT", "CHECKLIST"),
        ("READONLY_ASSISTANT", "DRAFT"),
    ],
)
def test_actual_agent_loop_uses_only_read_tools_and_emits_bound_report(kind, mode):
    async def scenario():
        pipeline, central, knowledge, chat = setup(kind=kind, mode=mode)
        result = (await run(pipeline))["report"]
        report = WorkResult.model_validate_json(json.dumps(result))
        assert report.kind.value == kind and report.mode.value == mode
        assert report.citations[0].document_id == DOCUMENT
        assert report.tool_calls[0].name == "read_evidence"
        assert chat.calls == 2 and knowledge.searches == 1
        assert any(event[1]["stage"] == "TOOL_READING" for event in central.events.values())
        await pipeline.before_complete(result)

    asyncio.run(scenario())


def test_empty_evidence_never_calls_chat():
    async def scenario():
        pipeline, _, _, chat = setup(knowledge=Knowledge(hits=()))
        report = (await run(pipeline))["report"]
        assert report["answerability"] == "NO_EVIDENCE" and not report["citations"]
        assert chat.calls == 0
        await pipeline.before_complete(report)

    asyncio.run(scenario())


@pytest.mark.parametrize("tool", ["execute", "task", "write_file", "read_file", "fetch_url"])
def test_model_cannot_invoke_hidden_or_external_tool(tool):
    async def scenario():
        chat = ToolsChat(
            [
                AIMessage(
                    content="",
                    tool_calls=[{"name": tool, "args": {}, "id": "bad", "type": "tool_call"}],
                )
            ]
        )
        pipeline, _, knowledge, _ = setup(chat=chat)
        with pytest.raises(QaError):
            await run(pipeline)
        assert knowledge.searches == 1 and chat.calls == 1

    asyncio.run(scenario())


def test_call_limit_stops_repeated_read_loop():
    async def scenario():
        chat = ToolsChat(
            [
                AIMessage(
                    content="",
                    tool_calls=[
                        {
                            "name": "read_evidence",
                            "args": {"citation_id": "1"},
                            "id": "repeat",
                            "type": "tool_call",
                        }
                    ],
                )
            ]
        )
        pipeline, *_ = setup(chat=chat)
        with pytest.raises(QaError):
            await run(pipeline)
        assert chat.calls == 3

    asyncio.run(scenario())


def test_revocation_before_tool_or_final_commit_rejects_result():
    async def scenario():
        knowledge = Knowledge()
        chat = ToolsChat(hook=lambda: setattr(knowledge, "revoked", True))
        pipeline, *_ = setup(chat=chat, knowledge=knowledge)
        with pytest.raises(QaError):
            await run(pipeline)
        assert chat.calls == 1
        pipeline, _, knowledge, _ = setup()
        result = (await run(pipeline))["report"]
        knowledge.revoked = True
        with pytest.raises(QaError):
            await pipeline.before_complete(result)

    asyncio.run(scenario())


def test_completed_analyze_checkpoint_resumes_without_model_or_tool_replay():
    async def scenario():
        pipeline, _, knowledge, chat = setup()
        saver = InMemorySaver()
        await run(pipeline, saver=saver, before=["verify"])
        assert chat.calls == 2
        with tracing_context(enabled=False):
            result = await pipeline.build(saver).ainvoke(
                None,
                {"configurable": {"thread_id": pipeline.binding.physical_thread}},
                durability="sync",
            )
        assert result["report"]["facts"] == DRAFT["facts"]
        assert chat.calls == 2 and knowledge.searches == 1

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "draft",
    [{**DRAFT, "facts": "无出处"}, {**DRAFT, "output": "伪引用[10]"}, {**DRAFT, "citations": []}],
)
def test_report_rejects_fabricated_references_or_uncontracted_fields(draft):
    async def scenario():
        pipeline, *_ = setup(chat=ToolsChat([AIMessage(content=json.dumps(draft))]))
        with pytest.raises(QaError) as error:
            await run(pipeline)
        assert error.value.code == QaCode.ANSWER_INVALID

    asyncio.run(scenario())


def test_multiple_tool_calls_from_one_reply_execute_serially():
    class SerialKnowledge(Knowledge):
        active = 0
        peak = 0

        async def citation(self, value):
            self.active += 1
            self.peak = max(self.peak, self.active)
            try:
                await asyncio.sleep(0.002)
                return await super().citation(value)
            finally:
                self.active -= 1

    async def scenario():
        chat = ToolsChat(
            [
                AIMessage(
                    content="",
                    tool_calls=[
                        {
                            "name": "read_evidence",
                            "args": {"citation_id": "1"},
                            "id": f"batch-{i}",
                            "type": "tool_call",
                        }
                        for i in range(3)
                    ],
                ),
                AIMessage(content=json.dumps(DRAFT)),
            ]
        )
        knowledge = SerialKnowledge()
        pipeline, *_ = setup(chat=chat, knowledge=knowledge)
        result = (await run(pipeline))["report"]
        assert len(result["toolCalls"]) == 3 and knowledge.peak == 1 and chat.calls == 2

    asyncio.run(scenario())


def test_extra_search_remains_scoped_and_search_limit_rejects_third():
    async def scenario():
        calls = [
            {
                "name": "search_knowledge",
                "args": {"query": "合成补充检索"},
                "id": f"search-{i}",
                "type": "tool_call",
            }
            for i in range(3)
        ]
        pipeline, _, knowledge, chat = setup(
            chat=ToolsChat(
                [AIMessage(content="", tool_calls=calls[:2]), AIMessage(content=json.dumps(DRAFT))]
            )
        )
        result = (await run(pipeline))["report"]
        assert knowledge.searches == 3 and len(result["toolCalls"]) == 2
        pipeline, _, knowledge, chat = setup(
            chat=ToolsChat([AIMessage(content="", tool_calls=calls)])
        )
        with pytest.raises(QaError):
            await run(pipeline)
        assert knowledge.searches == 3 and chat.calls == 1

    asyncio.run(scenario())
