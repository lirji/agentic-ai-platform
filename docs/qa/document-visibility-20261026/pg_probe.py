"""真实PG快照遇到真实Knowledge墓碑：QA/研究/助手均在模型之前拒绝恢复。"""

from __future__ import annotations

import argparse
import asyncio
from contextlib import AsyncExitStack
from uuid import uuid4

import httpx
from langsmith import tracing_context

from http_probe import KINDS, KNOWLEDGE, OWNER, TASKS, TENANT, load, local, save, task_smoke
import checkpoint_local
from agentic_ai.agents.pipeline import WorkPipeline
from agentic_ai.qa.knowledge import KnowledgeClient
from agentic_ai.qa.models import KnowledgeHit
from agentic_ai.qa.pipeline import QaPipeline
from agentic_ai.qa.worker import execute_qa_task
from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.models import TaskStatus
from agentic_ai.tasks.postgres import CheckpointConfig, PostgresCheckpoints


class NoModel:
    """任何模型入口都立即报错并计数，不能把本测试当作真实回答质量证明。"""

    def __init__(self):
        self.calls = 0

    async def complete(self, *_args, **_kwargs):
        self.calls += 1
        raise AssertionError("墓碑恢复不得调用模型")

    async def stream(self, *_args, **_kwargs):
        self.calls += 1
        raise AssertionError("墓碑恢复不得调用模型")
        yield ""  # 维持异步迭代端口，禁止产生任何模型正文。


class CurrentCitation:
    """检索阶段复用真实已授权块以免重复Embedding；重新授权始终使用真实K05。"""

    def __init__(self, client, hit, *, preparing):
        self.client, self.hit, self.preparing = client, hit, preparing
        self.searches = self.reads = 0

    async def search(self, _question, document_ids):
        assert self.preparing and document_ids == [self.hit.document_id]
        self.searches += 1
        return (await self.citation(self.hit),)

    async def citation(self, value):
        self.reads += 1
        return await self.client.citation(value)


async def run(phase, name):
    state = load()
    config = CheckpointConfig(dsn=checkpoint_local.dsn("test"))
    with tracing_context(enabled=False):
        async with AsyncExitStack() as stack:
            storage = await stack.enter_async_context(PostgresCheckpoints.open(config))
            citation = KnowledgeClient.from_file(KNOWLEDGE, TENANT, OWNER, local.STATE/"runtime-knowledge-private.pem")
            stack.push_async_callback(citation.aclose)
            async with httpx.AsyncClient(timeout=10, trust_env=False) as http:
                selected = state["documents"].items() if phase == "prepare" else [(name, state["documents"][name])]
                for document_name, doc in selected:
                    for task_name, (kind, graph) in KINDS.items():
                        ident = doc["pgTasks"][task_name]
                        worker = CentralTaskClient.from_file(TASKS, TENANT,
                            f"s10c-{phase}-{document_name}-{task_name}", local.STATE/"task-worker-private.pem")
                        stack.push_async_callback(worker.aclose)
                        model = NoModel()
                        knowledge = CurrentCitation(citation, KnowledgeHit.model_validate(doc["hit"]), preparing=phase == "prepare")
                        if phase == "prepare":
                            if task_name in doc.get("pgPaused", {}):
                                assert (await worker.inspect(ident)).checkpoint is not None
                                continue
                            # 输入UUID与任务号一起持久化，不能在同键重试时改变指纹。
                            if task_name not in doc.get("pgInputs", {}):
                                value = {"schemaVersion": "knowledge-work-input-v1", "kind": kind,
                                    "mode": "REPORT" if task_name == "research" else "EXPLAIN",
                                    "question": "撤下资料后的恢复必须重新授权", "documentIds": [doc["documentId"]]}
                                if task_name == "qa":
                                    value = {"schemaVersion": "knowledge-qa-input-v1", "conversationId": str(uuid4()),
                                        "turnId": str(uuid4()), "question": "撤下资料后的恢复必须重新授权",
                                        "documentIds": [doc["documentId"]], "contextTurnId": None,
                                        "contextTaskId": None, "contextQuestion": None}
                                doc.setdefault("pgInputs", {})[task_name] = value; save(state)
                            reply = await http.post(TASKS + "/internal/v1/tasks", headers={"X-Internal-Token":
                                task_smoke.service_token("submit", TENANT, OWNER), "Idempotency-Key": ident},
                                json={"taskId": ident, "kind": kind, "runtimeVersion": "langgraph-v1",
                                    "graphVersion": graph, "input": doc["pgInputs"][task_name]})
                            assert reply.status_code == 202
                            binding = await worker.claim(graph)
                            assert binding is not None and binding.task.task_id == ident
                            saver = FencedPostgresSaver(binding, worker, storage)
                            pipeline = (QaPipeline if task_name == "qa" else WorkPipeline)(binding, worker, knowledge, model)
                            await pipeline.build(saver, interrupt_before=["generate" if task_name == "qa" else "analyze"]).ainvoke(
                                binding.input, saver.config, durability="sync")
                            checkpoint = (await worker.inspect(ident)).checkpoint
                            assert checkpoint is not None and model.calls == 0 and knowledge.searches == 1
                            doc.setdefault("pgPaused", {})[task_name] = {"epoch": binding.epoch,
                                "checkpointSha256": checkpoint.sha256, "modelCalls": model.calls}
                        else:
                            observed = await worker.inspect(ident)
                            if observed.task.status.terminal:
                                assert observed.task.status == TaskStatus.FAILED and observed.task.error_code == "INPUT_REJECTED"
                                assert task_name in doc.get("pgRejected", {}), "已终止但缺失原检查记录，需核对日志"
                                continue
                            count = await asyncio.to_thread(local.mysql,
                                "UPDATE agentic_tasks.ai_task SET lease_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) "
                                f"WHERE task_id='{ident}' AND tenant_id='{TENANT}' AND owner_id='{OWNER}' AND status='RUNNING';SELECT ROW_COUNT();")
                            assert count.strip() == "1"
                            binding = await worker.claim(graph)
                            assert binding is not None and binding.task.task_id == ident and binding.checkpoint is not None
                            assert binding.epoch > doc["pgPaused"][task_name]["epoch"]
                            result = await execute_qa_task(binding, worker, config, knowledge, model, tool_chat=model)
                            assert result.status == TaskStatus.FAILED and result.error_code == "INPUT_REJECTED"
                            assert model.calls == 0 and knowledge.reads > 0 and knowledge.searches == 0
                            doc.setdefault("pgRejected", {})[task_name] = {"status": "PASS", "errorCode": result.error_code,
                                "modelCalls": model.calls, "realCitationChecks": knowledge.reads, "epoch": binding.epoch}
                        save(state)
    print(f"PG {phase} {name}: PASS")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(); parser.add_argument("phase", choices=["prepare", "resume"])
    parser.add_argument("--document", choices=["withdraw", "delete"], default="withdraw")
    args = parser.parse_args(); asyncio.run(run(args.phase, args.document))
