"""真实 PG/中央 MySQL/HTTP + 本机合成模型；不调用知识 Embedding 或真实聊天提供方。"""

import asyncio
import json
import os
import signal
import subprocess
import sys
import threading
import time
from contextlib import AsyncExitStack, asynccontextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import pytest
from langsmith import tracing_context
from test_qa_chat import SUCCESS, config_at, event, ledger_at, read_ledger
from test_qa_pipeline import Knowledge, question
from test_task_checkpoints_integration import CENTRAL, RealTask

from agentic_ai.qa.chat import ChatClient
from agentic_ai.qa.chat_budget import ChatBudget
from agentic_ai.qa.pipeline import QaPipeline
from agentic_ai.qa.worker import execute_qa_task
from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.models import TaskStatus
from agentic_ai.tasks.postgres import CheckpointConfig

pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_QA_EXECUTION_IT") != "1", reason="需显式启用本项目真实执行验收"
)


class ModelServer:
    """测试替身是实际 loopback TCP/SSE 服务，仅返回固定合成文本。"""

    def __init__(self):
        self.calls = 0
        self.pause = False
        self.started = threading.Event()
        self.release = threading.Event()
        self.before_request = None
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_POST(self):
                assert self.path == "/v1/chat/completions"
                assert self.headers["Authorization"] == "Bearer synthetic-key"
                content = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                assert content["model"] == "chat-default" and content["stream"]
                assert content["max_tokens"] <= 1024 and "tools" not in content
                if owner.before_request:
                    owner.before_request()
                owner.calls += 1
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()
                try:
                    if owner.pause:
                        self.wfile.write(event("未完成"))
                        self.wfile.flush()
                        owner.started.set()
                        owner.release.wait(timeout=5)
                    self.wfile.write(SUCCESS)
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self):
        self.thread.start()

    def close(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


@pytest.fixture
def model_server():
    server = ModelServer()
    server.start()
    try:
        yield server
    finally:
        server.close()


@asynccontextmanager
async def qa_task(tmp_path, model_server):
    with tracing_context(enabled=False):
        async with AsyncExitStack() as stack:
            task = RealTask(stack)
            task.input = question()
            task.local.private(
                task.local.STATE / f"s5d-{task.task_id}.json",
                json.dumps({"taskId": task.task_id, "tenant": task.tenant, "owner": task.owner}),
            )
            async with httpx.AsyncClient(trust_env=False, timeout=5) as http:
                response = await http.post(
                    CENTRAL + "/internal/v1/tasks",
                    headers={
                        "X-Internal-Token": task.sign("submit", task.tenant, task.owner),
                        "Idempotency-Key": task.task_id,
                    },
                    json={
                        "taskId": task.task_id,
                        "kind": "KNOWLEDGE_QA",
                        "runtimeVersion": "langgraph-v1",
                        "graphVersion": "knowledge-qa-v1",
                        "input": task.input,
                    },
                )
                assert response.status_code == 202
            config = config_at(
                tmp_path, base_url=f"http://127.0.0.1:{model_server.server.server_port}/v1"
            )
            ledger_at(config)
            task.chat_config = config

            def reserved_before_wire():
                assert len(read_ledger(config)["calls"]) == model_server.calls + 1

            model_server.before_request = reserved_before_wire
            task.chat = ChatClient(config, ChatBudget(config))
            stack.push_async_callback(task.chat.aclose)
            task.knowledge = Knowledge()
            yield task


async def stored_events(task):
    """合成Knowledge命中不存在于权威库，公共正文必须拒绝；原始进度只在测试库中核对。"""
    async with httpx.AsyncClient(trust_env=False, timeout=5) as http:
        response = await http.get(
            f"http://127.0.0.1:28080/api/v1/tasks/{task.task_id}/events",
            headers={
                "Authorization": "Bearer "
                + task.local.token(task.tenant, task.owner, "task:read task:write")
            },
        )
        assert response.status_code == 404
        assert "未完成" not in response.text
    rows = await asyncio.to_thread(
        task.local.mysql,
        "SELECT type,HEX(payload_json) FROM agentic_tasks.ai_task_event "
        f"WHERE task_id='{task.task_id}' ORDER BY sequence;",
    )
    return [
        {"type": kind, "payload": json.loads(bytes.fromhex(body))}
        for kind, body in (line.split("\t") for line in rows.splitlines())
    ]


@pytest.mark.parametrize("stage", ["generate", "verify"])
def test_real_graph_recovers_at_each_boundary_without_repeating_paid_steps(
    tmp_path, model_server, stage
):
    async def scenario():
        async with qa_task(tmp_path, model_server) as task:
            first = await task.client()
            lease = await first.claim()
            assert lease.task.task_id == task.task_id
            storage = await task.storage()
            await storage.verify_ready()
            saver = FencedPostgresSaver(lease, first, storage)
            pipeline = QaPipeline(lease, first, task.knowledge, task.chat)
            await pipeline.build(saver, interrupt_before=[stage]).ainvoke(
                lease.input, saver.config, durability="sync"
            )
            assert task.knowledge.searches == 1
            assert model_server.calls == (1 if stage == "verify" else 0)
            checkpoint = (await first.inspect(task.task_id)).checkpoint
            assert checkpoint is not None
            await task.expire()
            second = await task.client()
            resumed = await second.claim()
            assert resumed.epoch == lease.epoch + 1
            result = await execute_qa_task(resumed, second, task.config, task.knowledge, task.chat)
            assert result.status == TaskStatus.SUCCEEDED
            assert result.result["schemaVersion"] == "knowledge-qa-result-v1"
            assert set(result.result) == {"schemaVersion", "answerability", "answer", "citations"}
            assert task.knowledge.searches == 1 and model_server.calls == 1
            assert len(read_ledger(task.chat_config)["calls"]) == 1
            page = await stored_events(task)
            assert page[-1]["payload"]["status"] == "SUCCEEDED"
            progress = [row["payload"]["stage"] for row in page if row["type"] == "PROGRESS"]
            assert progress.count("RETRIEVING") == 1
            assert progress.count("GENERATING") == 1
            assert progress.count("VERIFYING") == 1
            # 同一终态在 PG 不可连接时也直接返回中央事实，不能重新付费。
            replay = await execute_qa_task(
                resumed,
                second,
                CheckpointConfig(dsn="postgresql://127.0.0.1:1/unavailable"),
                task.knowledge,
                task.chat,
            )
            assert replay == result and model_server.calls == 1
            latest = await second.inspect(task.task_id)
            assert latest.checkpoint.epoch == resumed.epoch
            snapshot = await storage.saver.aget_tuple(
                {
                    "configurable": {
                        "thread_id": latest.checkpoint.thread_id,
                        "checkpoint_ns": "",
                        "checkpoint_id": latest.checkpoint.checkpoint_id,
                    }
                }
            )
            state_json = json.dumps(snapshot.checkpoint["channel_values"])
            assert "synthetic-key" not in state_json
            assert "Authorization" not in state_json

    asyncio.run(scenario())


def test_restored_retrieval_is_revalidated_before_first_model_call(tmp_path, model_server):
    """旧PG快照含命中时，失效检查必须发生在恢复generate及模型外发之前。"""

    async def scenario():
        async with qa_task(tmp_path, model_server) as task:
            first = await task.client()
            lease = await first.claim()
            saver = FencedPostgresSaver(lease, first, await task.storage())
            await (
                QaPipeline(lease, first, task.knowledge, task.chat)
                .build(saver, interrupt_before=["generate"])
                .ainvoke(lease.input, saver.config, durability="sync")
            )
            assert task.knowledge.searches == 1 and model_server.calls == 0
            await task.expire()
            second = await task.client()
            resumed = await second.claim()
            task.knowledge.revoked = True
            result = await execute_qa_task(resumed, second, task.config, task.knowledge, task.chat)
            assert result.status == TaskStatus.FAILED and result.error_code == "INPUT_REJECTED"
            assert model_server.calls == 0 and len(read_ledger(task.chat_config)["calls"]) == 0

    asyncio.run(scenario())


def test_real_complete_snapshot_still_revalidates_permission_before_central_success(
    tmp_path, model_server
):
    async def scenario():
        async with qa_task(tmp_path, model_server) as task:
            first = await task.client()
            lease = await first.claim()
            storage = await task.storage()
            saver = FencedPostgresSaver(lease, first, storage)
            graph = QaPipeline(lease, first, task.knowledge, task.chat).build(saver)
            result = await graph.ainvoke(lease.input, saver.config, durability="sync")
            assert result["answerability"] == "ANSWERED"
            assert not (await graph.aget_state(saver.config)).next
            assert (await first.inspect(task.task_id)).task.status == TaskStatus.RUNNING
            await task.expire()
            second = await task.client()
            resumed = await second.claim()
            task.knowledge.revoked = True
            failed = await execute_qa_task(resumed, second, task.config, task.knowledge, task.chat)
            assert failed.status == TaskStatus.FAILED
            assert failed.error_code == "INPUT_REJECTED" and not failed.retryable
            assert failed.result is None and model_server.calls == 1
            assert task.knowledge.searches == 1
            assert (await stored_events(task))[-1]["payload"]["status"] == "FAILED"

    asyncio.run(scenario())


def test_real_cancel_during_stream_stops_heartbeat_and_preserves_unknown_cost(
    tmp_path, model_server, monkeypatch
):
    async def scenario():
        async with qa_task(tmp_path, model_server) as task:
            model_server.pause = True
            monkeypatch.setattr("agentic_ai.qa.worker.HEARTBEAT_SECONDS", 0.05)
            central = await task.client()
            lease = await central.claim()
            work = asyncio.create_task(
                execute_qa_task(lease, central, task.config, task.knowledge, task.chat)
            )
            try:
                assert await asyncio.to_thread(model_server.started.wait, 5)
                current = await central.inspect(task.task_id)
                async with httpx.AsyncClient(trust_env=False, timeout=5) as http:
                    cancelled = await http.post(
                        f"http://127.0.0.1:28080/api/v1/tasks/{task.task_id}/cancel",
                        headers={
                            "Authorization": "Bearer "
                            + task.local.token(task.tenant, task.owner, "task:read task:write")
                        },
                        json={"expectedRevision": current.task.revision},
                    )
                    assert cancelled.status_code == 200
                result = await asyncio.wait_for(work, 5)
                assert result.status == TaskStatus.CANCELLED and result.result is None
                page = await stored_events(task)
                assert page[-1]["payload"]["status"] == "CANCELLED"
                assert all(row["type"] != "TEXT_DELTA" for row in page)
                assert len(read_ledger(task.chat_config)["calls"]) == 1
                assert model_server.calls == 1
            finally:
                model_server.release.set()
                work.cancel()
                await asyncio.gather(work, return_exceptions=True)

    asyncio.run(scenario())


def test_worker_cli_preflight_and_sigterm_with_real_dependencies_and_no_queued_task(
    tmp_path, model_server
):
    """空队列进程也验证真实 PG 预检、claim 身份和信号退出，不触发模型/检索。"""

    async def prepare():
        async with AsyncExitStack() as stack:
            return RealTask(stack)

    task = asyncio.run(prepare())
    chat_config = config_at(
        tmp_path, base_url=f"http://127.0.0.1:{model_server.server.server_port}/v1"
    )
    ledger_at(chat_config)
    chat_path = tmp_path / "chat.json"
    chat_value = json.loads(chat_config.model_dump_json())
    chat_value["api_key"] = "synthetic-key"
    task.local.private(chat_path, json.dumps(chat_value))
    worker_path = tmp_path / "worker.json"
    task.local.private(
        worker_path,
        json.dumps(
            {
                "tenant_id": task.tenant,
                "tasks_endpoint": CENTRAL,
                "knowledge_endpoint": "http://127.0.0.1:28084",
                "task_private_key": str(task.local.STATE / "task-worker-private.pem"),
                "knowledge_private_key": str(task.local.STATE / "runtime-knowledge-private.pem"),
                "chat_config": str(chat_path),
                "checkpoints": {"dsn": task.config.dsn.get_secret_value()},
            }
        ),
    )
    checked = subprocess.run(
        [sys.executable, "-m", "agentic_ai.cli", "check-qa-worker", "--config", str(worker_path)],
        capture_output=True,
        timeout=10,
    )
    assert checked.returncode == 0
    assert b"CONFIGURATION_VALID" in checked.stderr
    log_path = tmp_path / "worker.log"
    with log_path.open("wb") as output:
        process = subprocess.Popen(
            [sys.executable, "-m", "agentic_ai.cli", "qa-worker", "--config", str(worker_path)],
            stdout=output,
            stderr=subprocess.STDOUT,
        )
        try:
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                text = log_path.read_text()
                if '"state": "READY"' in text and "/task-worker/claim" in text:
                    break
                assert process.poll() is None
                time.sleep(0.05)
            else:
                raise AssertionError("worker 未在时限内通过真实预检并领取空队列")
            process.send_signal(signal.SIGTERM)
            assert process.wait(timeout=8) == 0
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=8)
    text = log_path.read_text()
    assert '"state": "STOPPING"' in text
    assert "synthetic-key" not in text and task.config.dsn.get_secret_value() not in text
    assert model_server.calls == 0 and read_ledger(chat_config)["calls"] == []
