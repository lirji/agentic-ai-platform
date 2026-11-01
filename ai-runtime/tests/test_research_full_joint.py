"""真实发布/双索引/Knowledge/Tasks/PG和浏览器；仅模型端口使用确定性测试响应。"""

import asyncio
import json
import os
from contextlib import AsyncExitStack
from uuid import uuid4

import pytest
from langchain_core.messages import AIMessage
from langsmith import tracing_context
from test_evaluation_full_joint import ROOT, STRATEGY, Joint
from test_evaluation_full_joint import joint as joint  # noqa: F401
from test_work_pipeline import ToolsChat

from agentic_ai.agents.research_pipeline import ResearchPipeline
from agentic_ai.qa.worker import execute_qa_task
from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.models import TaskStatus
from agentic_ai.tasks.postgres import PostgresCheckpoints

pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_RESEARCH_JOINT_IT") != "1", reason="由Java真实发布夹具显式启动"
)
QUESTION = "核对授权候选检索资料的原文与餐补标准"


class ResearchChat:
    """从收到的实际授权片段形成可校验响应，不编造source/权限/额度或模型质量结果。"""

    def __init__(self):
        self.calls = 0

    async def complete(self, messages, tools, operation):
        self.calls += 1
        if operation.startswith("research-plan:"):
            value = {
                "items": [
                    {"id": "Q1", "question": "资料中有哪些原文信息？"},
                    {"id": "Q2", "question": "是否提供餐补标准？"},
                ]
            }
        else:
            evidence = json.loads(messages[1]["content"])["evidence"]
            assert len(evidence) == 2
            value = {
                "title": "资料原文与餐补缺口",
                "coverage": [
                    {
                        "questionId": "Q1",
                        "status": "EVIDENCE_FOUND",
                        "answer": "资料包含授权候选检索信息。[1]",
                        "evidence": [{"citationId": "1", "quote": evidence[0]["text"][:200]}],
                        "gap": "",
                    },
                    {
                        "questionId": "Q2",
                        "status": "GAP",
                        "answer": "",
                        "evidence": [],
                        "gap": "当前资料没有餐补标准，无法确定金额和适用条件。",
                    },
                ],
                "summary": "资料中有授权候选检索信息[1]；餐补标准仍缺少依据。",
            }
        return AIMessage(content=json.dumps(value, ensure_ascii=False))


async def submit(run, *, legacy=False):
    task_id = str(uuid4())
    command = {
        "taskId": task_id,
        "kind": "RESEARCH",
        "question": QUESTION,
        "documentIds": run.documents,
        "mode": "REPORT",
        "retrievalStrategy": STRATEGY,
    }
    route = "/api/v2/tasks" if legacy else "/api/v3/tasks"
    result = await run.request("POST", route, body=command, key=task_id, task=True, status=202)
    assert (
        await run.request("POST", route, body=command, key=task_id, task=True, status=202)
    ) == result
    run.state["tasks"].append(task_id)
    return task_id


async def execute(run, task_id, chat, *, legacy=False, restore=False):
    central, lease = await run.client(
        "knowledge-work-v2" if legacy else "knowledge-work-v3", task_id
    )
    candidates = await run.candidates()
    if restore:
        store = await run.stack.enter_async_context(PostgresCheckpoints.open(run.checkpoints))
        saver = FencedPostgresSaver(lease, central, store)
        pipeline = ResearchPipeline(lease, central, candidates, chat, run.use_case)
        graph = pipeline.build(saver, interrupt_before=["verify"])
        await graph.ainvoke(lease.input, saver.config, durability="sync")
        assert (await graph.aget_state(saver.config)).values["plan"]["items"][0]["id"] == "Q1"
        await run.expire(task_id)
        central, lease = await run.client("knowledge-work-v3", task_id)
    result = await execute_qa_task(
        lease,
        central,
        run.checkpoints,
        candidates,
        chat,
        tool_chat=chat,
        retrieval=run.use_case,
        candidates=candidates,
    )
    assert result.status == TaskStatus.SUCCEEDED, result.error_code
    return result


async def browser(run, values):
    if os.environ.get("AGENTIC_JOINT_BROWSER") != "1":
        return
    import local

    frontend = ROOT.parent / "agentic-ai-platform-web"
    fixture = run.fixture["directory"] / "research-browser-fixture.json"
    local.private(
        fixture,
        json.dumps(
            {
                **{
                    key: run.fixture[key]
                    for key in ("knowledgeEndpoint", "taskEndpoint", "tenant", "owner")
                },
                **values,
                "backendRoot": str(ROOT),
            }
        ),
    )
    environment = dict(os.environ)
    environment.update(
        RESEARCH_BROWSER_FIXTURE=str(fixture),
        PLAYWRIGHT_BASE_URL="http://127.0.0.1:28186",
        PATH=str(frontend / ".local/toolchains/node-v24.22.0-darwin-arm64/bin")
        + os.pathsep
        + environment["PATH"],
    )
    with (run.fixture["directory"] / "research-browser.log").open("w") as log:
        process = await asyncio.create_subprocess_exec(
            "pnpm",
            "exec",
            "playwright",
            "test",
            "tests/research-real-joint.spec.ts",
            "--reporter=list",
            "--output=.logs/s12c-real-joint-artifacts",
            cwd=frontend,
            env=environment,
            stdout=log,
            stderr=asyncio.subprocess.STDOUT,
        )
        try:
            async with asyncio.timeout(110):
                assert await process.wait() == 0, "真实浏览器失败，见research-browser.log"
        finally:
            if process.returncode is None:
                process.terminate()
                await asyncio.wait_for(process.wait(), 10)


def test_real_coverage_partial_and_empty_results_with_pg_takeover_and_browser(joint):
    async def scenario():
        with tracing_context(enabled=False):
            async with AsyncExitStack() as stack:
                run = Joint(joint, stack)
                assert (await run.request("GET", "/api/v3/tasks/capabilities", task=True))[
                    "enabled"
                ]
                task_id = await submit(run)
                chat = ResearchChat()
                result = await execute(run, task_id, chat, restore=True)
                assert result.result["content"]["outcome"] == "PARTIAL" and chat.calls == 2
                assert result.result["content"]["usage"] == {
                    "modelCalls": 2,
                    "toolCalls": 0,
                    "searchCalls": 1,
                }
                assert run.embedding.calls == 1
                assert len(result.result["references"]) == 1
                # 在浏览器并发加载前独立验证公开历史重验，区分领域失败与页面调度问题。
                public = await run.request("GET", f"/api/v1/tasks/{task_id}", task=True)
                assert public["result"] == result.result
                legacy_id = await submit(run, legacy=True)
                assert (await execute(run, legacy_id, ToolsChat(), legacy=True)).result[
                    "schemaVersion"
                ] == "knowledge-work-result-v2"
                empty = Joint(joint, stack)
                empty.owner, empty.documents = "research-empty-owner", []
                empty_id = await submit(empty)
                empty_chat = ResearchChat()
                empty_result = await execute(empty, empty_id, empty_chat)
                assert (
                    empty_result.result["content"]["outcome"] == "INSUFFICIENT"
                    and empty_chat.calls == 0
                )
                values = {
                    "researchTaskId": task_id,
                    "legacyTaskId": legacy_id,
                    "emptyTaskId": empty_id,
                    "emptyOwner": empty.owner,
                }
                run.state.update(values)
                run.save()
                await browser(run, values)
                # 真实资料撤下后中央历史不可读，不能靠只有一个最终引用忽略其他生成依赖。
                hidden = run.documents[1]
                detail = await run.request("GET", f"/api/v2/knowledge/documents/{hidden}")
                await run.request(
                    "POST",
                    f"/api/v2/knowledge/documents/{hidden}/withdrawals",
                    body={"expectedRevision": detail["revision"]},
                    key="research-withdraw-" + str(uuid4()),
                )
                await run.request("GET", f"/api/v1/tasks/{task_id}", task=True, status=404)

    asyncio.run(scenario())
