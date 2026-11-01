"""真实框架运行、原文绑定与持久额度消费协议；固定模型响应不代表生产质量。"""

import asyncio
import copy
import json
from pathlib import Path

import pytest
from langchain_core.messages import AIMessage
from langgraph.checkpoint.memory import InMemorySaver
from langsmith import tracing_context
from pydantic import ValidationError
from test_generation_work import GenerationCentral
from test_qa_pipeline import lease
from test_retrieval import TEXT, Embedding, Knowledge, parsed
from test_work_pipeline import ToolsChat, run, work_input

from agentic_ai.agents.research_models import ResearchDraft, ResearchPlan, ResearchResult
from agentic_ai.agents.research_pipeline import ResearchPipeline
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.deadline import RetrievalCapacity
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.research import ResearchAllowanceReceipt, ResearchCall, ResearchUsage

PLAN = {
    "items": [{"id": "Q1", "question": "同正文如何处理？"}, {"id": "Q2", "question": "餐补标准？"}]
}
DRAFT = {
    "title": "资料范围与缺口",
    "coverage": [
        {
            "questionId": "Q1",
            "status": "EVIDENCE_FOUND",
            "answer": "同正文也不能跨资料合并。[1]",
            "evidence": [{"citationId": "1", "quote": TEXT}],
            "gap": "",
        },
        {
            "questionId": "Q2",
            "status": "GAP",
            "answer": "",
            "evidence": [],
            "gap": "资料没有餐补标准。",
        },
    ],
    "summary": "资料说明相同正文仍区分资料[1]；餐补标准仍缺失。",
}


def reply(value):
    return AIMessage(content=json.dumps(value, ensure_ascii=False))


def read_tool():
    return AIMessage(
        content="",
        tool_calls=[
            {
                "name": "read_evidence",
                "args": {"citation_id": "1"},
                "id": "read-1",
                "type": "tool_call",
            }
        ],
    )


class BudgetCentral(GenerationCentral):
    """只用于框架端口验证；实际锁/幂等/恢复已经由Java MySQL测试证明。"""

    def __init__(self, binding):
        super().__init__(binding)
        self.usage = ResearchUsage(model_calls=0, tool_calls=0, search_calls=0)
        self.reservations = {}

    async def reserve_research(self, task_id, epoch, key, kind):
        assert task_id == self.binding.task.task_id and epoch == self.binding.epoch
        assert key not in self.reservations
        current = self.usage.model_dump()
        increments = {
            ResearchCall.MODEL: (1, 0, 0),
            ResearchCall.INITIAL_SEARCH: (0, 0, 1),
            ResearchCall.SEARCH_TOOL: (0, 1, 1),
            ResearchCall.READ_TOOL: (0, 1, 0),
        }[kind]
        try:
            self.usage = ResearchUsage(
                **{
                    name: current[name] + increment
                    for name, increment in zip(
                        ("model_calls", "tool_calls", "search_calls"), increments, strict=True
                    )
                }
            )
        except ValidationError:
            raise TaskRuntimeError(TaskRuntimeCode.RESEARCH_LIMIT) from None
        self.reservations[key] = kind
        return ResearchAllowanceReceipt(
            task_id=task_id, epoch=epoch, operation_key=key, kind=kind, usage=self.usage
        )

    async def research_usage(self, task_id, epoch):
        assert task_id == self.binding.task.task_id and epoch == self.binding.epoch
        return self.usage


def setup(replies=None, *, knowledge=None):
    value = {
        **work_input(),
        "schemaVersion": "knowledge-work-input-v3",
        "documentIds": [],
        "retrievalStrategy": "LLAMA_RRF_V1",
    }
    binding = lease(input_value=value, graphVersion="knowledge-work-v3")
    binding = binding.model_copy(
        update={"task": binding.task.model_copy(update={"kind": "RESEARCH"})}
    )
    central, embedding, candidates, chat = (
        BudgetCentral(binding),
        Embedding(),
        knowledge or Knowledge(),
        ToolsChat(replies or [reply(PLAN), reply(DRAFT)]),
    )
    use_case = RetrievalUseCase(embedding, RetrievalCapacity(1))
    return (
        ResearchPipeline(binding, central, candidates, chat, use_case),
        central,
        embedding,
        candidates,
        chat,
        use_case,
    )


@pytest.mark.parametrize("with_tool", [False, True])
def test_plan_and_report_share_budget_and_keep_partial_result(with_tool):
    async def scenario():
        replies = [reply(PLAN), *([read_tool()] if with_tool else []), reply(DRAFT)]
        pipeline, central, embedding, _, chat, _ = setup(replies)
        output = (await run(pipeline))["report"]
        result = ResearchResult.from_wire(json.dumps(output).encode())
        await pipeline.before_complete(output)
        assert result.content.outcome.value == "PARTIAL"
        assert result.content.plan[0].question == PLAN["items"][0]["question"]
        assert result.content.coverage[1].answer == "" and not result.content.coverage[1].evidence
        assert result.content.usage == ResearchUsage(
            model_calls=3 if with_tool else 2, tool_calls=int(with_tool), search_calls=1
        )
        assert chat.calls == result.content.usage.model_calls and len(embedding.calls) == 1
        assert len(central.manifests[-1]) >= len(result.references) == 1
        assert json.loads(chat.requests[-1][1]["content"])["plan"] == PLAN

    asyncio.run(scenario())


def test_extra_model_round_is_rejected_before_sending_and_does_not_reset_planning_cost():
    async def scenario():
        pipeline, central, _, _, chat, _ = setup(
            [reply(PLAN), read_tool(), read_tool(), reply(DRAFT)]
        )
        with pytest.raises(QaError) as rejected:
            await run(pipeline)
        assert rejected.value.code == QaCode.INPUT_REJECTED
        assert chat.calls == 3 and central.usage.model_calls == 3

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "failure", ["plan-tool", "plan-scope", "changed-plan", "forged-quote", "unknown-citation"]
)
def test_invalid_or_injected_outputs_never_become_completed_reports(failure):
    async def scenario():
        plan, draft = copy.deepcopy(PLAN), copy.deepcopy(DRAFT)
        if failure == "plan-scope":
            plan["documentIds"] = []
        if failure == "changed-plan":
            draft["coverage"].pop()
        if failure == "forged-quote":
            draft["coverage"][0]["evidence"][0]["quote"] = "不存在的原文"
        if failure == "unknown-citation":
            draft["coverage"][0]["answer"] = "虚构。[9]"
            draft["coverage"][0]["evidence"][0]["citationId"] = "9"
            draft["summary"] = "虚构。[9]"
        pipeline, central, _, _, chat, _ = setup(
            [read_tool() if failure == "plan-tool" else reply(plan), reply(draft)]
        )
        with pytest.raises(QaError):
            await run(pipeline)
        assert central.usage.tool_calls == 0
        assert chat.calls == (1 if failure.startswith("plan-") else 2)

    asyncio.run(scenario())


def test_empty_evidence_uses_no_model_and_keeps_explicit_gap_for_full_user_goal():
    async def scenario():
        knowledge = Knowledge(
            parsed(
                {
                    "schemaVersion": "knowledge-retrieval-v1",
                    "candidateLimit": 20,
                    "vectorCandidates": [],
                    "lexicalCandidates": [],
                }
            )
        )
        pipeline, central, _, _, chat, _ = setup(knowledge=knowledge)
        output = (await run(pipeline))["report"]
        await pipeline.before_complete(output)
        assert output["content"]["outcome"] == "INSUFFICIENT"
        assert output["content"]["coverage"][0]["status"] == "GAP"
        assert not output["references"] and chat.calls == central.usage.model_calls == 0
        assert central.usage.search_calls == 1

    asyncio.run(scenario())


def test_confirmed_analysis_restore_reuses_plan_quotes_and_total_usage_without_new_calls():
    async def scenario():
        pipeline, central, embedding, knowledge, chat, use_case = setup()
        saver = InMemorySaver()
        await run(pipeline, saver=saver, before=["verify"])
        restored = ResearchPipeline(pipeline.binding, central, knowledge, chat, use_case)
        graph = restored.build(saver)
        config = {"configurable": {"thread_id": pipeline.binding.physical_thread}}
        snapshot = await graph.aget_state(config)
        assert snapshot.values["plan"] == PLAN
        await restored.before_restore(snapshot.values)
        with tracing_context(enabled=False):
            output = (await graph.ainvoke(None, config, durability="sync"))["report"]
        await restored.before_complete(output)
        assert chat.calls == 2 and len(embedding.calls) == 1 and central.usage.model_calls == 2
        forged = copy.deepcopy(output)
        forged["content"]["plan"][0]["question"] = "替换目标"
        with pytest.raises(QaError):
            await restored.before_complete(forged)
        forged = copy.deepcopy(output)
        forged["content"]["citations"][0]["source"]["displayName"] = "伪造来源标题"
        with pytest.raises(QaError):
            await restored.before_complete(forged)
        forged = copy.deepcopy(output)
        forged["content"]["citations"][0]["citationId"] = "2"
        forged["content"]["coverage"][0]["answer"] = "结论[2]"
        forged["content"]["coverage"][0]["evidence"][0]["citationId"] = "2"
        forged["content"]["summary"] = "结论[2]"
        with pytest.raises(QaError):
            await restored.before_complete(forged)

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "field,value",
    [
        ("answer", "没有依据却断言[1]"),
        ("evidence", [{"citationId": "1", "quote": TEXT}]),
        ("gap", ""),
    ],
)
def test_gap_cannot_carry_unsupported_answer_or_citation(field, value):
    draft = copy.deepcopy(DRAFT)
    draft["coverage"][1][field] = value
    with pytest.raises(ValidationError):
        ResearchDraft.model_validate_json(json.dumps(draft))


def test_plan_ids_and_permission_fields_are_not_free_form():
    for plan in [
        {"items": [{"id": "Q2", "question": "漏掉第一项"}]},
        {**PLAN, "tools": ["shell"]},
        {"items": []},
    ]:
        with pytest.raises(ValidationError):
            ResearchPlan.model_validate_json(json.dumps(plan))


def test_shared_java_python_wire_fixture_and_negative_cases():
    root = Path(__file__).resolve().parents[2]
    fixture = json.loads(
        (root / "async-task-service/src/test/resources/research-coverage-golden.json").read_text()
    )
    for name in ("result", "empty"):
        ResearchResult.from_wire(json.dumps(fixture[name]).encode())
    for invalid in fixture["invalidResults"]:
        value = copy.deepcopy(fixture["result"])
        target = value
        for key in invalid["path"][:-1]:
            target = target[key]
        target[invalid["path"][-1]] = invalid["value"]
        with pytest.raises((ValidationError, ValueError)):
            ResearchResult.from_wire(json.dumps(value).encode())


def test_worker_selects_new_graph_and_keeps_work_execution_budget(monkeypatch):
    from agentic_ai.qa import worker
    from agentic_ai.tasks.postgres import CheckpointConfig

    async def scenario():
        pipeline, central, _, knowledge, chat, use_case = setup()

        async def claimed(binding, api, checkpoints, build, **options):
            assert isinstance(build.__self__, ResearchPipeline)
            assert options["result_key"] == "report" and options["execution_seconds"] == 120
            report = (await run(build.__self__))["report"]
            await options["before_complete"](report)
            return binding.task

        monkeypatch.setattr(worker, "run_claimed_graph", claimed)
        result = await worker.execute_qa_task(
            pipeline.binding,
            central,
            CheckpointConfig(dsn="postgresql://127.0.0.1:1/unused"),
            knowledge,
            chat,
            tool_chat=chat,
            retrieval=use_case,
            candidates=knowledge,
        )
        assert result == pipeline.binding.task and chat.calls == 2

    asyncio.run(scenario())
