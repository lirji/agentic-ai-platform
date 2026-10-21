"""聊天替身协议与真实文件锁/预算测试；不会连接任何提供方或正式账本。"""

import asyncio
import fcntl
import json
import os
from contextlib import aclosing
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import httpx
import pytest
from pydantic import ValidationError

from agentic_ai.qa.chat import MAX_EVENT_BYTES, ChatClient, ChatMessage
from agentic_ai.qa.chat_budget import ChatBudget
from agentic_ai.qa.chat_config import CHAT_BUDGET_SCOPE, MAX_INPUT_BYTES, ChatConfig
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.models import MAX_ANSWER, MAX_RESPONSE

MESSAGES = [ChatMessage(role="user", content="合成问题")]


def config_at(tmp_path, **updates):
    return ChatConfig(
        **{
            "base_url": "http://127.0.0.1:29123/v1",
            "api_key": "synthetic-key",
            "approved_model": "synthetic-test-model",
            "budget_file": tmp_path / "budget.json",
            "input_price_cny_per_million": "1",
            "output_price_cny_per_million": "1",
            "pricing_checked_at": datetime.now(UTC).date(),
            "pricing_reference": "synthetic-test-price",
            **updates,
        }
    )


def ledger_at(config, **updates):
    ledger = {
        "version": 1,
        "scope": CHAT_BUDGET_SCOPE,
        "currency": "CNY",
        "limitCny": "1",
        "reservedCny": "0",
        "calls": [],
        **updates,
    }
    config.budget_file.write_text(json.dumps(ledger))
    config.budget_file.chmod(0o600)
    return ledger


def read_ledger(config):
    return json.loads(config.budget_file.read_text())


def event(content=None, *, reason=None, **delta):
    return (
        "data: "
        + json.dumps(
            {
                "choices": [
                    {
                        "index": 0,
                        "delta": {"content": content, **delta},
                        "finish_reason": reason,
                    }
                ]
            },
            ensure_ascii=False,
        )
        + "\r\n\r\n"
    ).encode()


SUCCESS = event("合成答案[1]") + event(reason="stop") + b"data: [DONE]\n\n"


class BytesStream(httpx.AsyncByteStream):
    def __init__(self, data, *, width=1):
        self.data, self.width = data, width
        self.closed = False

    async def __aiter__(self):
        for start in range(0, len(self.data), self.width):
            yield self.data[start : start + self.width]

    async def aclose(self):
        self.closed = True


async def consume(client, operation="test:1", messages=MESSAGES):
    async with aclosing(client.stream(messages, operation)) as stream:
        return "".join([part async for part in stream])


@pytest.mark.parametrize(
    "updates",
    [
        {"base_url": "http://example.org/v1"},
        {"base_url": "http://127.0.0.1:0/v1"},
        {"base_url": "https://user:secret@example.org/v1"},
        {"base_url": "https://example.org/v1?override=x"},
        {"base_url": "https://example.org/wrong"},
        {"base_url": "http://127.0.0.1:99999/v1"},
        {"base_url": "https://example.org/v1\n"},
        {"api_key": "unsafe\n"},
        {"max_tokens": 1025},
        {"max_tokens": True},
        {"deadline_seconds": 26},
        {"budget_file": "relative.json"},
        {"budget_limit_cny": "1.01"},
        {"input_price_cny_per_million": "NaN"},
        {"output_price_cny_per_million": "0"},
        {"route": "unapproved-route"},
        {"pricing_checked_at": (datetime.now(UTC) - timedelta(days=8)).date()},
    ],
)
def test_chat_configuration_cannot_expand_call_boundaries(tmp_path, updates):
    with pytest.raises(ValidationError):
        config_at(tmp_path, **updates)


def test_config_reading_protects_secrets_and_rejects_symlinks(tmp_path):
    config = config_at(tmp_path)
    path = tmp_path / "config.json"
    data = json.loads(config.model_dump_json())
    data["api_key"] = "synthetic-key"
    path.write_text(json.dumps(data))
    path.chmod(0o600)
    assert ChatConfig.from_file(path).api_key.get_secret_value() == "synthetic-key"
    assert "synthetic-key" not in repr(config)
    alias = tmp_path / "alias.json"
    alias.symlink_to(path)
    for bad in (alias, tmp_path / "missing.json"):
        with pytest.raises(QaError, match=QaCode.CONFIG_INVALID):
            ChatConfig.from_file(bad)
    path.chmod(0o644)
    with pytest.raises(QaError, match=QaCode.CONFIG_INVALID):
        ChatConfig.from_file(path)


def test_reservation_is_durable_before_wire_and_cumulative_after_restart(tmp_path, monkeypatch):
    config = config_at(tmp_path)
    ledger_at(config)
    flushed = []
    real_fsync = os.fsync

    def fsync(descriptor):
        real_fsync(descriptor)
        flushed.append(True)

    monkeypatch.setattr(os, "fsync", fsync)

    async def scenario():
        async def handle(request):
            data = read_ledger(config)
            assert flushed and len(data["calls"]) == 1
            assert Decimal(data["reservedCny"]) == Decimal("0.01")
            assert request.url.path == "/v1/chat/completions"
            assert request.headers["authorization"] == "Bearer synthetic-key"
            body = json.loads(request.content)
            assert body["model"] == "chat-default" and body["max_tokens"] == 1024
            assert body["stream"] and body["n"] == 1 and "tools" not in body
            response_stream = BytesStream(SUCCESS)
            return httpx.Response(
                200, headers={"content-type": "text/event-stream"}, stream=response_stream
            )

        client = ChatClient(config, ChatBudget(config), transport=httpx.MockTransport(handle))
        try:
            assert await consume(client) == "合成答案[1]"
        finally:
            await client.aclose()

    asyncio.run(scenario())
    # 恢复/重复实际发送不能复用已花费的 reservation。
    ChatBudget(config).reserve("test:1", 10)
    state = read_ledger(config)
    assert len(state["calls"]) == 2 and Decimal(state["reservedCny"]) == Decimal(".02")
    assert state["calls"][0]["id"] != state["calls"][1]["id"]
    assert "合成问题" not in config.budget_file.read_text()


@pytest.mark.parametrize(
    "updates",
    [
        {"version": True},
        {"scope": "another-round"},
        {"currency": "USD"},
        {"limitCny": "2"},
        {"limitCny": "0.009"},
        {"reservedCny": "0.01"},
        {"reservedCny": "NaN"},
        {"calls": [{"reservedCny": 1, "status": "RESERVED"}]},
        {"calls": [{"reservedCny": "-1", "status": "RESERVED"}]},
        {"calls": [{"reservedCny": ".01", "status": "REFUNDED"}]},
    ],
)
def test_corrupt_or_exhausted_budget_never_sends(tmp_path, updates):
    config = config_at(tmp_path)
    ledger_at(config, **updates)
    before = config.budget_file.read_bytes()
    with pytest.raises(QaError, match=QaCode.BUDGET_EXCEEDED):
        ChatBudget(config).reserve("test:1", 10)
    assert config.budget_file.read_bytes() == before


def test_missing_insecure_duplicate_and_locked_budget_fail_closed(tmp_path):
    config = config_at(tmp_path)
    budget = ChatBudget(config)
    with pytest.raises(QaError, match=QaCode.BUDGET_EXCEEDED):
        budget.reserve("test:1", 1)
    assert not config.budget_file.exists()
    ledger_at(config)
    with config.budget_file.open("r+") as held:
        fcntl.flock(held, fcntl.LOCK_EX | fcntl.LOCK_NB)
        with pytest.raises(QaError, match=QaCode.CAPACITY_EXCEEDED):
            budget.reserve("test:1", 1)
    config.budget_file.chmod(0o644)
    with pytest.raises(QaError, match=QaCode.BUDGET_EXCEEDED):
        budget.reserve("test:1", 1)
    ledger_at(config)
    raw = config.budget_file.read_text().replace(
        '"reservedCny": "0"', '"reservedCny":"0","reservedCny":"0"'
    )
    config.budget_file.write_text(raw)
    with pytest.raises(QaError, match=QaCode.BUDGET_EXCEEDED):
        budget.reserve("test:1", 1)
    other = tmp_path / "real-ledger.json"
    config.budget_file.rename(other)
    config.budget_file.symlink_to(other)
    with pytest.raises(QaError, match=QaCode.BUDGET_EXCEEDED):
        budget.reserve("test:1", 1)


def test_budget_cap_disk_failure_and_runtime_stale_price(tmp_path, monkeypatch):
    config = config_at(tmp_path)
    ledger_at(config, limitCny=".02")
    ChatBudget(config).reserve("test:1", 1)
    ChatBudget(config).reserve("test:2", 1)
    with pytest.raises(QaError, match=QaCode.BUDGET_EXCEEDED):
        ChatBudget(config).reserve("test:3", 1)
    assert Decimal(read_ledger(config)["reservedCny"]) == Decimal(".02")
    ledger_at(config)

    def unavailable(_):
        raise OSError("synthetic-disk-error")

    monkeypatch.setattr(os, "fsync", unavailable)
    with pytest.raises(QaError, match=QaCode.BUDGET_EXCEEDED):
        ChatBudget(config).reserve("test:4", 1)
    stale = config.model_copy(
        update={"pricing_checked_at": (datetime.now(UTC) - timedelta(days=8)).date()}
    )
    with pytest.raises(QaError, match=QaCode.CONFIG_INVALID):
        ChatBudget(stale).reserve("test:5", 1)


@pytest.mark.parametrize(
    ("body", "code"),
    [
        (event("answer") + b"data: [DONE]\n\n", QaCode.ANSWER_INVALID),
        (event("answer", reason="stop"), QaCode.ANSWER_INVALID),
        (event("answer", reason="length") + b"data: [DONE]\n\n", QaCode.ANSWER_INVALID),
        (event("answer", tool_calls=[{}]), QaCode.ANSWER_INVALID),
        (event("answer", reasoning_content="private"), QaCode.ANSWER_INVALID),
        (event("answer", role="system"), QaCode.ANSWER_INVALID),
        (event("answer", reason="stop") + event("later"), QaCode.ANSWER_INVALID),
        (b'data: {"error":"private-provider-message"}\n\n', QaCode.MODEL_UNAVAILABLE),
        (b"data: {invalid}\n\n", QaCode.ANSWER_INVALID),
        (b"data: \xff\n\n", QaCode.ANSWER_INVALID),
        (b":" + b"x" * MAX_EVENT_BYTES + b"\n\n", QaCode.ANSWER_INVALID),
        (b"x" * (MAX_EVENT_BYTES + 1), QaCode.ANSWER_INVALID),
        (b":x\n\n" * (MAX_RESPONSE // 4 + 1), QaCode.ANSWER_INVALID),
        (event("x" * (MAX_ANSWER + 1)), QaCode.ANSWER_INVALID),
    ],
)
def test_invalid_stream_preserves_reservation_and_closes_connection(tmp_path, body, code):
    config = config_at(tmp_path)
    ledger_at(config)

    async def scenario():
        stream = BytesStream(body, width=len(body))
        client = ChatClient(
            config,
            ChatBudget(config),
            transport=httpx.MockTransport(
                lambda _: httpx.Response(
                    200, headers={"content-type": "text/event-stream"}, stream=stream
                )
            ),
        )
        try:
            with pytest.raises(QaError, match=code):
                await consume(client)
            assert stream.closed
            assert Decimal(read_ledger(config)["reservedCny"]) == Decimal(".01")
        finally:
            await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize("status", [307, 401, 429, 503])
def test_failed_http_is_not_retried_or_refunded(tmp_path, status):
    config = config_at(tmp_path)
    ledger_at(config)

    async def scenario():
        requests = []

        async def handle(request):
            requests.append(request)
            return httpx.Response(status, headers={"location": "https://untrusted.invalid/"})

        client = ChatClient(config, ChatBudget(config), transport=httpx.MockTransport(handle))
        try:
            with pytest.raises(QaError, match=QaCode.MODEL_UNAVAILABLE):
                await consume(client)
            assert len(requests) == 1 and len(read_ledger(config)["calls"]) == 1
        finally:
            await client.aclose()

    asyncio.run(scenario())


def test_capacity_cancellation_and_total_timeout_release_slot_without_refund(tmp_path):
    config = config_at(tmp_path, deadline_seconds=1)
    ledger_at(config)

    async def scenario():
        started = asyncio.Event()

        async def hang(_):
            started.set()
            await asyncio.Event().wait()

        client = ChatClient(config, ChatBudget(config), transport=httpx.MockTransport(hang))
        first = asyncio.create_task(consume(client))
        await started.wait()
        try:
            with pytest.raises(QaError, match=QaCode.CAPACITY_EXCEEDED):
                await consume(client, "test:2")
            assert len(read_ledger(config)["calls"]) == 1
            first.cancel()
            with pytest.raises(asyncio.CancelledError):
                await first
            with pytest.raises(QaError, match=QaCode.MODEL_UNAVAILABLE):
                await consume(client, "test:3")
            assert len(read_ledger(config)["calls"]) == 2
        finally:
            first.cancel()
            await client.aclose()

    asyncio.run(scenario())


def test_oversized_prompt_and_invalid_operation_do_not_reserve(tmp_path):
    config = config_at(tmp_path)
    ledger_at(config)

    async def scenario():
        client = ChatClient(config, ChatBudget(config))
        try:
            with pytest.raises(QaError, match=QaCode.INPUT_REJECTED):
                await consume(
                    client, messages=[ChatMessage(role="user", content="汉" * MAX_INPUT_BYTES)]
                )
            with pytest.raises(QaError, match=QaCode.INPUT_REJECTED):
                await consume(client, "../invalid")
            assert len(read_ledger(config)["calls"]) == 0
        finally:
            await client.aclose()

    asyncio.run(scenario())
