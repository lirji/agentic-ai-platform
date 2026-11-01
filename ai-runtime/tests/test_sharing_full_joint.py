"""真实共享ACL/Tasks/MySQL/PG；仅聊天和向量端口确定化，不调用付费模型。"""

import asyncio
import os
from contextlib import AsyncExitStack
from uuid import uuid4

import httpx
import pytest
from langsmith import tracing_context
from test_evaluation_full_joint import QUERY, STRATEGY, Joint
from test_evaluation_full_joint import joint as joint  # noqa: F401
from test_evaluation_pipeline import EvaluationChat
from test_qa_pipeline import Chat, question
from test_rerank import Scorer
from test_research_full_joint import ResearchChat, execute, submit

from agentic_ai.agents.staged_research_pipeline import StagedResearchPipeline
from agentic_ai.qa.worker import execute_qa_task
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.deadline import RetrievalCapacity
from agentic_ai.retrieval.errors import RetrievalError
from agentic_ai.retrieval.models import RetrievalInput, RetrievalStrategy
from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.models import TaskStatus
from agentic_ai.tasks.postgres import PostgresCheckpoints

pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_SHARING_JOINT") != "1", reason="由真实共享Java夹具显式启动"
)


async def permission(run, operation):
    """每次命令先读真实版本；读者凭据不能代替资料所有者。"""
    for document in run.documents:
        base = f"/api/v1/knowledge/sharing/documents/{document}"
        current = await run.request("GET", base, owner=run.fixture["trueOwner"])
        await run.request(
            "POST",
            base + "/commands",
            key=str(uuid4()),
            owner=run.fixture["trueOwner"],
            body={
                "operation": operation,
                "recipientId": run.owner,
                "expectedRevision": current["revision"],
            },
        )


async def qa(run, chat):
    """通过原中央受限QA入口受理，真实worker读取共享候选和最终引用。"""
    import task_smoke

    task_id = str(uuid4())
    run.state["tasks"].append(task_id)
    async with httpx.AsyncClient(trust_env=False, timeout=10) as http:
        response = await http.post(
            run.fixture["taskEndpoint"] + "/internal/v1/tasks",
            headers={
                "X-Internal-Token": task_smoke.service_token("submit", run.tenant, run.owner),
                "Idempotency-Key": task_id,
            },
            json={
                "taskId": task_id,
                "kind": "KNOWLEDGE_QA",
                "runtimeVersion": "langgraph-v1",
                "graphVersion": "knowledge-qa-v2",
                "input": question(
                    schemaVersion="knowledge-qa-input-v2",
                    question=QUERY,
                    documentIds=run.documents,
                    retrievalStrategy=STRATEGY,
                ),
            },
        )
    assert response.status_code == 202, response.text
    central, lease = await run.client("knowledge-qa-v2", task_id)
    candidates = await run.candidates()
    result = await execute_qa_task(
        lease,
        central,
        run.checkpoints,
        candidates,
        chat,
        retrieval=run.use_case,
        candidates=candidates,
    )
    return task_id, result


async def evaluation(run):
    """形成可被撤权抑制的历史评测报告，定义/交接/任务/结果均来自真实服务。"""
    gold = {key: run.fixture["documents"][0][key] for key in ("documentId", "version", "chunkId")}
    question_id = str(uuid4())
    dataset = await run.request(
        "POST",
        "/api/v1/knowledge/evaluations/datasets",
        key=str(uuid4()),
        status=201,
        body={
            "name": "共享撤权机制",
            "description": "协议模型，不表示真实质量",
            "purpose": "MECHANISM",
            "questions": [
                {
                    "questionId": question_id,
                    "question": QUERY,
                    "expectation": "ANSWERABLE",
                    "critical": True,
                    "rubric": "当前合法引用",
                    "referenceAnswer": "资料原文",
                    "documentIds": run.documents,
                    "evidence": [gold | {"relevance": 2}],
                }
            ],
        },
    )
    accepted = await run.request(
        "POST",
        "/api/v2/knowledge/evaluations/runs",
        key=str(uuid4()),
        status=202,
        body={
            "datasetId": dataset["datasetId"],
            "revision": 1,
            "topK": 5,
            "retrievalStrategy": STRATEGY,
        },
    )
    run_id = accepted["summary"]["runId"]
    for _ in range(100):
        view = await run.request("GET", f"/api/v1/knowledge/evaluations/runs/{run_id}")
        if view["task"] and view["task"]["status"] == "QUEUED":
            break
        await asyncio.sleep(0.1)
    else:
        pytest.fail("共享评测交接未排队")
    task_id = view["task"]["taskId"]
    run.state["tasks"].append(task_id)
    central, lease = await run.client("knowledge-evaluation-v2", task_id)
    candidates = await run.candidates()
    result = await execute_qa_task(
        lease,
        central,
        run.checkpoints,
        candidates,
        EvaluationChat(),
        evaluations=await run.reports(lease),
        profile=run.profile,
        retrieval=run.use_case,
        candidates=candidates,
    )
    assert result.status == TaskStatus.SUCCEEDED, result.error_code
    detail = await run.request("GET", f"/api/v1/knowledge/evaluations/runs/{run_id}")
    assert detail["report"]["complete"] and detail["report"]["succeeded"] == 1
    return run_id, question_id


def test_shared_history_inflight_generation_and_pg_restore_recheck_current_acl(joint):
    async def scenario():
        with tracing_context(enabled=False):
            async with AsyncExitStack() as stack:
                run = Joint(joint, stack)
                qa_id, completed = await qa(run, Chat())
                assert completed.status == TaskStatus.SUCCEEDED, completed.error_code
                report_id = await submit(run, staged=True)
                await execute(run, report_id, ResearchChat(), staged=True)
                evaluation_id, question_id = await evaluation(run)
                pending = await submit(run, staged=True)
                central, lease = await run.client("knowledge-work-v4", pending)
                candidates = await run.candidates()
                store = await stack.enter_async_context(PostgresCheckpoints.open(run.checkpoints))
                saver = FencedPostgresSaver(lease, central, store)
                chat = ResearchChat()
                graph = StagedResearchPipeline(
                    lease, central, candidates, chat, run.use_case
                ).build(saver, interrupt_before=["analyze"])
                await graph.ainvoke(lease.input, saver.config, durability="sync")
                assert chat.calls == 1
                for task_id in (qa_id, report_id):
                    visible = await run.request("GET", f"/api/v1/tasks/{task_id}", task=True)
                    assert visible["result"] is not None
                await permission(run, "REVOKE_READ")
                # 历史成功状态不能使正文继续可见；资料列表也不残留共享项。
                for task_id in (qa_id, report_id):
                    await run.request("GET", f"/api/v1/tasks/{task_id}", task=True, status=404)
                    await run.request(
                        "GET", f"/api/v1/tasks/{task_id}/events", task=True, status=404
                    )
                hidden = await run.request(
                    "GET", f"/api/v1/knowledge/evaluations/runs/{evaluation_id}"
                )
                assert hidden["report"] is None and hidden["bindingState"] != "AVAILABLE"
                await run.request(
                    "GET",
                    f"/api/v1/knowledge/evaluations/runs/{evaluation_id}/questions/{question_id}",
                    status=409,
                )
                assert not (await run.request("GET", "/api/v1/knowledge/readable-documents"))[
                    "items"
                ]
                # 真实PG接管不能把已确认计划或旧证据直接送给下一次模型调用。
                await run.expire(pending)
                second, resumed = await run.client("knowledge-work-v4", pending)
                failed = await execute_qa_task(
                    resumed,
                    second,
                    run.checkpoints,
                    candidates,
                    chat,
                    tool_chat=chat,
                    retrieval=run.use_case,
                    candidates=candidates,
                )
                assert failed.status == TaskStatus.FAILED and failed.result is None
                assert chat.calls == 1
                await permission(run, "GRANT_READ")

                class RevokeDuringGeneration(Chat):
                    async def stream(self, messages, operation):
                        async for part in super().stream(messages, operation):
                            await permission(run, "REVOKE_READ")
                            yield part

                during = RevokeDuringGeneration()
                _, failed = await qa(run, during)
                assert failed.status == TaskStatus.FAILED and failed.result is None
                assert during.calls == 1
                await permission(run, "GRANT_READ")

                class RevokeAfterCandidates:
                    async def candidates(self, query, vector, timeout_ms):
                        value = await candidates.candidates(query, vector, timeout_ms)
                        await permission(run, "REVOKE_READ")
                        return value

                    async def resolve(self, frozen, document_ids, timeout_ms):
                        return await candidates.resolve(frozen, document_ids, timeout_ms)

                scorer = Scorer()
                rerank = RetrievalUseCase(run.embedding, RetrievalCapacity(1), scorer)
                with pytest.raises(RetrievalError):
                    await rerank.execute(
                        RetrievalInput(
                            query=QUERY,
                            document_ids=tuple(run.documents),
                            top_k=2,
                            strategy=RetrievalStrategy.RERANK,
                        ),
                        RevokeAfterCandidates(),
                    )
                assert not scorer.calls, "召回后撤权的正文不得外发精排"
                run.state.update(
                    status="PASS",
                    qaTaskId=qa_id,
                    reportTaskId=report_id,
                    evaluationRunId=evaluation_id,
                    restoredTaskId=pending,
                    scope="real-shared-acl-pg-history-and-generation",
                    paidCalls=0,
                )
                run.save()

    asyncio.run(scenario())
