"""S16C隔离进程：正式处理/检索/图执行，仅模型端口和依赖故障闸门为测试设施。"""

import argparse
import asyncio
import json
import socket
import sys
import time
from contextlib import asynccontextmanager
from pathlib import Path
from uuid import uuid4

import httpx
import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import Response
from langsmith import tracing_context
from test_research_full_joint import ResearchChat

from agentic_ai.agents.staged_research_pipeline import StagedResearchPipeline
from agentic_ai.api.processing import processing_router
from agentic_ai.api.retrieval import retrieval_router
from agentic_ai.processing.identity import ServiceVerifier
from agentic_ai.processing.pipeline import PreparationPipeline
from agentic_ai.qa.worker import execute_qa_task
from agentic_ai.rag.ports import EmbeddingResult
from agentic_ai.retrieval.client import RetrievalClient
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.deadline import RetrievalCapacity
from agentic_ai.runtime.processing_config import ProcessingConfig
from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.postgres import CheckpointConfig, PostgresCheckpoints

ROOT = Path(__file__).resolve().parents[2]
GRAPHS = ("knowledge-qa-v2", "knowledge-work-v4")


def event(directory, name, **values):
    """每个进程独占日志文件；只写时间/类型/合成任务编号，不写令牌和业务正文。"""
    with (directory / (name + ".jsonl")).open("a") as stream:
        stream.write(json.dumps({"at": time.time(), **values}) + "\n")


class Embedding:
    def __init__(self, directory, name):
        self.directory, self.name = directory, name

    async def embed(self, texts):
        event(self.directory, self.name, type="embedding", items=len(texts))
        delay = 40 if (self.directory / "model-timeout").exists() else 0.08
        await asyncio.sleep(delay)
        return EmbeddingResult(tuple((0.125,) * 1024 for _ in texts), "text-embedding-v4", 1024)


class Chat(ResearchChat):
    def __init__(self, directory, name):
        super().__init__()
        self.directory, self.name = directory, name

    async def stream(self, messages, operation):
        event(self.directory, self.name, type="chat", stage="qa")
        await asyncio.sleep(0.15)
        yield "资料提供授权候选检索信息。[1]"

    async def complete(self, messages, tools, operation):
        event(self.directory, self.name, type="chat", stage=operation.split(":")[0])
        await asyncio.sleep(0.15)
        return await super().complete(messages, tools, operation)


def runtime(config):
    directory = Path(config["directory"])
    embedding = Embedding(directory, "runtime-model")
    verifier = ServiceVerifier.from_file(ROOT / ".local/knowledge-ai-public.pem")

    @asynccontextmanager
    async def clients(owner):
        client = RetrievalClient.from_file(
            config["knowledgeEndpoint"],
            owner.tenant_id,
            owner.owner_id,
            ROOT / ".local/runtime-knowledge-private.pem",
        )
        try:
            yield client
        finally:
            await client.aclose()

    app = FastAPI()
    app.include_router(
        processing_router(
            PreparationPipeline(embedding),
            verifier,
            ProcessingConfig(
                public_key_path=ROOT / ".local/knowledge-ai-public.pem",
                max_concurrent=4,
                deadline_seconds=2,
            ),
        )
    )
    app.include_router(
        retrieval_router(
            RetrievalUseCase(embedding, RetrievalCapacity(2)),
            verifier,
            clients,
            maximum=2,
        )
    )

    @app.api_route(
        "/{path:path}", methods=["GET", "POST", "PUT", "DELETE"], include_in_schema=False
    )
    async def proxy(path: str, request: Request):
        # 只转发本轮应用指向的两个已登记本机索引，不停止共享组件或修改其配置。
        if path.startswith("collections/agentic_knowledge_text_v1_test"):
            backend = "qdrant"
        elif path.startswith("agentic-knowledge-text-v1-test"):
            backend = "elastic"
        else:
            return Response(status_code=404)
        if (directory / "index-offline").exists():
            event(directory, "index-proxy", type="rejected", backend=backend)
            return Response(status_code=503)
        headers = {
            k: v
            for k, v in request.headers.items()
            if k.lower() not in {"host", "content-length", "connection"}
        }
        async with httpx.AsyncClient(trust_env=False, timeout=8) as client:
            response = await client.request(
                request.method,
                config["indexes"][backend] + "/" + path,
                params=request.query_params,
                content=await request.body(),
                headers=headers,
            )
        return Response(
            response.content,
            status_code=response.status_code,
            media_type=response.headers.get("content-type"),
        )

    with socket.socket() as listener:
        listener.bind(("127.0.0.1", config["runtimePort"]))
        listener.listen(128)
        uvicorn.Server(uvicorn.Config(app, access_log=False, log_level="warning")).run(
            sockets=[listener]
        )


async def worker(config, tenant, name):
    """两个独立进程每进程仅一在途；受中央租约准入，不靠测试内存排队冒充任务持久化。"""
    sys.path.insert(0, str(ROOT / "deploy"))
    import checkpoint_local

    directory = Path(config["directory"])
    checkpoints = CheckpointConfig(dsn=checkpoint_local.dsn("test"), max_connections=2)
    central = CentralTaskClient.from_file(
        config["taskEndpoint"], tenant, name, ROOT / ".local/task-worker-private.pem"
    )
    candidates = RetrievalClient.from_file(
        config["knowledgeEndpoint"],
        tenant,
        "capacity-owner",
        ROOT / ".local/runtime-knowledge-private.pem",
    )
    retrieval = RetrievalUseCase(Embedding(directory, name), RetrievalCapacity(1))
    try:
        event(directory, name, type="ready")
        while not (directory / "workers-stop").exists():
            for graph in GRAPHS:
                lease = await central.claim(graph)
                if lease is None:
                    continue
                task_id = lease.task.task_id
                chat = Chat(directory, name)
                event(
                    directory,
                    name,
                    type="claimed",
                    taskId=task_id,
                    epoch=lease.epoch,
                    createdAt=lease.task.created_at.isoformat(),
                    graph=graph,
                )
                if graph == GRAPHS[1] and (directory / (tenant + "-pause")).exists():
                    # 保存中央确认过的真实PG快照，然后由父进程终止本进程。
                    async with PostgresCheckpoints.open(checkpoints) as store:
                        saver = FencedPostgresSaver(lease, central, store)
                        pipeline = StagedResearchPipeline(
                            lease, central, candidates, chat, retrieval
                        )
                        compiled = pipeline.build(saver, interrupt_before=["analyze"])
                        await compiled.ainvoke(lease.input, saver.config, durability="sync")
                        assert (await compiled.aget_state(saver.config)).values["plan"]
                        (directory / (tenant + "-paused.json")).write_text(
                            json.dumps(
                                {
                                    "taskId": task_id,
                                    "worker": name,
                                    "epoch": lease.epoch,
                                }
                            )
                        )
                        await asyncio.Event().wait()
                result = await execute_qa_task(
                    lease,
                    central,
                    checkpoints,
                    candidates,
                    chat,
                    tool_chat=chat,
                    retrieval=retrieval,
                    candidates=candidates,
                )
                event(
                    directory,
                    name,
                    type="finished",
                    taskId=task_id,
                    epoch=lease.epoch,
                    status=result.status.value,
                    error=result.error_code,
                )
            await asyncio.sleep(0.05)
    finally:
        await central.aclose()
        await candidates.aclose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--tenant")
    options = parser.parse_args()
    value = json.loads(options.manifest.read_text())
    with tracing_context(enabled=False):
        if options.tenant:
            asyncio.run(worker(value, options.tenant, "worker-" + uuid4().hex[:8]))
        else:
            runtime(value)
