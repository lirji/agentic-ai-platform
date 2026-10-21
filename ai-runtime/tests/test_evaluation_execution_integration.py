"""真实 Gateway/Knowledge/MySQL/Tasks/PG 评测闭环；检索返回固定已发布片段，聊天为确定性替身。"""

import asyncio
import json
import os
import sys
from contextlib import AsyncExitStack, asynccontextmanager
from pathlib import Path
from uuid import uuid4

import httpx
import pytest
from langsmith import tracing_context
from test_evaluation_pipeline import PROFILE, EvaluationChat

from agentic_ai.evaluation.client import EvaluationClient
from agentic_ai.evaluation.models import EVALUATION_GRAPH
from agentic_ai.evaluation.pipeline import EvaluationPipeline
from agentic_ai.qa.knowledge import KnowledgeClient
from agentic_ai.qa.models import KnowledgeHit
from agentic_ai.qa.worker import execute_qa_task
from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import TaskStatus
from agentic_ai.tasks.postgres import CheckpointConfig, PostgresCheckpoints

pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_EVALUATION_IT") != "1", reason="需显式启用本机 E02 真实协议与恢复验收"
)
ROOT = Path(__file__).resolve().parents[2]
BASE = "http://127.0.0.1:28080/api/v1/knowledge"


class PublishedFixture:
    """只替换召回以避免 Embedding 费用，所有引用读取仍经过真实 K05 身份与发布权限。"""

    def __init__(self, hit, client):
        self.hit, self.client = hit, client
        self.searches = 0

    async def search(self, question, documents):
        assert question and documents == [self.hit.document_id]
        self.searches += 1
        return (self.hit,)

    async def citation(self, value):
        return await self.client.citation(value)


class RealEvaluation:
    """每次验收编号落在忽略文件；失败后保留原任务，不清库或盲目重发未知模型操作。"""

    def __init__(self, stack, record=None):
        sys.path.insert(0, str(ROOT / "deploy"))
        import chat_local
        import checkpoint_local
        import local

        assert not chat_local.alive("qa-worker"), "确定性评测验收前须正常停止本机模型 worker"
        self.local, self.stack = local, stack
        self.key = str(uuid4())
        self.tenant, self.owner = "local-dev", "workbench-user"
        self.record = record or ROOT / ".local" / f"s8c-evaluation-it-{self.key}.json"
        self.state = (
            json.loads(self.record.read_text())
            if record
            else {
                "key": self.key,
                "scope": "deterministic-protocol-test",
                "status": "PREPARING",
            }
        )
        self.save()
        self.checkpoints = CheckpointConfig(dsn=checkpoint_local.dsn("test"))

    async def attach(self):
        """浏览器控制后恢复同一验收运行，仅装载已保存身份，不新建或重放命令。"""
        self.run_id, self.task_id = self.state["runId"], self.state["taskId"]
        gold = self.state["definition"]["questions"][0]["evidence"][0]
        hit = await self.request(
            "GET",
            f"/documents/{gold['documentId']}/versions/{gold['version']}/chunks/{gold['chunkId']}",
        )
        self.hit = KnowledgeHit.model_validate(hit)
        self.bind_knowledge()

    def save(self):
        self.local.private(self.record, json.dumps(self.state, ensure_ascii=False, indent=2))

    async def request(self, method, path, *, body=None, key=None, status=200):
        headers = {
            "Authorization": "Bearer "
            + self.local.token(self.tenant, self.owner, "knowledge:read knowledge:write")
        }
        if key:
            headers["Idempotency-Key"] = key
        async with httpx.AsyncClient(trust_env=False, timeout=10) as client:
            reply = await client.request(method, BASE + path, headers=headers, json=body)
        assert reply.status_code == status, (reply.status_code, reply.json().get("code"))
        return reply.json()

    async def initialize(self):
        source_id = os.environ["AGENTIC_EVALUATION_DATASET"]
        source = await self.request("GET", f"/evaluations/datasets/{source_id}")
        assert source["bindingState"] == "AVAILABLE"
        gold = next(q["evidence"][0] for q in source["definition"]["questions"] if q["evidence"])
        hit = await self.request(
            "GET",
            f"/documents/{gold['documentId']}/versions/{gold['version']}/chunks/{gold['chunkId']}",
        )
        self.hit = KnowledgeHit.model_validate(hit)
        questions = [
            {
                "questionId": str(uuid4()),
                "question": "资料何时可以检索？",
                "expectation": "ANSWERABLE",
                "critical": True,
                "rubric": "应有真实已发布引用",
                "referenceAnswer": "两路索引确认后发布",
                "documentIds": [gold["documentId"]],
                "evidence": [gold],
            },
            {
                "questionId": str(uuid4()),
                "question": "项目资料能证明公司餐补金额吗？",
                "expectation": "NO_ANSWER",
                "critical": False,
                "rubric": "没有业务事实，不编造金额",
                "referenceAnswer": "",
                "documentIds": [gold["documentId"]],
                "evidence": [],
            },
        ]
        definition = {
            "name": "确定性恢复验收 · " + self.key[:8],
            "description": "S8C 协议夹具，非模型质量基线。",
            "purpose": "MECHANISM",
            "questions": questions,
        }
        created = await self.request(
            "POST", "/evaluations/datasets", body=definition, key=self.key, status=201
        )
        self.state.update(datasetId=created["datasetId"], definition=definition)
        self.save()
        view = await self.request(
            "POST",
            "/evaluations/runs",
            body={"datasetId": created["datasetId"], "revision": 1, "topK": 5},
            key=self.key,
            status=202,
        )
        self.run_id = view["summary"]["runId"]
        self.state.update(runId=self.run_id, status="ACCEPTED")
        self.save()
        view = await self.wait_status("QUEUED")
        self.task_id = view["task"]["taskId"]
        self.state.update(taskId=self.task_id)
        self.save()
        self.bind_knowledge()

    def bind_knowledge(self):
        """引用始终经真实权限与发布版本校验。"""
        knowledge = KnowledgeClient.from_file(
            "http://127.0.0.1:28084",
            self.tenant,
            self.owner,
            ROOT / ".local/runtime-knowledge-private.pem",
        )
        self.stack.push_async_callback(knowledge.aclose)
        self.knowledge = PublishedFixture(self.hit, knowledge)

    async def wait_status(self, status):
        for _ in range(80):
            view = await self.request("GET", f"/evaluations/runs/{self.run_id}")
            if view["task"] is not None and view["task"]["status"] == status:
                return view
            assert view["summary"]["dispatchState"] != "FAILED", view.get("errorCode")
            await asyncio.sleep(0.2)
        pytest.fail("中央状态未在期限内达到期望；原任务编号已保存")

    async def claim(self):
        central = CentralTaskClient.from_file(
            "http://127.0.0.1:28087",
            self.tenant,
            "s8c-" + str(uuid4()),
            ROOT / ".local/task-worker-private.pem",
        )
        self.stack.push_async_callback(central.aclose)
        binding = await central.claim(EVALUATION_GRAPH)
        assert binding is not None and binding.task.task_id == self.task_id, (
            "不能执行不属于本夹具的任务"
        )
        reports = EvaluationClient.from_file(
            "http://127.0.0.1:28084", binding, ROOT / ".local/runtime-evaluation-private.pem"
        )
        self.stack.push_async_callback(reports.aclose)
        return central, binding, reports

    async def finish(self, central, binding, reports, chat):
        result = await execute_qa_task(
            binding,
            central,
            self.checkpoints,
            self.knowledge,
            chat,
            evaluations=reports,
            profile=PROFILE,
        )
        self.state.update(status=result.status.value, epoch=binding.epoch)
        self.save()
        return result

    async def expire(self):
        # UUID 来自本夹具已保存的正式受理结果；SQL 仅注入本次租约到期，不改输入/报告。
        result = await asyncio.to_thread(
            self.local.mysql,
            "UPDATE agentic_tasks.ai_task "
            "SET lease_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) "
            f"WHERE task_id='{self.task_id}' AND tenant_id='local-dev' "
            "AND status='RUNNING'; SELECT ROW_COUNT();",
        )
        assert result.strip() == "1"


@asynccontextmanager
async def real_evaluation():
    with tracing_context(enabled=False):
        async with AsyncExitStack() as stack:
            task = RealEvaluation(stack)
            await task.initialize()
            yield task


def test_real_report_failure_resume_reuses_committed_success_and_confirms_partial_report():
    async def scenario():
        async with real_evaluation() as task:
            first, binding, reports = await task.claim()
            chat = EvaluationChat(fail_at=2)
            failed = await task.finish(first, binding, reports, chat)
            assert failed.status == TaskStatus.FAILED and failed.result is not None
            view = await task.wait_status("FAILED")
            assert view["report"]["succeeded"] == 1 and view["report"]["failed"] == 1
            assert view["report"]["questions"][1]["recall"] is None
            committed = (await first.inspect(task.task_id)).checkpoint
            assert committed is not None
            await task.request(
                "POST",
                f"/evaluations/runs/{task.run_id}/resume",
                body={"expectedTaskRevision": failed.revision},
                status=202,
            )
            await task.wait_status("QUEUED")
            second, resumed, new_reports = await task.claim()
            assert resumed.checkpoint == committed and resumed.epoch == binding.epoch + 1
            succeeded = await task.finish(second, resumed, new_reports, chat)
            assert (
                succeeded.status == TaskStatus.SUCCEEDED
                and chat.calls == 3
                and task.knowledge.searches == 3
            )
            final = await task.wait_status("SUCCEEDED")
            assert final["report"]["epoch"] == resumed.epoch and final["report"]["succeeded"] == 2
            qid = final["report"]["questions"][0]["questionId"]
            question = await task.request("GET", f"/evaluations/runs/{task.run_id}/questions/{qid}")
            assert question["retrieved"][0]["chunkId"] == task.hit.chunk_id
            assert question["metrics"] == {"recall": 1.0, "mrr": 1.0, "ndcg": 1.0}
            assert question["cost"]["providerUsage"] is None
            with pytest.raises(TaskRuntimeError) as stale:
                await reports.open(PROFILE)
            assert stale.value.code == TaskRuntimeCode.LEASE_LOST

    asyncio.run(scenario())


def test_real_complete_graph_snapshot_reclaims_with_new_manifest_without_model_replay():
    async def scenario():
        async with real_evaluation() as task:
            first, binding, reports = await task.claim()
            chat = EvaluationChat()
            opened = await reports.open(PROFILE)
            pipeline = EvaluationPipeline(
                binding, first, task.knowledge, chat, reports, PROFILE, opened
            )
            async with PostgresCheckpoints.open(task.checkpoints) as storage:
                saver = FencedPostgresSaver(binding, first, storage)
                final = await pipeline.build(saver).ainvoke(
                    binding.input, saver.config, durability="sync"
                )
                assert final["nextIndex"] == 2 and chat.calls == 2
            checkpoint = (await first.inspect(task.task_id)).checkpoint
            await task.expire()
            second, resumed, new_reports = await task.claim()
            assert resumed.checkpoint == checkpoint
            result = await task.finish(second, resumed, new_reports, chat)
            assert result.status == TaskStatus.SUCCEEDED and result.result["epoch"] == resumed.epoch
            assert chat.calls == 2 and task.knowledge.searches == 2
            final = await task.wait_status("SUCCEEDED")
            assert final["report"]["complete"] and final["report"]["epoch"] == resumed.epoch

    asyncio.run(scenario())


def test_real_cancel_during_model_wait_preserves_central_terminal_without_fake_report():
    async def scenario():
        async with real_evaluation() as task:
            central, binding, reports = await task.claim()
            started, release = asyncio.Event(), asyncio.Event()

            class PausingChat:
                calls = 0

                async def stream(self, messages, operation):
                    self.calls += 1
                    started.set()
                    await release.wait()
                    yield "确定性等待后的迟到结果。[1]"

            chat = PausingChat()
            work = asyncio.create_task(task.finish(central, binding, reports, chat))
            try:
                await asyncio.wait_for(started.wait(), 15)
                current = await task.request("GET", f"/evaluations/runs/{task.run_id}")
                await task.request(
                    "POST",
                    f"/evaluations/runs/{task.run_id}/cancel",
                    body={"expectedRevision": current["summary"]["revision"]},
                    status=202,
                )
                await task.wait_status("CANCELLED")
                release.set()
                result = await work
                assert result.status == TaskStatus.CANCELLED and chat.calls == 1
                final = await task.wait_status("CANCELLED")
                assert final["report"] is None and final["summary"]["cancelRequested"]
            finally:
                release.set()
                work.cancel()
                await asyncio.gather(work, return_exceptions=True)

    asyncio.run(scenario())
