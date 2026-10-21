"""工具协议单独适配，保留原问答文本客户端的严格边界。"""

import asyncio
import json
import logging
import re
from collections.abc import Awaitable, Callable, Sequence
from typing import Any, Protocol

import httpx
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.utils.function_calling import convert_to_openai_tool
from pydantic import Field, PrivateAttr

from agentic_ai.agents.models import ALLOWED_TOOLS, MAX_MODEL_CALLS, MAX_TOOL_CALLS
from agentic_ai.qa.chat_budget import ChatBudget
from agentic_ai.qa.chat_config import MAX_INPUT_BYTES, ChatConfig
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.models import MAX_RESPONSE

MODEL_PROFILE = "agentic:read-only-v1"


class ToolChatPort(Protocol):
    """每次调用均为一次实际发送，预算/超时与未知响应不能由 Agent 绕过。"""

    async def complete(
        self, messages: list[dict[str, Any]], tools: list[dict[str, Any]], operation_id: str
    ) -> AIMessage:
        """返回受控文本或有界的允许工具调用，执行阶段串行处理。"""
        ...


def validate_reply(reply: AIMessage) -> AIMessage:
    """执行器前第二道工具白名单，测试替身和未来提供方也遵守相同边界。"""
    if not isinstance(reply.content, str) or len(reply.content) > 24000 or reply.invalid_tool_calls:
        raise QaError(QaCode.ANSWER_INVALID)
    if "\0" in reply.content or len(reply.tool_calls) > MAX_TOOL_CALLS:
        raise QaError(QaCode.ANSWER_INVALID)
    if len({call["id"] for call in reply.tool_calls}) != len(reply.tool_calls):
        raise QaError(QaCode.ANSWER_INVALID)
    for call in reply.tool_calls:
        name, args = call["name"], call["args"]
        if name not in ALLOWED_TOOLS or not re.fullmatch(
            r"[A-Za-z0-9._:-]{1,128}", call["id"] or ""
        ):
            raise QaError(QaCode.INPUT_REJECTED)
        if name == "search_knowledge":
            if (
                set(args) != {"query"}
                or not isinstance(args["query"], str)
                or not 1 <= len(args["query"]) <= 2000
                or not args["query"].strip()
            ):
                raise QaError(QaCode.INPUT_REJECTED)
        elif (
            set(args) != {"citation_id"}
            or not isinstance(args["citation_id"], str)
            or not re.fullmatch(r"[1-9]|10", args["citation_id"])
        ):
            raise QaError(QaCode.INPUT_REJECTED)
    return reply


class ToolChatClient:
    """非流式工具协议：限制总字节/时间，关闭重试和重定向，不保存原响应。"""

    def __init__(
        self,
        config: ChatConfig,
        budget: ChatBudget,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.config, self.budget = config, budget
        self.slot = asyncio.Semaphore(1)
        self.http = httpx.AsyncClient(
            base_url=config.base_url + "/",
            trust_env=False,
            follow_redirects=False,
            transport=transport,
            timeout=httpx.Timeout(config.deadline_seconds, pool=1, connect=3),
            limits=httpx.Limits(max_connections=1, max_keepalive_connections=1),
        )

    async def complete(
        self, messages: list[dict[str, Any]], tools: list[dict[str, Any]], operation_id: str
    ) -> AIMessage:
        """预算先持久预留；网络失败、无效响应或取消均不退款或重发。"""
        if (
            not 1 <= len(messages) <= 16
            or {tool["function"]["name"] for tool in tools} != ALLOWED_TOOLS
        ):
            raise QaError(QaCode.INPUT_REJECTED)
        body = json.dumps(
            {
                "model": self.config.route,
                "messages": messages,
                "tools": tools,
                "tool_choice": "auto",
                "parallel_tool_calls": False,
                "stream": False,
                "n": 1,
                "max_tokens": self.config.max_tokens,
            },
            ensure_ascii=False,
        ).encode()
        if len(body) > MAX_INPUT_BYTES:
            raise QaError(QaCode.INPUT_REJECTED)
        if self.slot.locked():
            raise QaError(QaCode.CAPACITY_EXCEEDED)
        async with self.slot:
            self.budget.reserve(operation_id, len(body))
            try:
                async with asyncio.timeout(self.config.deadline_seconds):
                    async with self.http.stream(
                        "POST",
                        "chat/completions",
                        content=body,
                        headers={
                            "Authorization": "Bearer " + self.config.api_key.get_secret_value(),
                            "Content-Type": "application/json",
                        },
                    ) as response:
                        if response.status_code != 200:
                            raise QaError(QaCode.MODEL_UNAVAILABLE)
                        raw = bytearray()
                        async for chunk in response.aiter_bytes():
                            raw.extend(chunk)
                            if len(raw) > MAX_RESPONSE:
                                raise QaError(QaCode.ANSWER_INVALID)
                payload = json.loads(raw)
                choices = payload["choices"]
                if len(choices) != 1 or choices[0]["index"] != 0 or "error" in payload:
                    raise ValueError
                message, reason = choices[0]["message"], choices[0]["finish_reason"]
                if message["role"] != "assistant" or any(
                    message.get(key)
                    for key in ("reasoning_content", "reasoning", "refusal", "function_call")
                ):
                    raise ValueError
                calls = message.get("tool_calls") or []
                logging.getLogger("agentic_ai.agents.chat").info(
                    json.dumps(
                        {
                            "component": "readonly-chat",
                            "state": "REPLY_RECEIVED",
                            "toolCount": len(calls),
                            "normalStop": reason in {"stop", "tool_calls"},
                            "textLength": len(message.get("content") or ""),
                        }
                    )
                )
                if reason != ("tool_calls" if calls else "stop"):
                    raise ValueError
                parsed = []
                for call in calls:
                    if call["type"] != "function":
                        raise ValueError
                    parsed.append(
                        {
                            "name": call["function"]["name"],
                            "args": json.loads(call["function"]["arguments"]),
                            "id": call["id"],
                            "type": "tool_call",
                        }
                    )
                return validate_reply(
                    AIMessage(content=message.get("content") or "", tool_calls=parsed)
                )
            except (httpx.HTTPError, TimeoutError):
                raise QaError(QaCode.MODEL_UNAVAILABLE) from None
            except (ValueError, KeyError, TypeError, UnicodeError, AttributeError):
                raise QaError(QaCode.ANSWER_INVALID) from None

    async def aclose(self) -> None:
        """退出关闭连接，已预留金额保留。"""
        await self.http.aclose()


class GuardedToolModel(BaseChatModel):
    """框架只获得受控端口；不能自行选择模型或发起后台摘要调用。"""

    model_name: str = MODEL_PROFILE
    port: Any = Field(exclude=True, repr=False)
    guard: Callable[[], Awaitable[None]] = Field(exclude=True, repr=False)
    operation_id: str
    _calls: int = PrivateAttr(default=0)

    @property
    def _llm_type(self) -> str:
        return "agentic-readonly"

    def bind_tools(self, tools: Sequence[Any], **kwargs: Any) -> Any:
        """检查最终可见工具集合，依赖升级注入新工具时直接停止。"""
        schemas = [convert_to_openai_tool(tool) for tool in tools]
        if {tool["function"]["name"] for tool in schemas} != ALLOWED_TOOLS:
            raise QaError(QaCode.CONFIG_INVALID)
        return self.bind(tools=schemas)

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: Any = None,
        **kwargs: Any,
    ) -> ChatResult:
        """同步/隐藏模型入口关闭，所有真实发送走同一异步预算路径。"""
        raise QaError(QaCode.CONFIG_INVALID)

    async def _agenerate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: Any = None,
        **kwargs: Any,
    ) -> ChatResult:
        """每轮先重验中央及证据，再执行最多三次模型调用。"""
        if self._calls >= MAX_MODEL_CALLS:
            raise QaError(QaCode.INPUT_REJECTED)
        await self.guard()
        encoded: list[dict[str, Any]] = []
        for message in messages:
            if not isinstance(message.content, str):
                raise QaError(QaCode.INPUT_REJECTED)
            if isinstance(message, SystemMessage):
                encoded.append({"role": "system", "content": message.content})
            elif isinstance(message, HumanMessage):
                encoded.append({"role": "user", "content": message.content})
            elif isinstance(message, ToolMessage):
                encoded.append(
                    {
                        "role": "tool",
                        "tool_call_id": message.tool_call_id,
                        "content": message.content,
                    }
                )
            elif isinstance(message, AIMessage):
                value: dict[str, Any] = {"role": "assistant", "content": message.content}
                if message.tool_calls:
                    value["tool_calls"] = [
                        {
                            "id": call["id"],
                            "type": "function",
                            "function": {
                                "name": call["name"],
                                "arguments": json.dumps(call["args"], ensure_ascii=False),
                            },
                        }
                        for call in message.tool_calls
                    ]
                encoded.append(value)
            else:
                raise QaError(QaCode.INPUT_REJECTED)
        self._calls += 1
        result = validate_reply(
            await self.port.complete(encoded, kwargs["tools"], f"{self.operation_id}:{self._calls}")
        )
        return ChatResult(generations=[ChatGeneration(message=result)])
