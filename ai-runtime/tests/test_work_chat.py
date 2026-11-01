"""工具 HTTP 协议和累计账本；仅使用受控本机 transport。"""

import asyncio
import json
from decimal import Decimal

import httpx
import pytest
from test_qa_chat import config_at, ledger_at, read_ledger

from agentic_ai.agents.chat import ToolChatClient
from agentic_ai.qa.chat_budget import ChatBudget
from agentic_ai.qa.errors import QaError

TOOLS = [
    {"type": "function", "function": {"name": name, "parameters": {"type": "object"}}}
    for name in ("search_knowledge", "read_evidence")
]
MESSAGE = [{"role": "user", "content": "合成任务"}]


def reply(**overrides):
    return {
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": "合成结果"},
                "finish_reason": "stop",
                **overrides,
            }
        ]
    }


def test_reserves_before_wire_and_restart_does_not_reset(tmp_path):
    async def scenario():
        config = config_at(tmp_path)
        ledger_at(config)
        calls = []

        def respond(request):
            calls.append(request)
            assert len(read_ledger(config)["calls"]) == len(calls)
            body = json.loads(request.content)
            assert not body["stream"] and not body["parallel_tool_calls"]
            return httpx.Response(200, json=reply())

        for _ in range(2):
            client = ToolChatClient(
                config, ChatBudget(config), transport=httpx.MockTransport(respond)
            )
            await client.complete(MESSAGE, TOOLS, "same-op")
            await client.aclose()
        assert len(calls) == 2
        assert Decimal(read_ledger(config)["reservedCny"]) > 0

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "response",
    [
        reply(finish_reason="length"),
        reply(message={"role": "assistant", "reasoning_content": "secret"}),
        reply(
            finish_reason="tool_calls",
            message={
                "role": "assistant",
                "tool_calls": [
                    {
                        "id": "evil",
                        "type": "function",
                        "function": {"name": "execute", "arguments": "{}"},
                    }
                ],
            },
        ),
    ],
)
def test_malformed_or_prohibited_response_retains_reservation(tmp_path, response):
    async def scenario():
        config = config_at(tmp_path)
        ledger_at(config)
        client = ToolChatClient(
            config,
            ChatBudget(config),
            transport=httpx.MockTransport(lambda request: httpx.Response(200, json=response)),
        )
        with pytest.raises(QaError):
            await client.complete(MESSAGE, TOOLS, "invalid-result")
        assert len(read_ledger(config)["calls"]) == 1
        await client.aclose()

    asyncio.run(scenario())


def test_unknown_response_is_not_retried_and_budget_exhaustion_prevents_wire(tmp_path):
    async def scenario():
        config = config_at(tmp_path)
        ledger_at(config, limitCny="0.01")
        count = 0

        def timeout(request):
            nonlocal count
            count += 1
            raise httpx.ReadTimeout("synthetic-only")

        client = ToolChatClient(config, ChatBudget(config), transport=httpx.MockTransport(timeout))
        for _ in range(2):
            with pytest.raises(QaError):
                await client.complete(MESSAGE, TOOLS, "unknown")
        assert count == 1 and len(read_ledger(config)["calls"]) == 1
        await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize("unsolicited_tool", [False, True])
def test_plan_only_wire_omits_tools_and_rejects_unsolicited_calls(tmp_path, unsolicited_tool):
    async def scenario():
        config = config_at(tmp_path)
        ledger_at(config)
        calls = []

        def respond(request):
            calls.append(request)
            body = json.loads(request.content)
            assert not {"tools", "tool_choice", "parallel_tool_calls"} & body.keys()
            response = reply()
            if unsolicited_tool:
                response = reply(
                    finish_reason="tool_calls",
                    message={
                        "role": "assistant",
                        "tool_calls": [
                            {
                                "id": "read",
                                "type": "function",
                                "function": {
                                    "name": "read_evidence",
                                    "arguments": '{"citation_id":"1"}',
                                },
                            }
                        ],
                    },
                )
            return httpx.Response(200, json=response)

        client = ToolChatClient(config, ChatBudget(config), transport=httpx.MockTransport(respond))
        try:
            if unsolicited_tool:
                with pytest.raises(QaError):
                    await client.complete(MESSAGE, [], "plan-only")
            else:
                assert (await client.complete(MESSAGE, [], "plan-only")).content == "合成结果"
            assert len(calls) == len(read_ledger(config)["calls"]) == 1
        finally:
            await client.aclose()

    asyncio.run(scenario())
