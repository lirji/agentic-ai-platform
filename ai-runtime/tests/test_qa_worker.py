"""worker 领取前门禁、失败对账与有界退出；不打开提供方连接。"""

import asyncio
import json
from contextlib import asynccontextmanager

import pytest
from test_qa_chat import config_at, ledger_at, read_ledger
from test_qa_pipeline import Central, Chat, Knowledge, lease

from agentic_ai.cli import main
from agentic_ai.qa import worker
from agentic_ai.qa.chat_budget import ChatBudget
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.worker import WorkerConfig, execute_qa_task, failure_code, run_worker
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import TaskFailureCode, TaskStatus
from agentic_ai.tasks.postgres import CheckpointConfig

UNUSED_PG = CheckpointConfig(dsn="postgresql://127.0.0.1:1/unused")


class CentralWithFailure(Central):
    def __init__(self, binding):
        super().__init__(binding)
        self.failed = []
        self.lose_reply = False

    async def fail(self, task_id, epoch, code, *, retryable):
        assert task_id == self.binding.task.task_id and epoch == self.binding.epoch
        self.failed.append((code, retryable))
        result = self.binding.task.model_copy(
            update={"status": TaskStatus.FAILED, "error_code": code.value, "retryable": retryable}
        )
        self.binding = self.binding.model_copy(update={"task": result})
        if self.lose_reply:
            raise TaskRuntimeError(TaskRuntimeCode.UNAVAILABLE)
        return result


@pytest.mark.parametrize(
    ("error", "expected", "retryable"),
    [
        (QaError(QaCode.BUDGET_EXCEEDED), TaskFailureCode.BUDGET_EXCEEDED, False),
        (QaError(QaCode.EVIDENCE_REJECTED), TaskFailureCode.INPUT_REJECTED, False),
        (QaError(QaCode.ANSWER_INVALID), TaskFailureCode.INPUT_REJECTED, False),
        (QaError(QaCode.CONFIG_INVALID), TaskFailureCode.INPUT_REJECTED, False),
        (QaError(QaCode.MODEL_UNAVAILABLE), TaskFailureCode.AI_UNAVAILABLE, True),
        (QaError(QaCode.KNOWLEDGE_UNAVAILABLE), TaskFailureCode.AI_UNAVAILABLE, True),
        (TaskRuntimeError(TaskRuntimeCode.UNAVAILABLE), TaskFailureCode.AI_UNAVAILABLE, True),
        (
            TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID),
            TaskFailureCode.EXECUTION_FAILED,
            False,
        ),
        (RuntimeError("synthetic-private-error"), TaskFailureCode.EXECUTION_FAILED, False),
    ],
)
def test_only_safe_central_codes_are_persisted(error, expected, retryable):
    assert failure_code(error) == (expected, retryable)


def test_invalid_input_is_failed_without_pg_or_model():
    async def scenario():
        binding = lease(input_value={"question": "legacy-input"})
        central, chat = CentralWithFailure(binding), Chat()
        result = await execute_qa_task(binding, central, UNUSED_PG, Knowledge(), chat)
        assert result.status == TaskStatus.FAILED and result.error_code == "INPUT_REJECTED"
        assert central.failed == [(TaskFailureCode.INPUT_REJECTED, False)]
        assert chat.calls == 0

    asyncio.run(scenario())


def test_failure_response_loss_is_reconciled_without_resending(monkeypatch):
    async def unavailable(*_args, **_kwargs):
        raise QaError(QaCode.MODEL_UNAVAILABLE)

    monkeypatch.setattr(worker, "run_claimed_graph", unavailable)

    async def scenario():
        binding = lease()
        central = CentralWithFailure(binding)
        central.lose_reply = True
        result = await execute_qa_task(binding, central, UNUSED_PG, Knowledge(), Chat())
        assert result.status == TaskStatus.FAILED and result.error_code == "AI_UNAVAILABLE"
        assert len(central.failed) == 1

    asyncio.run(scenario())


def test_explicit_shutdown_cancels_graph_and_heartbeat_without_failing_task(monkeypatch):
    async def scenario():
        started, stopped = asyncio.Event(), asyncio.Event()

        async def running(*_args, **_kwargs):
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                stopped.set()

        monkeypatch.setattr(worker, "run_claimed_graph", running)
        monkeypatch.setattr(worker, "HEARTBEAT_SECONDS", 0.01)
        binding = lease()
        central = CentralWithFailure(binding)
        work = asyncio.create_task(
            execute_qa_task(binding, central, UNUSED_PG, Knowledge(), Chat())
        )
        await started.wait()
        await asyncio.sleep(0.025)
        work.cancel()
        with pytest.raises(asyncio.CancelledError):
            await work
        assert stopped.is_set() and central.beats >= 1 and central.failed == []
        count = central.beats
        await asyncio.sleep(0.025)
        assert central.beats == count

    asyncio.run(scenario())


def test_old_worker_cannot_report_failure_after_takeover(monkeypatch):
    async def scenario():
        binding = lease()
        central = CentralWithFailure(binding)

        async def takeover(*_args, **_kwargs):
            central.binding = binding.model_copy(update={"epoch": binding.epoch + 1})
            raise TaskRuntimeError(TaskRuntimeCode.LEASE_LOST)

        monkeypatch.setattr(worker, "run_claimed_graph", takeover)
        with pytest.raises(TaskRuntimeError, match=TaskRuntimeCode.LEASE_LOST):
            await execute_qa_task(binding, central, UNUSED_PG, Knowledge(), Chat())
        assert central.failed == []

    asyncio.run(scenario())


def test_budget_preflight_does_not_spend_or_reset_existing_reservations(tmp_path):
    config = config_at(tmp_path)
    ledger_at(config)
    ChatBudget(config).reserve("previous", 10)
    before = config.budget_file.read_bytes()
    ChatBudget(config).check_available()
    assert config.budget_file.read_bytes() == before
    assert len(read_ledger(config)["calls"]) == 1


def test_worker_config_is_private_absolute_and_missing_config_cli_is_safe(tmp_path, capsys):
    path = tmp_path / "worker.json"
    assert main(["check-qa-worker", "--config", str(path)]) == 2
    assert json.loads(capsys.readouterr().err) == {"code": "QA_CONFIG_INVALID"}
    value = {
        "tenant_id": "synthetic-tenant",
        "tasks_endpoint": "http://127.0.0.1:28087",
        "knowledge_endpoint": "http://127.0.0.1:28084",
        "task_private_key": str(tmp_path / "task.pem"),
        "knowledge_private_key": str(tmp_path / "knowledge.pem"),
        "chat_config": str(tmp_path / "chat.json"),
        "checkpoints": {"dsn": "synthetic-secret"},
    }
    path.write_text(json.dumps(value))
    path.chmod(0o600)
    parsed = WorkerConfig.from_file(path)
    assert parsed.tenant_id == "synthetic-tenant" and "synthetic-secret" not in repr(parsed)
    assert not parsed.contextual_qa_enabled
    path.write_text(json.dumps({**value, "contextual_qa_enabled": True}))
    with pytest.raises(QaError, match=QaCode.CONFIG_INVALID):
        WorkerConfig.from_file(path)
    path.write_text(json.dumps({**value, "retrieval_enabled": True, "contextual_qa_enabled": True}))
    assert WorkerConfig.from_file(path).contextual_qa_enabled
    path.write_text(json.dumps(value))
    path.chmod(0o644)
    with pytest.raises(QaError, match=QaCode.CONFIG_INVALID):
        WorkerConfig.from_file(path)
    path.chmod(0o600)
    path.write_text(json.dumps({**value, "task_private_key": "relative.pem"}))
    with pytest.raises(QaError, match=QaCode.CONFIG_INVALID):
        WorkerConfig.from_file(path)


def test_pg_preflight_failure_never_claims_or_constructs_chat_client(tmp_path, monkeypatch):
    called = []

    class UnusedClient:
        async def aclose(self):
            called.append("closed")

        async def claim(self):
            raise AssertionError("PG 未就绪不能领取")

    monkeypatch.setattr(worker.CentralTaskClient, "from_file", lambda *_args: UnusedClient())
    monkeypatch.setattr(worker.KnowledgeClient, "from_file", lambda *_args: UnusedClient())
    chat_config = config_at(tmp_path)
    ledger_at(chat_config)
    monkeypatch.setattr(worker.ChatConfig, "from_file", lambda *_args: chat_config)

    @asynccontextmanager
    async def unavailable(_config):
        raise TaskRuntimeError(TaskRuntimeCode.UNAVAILABLE)
        yield

    monkeypatch.setattr(worker.PostgresCheckpoints, "open", unavailable)
    config = WorkerConfig(
        tenant_id="synthetic-tenant",
        tasks_endpoint="http://127.0.0.1:1",
        knowledge_endpoint="http://127.0.0.1:2",
        task_private_key=tmp_path / "task.pem",
        knowledge_private_key=tmp_path / "knowledge.pem",
        chat_config=tmp_path / "chat.json",
        checkpoints=UNUSED_PG,
    )
    with pytest.raises(TaskRuntimeError, match=TaskRuntimeCode.UNAVAILABLE):
        asyncio.run(run_worker(config, check_only=True))
    assert called == ["closed", "closed"]
    assert read_ledger(chat_config)["calls"] == []


@pytest.mark.parametrize("kind", ["KNOWLEDGE_QA", "RESEARCH"])
def test_v2_routes_are_explicit_and_require_shared_retrieval(monkeypatch, kind):
    from test_generation_qa import setup as qa_setup
    from test_generation_work import setup as work_setup

    from agentic_ai.agents.generation_pipeline import GenerationWorkPipeline
    from agentic_ai.qa.generation_pipeline import GenerationQaPipeline

    async def scenario():
        pipeline, _, embedding, candidates, chat, use_case = (
            qa_setup() if kind == "KNOWLEDGE_QA" else work_setup()
        )
        seen = []

        async def routed(current, _checkpoints):
            seen.append(current)
            return current.binding.task

        monkeypatch.setattr(worker, "_run_with_heartbeat", routed)
        central = CentralWithFailure(pipeline.binding)
        await execute_qa_task(
            pipeline.binding,
            central,
            UNUSED_PG,
            Knowledge(),
            Chat(),
            tool_chat=chat if kind == "RESEARCH" else None,
            retrieval=use_case,
            candidates=candidates,
        )
        assert isinstance(
            seen[0], GenerationQaPipeline if kind == "KNOWLEDGE_QA" else GenerationWorkPipeline
        )
        central = CentralWithFailure(pipeline.binding)
        result = await execute_qa_task(pipeline.binding, central, UNUSED_PG, Knowledge(), Chat())
        assert result.error_code == "INPUT_REJECTED" and len(seen) == 1
        assert not embedding.calls

    asyncio.run(scenario())


def test_retrieval_failure_mapping_does_not_enable_retry_for_budget_or_identity():
    from agentic_ai.rag.errors import EmbeddingError, EmbeddingErrorCode
    from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError

    for code in (
        RetrievalCode.RERANK_DISABLED,
        RetrievalCode.EVIDENCE_REJECTED,
        RetrievalCode.AUTH_REJECTED,
        RetrievalCode.INPUT_INVALID,
    ):
        assert failure_code(RetrievalError(code)) == (TaskFailureCode.INPUT_REJECTED, False)
    assert failure_code(RetrievalError(RetrievalCode.RERANK_BUDGET_REJECTED)) == (
        TaskFailureCode.BUDGET_EXCEEDED,
        False,
    )
    assert failure_code(EmbeddingError(EmbeddingErrorCode.BUDGET_REJECTED)) == (
        TaskFailureCode.BUDGET_EXCEEDED,
        False,
    )
    assert failure_code(RetrievalError(RetrievalCode.KNOWLEDGE_UNAVAILABLE)) == (
        TaskFailureCode.AI_UNAVAILABLE,
        True,
    )
