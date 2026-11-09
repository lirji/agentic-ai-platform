"""真实Conversation/Tasks/Knowledge/MySQL/PG/双索引联合验收，仅模型协议为替身。"""

import asyncio
import json
import os
import subprocess
from contextlib import AsyncExitStack
from uuid import uuid4

import pytest
from langsmith import tracing_context
from test_contextual_full_joint import conversations as conversations  # noqa: F401
from test_contextual_full_joint import request as conversation_request
from test_evaluation_full_joint import ROOT, Joint
from test_evaluation_full_joint import joint as joint  # noqa: F401

from agentic_ai.memory.client import MemoryClient
from agentic_ai.memory.proposal_pipeline import MemoryProposalPipeline
from agentic_ai.memory.qa_pipeline import MemoryQaPipeline
from agentic_ai.qa.worker import execute_qa_task
from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.models import TaskStatus
from agentic_ai.tasks.postgres import PostgresCheckpoints

pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_MEMORY_JOINT_IT") != "1", reason="由真实Java发布夹具显式启动"
)
QUESTION = "我偏好中文短句，项目代号是星河。请说明两份资料。"
PRIVATE = "偏好中文短句"


class Chat:
    def __init__(self):
        self.calls = []

    async def stream(self, messages, operation):
        self.calls.append((operation, messages))
        if ":memory:" in operation:
            yield json.dumps(
                {
                    "candidates": [
                        {"category": "PREFERENCE", "content": PRIVATE, "quote": "我偏好中文短句"},
                        {
                            "category": "PROJECT_CONTEXT",
                            "content": "项目代号为星河",
                            "quote": "项目代号是星河",
                        },
                    ]
                },
                ensure_ascii=False,
            )
        else:
            assert PRIVATE in messages[-1].content
            assert PRIVATE not in messages[0].content
            yield "两份授权资料说明了相应限制。[1][2]"


async def request(run, endpoint, method, path, **options):
    status = (
        201
        if method == "POST" and path == "/api/v1/conversations"
        else 202
        if method == "POST" and path.endswith("/turns")
        else 200
    )
    return await conversation_request(run, endpoint, method, path, status=status, **options)


async def ready(run, endpoint, path, value):
    async with asyncio.timeout(20):
        while value["dispatchState"] != "SUBMITTED":
            assert value["dispatchState"] != "FAILED", value
            await asyncio.sleep(0.1)
            value = await request(run, endpoint, "GET", path)
    run.state["tasks"].append(value["taskId"])
    run.save()
    return value


async def memory(run, lease, endpoint):
    client = MemoryClient(
        endpoint,
        lease,
        MemoryClient.load_private_key(ROOT / ".local/s18c/runtime-memory-private.pem"),
    )
    run.stack.push_async_callback(client.aclose)
    return client


async def pause(run, lease, central, pipeline, before=None):
    store = await run.stack.enter_async_context(PostgresCheckpoints.open(run.checkpoints))
    saver = FencedPostgresSaver(lease, central, store)
    graph = pipeline.build(saver, interrupt_before=before) if before else pipeline.build(saver)
    await graph.ainvoke(lease.input, saver.config, durability="sync")
    state = await graph.aget_state(saver.config)
    assert PRIVATE not in json.dumps(state.values, ensure_ascii=False)
    return state


async def browser(run, endpoint, conversation, qa_task):
    if os.environ.get("AGENTIC_JOINT_BROWSER") != "1":
        return
    import local

    fixture = run.fixture["directory"] / "memory-browser-fixture.json"
    local.private(
        fixture,
        json.dumps(
            {
                **{
                    key: run.fixture[key]
                    for key in ("knowledgeEndpoint", "taskEndpoint", "tenant", "owner")
                },
                "conversationEndpoint": endpoint,
                "conversationId": conversation,
                "qaTaskId": qa_task,
                "backendRoot": str(ROOT),
            }
        ),
    )
    environment = dict(os.environ)
    environment["MEMORY_AI_BROWSER_FIXTURE"] = str(fixture)
    frontend = ROOT.parent / "agentic-ai-platform-web"
    with (run.fixture["directory"] / "memory-browser.log").open("w") as output:
        process = await asyncio.create_subprocess_exec(
            "pnpm",
            "exec",
            "playwright",
            "test",
            "tests/memory-ai-real.spec.ts",
            cwd=frontend,
            env=environment,
            stdout=output,
            stderr=subprocess.STDOUT,
        )
        assert await asyncio.wait_for(process.wait(), 100) == 0, str(
            run.fixture["directory"] / "memory-browser.log"
        )


def test_controlled_memory_real_authority_pg_resume_revoke_and_browser(joint, conversations):
    async def scenario():
        with tracing_context(enabled=False):
            async with AsyncExitStack() as stack:
                run, endpoint, chat = Joint(joint, stack), conversations, Chat()
                conv = await request(
                    run,
                    endpoint,
                    "POST",
                    "/api/v1/conversations",
                    body={"title": "受控记忆联合验收"},
                    key=str(uuid4()),
                )
                root = f"/api/v1/conversations/{conv['conversationId']}"
                turn = await request(
                    run,
                    endpoint,
                    "POST",
                    root + "/turns",
                    body={
                        "expectedRevision": 0,
                        "question": QUESTION,
                        "documentIds": run.documents,
                        "contextTurnId": None,
                    },
                    key=str(uuid4()),
                )
                proposal = await request(
                    run,
                    endpoint,
                    "POST",
                    "/api/v1/conversations/memory/proposals",
                    body={
                        "schemaVersion": "memory-proposal-command-v1",
                        "expectedRevision": 0,
                        "conversationId": conv["conversationId"],
                        "turnId": turn["turnId"],
                        "retentionDays": 90,
                    },
                    key=str(uuid4()),
                )
                proposal_path = "/api/v1/conversations/memory/proposals/" + proposal["proposalId"]
                proposal = await ready(run, endpoint, proposal_path, proposal)
                central, lease = await run.client("memory-proposal-v1", proposal["taskId"])
                role = await memory(run, lease, endpoint)
                # 候选已经提交但中央完成前进程退出，PG恢复不得再次生成。
                await pause(run, lease, central, MemoryProposalPipeline(lease, central, role, chat))
                await run.expire(proposal["taskId"])
                central2, lease2 = await run.client("memory-proposal-v1", proposal["taskId"])
                role2 = await memory(run, lease2, endpoint)
                result = await execute_qa_task(
                    lease2, central2, run.checkpoints, None, chat, memory=role2
                )
                assert result.status == TaskStatus.SUCCEEDED, result.error_code
                assert len(chat.calls) == 1
                view = await request(run, endpoint, "GET", "/api/v2/conversations/memory")
                assert len(view["snapshot"]["items"]) == len(view["origins"]) == 2
                assert all(item["status"] == "PENDING" for item in view["snapshot"]["items"])
                item = next(
                    item for item in view["snapshot"]["items"] if item["content"] == PRIVATE
                )
                command = {
                    "schemaVersion": "memory-command-v1",
                    "expectedRevision": view["snapshot"]["revision"],
                    "action": "CONFIRM",
                    "memoryId": item["memoryId"],
                    "category": None,
                    "content": None,
                    "retentionDays": None,
                    "enabled": None,
                }
                confirmed = await request(
                    run,
                    endpoint,
                    "POST",
                    "/api/v1/conversations/memory/commands",
                    body=command,
                    key=str(uuid4()),
                )
                payload = {
                    "expectedRevision": 1,
                    "question": "两份资料有哪些限制？",
                    "documentIds": run.documents,
                    "contextTurnId": None,
                    "retrievalStrategy": "LLAMA_RRF_V1",
                    "queryPolicy": "CONTEXT_REWRITE_V1",
                    "memoryPolicy": "CONFIRMED_MEMORY_V1",
                    "expectedMemoryRevision": confirmed["revision"],
                }
                qa = await request(
                    run,
                    endpoint,
                    "POST",
                    f"/api/v4/conversations/{conv['conversationId']}/turns",
                    body=payload,
                    key=str(uuid4()),
                )
                qa = await ready(run, endpoint, root + "/turns/" + qa["turnId"], qa)
                central, lease = await run.client("knowledge-qa-v4", qa["taskId"])
                candidates = await run.candidates()
                role = await memory(run, lease, endpoint)
                await pause(
                    run,
                    lease,
                    central,
                    MemoryQaPipeline(lease, central, candidates, chat, run.use_case, role),
                    before=["generate"],
                )
                await run.expire(qa["taskId"])
                central2, lease2 = await run.client("knowledge-qa-v4", qa["taskId"])
                role2 = await memory(run, lease2, endpoint)
                result = await execute_qa_task(
                    lease2,
                    central2,
                    run.checkpoints,
                    candidates,
                    chat,
                    retrieval=run.use_case,
                    candidates=candidates,
                    memory=role2,
                )
                assert result.status == TaskStatus.SUCCEEDED, result.error_code
                assert len(chat.calls) == 2 and run.embedding.calls == 1
                assert result.result["memory"]["items"][0]["memoryId"] == item["memoryId"]
                await browser(run, endpoint, conv["conversationId"], qa["taskId"])
                # 另一轮停在生成之前；忘记后旧检查点必须失败，不能恢复旧正文。
                current = await request(run, endpoint, "GET", root)
                snap = await request(run, endpoint, "GET", "/api/v1/conversations/memory")
                payload.update(
                    expectedRevision=current["revision"], expectedMemoryRevision=snap["revision"]
                )
                later = await request(
                    run,
                    endpoint,
                    "POST",
                    f"/api/v4/conversations/{conv['conversationId']}/turns",
                    body=payload,
                    key=str(uuid4()),
                )
                later = await ready(run, endpoint, root + "/turns/" + later["turnId"], later)
                central, lease = await run.client("knowledge-qa-v4", later["taskId"])
                role = await memory(run, lease, endpoint)
                await pause(
                    run,
                    lease,
                    central,
                    MemoryQaPipeline(lease, central, candidates, chat, run.use_case, role),
                    before=["generate"],
                )
                command.update(expectedRevision=snap["revision"], action="CLEAR", memoryId=None)
                await request(
                    run,
                    endpoint,
                    "POST",
                    "/api/v1/conversations/memory/commands",
                    body=command,
                    key=str(uuid4()),
                )
                await run.expire(later["taskId"])
                central2, lease2 = await run.client("knowledge-qa-v4", later["taskId"])
                role2 = await memory(run, lease2, endpoint)
                failed = await execute_qa_task(
                    lease2,
                    central2,
                    run.checkpoints,
                    candidates,
                    chat,
                    retrieval=run.use_case,
                    candidates=candidates,
                    memory=role2,
                )
                assert failed.status == TaskStatus.FAILED and failed.error_code == "MEMORY_STALE"
                assert len(chat.calls) == 2
                empty = await request(run, endpoint, "GET", "/api/v2/conversations/memory")
                assert not empty["snapshot"]["items"] and not empty["origins"]
                run.state.update(memoryResult="PASS", calls=len(chat.calls), qaTaskId=qa["taskId"])
                run.save()

    asyncio.run(scenario())
