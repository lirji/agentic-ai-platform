"""由CrossRuntimeRetrievalIT提供真实已发布资料与Knowledge；中央/报告/PG全部真实。"""

import asyncio
import json
import os
import re
import subprocess
import sys
import time
from contextlib import AsyncExitStack
from pathlib import Path
from uuid import UUID, uuid4

import httpx
import jwt
import pytest
from langsmith import tracing_context
from test_evaluation_pipeline import PROFILE, EvaluationChat
from test_qa_pipeline import Chat, question
from test_work_pipeline import ToolsChat

from agentic_ai.evaluation.client import EvaluationClient
from agentic_ai.evaluation.generation_pipeline import GenerationEvaluationPipeline
from agentic_ai.qa.worker import execute_qa_task
from agentic_ai.rag.ports import EmbeddingResult
from agentic_ai.retrieval.client import RetrievalClient
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.deadline import RetrievalCapacity
from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.models import TaskStatus
from agentic_ai.tasks.postgres import CheckpointConfig, PostgresCheckpoints

pytestmark = pytest.mark.skipif(
    not os.environ.get("AGENTIC_EVALUATION_FULL_IT"), reason="由Java真实发布/HTTP联合夹具显式启动"
)
ROOT = Path(__file__).resolve().parents[2]
QUERY = "授权候选检索资料"
STRATEGY = "LLAMA_RRF_V1"


@pytest.fixture(scope="module")
def joint():
    sys.path.insert(0, str(ROOT / "deploy"))
    import local
    import task_local

    fixture = Path(os.environ["AGENTIC_EVALUATION_FULL_IT"])
    value = json.loads(fixture.read_text())
    environment = local.environment("test")
    environment.update(
        TASK_PORT=str(value["taskPort"]),
        TASK_MANAGEMENT_PORT="0",
        TASK_GENERATION_ENABLED="true",
        TASK_KNOWLEDGE_READER_ENABLED="true",
        TASK_KNOWLEDGE_READER_ENDPOINT=value["knowledgeEndpoint"],
    )
    value["taskEndpoint"] = f"http://127.0.0.1:{value['taskPort']}"
    value["directory"] = fixture.parent
    value["database"] = task_local.load()["resources"]["test"]["database"]
    assert re.fullmatch(r"[a-zA-Z0-9_]+", value["database"]) and "test" in value["database"]
    with (fixture.parent / "full-tasks.log").open("w") as log:
        process = subprocess.Popen(
            [
                "java",
                "-jar",
                str(ROOT / "async-task-service/target/async-task-service-0.1.0-SNAPSHOT.jar"),
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
                    assert process.poll() is None, "测试Task未启动，见full-tasks.log"
                    try:
                        if http.get(value["taskEndpoint"] + "/api/v1/tasks").status_code == 401:
                            break
                    except httpx.HTTPError:
                        pass
                    time.sleep(0.1)
                else:
                    pytest.fail("测试Task启动超过期限")
            yield value
        finally:
            process.terminate()
            process.wait(timeout=15)


class ProtocolEmbedding:
    """仅模型端口用确定性向量；候选、融合、解析、权限、发布和报告不替换。"""

    def __init__(self):
        self.calls = 0

    async def embed(self, texts):
        self.calls += 1
        return EmbeddingResult(((0.125,) * 1024,), "text-embedding-v4", 1024)


class Joint:
    def __init__(self, fixture, stack):
        import checkpoint_local

        self.fixture, self.stack = fixture, stack
        self.tenant, self.owner = fixture["tenant"], fixture["owner"]
        assert re.fullmatch(r"eval-joint-[a-f0-9-]+", self.tenant)
        self.documents = sorted(doc["documentId"] for doc in fixture["documents"])
        self.embedding = ProtocolEmbedding()
        self.use_case = RetrievalUseCase(self.embedding, RetrievalCapacity(1))
        self.profile = PROFILE.model_copy(update={"retrieval_profile": STRATEGY})
        self.checkpoints = CheckpointConfig(dsn=checkpoint_local.dsn("test"))
        self.state = {"tenant": self.tenant, "scope": "real-services-protocol-models", "tasks": []}

    def save(self):
        import local

        local.private(self.fixture["directory"] / "consumer-state.json", json.dumps(self.state))

    async def request(
        self, method, path, *, body=None, key=None, status=200, task=False, owner=None
    ):
        now = int(time.time())
        token = jwt.encode(
            {
                "iss": "urn:agentic-ai:edge",
                "aud": "agentic-ai-tasks" if task else "agentic-ai-knowledge",
                "sub": owner or self.owner,
                "tenant_id": self.tenant,
                "scope": "task:read task:write" if task else "knowledge:read knowledge:write",
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
        async with httpx.AsyncClient(trust_env=False, timeout=35) as http:
            response = await http.request(
                method,
                self.fixture["taskEndpoint" if task else "knowledgeEndpoint"] + path,
                headers=headers,
                json=body,
            )
        assert response.status_code == status, (response.status_code, response.text)
        return response.json()

    async def client(self, graph, task_id):
        central = CentralTaskClient.from_file(
            self.fixture["taskEndpoint"],
            self.tenant,
            "full-worker-" + str(uuid4()),
            ROOT / ".local/task-worker-private.pem",
        )
        self.stack.push_async_callback(central.aclose)
        lease = await central.claim(graph)
        assert lease is not None and lease.task.task_id == task_id
        return central, lease

    async def candidates(self):
        client = RetrievalClient.from_file(
            self.fixture["knowledgeEndpoint"],
            self.tenant,
            self.owner,
            ROOT / ".local/runtime-knowledge-private.pem",
        )
        self.stack.push_async_callback(client.aclose)
        return client

    async def reports(self, lease):
        client = EvaluationClient.from_file(
            self.fixture["knowledgeEndpoint"], lease, ROOT / ".local/runtime-evaluation-private.pem"
        )
        self.stack.push_async_callback(client.aclose)
        return client

    async def expire(self, task_id):
        import local

        assert str(UUID(task_id)) == task_id and task_id in self.state["tasks"]
        statement = (
            f"UPDATE {self.fixture['database']}.ai_task SET lease_expires_at="
            "TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) "
            f"WHERE task_id='{task_id}' AND tenant_id='{self.tenant}' AND status='RUNNING';"
            "SELECT ROW_COUNT();"
        )
        assert (await asyncio.to_thread(local.mysql, statement)).strip() == "1"

    async def browser(self):
        """可选真实浏览器只代理本次隔离服务；签名在Node侧产生，夹具不保存令牌。"""
        if os.environ.get("AGENTIC_JOINT_BROWSER") != "1":
            return
        frontend = ROOT.parent / "agentic-ai-platform-web"
        fixture = self.fixture["directory"] / "browser-fixture.json"
        fixture.write_text(
            json.dumps(
                {
                    **{
                        key: self.fixture[key]
                        for key in ("knowledgeEndpoint", "taskEndpoint", "tenant", "owner")
                    },
                    **{key: self.state[key] for key in ("workTaskId", "runId", "datasetId")},
                    "backendRoot": str(ROOT),
                }
            )
        )
        environment = dict(os.environ)
        environment.update(
            JOINT_BROWSER_FIXTURE=str(fixture),
            PLAYWRIGHT_BASE_URL="http://127.0.0.1:28187",
            PATH=str(frontend / ".local/toolchains/node-v24.22.0-darwin-arm64/bin")
            + os.pathsep
            + environment["PATH"],
        )
        with (self.fixture["directory"] / "browser.log").open("w") as log:
            process = await asyncio.create_subprocess_exec(
                "pnpm",
                "exec",
                "playwright",
                "test",
                "tests/retrieval-real-joint.spec.ts",
                "--reporter=list",
                "--output=.logs/s11e-real-joint-artifacts",
                cwd=frontend,
                env=environment,
                stdout=log,
                stderr=subprocess.STDOUT,
            )
            try:
                async with asyncio.timeout(100):
                    assert await process.wait() == 0, "真实浏览器失败，见同目录browser.log"
            finally:
                if process.returncode is None:
                    process.terminate()
                    await asyncio.wait_for(process.wait(), 10)


def test_search_qa_work_and_evaluation_share_real_authorized_hits_and_restore_reports(joint):
    async def scenario():
        import task_smoke

        with tracing_context(enabled=False):
            async with AsyncExitStack() as stack:
                run = Joint(joint, stack)
                search = await run.request(
                    "POST",
                    "/api/v2/knowledge/search",
                    body={
                        "schemaVersion": "knowledge-search-input-v2",
                        "query": QUERY,
                        "documentIds": run.documents,
                        "topK": 5,
                        "strategy": STRATEGY,
                    },
                )
                assert search["mode"] == "RRF" and len(search["hits"]) == 2
                expected = {json.dumps(hit["identity"], sort_keys=True) for hit in search["hits"]}
                candidates = await run.candidates()
                # QA走中央受限受理，研究走新公开入口；两条执行路径都使用正式同一检索用例。
                for kind in ("qa", "work"):
                    task_id = str(uuid4())
                    run.state[kind + "TaskId"] = task_id
                    run.state["tasks"].append(task_id)
                    run.save()
                    if kind == "qa":
                        async with httpx.AsyncClient(trust_env=False, timeout=10) as http:
                            response = await http.post(
                                joint["taskEndpoint"] + "/internal/v1/tasks",
                                headers={
                                    "X-Internal-Token": task_smoke.service_token(
                                        "submit", run.tenant, run.owner
                                    ),
                                    "Idempotency-Key": task_id,
                                },
                                json={
                                    "taskId": task_id,
                                    "kind": "KNOWLEDGE_QA",
                                    "runtimeVersion": "langgraph-v1",
                                    "graphVersion": "knowledge-qa-v2",
                                    "input": question(
                                        schemaVersion="knowledge-qa-input-v2",
                                        question=QUERY,
                                        documentIds=run.documents,
                                        retrievalStrategy=STRATEGY,
                                    ),
                                },
                            )
                        assert response.status_code == 202, response.text
                    else:
                        await run.request(
                            "POST",
                            "/api/v2/tasks",
                            task=True,
                            key=task_id,
                            status=202,
                            body={
                                "taskId": task_id,
                                "kind": "RESEARCH",
                                "mode": "REPORT",
                                "question": QUERY,
                                "documentIds": run.documents,
                                "retrievalStrategy": STRATEGY,
                            },
                        )
                    central, lease = await run.client(
                        "knowledge-qa-v2" if kind == "qa" else "knowledge-work-v2", task_id
                    )
                    result = await execute_qa_task(
                        lease,
                        central,
                        run.checkpoints,
                        candidates,
                        Chat(),
                        tool_chat=ToolsChat(),
                        retrieval=run.use_case,
                        candidates=candidates,
                    )
                    assert result.status == TaskStatus.SUCCEEDED, result.error_code
                    manifest = await central.generation_manifest(task_id)
                    assert {
                        json.dumps(ref.model_dump(mode="json", by_alias=True), sort_keys=True)
                        for ref in manifest.references
                    } == expected
                    assert result.result["retrieval"][0]["mode"] == "RRF"
                assert run.embedding.calls == 2

                questions = [
                    {
                        "questionId": str(uuid4()),
                        "question": QUERY,
                        "expectation": "ANSWERABLE",
                        "critical": True,
                        "rubric": "必须保留当前发布引用",
                        "referenceAnswer": "机制夹具",
                        "documentIds": run.documents,
                        "evidence": [
                            {
                                k: joint["documents"][0][k]
                                for k in ("documentId", "version", "chunkId")
                            }
                            | {"relevance": 2}
                        ],
                    }
                    for _ in range(2)
                ]
                dataset = await run.request(
                    "POST",
                    "/api/v1/knowledge/evaluations/datasets",
                    key=str(uuid4()),
                    status=201,
                    body={
                        "name": "S11E真实联合机制",
                        "description": "协议模型，不代表真实质量",
                        "purpose": "MECHANISM",
                        "questions": questions,
                    },
                )
                key = str(uuid4())
                body = {
                    "datasetId": dataset["datasetId"],
                    "revision": 1,
                    "topK": 5,
                    "retrievalStrategy": STRATEGY,
                }
                accepted = await run.request(
                    "POST", "/api/v2/knowledge/evaluations/runs", key=key, body=body, status=202
                )
                run_id = accepted["summary"]["runId"]
                run.state.update(runId=run_id, datasetId=dataset["datasetId"], key=key)
                run.save()
                repeated = await run.request(
                    "POST", "/api/v2/knowledge/evaluations/runs", key=key, body=body, status=202
                )
                assert repeated["summary"]["runId"] == run_id
                await run.request(
                    "POST",
                    "/api/v2/knowledge/evaluations/runs",
                    key=key,
                    body={**body, "retrievalStrategy": "LLAMA_RERANK_V1"},
                    status=409,
                )
                for _ in range(60):
                    view = await run.request("GET", f"/api/v1/knowledge/evaluations/runs/{run_id}")
                    if view["task"] and view["task"]["status"] == "QUEUED":
                        break
                    await asyncio.sleep(0.1)
                else:
                    pytest.fail("真实评测交接未在期限内排队")
                task_id = view["task"]["taskId"]
                run.state["tasks"].append(task_id)
                run.save()
                first, lease = await run.client("knowledge-evaluation-v2", task_id)
                reports = await run.reports(lease)
                opened = await reports.open(run.profile)
                chat = EvaluationChat()
                pipeline = GenerationEvaluationPipeline(
                    lease, first, candidates, chat, reports, run.profile, opened, run.use_case
                )
                store = await stack.enter_async_context(PostgresCheckpoints.open(run.checkpoints))
                saver = FencedPostgresSaver(lease, first, store)
                graph = pipeline.build(saver, interrupt_before=["question"])
                await graph.ainvoke(lease.input, saver.config, durability="sync")
                await graph.ainvoke(None, saver.config, durability="sync")
                snapshot = await graph.aget_state(saver.config)
                assert (
                    snapshot.values["nextIndex"] == 1
                    and chat.calls == 1
                    and run.embedding.calls == 3
                )
                await run.expire(task_id)
                second, resumed = await run.client("knowledge-evaluation-v2", task_id)
                final = await execute_qa_task(
                    resumed,
                    second,
                    run.checkpoints,
                    candidates,
                    chat,
                    evaluations=await run.reports(resumed),
                    profile=run.profile,
                    retrieval=run.use_case,
                    candidates=candidates,
                )
                assert final.status == TaskStatus.SUCCEEDED, final.error_code
                assert chat.calls == 2 and run.embedding.calls == 4
                detail = await run.request("GET", f"/api/v1/knowledge/evaluations/runs/{run_id}")
                assert detail["report"]["complete"] and detail["report"]["succeeded"] == 2
                for question_id in [q["questionId"] for q in questions]:
                    report = await run.request(
                        "GET",
                        f"/api/v1/knowledge/evaluations/runs/{run_id}/questions/{question_id}",
                    )
                    assert {
                        json.dumps(ref, sort_keys=True)
                        for ref in report["generation"]["references"]
                    } == expected
                    assert report["generation"]["retrieval"]["mode"] == "RRF"
                await run.request(
                    "GET",
                    f"/api/v1/knowledge/evaluations/runs/{run_id}",
                    owner="foreign-owner",
                    status=404,
                )
                await run.browser()
                # 撤下后历史报告/运行也必须核对当前完整证据，不能借已成功快照透出旧正文。
                document = await run.request(
                    "GET", "/api/v2/knowledge/documents/" + run.documents[0]
                )
                await run.request(
                    "POST",
                    "/api/v2/knowledge/documents/" + run.documents[0] + "/withdrawals",
                    key=str(uuid4()),
                    body={"expectedRevision": document["revision"]},
                )
                hidden = await run.request("GET", f"/api/v1/knowledge/evaluations/runs/{run_id}")
                assert hidden["report"] is None and hidden["bindingState"] != "AVAILABLE"
                await run.request(
                    "GET",
                    f"/api/v1/knowledge/evaluations/runs/{run_id}/questions/{questions[0]['questionId']}",
                    status=409,
                )
                assert chat.calls == 2 and run.embedding.calls == 4
                run.state.update(status="PASS", embeddingCalls=4, evaluationModelCalls=2)
                run.save()

    asyncio.run(scenario())
