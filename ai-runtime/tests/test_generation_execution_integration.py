"""新图真实PG/中央HTTP接管；隔离测试库，知识/模型为显式协议替身，无付费调用。"""

import asyncio
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
from contextlib import AsyncExitStack, asynccontextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import httpx
import jwt
import pytest
from langsmith import tracing_context
from test_generation_evaluation import setup as evaluation_setup
from test_generation_qa import setup as qa_setup
from test_generation_work import setup as work_setup
from test_qa_pipeline import Knowledge as LegacyKnowledge
from test_qa_pipeline import question

from agentic_ai.agents.generation_pipeline import GenerationWorkPipeline
from agentic_ai.evaluation.generation_pipeline import GenerationEvaluationPipeline
from agentic_ai.qa.generation_pipeline import GenerationQaPipeline
from agentic_ai.qa.pipeline import QaPipeline
from agentic_ai.qa.worker import execute_qa_task
from agentic_ai.retrieval.errors import RetrievalCode
from agentic_ai.retrieval.evidence import GenerationReference
from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import TaskStatus
from agentic_ai.tasks.postgres import CheckpointConfig, PostgresCheckpoints

pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_GENERATION_PG_IT") != "1", reason="需显式启用隔离新图PG/中央HTTP验收"
)
ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture(scope="module")
def services():
    """仅启动当前Task jar的测试实例，不改已运行容器、开发库或模型worker。"""
    sys.path.insert(0, str(ROOT / "deploy"))
    import checkpoint_local
    import lifecycle_local
    import local
    import task_local

    environment = local.environment("test")
    allowed, denied, checks, errors = set(), set(), [], []
    public = lifecycle_local.PUBLIC_KEY.read_bytes()

    class Citations(BaseHTTPRequestHandler):
        def do_POST(self):
            try:
                claims = jwt.decode(
                    self.headers["X-Internal-Token"],
                    public,
                    algorithms=["RS256"],
                    issuer="urn:agentic-ai:task-knowledge-reader",
                    audience="agentic-ai-knowledge",
                )
                assert claims["scope"] == "knowledge:citations:check"
                assert claims["azp"] == "async-task-service"
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if self.path == "/internal/v2/knowledge/citations/check":
                    assert body["schemaVersion"] == "knowledge-evidence-v2"
                    fields = 8
                else:
                    assert self.path == "/internal/v1/knowledge/citations/check"
                    fields = 3
                assert body["references"] and all(len(ref) == fields for ref in body["references"])
                checks.append((claims["tenant_id"], body["references"]))
                accessible = claims["tenant_id"] in allowed and claims["tenant_id"] not in denied
                self.send_response(200)
                payload = json.dumps({"accessible": accessible}).encode()
            except Exception as failure:
                errors.append(type(failure).__name__)
                self.send_response(400)
                payload = b"{}"
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *_args):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Citations) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        environment.update(
            TASK_PORT=str(port),
            TASK_MANAGEMENT_PORT="0",
            TASK_GENERATION_ENABLED="true",
            TASK_KNOWLEDGE_READER_ENABLED="true",
            TASK_KNOWLEDGE_READER_ENDPOINT=f"http://127.0.0.1:{server.server_port}",
        )
        endpoint = f"http://127.0.0.1:{port}"
        directory = ROOT / ".logs" / f"s11e-generation-pg-{uuid4()}"
        directory.mkdir()
        database = task_local.load()["resources"]["test"]["database"]
        assert re.fullmatch(r"[a-zA-Z0-9_]+", database) and "test" in database
        with (directory / "tasks.log").open("w") as log:
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
                        assert process.poll() is None, "隔离Task启动失败，见测试目录tasks.log"
                        try:
                            if http.get(endpoint + "/api/v1/tasks").status_code == 401:
                                break
                        except httpx.HTTPError:
                            pass
                        time.sleep(0.1)
                    else:
                        pytest.fail("隔离Task启动超过期限")
                yield SimpleNamespace(
                    endpoint=endpoint,
                    allowed=allowed,
                    denied=denied,
                    checks=checks,
                    directory=directory,
                    database=database,
                    local=local,
                    checkpoints=CheckpointConfig(dsn=checkpoint_local.dsn("test")),
                )
                assert not errors
            finally:
                process.terminate()
                process.wait(timeout=15)
                server.shutdown()
                thread.join(timeout=5)


class PersistentCase:
    """输入/任务编号先留存，故障注入只触及本测试库中本任务的租约时间。"""

    def __init__(self, services, stack, kind):
        self.services, self.stack, self.kind = services, stack, kind
        self.task_id, self.tenant = str(uuid4()), "generation-pg-" + str(uuid4())
        self.owner = "protocol-owner"
        values = qa_setup() if kind == "qa" else work_setup()
        pipeline, _, self.embedding, self.knowledge, self.chat, self.use_case = values
        self.input, self.graph = pipeline.binding.input, pipeline.binding.graph_version
        self.task_kind = pipeline.binding.task.kind
        self.services.allowed.add(self.tenant)
        self.services.local.private(
            services.directory / (self.task_id + ".json"),
            json.dumps({"taskId": self.task_id, "tenant": self.tenant, "graph": self.graph}),
        )

    async def submit(self, *, route="/internal/v1/tasks", claims=None, key=None):
        import task_smoke

        async with httpx.AsyncClient(trust_env=False, timeout=5) as http:
            response = await http.post(
                self.services.endpoint + route,
                headers={
                    "X-Internal-Token": task_smoke.service_token(
                        "submit", self.tenant, self.owner, claims=claims, key=key
                    ),
                    "Idempotency-Key": self.task_id,
                },
                json={
                    "taskId": self.task_id,
                    "kind": self.task_kind,
                    "runtimeVersion": "langgraph-v1",
                    "graphVersion": self.graph,
                    "input": self.input,
                },
            )
            assert response.status_code == 202, response.text

    async def client(self):
        client = CentralTaskClient.from_file(
            self.services.endpoint,
            self.tenant,
            "generation-worker-" + str(uuid4()),
            ROOT / ".local/task-worker-private.pem",
        )
        self.stack.push_async_callback(client.aclose)
        return client

    async def claim(self):
        client = await self.client()
        lease = await client.claim(self.graph)
        assert lease is not None and lease.task.task_id == self.task_id
        assert lease.input == self.input
        return client, lease

    def pipeline(self, client, lease):
        factory = GenerationQaPipeline if self.kind == "qa" else GenerationWorkPipeline
        return factory(lease, client, self.knowledge, self.chat, self.use_case)

    async def pause(self, client, lease, stage):
        store = await self.stack.enter_async_context(
            PostgresCheckpoints.open(self.services.checkpoints)
        )
        saver = FencedPostgresSaver(lease, client, store)
        pipeline = self.pipeline(client, lease)
        graph = pipeline.build(saver, interrupt_before=[stage] if stage else None)
        await graph.ainvoke(lease.input, saver.config, durability="sync")
        snapshot = await graph.aget_state(saver.config)
        assert snapshot.values["generationEvidence"]
        # 中央清单读取只向已成功的上下文开放；进行中从已确认PG快照保留完整依赖。
        references = tuple(
            GenerationReference.model_validate(hit.identity().model_dump())
            for hit in pipeline.generation.hits.values()
        )
        assert len(references) >= 2
        return references, snapshot

    async def expire(self):
        sql = (
            f"UPDATE {self.services.database}.ai_task SET lease_expires_at="
            "TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) "
            f"WHERE task_id='{self.task_id}' AND tenant_id='{self.tenant}' AND status='RUNNING';"
            "SELECT ROW_COUNT();"
        )
        assert (await asyncio.to_thread(self.services.local.mysql, sql)).strip() == "1"

    async def execute(self, client, lease, *, checkpoints=None):
        return await execute_qa_task(
            lease,
            client,
            checkpoints or self.services.checkpoints,
            LegacyKnowledge(),
            self.chat,
            tool_chat=self.chat if self.kind == "work" else None,
            retrieval=self.use_case,
            candidates=self.knowledge,
        )

    async def cancel(self, revision):
        now = int(time.time())
        token = jwt.encode(
            {
                "iss": "urn:agentic-ai:edge",
                "aud": "agentic-ai-tasks",
                "sub": self.owner,
                "tenant_id": self.tenant,
                "scope": "task:read task:write",
                "iat": now,
                "nbf": now,
                "exp": now + 60,
            },
            (ROOT / ".local/internal-private.pem").read_bytes(),
            algorithm="RS256",
        )
        async with httpx.AsyncClient(trust_env=False, timeout=5) as http:
            response = await http.post(
                self.services.endpoint + f"/api/v1/tasks/{self.task_id}/cancel",
                headers={"X-Internal-Token": token},
                json={"expectedRevision": revision},
            )
        assert response.status_code == 200


@asynccontextmanager
async def case(services, kind):
    with tracing_context(enabled=False):
        async with AsyncExitStack() as stack:
            value = (
                PersistentEvaluation(services, stack)
                if kind == "evaluation"
                else PersistentCase(services, stack, kind)
            )
            await value.submit()
            yield value


@pytest.mark.parametrize(
    "kind,stage",
    [
        ("qa", "generate"),
        ("qa", "verify"),
        ("qa", None),
        ("work", "analyze"),
        ("work", "verify"),
        ("work", None),
    ],
)
def test_new_graph_real_takeover_preserves_complete_dependencies_and_finished_calls(
    services, kind, stage
):
    async def scenario():
        async with case(services, kind) as task:
            first, lease = await task.claim()
            manifest, _ = await task.pause(first, lease, stage)
            assert len(task.embedding.calls) == 1
            await task.expire()
            second, resumed = await task.claim()
            assert resumed.epoch == lease.epoch + 1 and resumed.checkpoint is not None
            result = await task.execute(second, resumed)
            assert result.status == TaskStatus.SUCCEEDED, result.error_code
            assert len(task.embedding.calls) == 1 and task.chat.calls == (1 if kind == "qa" else 2)
            current = await second.generation_manifest(task.task_id)
            assert set(current.references) == set(manifest)
            assert len(current.references) > len(result.result["references"])
            # 终态重放不连PG、不重复模型，也不升级为不同图或策略。
            replay = await task.execute(
                second,
                resumed,
                checkpoints=CheckpointConfig(dsn="postgresql://127.0.0.1:1/unreachable"),
            )
            assert replay == result and len(task.embedding.calls) == 1

    asyncio.run(scenario())


class PersistentEvaluation(PersistentCase):
    """报告端口仍为显式协议替身；这里只证明新评测根图和中央完成凭据的真实持久恢复。"""

    def __init__(self, services, stack):
        super().__init__(services, stack, "qa")
        self.kind = "evaluation"
        pipeline, _, self.reports, self.embedding, self.chat, self.knowledge, self.use_case = (
            evaluation_setup()
        )
        self.input, self.graph = pipeline.binding.input, pipeline.binding.graph_version
        self.task_kind, self.profile = pipeline.binding.task.kind, pipeline.profile
        self.opened = pipeline.opened.model_copy(update={"task_id": self.task_id})
        self.reports.opened = self.opened
        self.services.local.private(
            services.directory / (self.task_id + ".json"),
            json.dumps({"taskId": self.task_id, "tenant": self.tenant, "graph": self.graph}),
        )

    async def submit(self):
        await super().submit(
            route="/internal/v1/evaluation-tasks",
            key="knowledge-evaluation",
            claims={
                "iss": "urn:agentic-ai:knowledge-evaluation",
                "azp": "knowledge-service",
                "scope": "evaluation:task",
            },
        )

    def pipeline(self, client, lease):
        self.reports.binding = lease
        return GenerationEvaluationPipeline(
            lease,
            client,
            self.knowledge,
            self.chat,
            self.reports,
            self.profile,
            self.opened,
            self.use_case,
        )

    async def execute(self, client, lease, *, checkpoints=None):
        self.reports.binding = lease
        return await execute_qa_task(
            lease,
            client,
            checkpoints or self.services.checkpoints,
            LegacyKnowledge(),
            self.chat,
            evaluations=self.reports,
            profile=self.profile,
            retrieval=self.use_case,
            candidates=self.knowledge,
        )


@pytest.mark.parametrize("stage", ["prefix", "complete"])
def test_evaluation_v2_pg_takeover_reuses_saved_questions_and_preserves_strategy(services, stage):
    async def scenario():
        async with case(services, "evaluation") as task:
            first, lease = await task.claim()
            store = await task.stack.enter_async_context(
                PostgresCheckpoints.open(services.checkpoints)
            )
            saver = FencedPostgresSaver(lease, first, store)
            graph = task.pipeline(first, lease).build(
                saver, interrupt_before=["question"] if stage == "prefix" else None
            )
            await graph.ainvoke(lease.input, saver.config, durability="sync")
            if stage == "prefix":
                await graph.ainvoke(None, saver.config, durability="sync")
            snapshot = await graph.aget_state(saver.config)
            assert snapshot.values["retrievalStrategy"] == "LLAMA_RRF_V1"
            assert snapshot.values["nextIndex"] == (1 if stage == "prefix" else 2)
            assert task.chat.calls == len(task.embedding.calls) == snapshot.values["nextIndex"]
            await task.expire()
            second, resumed = await task.claim()
            assert resumed.epoch == lease.epoch + 1 and resumed.checkpoint is not None
            result = await task.execute(second, resumed)
            assert result.status == TaskStatus.SUCCEEDED, result.error_code
            assert task.chat.calls == len(task.embedding.calls) == 2
            assert result.result["epoch"] == resumed.epoch
            replay = await task.execute(
                second,
                resumed,
                checkpoints=CheckpointConfig(dsn="postgresql://127.0.0.1:1/unreachable"),
            )
            assert replay == result and task.chat.calls == 2

    asyncio.run(scenario())


@pytest.mark.parametrize("kind", ["qa", "work"])
def test_new_graph_persisted_restore_rejects_generation_drift_before_model(services, kind):
    async def scenario():
        async with case(services, kind) as task:
            first, lease = await task.claim()
            await task.pause(first, lease, "generate" if kind == "qa" else "analyze")
            await task.expire()
            second, resumed = await task.claim()
            task.knowledge.failure = RetrievalCode.EVIDENCE_REJECTED
            result = await task.execute(second, resumed)
            assert result.status == TaskStatus.FAILED and result.error_code == "INPUT_REJECTED"
            assert task.chat.calls == 0 and len(task.embedding.calls) == 1

    asyncio.run(scenario())


@pytest.mark.parametrize("kind", ["qa", "work"])
def test_new_graph_cancellation_after_checkpoint_prevents_model_and_completion(services, kind):
    async def scenario():
        async with case(services, kind) as task:
            first, lease = await task.claim()
            await task.pause(first, lease, "generate" if kind == "qa" else "analyze")
            current = await first.inspect(task.task_id)
            await task.cancel(current.task.revision)
            result = await task.execute(first, lease)
            assert result.status == TaskStatus.CANCELLED and result.result is None
            assert task.chat.calls == 0 and len(task.embedding.calls) == 1

    asyncio.run(scenario())


def test_completed_generation_context_read_obeys_central_full_evidence_denial(services):
    async def scenario():
        async with case(services, "qa") as task:
            first, lease = await task.claim()
            await task.pause(first, lease, None)
            assert task.chat.calls == 1
            await task.expire()
            second, resumed = await task.claim()
            result = await task.execute(second, resumed)
            assert result.status == TaskStatus.SUCCEEDED
            # 模型/完成前重验由运行时原文端口负责；中央受限清单读取另核对当前权限。
            services.denied.add(task.tenant)
            with pytest.raises(TaskRuntimeError) as denied:
                await second.generation_manifest(task.task_id)
            assert denied.value.code == TaskRuntimeCode.PROTOCOL_INVALID
            assert task.chat.calls == len(task.embedding.calls) == 1
            assert any(tenant == task.tenant and len(refs) > 1 for tenant, refs in services.checks)

    asyncio.run(scenario())


def test_legacy_graph_keeps_original_input_and_restores_under_new_central_service(services):
    async def scenario():
        with tracing_context(enabled=False):
            async with AsyncExitStack() as stack:
                task = PersistentCase(services, stack, "qa")
                task.graph, task.input = "knowledge-qa-v1", question()
                services.local.private(
                    services.directory / (task.task_id + ".json"),
                    json.dumps(
                        {"taskId": task.task_id, "tenant": task.tenant, "graph": task.graph}
                    ),
                )
                await task.submit()
                first, lease = await task.claim()
                legacy = LegacyKnowledge()
                store = await stack.enter_async_context(
                    PostgresCheckpoints.open(services.checkpoints)
                )
                saver = FencedPostgresSaver(lease, first, store)
                graph = QaPipeline(lease, first, legacy, task.chat).build(
                    saver, interrupt_before=["generate"]
                )
                await graph.ainvoke(lease.input, saver.config, durability="sync")
                await task.expire()
                second, resumed = await task.claim()
                assert (
                    resumed.graph_version == "knowledge-qa-v1"
                    and "retrievalStrategy" not in resumed.input
                )
                result = await execute_qa_task(
                    resumed, second, services.checkpoints, legacy, task.chat
                )
                assert result.status == TaskStatus.SUCCEEDED
                assert result.result["schemaVersion"] == "knowledge-qa-result-v1"
                assert "references" not in result.result and legacy.searches == task.chat.calls == 1

    asyncio.run(scenario())
