"""显式启用内部处理接口；存活检查始终不代表模型或知识发布已就绪。"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Literal

from fastapi import FastAPI
from pydantic import BaseModel

from agentic_ai.adapters.openai_embedding import OpenAIEmbeddingAdapter
from agentic_ai.processing.errors import ProcessingCode, ProcessingError
from agentic_ai.processing.models import EMBEDDING_DIMENSIONS, EMBEDDING_MODEL
from agentic_ai.retrieval.rerank_client import RerankClient
from agentic_ai.runtime.embedding_budget import EmbeddingBudget
from agentic_ai.runtime.embedding_config import EmbeddingConfig
from agentic_ai.runtime.processing_config import ProcessingConfig
from agentic_ai.runtime.retrieval_config import RetrievalHttpConfig


class LivenessReply(BaseModel):
    """存活检查的固定响应，避免泄漏配置并明确探针范围。"""

    status: Literal["alive"] = "alive"
    service: Literal["agentic-ai-runtime"] = "agentic-ai-runtime"


def create_app() -> FastAPI:
    """使用工厂隔离实例生命周期，为后续真实依赖装配保留单一入口。"""
    config = ProcessingConfig.from_environment()
    retrieval = RetrievalHttpConfig.from_environment()
    client: OpenAIEmbeddingAdapter | None = None
    reranker: RerankClient | None = None

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        """服务退出释放模型连接；取消由调用链继续传播。"""
        try:
            yield
        finally:
            if reranker is not None:
                await reranker.aclose()
            if client is not None:
                await client.aclose()

    app = FastAPI(
        title="Agentic AI Runtime",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    if config is not None or retrieval is not None:
        embedding = EmbeddingConfig.from_environment()
        if embedding.model != EMBEDDING_MODEL or embedding.dimensions != EMBEDDING_DIMENSIONS:
            raise ProcessingError(ProcessingCode.CONFIGURATION_INVALID)
        budget = EmbeddingBudget.from_environment()
        if retrieval is not None and budget is None:
            # 当前检索迁移复用已授权累计账本，缺配置不能成为无预算的新付费入口。
            raise ProcessingError(ProcessingCode.CONFIGURATION_INVALID)
        client = OpenAIEmbeddingAdapter(embedding, budget=budget)
    if config is not None:
        # 延迟载入使关闭处理功能的 S0 探针无需初始化框架或模型资源。
        from agentic_ai.api.processing import processing_router
        from agentic_ai.processing.identity import ServiceVerifier
        from agentic_ai.processing.pipeline import PreparationPipeline

        verifier = ServiceVerifier.from_file(config.public_key_path)
        assert client is not None
        app.include_router(processing_router(PreparationPipeline(client), verifier, config))

    if retrieval is not None:
        from agentic_ai.api.retrieval import retrieval_router
        from agentic_ai.processing.identity import ServiceIdentity, ServiceVerifier
        from agentic_ai.qa.knowledge import KnowledgeClient
        from agentic_ai.retrieval.client import RetrievalClient
        from agentic_ai.retrieval.consumer import RetrievalUseCase
        from agentic_ai.retrieval.deadline import RetrievalCapacity
        from agentic_ai.retrieval.rerank_config import RerankConfig
        from agentic_ai.retrieval.retriever import CandidatePort

        assert client is not None
        verifier = ServiceVerifier.from_file(retrieval.public_key_path)
        key = KnowledgeClient.load_private_key(retrieval.knowledge_private_key)
        endpoint = retrieval.knowledge_endpoint
        if retrieval.rerank_config is not None:
            reranker = RerankClient(RerankConfig.from_file(retrieval.rerank_config))

        @asynccontextmanager
        async def candidates(identity: ServiceIdentity) -> AsyncIterator[CandidatePort]:
            # 身份固定于请求局部客户端，不能让一个owner复用上个owner的请求态。
            scoped = RetrievalClient(endpoint, identity.tenant_id, identity.owner_id, key)
            try:
                yield scoped
            finally:
                await scoped.aclose()

        use_case = RetrievalUseCase(client, RetrievalCapacity(1), reranker)
        app.include_router(retrieval_router(use_case, verifier, candidates, reranker))

    @app.get("/health/live", response_model=LivenessReply)
    async def liveness() -> LivenessReply:
        """只证明 HTTP 进程可响应，不能用作模型或知识链路就绪证据。"""
        return LivenessReply()

    return app
