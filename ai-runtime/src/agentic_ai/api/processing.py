"""有界内部 HTTP 适配，不暴露底层框架状态、原文异常或未经认证的模型入口。"""

import asyncio
from collections.abc import AsyncIterator, Coroutine
from contextlib import asynccontextmanager
from uuid import uuid4

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from starlette.requests import ClientDisconnect

from agentic_ai.processing.errors import ProcessingCode, ProcessingError
from agentic_ai.processing.identity import (
    INTERNAL_HEADER,
    PREPARE_SCOPE,
    QUERY_SCOPE,
    ServiceVerifier,
)
from agentic_ai.processing.models import MAX_BODY_BYTES, PrepareRequest, QueryRequest
from agentic_ai.processing.pipeline import PreparationPipeline
from agentic_ai.rag.errors import EmbeddingError, EmbeddingErrorCode
from agentic_ai.runtime.processing_config import ProcessingConfig

EMBEDDING_HTTP_STATUS = {
    EmbeddingErrorCode.CONFIGURATION_INVALID: 503,
    EmbeddingErrorCode.INPUT_INVALID: 400,
    EmbeddingErrorCode.UPSTREAM_REJECTED: 502,
    EmbeddingErrorCode.UPSTREAM_UNAVAILABLE: 503,
    EmbeddingErrorCode.RESPONSE_INVALID: 502,
}


class Admission:
    """同一事件循环内无等待领取名额，避免慢模型制造无界排队。"""

    def __init__(self, maximum: int) -> None:
        self._maximum = maximum
        self._active = 0

    @asynccontextmanager
    async def admit(self) -> AsyncIterator[None]:
        """递增前不 await；失败和取消都在 finally 归还名额。"""
        if self._active >= self._maximum:
            raise ProcessingError(ProcessingCode.CAPACITY_EXCEEDED)
        self._active += 1
        try:
            yield
        finally:
            self._active -= 1


async def limited_body(request: Request) -> bytes:
    """对实际流量计数，不依赖可伪造或缺失的 Content-Length。"""
    if request.headers.get("content-type", "").split(";")[0].strip() != "application/json":
        raise ProcessingError(ProcessingCode.INPUT_INVALID)
    body = bytearray()
    async for chunk in request.stream():
        if len(body) + len(chunk) > MAX_BODY_BYTES:
            raise ProcessingError(ProcessingCode.INPUT_TOO_LARGE)
        body.extend(chunk)
    return bytes(body)


async def until_disconnect[T](request: Request, operation: Coroutine[None, None, T]) -> T:
    """HTTP 断开不会自动取消普通 ASGI handler，显式停止尚未完成的模型等待。"""

    async def watch() -> None:
        # 正文已全部读取，此时独占 receive 等待断开事件，不与正文解析竞争。
        while True:
            event = await request.receive()
            if event["type"] == "http.disconnect":
                raise ClientDisconnect()

    work = asyncio.create_task(operation)
    disconnected = asyncio.create_task(watch())
    try:
        done, _ = await asyncio.wait((work, disconnected), return_when=asyncio.FIRST_COMPLETED)
        if disconnected in done:
            await disconnected
        return await work
    finally:
        work.cancel()
        disconnected.cancel()
        await asyncio.gather(work, disconnected, return_exceptions=True)


def processing_router(
    pipeline: PreparationPipeline, verifier: ServiceVerifier, config: ProcessingConfig
) -> APIRouter:
    """闭包只保存服务依赖；请求身份和文本始终是调用局部变量。"""
    router = APIRouter(prefix="/internal/v1/knowledge")
    admission = Admission(config.max_concurrent)

    async def execute(request: Request, *, prepare: bool) -> JSONResponse:
        """认证、容量、读入和计算在明确边界中完成，错误不携带底层上下文。"""
        trace_id = str(uuid4())
        code: str
        message: str
        status: int
        try:
            identity = verifier.verify(
                request.headers.get(INTERNAL_HEADER), PREPARE_SCOPE if prepare else QUERY_SCOPE
            )
            async with admission.admit(), asyncio.timeout(config.deadline_seconds):
                body = await limited_body(request)
                if prepare:
                    command = PrepareRequest.model_validate_json(body, by_name=False)
                    identity.require_owner(command)
                    result = await until_disconnect(request, pipeline.prepare(command))
                    content = result.model_dump(mode="json", by_alias=True)
                else:
                    query = QueryRequest.model_validate_json(body, by_name=False)
                    identity.require_owner(query)
                    response = await until_disconnect(request, pipeline.query(query.query))
                    content = response.model_dump(mode="json", by_alias=True)
                return JSONResponse(content, headers={"X-Request-Id": trace_id})
        except ProcessingError as failure:
            code, message, status = failure.code, failure.message, failure.status
        except EmbeddingError as failure:
            code, message, status = (
                failure.code,
                failure.message,
                EMBEDDING_HTTP_STATUS[failure.code],
            )
        except (ValidationError, ValueError, UnicodeError, ClientDisconnect):
            error = ProcessingError(ProcessingCode.INPUT_INVALID)
            code, message, status = error.code, error.message, error.status
        except TimeoutError:
            error = ProcessingError(ProcessingCode.DEADLINE_EXCEEDED)
            code, message, status = error.code, error.message, error.status
        except Exception:
            # 框架错误不得被当作成功；避免框架异常附带原文进入访问日志。
            error = ProcessingError(ProcessingCode.INTERNAL)
            code, message, status = error.code, error.message, error.status
        return JSONResponse(
            {"code": code, "message": message, "traceId": trace_id},
            status_code=status,
            headers={"X-Request-Id": trace_id},
        )

    @router.post("/prepare")
    async def prepare_document(request: Request) -> JSONResponse:
        """只返回待 Java 核验的准备结果，不改变文档发布状态。"""
        return await execute(request, prepare=True)

    @router.post("/embed-query")
    async def embed_query(request: Request) -> JSONResponse:
        """只有被委托的 Knowledge 查询能够使用模型额度。"""
        return await execute(request, prepare=False)

    return router
