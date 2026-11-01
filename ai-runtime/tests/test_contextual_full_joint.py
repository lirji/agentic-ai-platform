"""真实Conversation/Tasks/Knowledge、双索引和PG追问链路；仅模型为协议替身。"""

import asyncio
import json
import os
import socket
import subprocess
import time
from contextlib import AsyncExitStack
from uuid import uuid4

import httpx
import jwt
import pytest
from langsmith import tracing_context
from test_evaluation_full_joint import ROOT, STRATEGY, Joint
from test_evaluation_full_joint import joint as joint  # noqa: F401

from agentic_ai.qa.worker import execute_qa_task
from agentic_ai.tasks.models import TaskStatus

pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_CONTEXTUAL_JOINT_IT") != "1", reason="由真实Java发布夹具显式启动"
)
ORIGINAL = "它们的原文分别是什么？"
REWRITTEN = "两份授权候选检索资料的原文分别是什么？"


class Chat:
    """只替换远端模型，记录改写与生成次数，不能冒充真实模型质量验收。"""

    def __init__(self):
        self.operations = []

    async def stream(self, messages, operation):
        self.operations.append(operation)
        if operation.startswith("qa-rewrite:"):
            yield json.dumps({"query": REWRITTEN}, ensure_ascii=False)
        else:
            yield "本轮检索到两份授权候选检索资料。[1][2]"


@pytest.fixture(scope="module")
def conversations(joint):
    import local

    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    environment = local.environment("test")
    environment.update(
        CONVERSATION_PORT=str(port),
        CONVERSATION_MANAGEMENT_PORT="0",
        CONVERSATION_GENERATION_ENABLED="true",
        CONVERSATION_CONTEXTUAL_QUERY_ENABLED="true",
        CONVERSATION_TASK_ENDPOINT=joint["taskEndpoint"],
        CONVERSATION_WORKER_ENABLED="true",
        CONVERSATION_WORKER_TENANT=joint["tenant"],
        CONVERSATION_POLL_SECONDS="1",
    )
    endpoint = f"http://127.0.0.1:{port}"
    with (joint["directory"] / "conversations.log").open("w") as log:
        process = subprocess.Popen(
            [
                "java",
                "-jar",
                str(ROOT / "conversation-service/target/conversation-service-0.1.0-SNAPSHOT.jar"),
            ],
            cwd=ROOT,
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        try:
            deadline = time.monotonic() + 45
            with httpx.Client(trust_env=False, timeout=1) as http:
                while time.monotonic() < deadline:
                    assert process.poll() is None, "隔离Conversation启动失败，见conversations.log"
                    try:
                        if http.get(endpoint + "/api/v1/conversations").status_code == 401:
                            break
                    except httpx.HTTPError:
                        pass
                    time.sleep(0.1)
                else:
                    pytest.fail("隔离Conversation启动超过期限")
            yield endpoint
        finally:
            process.terminate()
            process.wait(timeout=15)


async def request(run, endpoint, method, path, *, body=None, key=None, status=200):
    now = int(time.time())
    token = jwt.encode(
        {
            "iss": "urn:agentic-ai:edge",
            "aud": "agentic-ai-conversation",
            "sub": run.owner,
            "tenant_id": run.tenant,
            "scope": "conversation:read conversation:write",
            "iat": now,
            "nbf": now,
            "exp": now + 60,
        },
        (ROOT / ".local/internal-private.pem").read_bytes(),
        algorithm="RS256",
    )
    headers = {"X-Internal-Token": token}
    if key:
        headers["Idempotency-Key"] = key
    async with httpx.AsyncClient(trust_env=False, timeout=15) as http:
        response = await http.request(method, endpoint + path, json=body, headers=headers)
    assert response.status_code == status, (response.status_code, response.text)
    return response.json()


async def execute(run, endpoint, conversation, turn, graph, chat):
    async with asyncio.timeout(20):
        while turn["dispatchState"] != "SUBMITTED":
            await asyncio.sleep(0.1)
            turn = await request(
                run,
                endpoint,
                "GET",
                f"/api/v1/conversations/{conversation}/turns/{turn['turnId']}",
            )
    central, lease = await run.client(graph, turn["taskId"])
    candidates = await run.candidates()
    result = await execute_qa_task(
        lease,
        central,
        run.checkpoints,
        candidates,
        chat,
        retrieval=run.use_case,
        candidates=candidates,
    )
    assert result.status == TaskStatus.SUCCEEDED, result.error_code
    run.state["tasks"].append(turn["taskId"])
    run.save()
    return result


async def browser(run, endpoint, conversation):
    if os.environ.get("AGENTIC_JOINT_BROWSER") != "1":
        return
    import local

    frontend = ROOT.parent / "agentic-ai-platform-web"
    fixture = run.fixture["directory"] / "contextual-browser-fixture.json"
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
                "originalQuestion": ORIGINAL,
                "retrievalQuestion": REWRITTEN,
                "backendRoot": str(ROOT),
            }
        ),
    )
    environment = dict(os.environ)
    environment.update(
        CONTEXTUAL_BROWSER_FIXTURE=str(fixture),
        PLAYWRIGHT_BASE_URL="http://127.0.0.1:28187",
        PATH=str(frontend / ".local/toolchains/node-v24.22.0-darwin-arm64/bin")
        + os.pathsep
        + environment["PATH"],
    )
    with (run.fixture["directory"] / "contextual-browser.log").open("w") as log:
        process = await asyncio.create_subprocess_exec(
            "pnpm",
            "exec",
            "playwright",
            "test",
            "tests/contextual-real-joint.spec.ts",
            "--reporter=list",
            "--output=.logs/s12a-real-joint-artifacts",
            cwd=frontend,
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        try:
            async with asyncio.timeout(100):
                assert await process.wait() == 0, "真实浏览器失败，见contextual-browser.log"
        finally:
            if process.returncode is None:
                process.terminate()
                await asyncio.wait_for(process.wait(), 10)


def test_contextual_turn_uses_persisted_history_and_current_real_evidence(joint, conversations):
    async def scenario():
        with tracing_context(enabled=False):
            async with AsyncExitStack() as stack:
                run, chat = Joint(joint, stack), Chat()
                created = await request(
                    run,
                    conversations,
                    "POST",
                    "/api/v1/conversations",
                    body={"title": "真实追问联合验收"},
                    key=str(uuid4()),
                    status=201,
                )
                conversation = created["conversationId"]
                command = {
                    "expectedRevision": 0,
                    "question": "两份授权候选检索资料是什么？",
                    "documentIds": run.documents,
                    "contextTurnId": None,
                    "retrievalStrategy": STRATEGY,
                }
                first = await request(
                    run,
                    conversations,
                    "POST",
                    f"/api/v2/conversations/{conversation}/turns",
                    body=command,
                    key=str(uuid4()),
                    status=202,
                )
                await execute(run, conversations, conversation, first, "knowledge-qa-v2", chat)
                command.update(
                    expectedRevision=1,
                    question=ORIGINAL,
                    contextTurnId=first["turnId"],
                    queryPolicy="CONTEXT_REWRITE_V1",
                )
                key, path = str(uuid4()), f"/api/v3/conversations/{conversation}/turns"
                second = await request(
                    run,
                    conversations,
                    "POST",
                    path,
                    body=command,
                    key=key,
                    status=202,
                )
                result = await execute(
                    run, conversations, conversation, second, "knowledge-qa-v3", chat
                )
                assert result.result["query"] == {
                    "originalQuestion": ORIGINAL,
                    "retrievalQuestion": REWRITTEN,
                    "mode": "REWRITTEN",
                    "fallbackReason": None,
                    "contextTruncated": False,
                }
                assert {ref["documentId"] for ref in result.result["references"]} == set(
                    run.documents
                )
                replay = await request(
                    run, conversations, "POST", path, body=command, key=key, status=202
                )
                assert replay["turnId"] == second["turnId"] and replay["taskId"] == second["taskId"]
                assert len(chat.operations) == 3 and run.embedding.calls == 2
                await browser(run, conversations, conversation)
                run.state.update(
                    conversationId=conversation,
                    query=result.result["query"],
                    modelCalls=len(chat.operations),
                    paidModelCalls=0,
                )
                run.save()

    asyncio.run(scenario())
