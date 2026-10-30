"""仅供真实跨语言集成测试：正式HTTP/用例/客户端，唯一替身是无费用向量端口。"""

import argparse
import asyncio
import json
import socket
from contextlib import asynccontextmanager
from pathlib import Path

import uvicorn
from fastapi import FastAPI

from agentic_ai.api.retrieval import retrieval_router
from agentic_ai.processing.identity import ServiceVerifier
from agentic_ai.rag.ports import EmbeddingResult
from agentic_ai.retrieval.client import RetrievalClient
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.deadline import RetrievalCapacity


def serve(directory: Path, public_key: Path, private_key: Path) -> None:
    """故障闸门只存在于测试目录，不给产品HTTP增加控制接口。"""
    counts: dict[str, int] = {}

    def record(event: str) -> None:
        counts[event] = counts.get(event, 0) + 1
        pending = directory / "counts.pending"
        pending.write_text(json.dumps(counts))
        pending.replace(directory / "counts.json")

    async def hold(stage: str) -> None:
        # Java进程通过测试文件释放闸门，不能用当前Python事件循环内的Event通信。
        while (directory / (stage + ".hold")).exists():  # noqa: ASYNC110
            await asyncio.sleep(0.01)

    class ProtocolEmbedding:
        async def embed(self, texts):
            record("embedding")
            try:
                await hold("embedding")
                return EmbeddingResult(((0.125,) * 1024,), "text-embedding-v4", 1024)
            except asyncio.CancelledError:
                record("cancelled")
                raise

    class ObservedClient(RetrievalClient):
        async def candidates(self, request, vector, timeout_ms):
            record("candidates")
            return await super().candidates(request, vector, timeout_ms)

        async def resolve(self, frozen, document_ids, timeout_ms):
            record("resolve")
            await hold("resolve")
            return await super().resolve(frozen, document_ids, timeout_ms)

    @asynccontextmanager
    async def clients(owner):
        client = ObservedClient.from_file(
            (directory / "knowledge-endpoint").read_text().strip(),
            owner.tenant_id,
            owner.owner_id,
            private_key,
        )
        record("opened")
        try:
            yield client
        finally:
            await client.aclose()
            record("closed")

    app = FastAPI()
    app.include_router(
        retrieval_router(
            RetrievalUseCase(ProtocolEmbedding(), RetrievalCapacity(1)),
            ServiceVerifier.from_file(public_key),
            clients,
        )
    )
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(128)
        config = uvicorn.Config(app, access_log=False, log_level="warning")

        class Server(uvicorn.Server):
            async def startup(self, sockets=None):
                await super().startup(sockets)
                (directory / "port").write_text(str(listener.getsockname()[1]))

        Server(config).run(sockets=[listener])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("public_key", type=Path)
    parser.add_argument("private_key", type=Path)
    options = parser.parse_args()
    serve(options.directory, options.public_key, options.private_key)
