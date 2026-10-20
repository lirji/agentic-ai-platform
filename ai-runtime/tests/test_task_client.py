"""中央协议边界测试；仅生成临时签名密钥，网络由测试 transport 控制。"""

import asyncio
import json
import re
from pathlib import Path
from uuid import uuid4

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

from agentic_ai.tasks.client import MAX_RESPONSE_BYTES, CentralTaskClient
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import TaskStatus


@pytest.fixture(scope="module")
def worker_key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def client(worker_key, handler):
    return CentralTaskClient(
        "http://127.0.0.1:28087",
        "test-tenant",
        "test-worker",
        worker_key,
        transport=httpx.MockTransport(handler),
    )


@pytest.mark.parametrize(
    "endpoint",
    [
        "http://remote.example.invalid",
        "https://user:secret@example.invalid",
        "https://example.invalid/path",
        "https://example.invalid?query=secret",
        "https://example.invalid#fragment",
        "http://[broken",
        "https://example.invalid:invalid",
        "http://127.0.0.1:0",
    ],
)
def test_endpoint_validation_is_fail_closed_and_secret_free(worker_key, endpoint):
    with pytest.raises(TaskRuntimeError) as rejected:
        CentralTaskClient(endpoint, "tenant", "worker", worker_key)
    assert rejected.value.code == TaskRuntimeCode.CONFIG_INVALID
    assert str(rejected.value) == TaskRuntimeCode.CONFIG_INVALID.value


def test_claim_uses_short_lived_tenant_bound_worker_identity(worker_key):
    async def scenario():
        def handle(request):
            payload = jwt.decode(
                request.headers["X-Internal-Token"],
                worker_key.public_key(),
                algorithms=["RS256"],
                audience="agentic-ai-tasks",
                issuer="urn:agentic-ai:runtime-worker",
            )
            assert payload["tenant_id"] == "test-tenant"
            assert payload["sub"] == "test-worker" and payload["scope"] == "task:worker"
            assert payload["exp"] - payload["iat"] == 60 and payload["nbf"] == payload["iat"]
            assert json.loads(request.content) == {
                "runtimeVersion": "langgraph-v1",
                "graphVersion": "knowledge-qa-v1",
            }
            return httpx.Response(200, json={"task": None})

        api = client(worker_key, handle)
        try:
            assert await api.claim() is None
        finally:
            await api.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    ("status", "body", "expected"),
    [
        (409, b'{"code":"TASK_LEASE_LOST"}', TaskRuntimeCode.LEASE_LOST),
        (409, b'{"code":"TASK_CONFLICT"}', TaskRuntimeCode.CONFLICT),
        (409, b"[]", TaskRuntimeCode.PROTOCOL_INVALID),
        (200, b'{"task":null,"unexpected":1}', TaskRuntimeCode.PROTOCOL_INVALID),
        (200, b'{"task":', TaskRuntimeCode.PROTOCOL_INVALID),
        (200, b"x" * (MAX_RESPONSE_BYTES + 1), TaskRuntimeCode.PROTOCOL_INVALID),
        (500, b'{"private":"do-not-expose"}', TaskRuntimeCode.UNAVAILABLE),
        (401, b'{"private":"do-not-expose"}', TaskRuntimeCode.PROTOCOL_INVALID),
    ],
)
def test_response_limits_and_errors_do_not_expose_payload(worker_key, status, body, expected):
    async def scenario():
        api = client(worker_key, lambda _: httpx.Response(status, content=body))
        try:
            with pytest.raises(TaskRuntimeError) as rejected:
                await api.claim()
            assert rejected.value.code == expected
            assert str(rejected.value) == expected.value
        finally:
            await api.aclose()

    asyncio.run(scenario())


def test_http_capacity_and_cancellation_release_all_slots(worker_key):
    async def scenario():
        release = asyncio.Event()
        entered = asyncio.Event()
        count = 0

        async def handle(_request):
            nonlocal count
            count += 1
            if count == 4:
                entered.set()
            await release.wait()
            return httpx.Response(200, json={"task": None})

        api = client(worker_key, handle)
        running = [asyncio.create_task(api.claim()) for _ in range(4)]
        try:
            await asyncio.wait_for(entered.wait(), 1)
            with pytest.raises(TaskRuntimeError) as rejected:
                await api.claim()
            assert rejected.value.code == TaskRuntimeCode.CAPACITY_EXCEEDED
            for task in running:
                task.cancel()
            results = await asyncio.gather(*running, return_exceptions=True)
            assert all(isinstance(item, asyncio.CancelledError) for item in results)
            release.set()
            assert await api.claim() is None
        finally:
            for task in running:
                task.cancel()
            await asyncio.gather(*running, return_exceptions=True)
            await api.aclose()

    asyncio.run(scenario())


def test_invalid_task_id_cannot_change_internal_request_path(worker_key):
    async def scenario():
        def must_not_send(_request):
            raise AssertionError("无效任务 ID 不能形成网络请求")

        api = client(worker_key, must_not_send)
        try:
            for task_id in ("../claim", str(uuid4()).upper(), "secret?token=value"):
                with pytest.raises(TaskRuntimeError) as rejected:
                    await api.inspect(task_id)
                assert rejected.value.code == TaskRuntimeCode.PROTOCOL_INVALID
        finally:
            await api.aclose()

    asyncio.run(scenario())


def test_missing_or_public_private_key_file_is_rejected(tmp_path: Path):
    path = tmp_path / "key.pem"
    for exists in (False, True):
        if exists:
            path.write_text("private-canary")
            path.chmod(0o644)
        with pytest.raises(TaskRuntimeError) as rejected:
            CentralTaskClient.from_file("http://127.0.0.1:28087", "tenant", "worker", path)
        assert rejected.value.code == TaskRuntimeCode.CONFIG_INVALID
        assert "canary" not in str(rejected.value)


def test_task_status_wire_codes_match_central_java_contract():
    """防止跨语言消费者独自添加/改名状态，中央服务是状态机权威。"""
    root = Path(__file__).resolve().parents[2]
    source = (
        root / "async-task-service/src/main/java/com/lrj/agentic/task/domain/TaskStatus.java"
    ).read_text()
    central_codes = dict(re.findall(r'([A-Z_]+)\("([A-Z_]+)"\)', source))
    assert central_codes
    assert {state.name: state.value for state in TaskStatus} == central_codes
