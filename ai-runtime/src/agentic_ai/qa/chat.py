"""固定 LiteLLM 路由的有界 SSE 适配器，提供方错误不进入用户日志或图状态。"""

import asyncio
import codecs
import json
from collections.abc import AsyncGenerator, AsyncIterator, Sequence
from typing import Literal, Protocol

import httpx
from pydantic import Field

from agentic_ai.qa.chat_budget import ChatBudget
from agentic_ai.qa.chat_config import MAX_INPUT_BYTES, ChatConfig
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.models import MAX_ANSWER, MAX_RESPONSE
from agentic_ai.tasks.models import WireModel

MAX_EVENT_BYTES = 32 * 1024
MAX_MESSAGES = 3


class ChatMessage(WireModel):
    """资料和历史答复放入用户数据正文；不允许动态工具或 assistant 指令角色。"""

    role: Literal["system", "user"]
    content: str = Field(min_length=1, max_length=MAX_INPUT_BYTES)


class ChatPort(Protocol):
    """图只消费文本流，不知道提供方密钥、路由地址或预算文件。"""

    def stream(
        self, messages: Sequence[ChatMessage], operation_id: str
    ) -> AsyncGenerator[str, None]:
        """每次实际发送先持久预留；使用方应在取消/提前结束时关闭迭代器。"""
        ...


class ChatClient:
    """一在途、无重定向/回退/自动重试，关闭或失败不释放未知费用。"""

    def __init__(
        self,
        config: ChatConfig,
        budget: ChatBudget,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._config, self._budget = config, budget
        self._slot = asyncio.Semaphore(1)
        self._http = httpx.AsyncClient(
            base_url=config.base_url + "/",
            trust_env=False,
            follow_redirects=False,
            transport=transport,
            timeout=httpx.Timeout(config.deadline_seconds, pool=1, connect=3),
            limits=httpx.Limits(max_connections=1, max_keepalive_connections=1),
        )

    async def stream(
        self, messages: Sequence[ChatMessage], operation_id: str
    ) -> AsyncGenerator[str, None]:
        """完整响应期限包含所有分片；DONE 和正常 finish_reason 都确认后才结束成功。"""
        if not 1 <= len(messages) <= MAX_MESSAGES:
            raise QaError(QaCode.INPUT_REJECTED)
        try:
            body = json.dumps(
                {
                    "model": self._config.route,
                    "messages": [message.model_dump() for message in messages],
                    "stream": True,
                    "n": 1,
                    "max_tokens": self._config.max_tokens,
                },
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode("utf-8")
        except (UnicodeError, ValueError, TypeError):
            raise QaError(QaCode.INPUT_REJECTED) from None
        if len(body) > MAX_INPUT_BYTES:
            raise QaError(QaCode.INPUT_REJECTED)
        if self._slot.locked():
            raise QaError(QaCode.CAPACITY_EXCEEDED)
        async with self._slot:
            self._budget.reserve(operation_id, len(body))
            try:
                async with asyncio.timeout(self._config.deadline_seconds):
                    async with self._http.stream(
                        "POST",
                        "chat/completions",
                        content=body,
                        headers={
                            "Authorization": "Bearer " + self._config.api_key.get_secret_value(),
                            "Content-Type": "application/json",
                        },
                    ) as response:
                        if response.status_code != 200 or (
                            response.headers.get("content-type", "").split(";")[0].strip()
                            != "text/event-stream"
                        ):
                            raise QaError(QaCode.MODEL_UNAVAILABLE)
                        stopped = False
                        done = False
                        size = 0
                        async for event in self._events(response):
                            if event == "[DONE]":
                                done = True
                                break
                            value = json.loads(event)
                            if not isinstance(value, dict) or "error" in value:
                                raise QaError(QaCode.MODEL_UNAVAILABLE)
                            choices = value.get("choices")
                            if choices == [] and isinstance(value.get("usage"), dict):
                                continue
                            if not isinstance(choices, list) or len(choices) != 1:
                                raise QaError(QaCode.ANSWER_INVALID)
                            choice = choices[0]
                            if (
                                not isinstance(choice, dict)
                                or type(choice.get("index")) is not int
                                or choice["index"] != 0
                            ):
                                raise QaError(QaCode.ANSWER_INVALID)
                            delta = choice.get("delta")
                            if not isinstance(delta, dict) or any(
                                delta.get(field)
                                for field in (
                                    "tool_calls",
                                    "function_call",
                                    "reasoning_content",
                                    "reasoning",
                                    "refusal",
                                )
                            ):
                                raise QaError(QaCode.ANSWER_INVALID)
                            if delta.get("role") not in {None, "assistant"}:
                                raise QaError(QaCode.ANSWER_INVALID)
                            text = delta.get("content")
                            if text is not None:
                                if not isinstance(text, str) or stopped:
                                    raise QaError(QaCode.ANSWER_INVALID)
                                size += len(text)
                                if size > MAX_ANSWER or "\0" in text:
                                    raise QaError(QaCode.ANSWER_INVALID)
                                text.encode("utf-8")
                                if text:
                                    yield text
                            reason = choice.get("finish_reason")
                            if reason is not None:
                                if reason != "stop" or stopped:
                                    raise QaError(QaCode.ANSWER_INVALID)
                                stopped = True
                        if not done or not stopped or size == 0:
                            raise QaError(QaCode.ANSWER_INVALID)
            except (httpx.HTTPError, TimeoutError):
                raise QaError(QaCode.MODEL_UNAVAILABLE) from None
            except (ValueError, TypeError, UnicodeError):
                raise QaError(QaCode.ANSWER_INVALID) from None

    async def _events(self, response: httpx.Response) -> AsyncIterator[str]:
        """按实际字节约束正文和单事件；处理跨网络分片的 UTF-8 与 SSE 行。"""
        decoder = codecs.getincrementaldecoder("utf-8")("strict")
        buffer = ""
        data: list[str] = []
        event_bytes = 0
        received = 0
        async for part in response.aiter_bytes():
            received += len(part)
            if received > MAX_RESPONSE:
                raise QaError(QaCode.ANSWER_INVALID)
            buffer += decoder.decode(part)
            while "\n" in buffer:
                line, buffer = buffer.split("\n", 1)
                # 注释、event/id 等字段同样占用事件预算，不能靠忽略行绕过上限。
                event_bytes += len(line.encode("utf-8")) + 1
                if event_bytes > MAX_EVENT_BYTES:
                    raise QaError(QaCode.ANSWER_INVALID)
                line = line.removesuffix("\r")
                if not line:
                    if data:
                        yield "\n".join(data)
                    data = []
                    event_bytes = 0
                elif line.startswith("data:"):
                    payload = line[5:].removeprefix(" ")
                    data.append(payload)
            if event_bytes + len(buffer.encode("utf-8")) > MAX_EVENT_BYTES:
                raise QaError(QaCode.ANSWER_INVALID)
        buffer += decoder.decode(b"", final=True)
        if buffer.strip() or data:
            raise QaError(QaCode.ANSWER_INVALID)

    async def aclose(self) -> None:
        """worker 停止后及时归还连接；未确认调用仍保留预算预留。"""
        await self._http.aclose()
