"""Java公共门面的受限检索入口；同一进程只装配一个共享用例。"""

import asyncio
from collections.abc import Callable
from contextlib import AbstractAsyncContextManager
from typing import Literal
from uuid import uuid4

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from pydantic import Field, ValidationError
from starlette.requests import ClientDisconnect

from agentic_ai.api.processing import EMBEDDING_HTTP_STATUS, Admission, until_disconnect
from agentic_ai.processing.errors import ProcessingCode, ProcessingError
from agentic_ai.processing.identity import INTERNAL_HEADER, ServiceIdentity, ServiceVerifier
from agentic_ai.rag.errors import EmbeddingError
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.models import (
    QUERY_SECONDS,
    RetrievalInput,
    RetrievalStrategy,
    RetrievalWire,
)
from agentic_ai.retrieval.rerank_client import RerankPort
from agentic_ai.retrieval.retriever import CandidatePort

RETRIEVE_SCOPE = "knowledge:retrieve"
MAX_SEARCH_BODY = 32 * 1024
HTTP_QUERY_CAPACITY = 1
ClientFactory = Callable[[ServiceIdentity], AbstractAsyncContextManager[CandidatePort]]

# 安全对外提示集中于HTTP边界；任务图仍只持久化原稳定失败码。
ERRORS = {
    RetrievalCode.INPUT_INVALID: (400, "检索参数无效。"),
    RetrievalCode.CONFIG_INVALID: (503, "检索服务尚未就绪。"),
    RetrievalCode.CAPACITY_EXCEEDED: (429, "检索服务繁忙，请稍后重试。"),
    RetrievalCode.DEADLINE_EXCEEDED: (504, "检索超过时限，请稍后重试。"),
    RetrievalCode.AUTH_REJECTED: (403, "当前身份无法执行检索。"),
    RetrievalCode.KNOWLEDGE_UNAVAILABLE: (503, "知识检索服务暂不可用。"),
    RetrievalCode.EVIDENCE_REJECTED: (409, "资料已变化，请重新检索。"),
    RetrievalCode.RERANK_DISABLED: (503, "精排策略尚未启用。"),
    RetrievalCode.RERANK_BUDGET_REJECTED: (503, "精排策略暂不可用，请检查授权额度。"),
    RetrievalCode.RERANK_PROTOCOL_INVALID: (502, "精排结果未通过校验。"),
    RetrievalCode.RERANK_UNAVAILABLE: (503, "精排服务暂不可用。"),
}


class SearchCommand(RetrievalInput, RetrievalWire):
    """HTTP必须显式指定策略，不能继承组合用例的默认值。"""

    schema_version: Literal["knowledge-search-input-v2"]
    strategy: RetrievalStrategy = Field(...)
    timeout_ms: int = Field(ge=1, le=QUERY_SECONDS * 1000)

    def query_input(self) -> RetrievalInput:
        """身份来自签名而非正文，内部传输字段不能进入业务请求。"""
        return RetrievalInput(
            query=self.query,
            document_ids=self.document_ids,
            top_k=self.top_k,
            strategy=self.strategy,
        )


class StrategyAvailability(RetrievalWire):
    """仅描述当前配置/精排预算预检，不公开账号或成本金额。"""

    strategy: RetrievalStrategy
    available: bool
    reason_code: RetrievalCode | None


class RetrievalCapabilities(RetrievalWire):
    """固定目录不允许用户动态指定模型或策略参数。"""

    schema_version: Literal["knowledge-retrieval-capabilities-v1"] = (
        "knowledge-retrieval-capabilities-v1"
    )
    strategies: tuple[StrategyAvailability, StrategyAvailability]


def retrieval_router(
    use_case: RetrievalUseCase,
    verifier: ServiceVerifier,
    clients: ClientFactory,
    reranker: RerankPort | None = None,
) -> APIRouter:
    """入口和计算均立即拒绝过载；候选客户端只在当前owner请求生命周期存在。"""
    router = APIRouter()
    admission = Admission(HTTP_QUERY_CAPACITY)

    def identity(request: Request) -> ServiceIdentity:
        headers = request.headers.getlist(INTERNAL_HEADER)
        if len(headers) != 1:
            raise ProcessingError(ProcessingCode.AUTH_REQUIRED)
        return verifier.verify(headers[0], RETRIEVE_SCOPE)

    async def execute(request: Request, *, search: bool) -> JSONResponse:
        trace = str(uuid4())
        code: str
        message: str
        status: int
        try:
            owner = identity(request)
            async with admission.admit(), asyncio.timeout(QUERY_SECONDS):
                started = asyncio.get_running_loop().time()
                deadline = started + QUERY_SECONDS
                if search:
                    if (
                        request.headers.get("content-type", "").split(";")[0].strip()
                        != "application/json"
                    ):
                        raise RetrievalError(RetrievalCode.INPUT_INVALID)
                    body = bytearray()
                    async for chunk in request.stream():
                        if len(body) + len(chunk) > MAX_SEARCH_BODY:
                            raise ProcessingError(ProcessingCode.INPUT_TOO_LARGE)
                        body.extend(chunk)
                    command = SearchCommand.from_wire(bytes(body))
                    deadline = min(deadline, started + command.timeout_ms / 1000)
                    async with clients(owner) as knowledge:
                        result = await until_disconnect(
                            request,
                            use_case.execute(
                                command.query_input(), knowledge, root_deadline=deadline
                            ),
                        )
                    content = result.public().model_dump(mode="json", by_alias=True)
                else:
                    reason: RetrievalCode | None = RetrievalCode.RERANK_DISABLED
                    if reranker is not None:
                        try:
                            await reranker.check_available()
                            reason = None
                        except RetrievalError as failure:
                            reason = failure.code
                    capabilities = RetrievalCapabilities(
                        strategies=(
                            StrategyAvailability(
                                strategy=RetrievalStrategy.RRF, available=True, reason_code=None
                            ),
                            StrategyAvailability(
                                strategy=RetrievalStrategy.RERANK,
                                available=reason is None,
                                reason_code=reason,
                            ),
                        )
                    )
                    content = capabilities.model_dump(mode="json", by_alias=True)
                return JSONResponse(content, headers={"X-Request-Id": trace})
        except RetrievalError as failure:
            code = failure.code.value
            status, message = ERRORS[failure.code]
        except ProcessingError as failure:
            code, status, message = failure.code, failure.status, failure.message
        except EmbeddingError as failure:
            code, status, message = (
                failure.code,
                EMBEDDING_HTTP_STATUS[failure.code],
                failure.message,
            )
        except (ValidationError, ValueError, UnicodeError, ClientDisconnect):
            code = RetrievalCode.INPUT_INVALID.value
            status, message = ERRORS[RetrievalCode.INPUT_INVALID]
        except TimeoutError:
            code = RetrievalCode.DEADLINE_EXCEEDED.value
            status, message = ERRORS[RetrievalCode.DEADLINE_EXCEEDED]
        except Exception:
            code = RetrievalCode.KNOWLEDGE_UNAVAILABLE.value
            status, message = ERRORS[RetrievalCode.KNOWLEDGE_UNAVAILABLE]
        return JSONResponse(
            {"code": code, "message": message, "traceId": trace},
            status_code=status,
            headers={"X-Request-Id": trace},
        )

    @router.post("/internal/v2/knowledge/search")
    async def search(request: Request) -> JSONResponse:
        """公开投影由唯一用例生成，内部贡献排名永不直接序列化给门面。"""
        return await execute(request, search=True)

    @router.get("/internal/v2/knowledge/retrieval-capabilities")
    async def capabilities(request: Request) -> JSONResponse:
        """无模型调用、无预算预留，身份不合格仍先拒绝。"""
        return await execute(request, search=False)

    return router
