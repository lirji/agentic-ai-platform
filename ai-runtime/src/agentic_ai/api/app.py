"""首片只提供进程存活探针，不将未接入的业务或依赖表示为就绪。"""

from typing import Literal

from fastapi import FastAPI
from pydantic import BaseModel


class LivenessReply(BaseModel):
    """存活检查的固定响应，避免泄漏配置并明确探针范围。"""

    status: Literal["alive"] = "alive"
    service: Literal["agentic-ai-runtime"] = "agentic-ai-runtime"


def create_app() -> FastAPI:
    """使用工厂隔离实例生命周期，为后续真实依赖装配保留单一入口。"""
    app = FastAPI(title="Agentic AI Runtime", version="0.1.0", docs_url=None, redoc_url=None)

    @app.get("/health/live", response_model=LivenessReply)
    async def liveness() -> LivenessReply:
        """只证明 HTTP 进程可响应，不能用作模型或知识链路就绪证据。"""
        return LivenessReply()

    return app
