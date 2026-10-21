"""固定租户、单在途的问答 worker；中央任务是状态权威，停止时保留可恢复租约。"""

import asyncio
import json
import logging
import os
import signal
import stat
import time
from contextlib import AsyncExitStack
from datetime import UTC, datetime
from pathlib import Path
from typing import Self
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

from agentic_ai.qa.chat import ChatClient, ChatPort
from agentic_ai.qa.chat_budget import ChatBudget
from agentic_ai.qa.chat_config import MAX_CONFIG_BYTES, ChatConfig
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.knowledge import KnowledgeClient
from agentic_ai.qa.pipeline import QaPipeline
from agentic_ai.qa.retriever import KnowledgePort
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.execution import run_claimed_graph
from agentic_ai.tasks.models import SubjectId, TaskFailureCode, TaskLease, TaskView
from agentic_ai.tasks.postgres import CheckpointConfig, PostgresCheckpoints

HEARTBEAT_SECONDS = 10
POLL_SECONDS = 2
LOGGER = logging.getLogger("agentic_ai.qa.worker")


class WorkerConfig(BaseModel):
    """只在显式 CLI 启动时启用，配置不包含模型默认值或用户可覆写的 worker ID。"""

    model_config = ConfigDict(frozen=True, extra="forbid", hide_input_in_errors=True)
    tenant_id: SubjectId
    tasks_endpoint: str = Field(repr=False, max_length=2048)
    knowledge_endpoint: str = Field(repr=False, max_length=2048)
    task_private_key: Path = Field(repr=False)
    knowledge_private_key: Path = Field(repr=False)
    chat_config: Path = Field(repr=False)
    checkpoints: CheckpointConfig = Field(repr=False)

    @field_validator("task_private_key", "knowledge_private_key", "chat_config")
    @classmethod
    def absolute(cls, value: Path) -> Path:
        """所有敏感路径固定，不随工作目录变化误读另一个环境。"""
        if not value.is_absolute():
            raise ValueError("worker 配置需要绝对路径")
        return value

    @classmethod
    def from_file(cls, path: Path) -> Self:
        """不输出私密配置或解析异常输入；错误不能使 worker 带缺省身份领取任务。"""
        parsed = None
        try:
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
            with os.fdopen(descriptor, "rb") as source:
                metadata = os.fstat(source.fileno())
                if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o077:
                    raise ValueError
                raw = source.read(MAX_CONFIG_BYTES + 1)
                if len(raw) <= MAX_CONFIG_BYTES:
                    parsed = cls.model_validate_json(raw)
        except (OSError, ValueError):
            parsed = None
        if parsed is None:
            raise QaError(QaCode.CONFIG_INVALID)
        return parsed


def failure_code(error: Exception) -> tuple[TaskFailureCode, bool]:
    """只把目录编码写入中央失败事实，预算/输入失败不开放自动额度恢复。"""
    if isinstance(error, QaError):
        if error.code == QaCode.BUDGET_EXCEEDED:
            return TaskFailureCode.BUDGET_EXCEEDED, False
        if error.code in {
            QaCode.INPUT_REJECTED,
            QaCode.EVIDENCE_REJECTED,
            QaCode.ANSWER_INVALID,
            QaCode.CONFIG_INVALID,
        }:
            return TaskFailureCode.INPUT_REJECTED, False
        return TaskFailureCode.AI_UNAVAILABLE, True
    if isinstance(error, TaskRuntimeError) and error.code in {
        TaskRuntimeCode.UNAVAILABLE,
        TaskRuntimeCode.CAPACITY_EXCEEDED,
    }:
        return TaskFailureCode.AI_UNAVAILABLE, True
    if isinstance(error, ValidationError):
        return TaskFailureCode.INPUT_REJECTED, False
    return TaskFailureCode.EXECUTION_FAILED, False


async def _run_with_heartbeat(pipeline: QaPipeline, checkpoints: CheckpointConfig) -> TaskView:
    async def pulse() -> None:
        while True:
            await asyncio.sleep(HEARTBEAT_SECONDS)
            await pipeline.assert_active()

    work = asyncio.create_task(
        run_claimed_graph(
            pipeline.binding,
            pipeline.central,
            checkpoints,
            pipeline.build,
            before_complete=pipeline.before_complete,
        ),
        name="qa-graph",
    )
    heartbeat = asyncio.create_task(pulse(), name="qa-heartbeat")
    try:
        finished, _ = await asyncio.wait({work, heartbeat}, return_when=asyncio.FIRST_COMPLETED)
        if work in finished:
            return await work
        # 心跳失败时取消在途模型/图，不能在租约未知时继续积累外部副作用。
        await heartbeat
        raise TaskRuntimeError(TaskRuntimeCode.LEASE_LOST)
    finally:
        work.cancel()
        heartbeat.cancel()
        await asyncio.gather(work, heartbeat, return_exceptions=True)


async def execute_qa_task(
    binding: TaskLease,
    central: CentralTaskClient,
    checkpoints: CheckpointConfig,
    knowledge: KnowledgePort,
    chat: ChatPort,
) -> TaskView:
    """异常先核对中央；取消/完成赢得竞争后不再报告覆盖它的失败。"""
    try:
        pipeline = QaPipeline(binding, central, knowledge, chat)
        return await _run_with_heartbeat(pipeline, checkpoints)
    except Exception as error:
        observed = await central.inspect(binding.task.task_id)
        if (
            observed.task.task_id != binding.task.task_id
            or observed.tenant_id != binding.tenant_id
            or observed.owner_id != binding.owner_id
            or observed.input != binding.input
        ):
            raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID) from None
        if observed.task.status.terminal:
            return observed.task
        if observed.epoch != binding.epoch or observed.worker_id != central.worker_id:
            raise TaskRuntimeError(TaskRuntimeCode.LEASE_LOST) from None
        if isinstance(error, TaskRuntimeError) and error.code == TaskRuntimeCode.LEASE_LOST:
            raise error
        code, retryable = failure_code(error)
        try:
            return await central.fail(
                binding.task.task_id, binding.epoch, code, retryable=retryable
            )
        except TaskRuntimeError:
            # 提交失败可能实际已落库，或取消刚刚赢得行锁；只查询，不重发副作用。
            latest = await central.inspect(binding.task.task_id)
            if (
                latest.task.task_id != binding.task.task_id
                or latest.tenant_id != binding.tenant_id
                or latest.owner_id != binding.owner_id
                or latest.input != binding.input
            ):
                raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID) from None
            if latest.task.status.terminal:
                return latest.task
            raise


def _log(state: str, *, task: TaskView | None = None, duration: float | None = None) -> None:
    value: dict[str, object] = {
        "time": datetime.now(UTC).isoformat(),
        "component": "knowledge-qa-worker",
        "state": state,
        "pid": os.getpid(),
    }
    if task is not None:
        value.update(taskId=task.task_id, status=task.status.value, code=task.error_code)
    if duration is not None:
        value["durationSeconds"] = round(duration, 3)
    LOGGER.info(json.dumps(value, ensure_ascii=False))


async def run_worker(config: WorkerConfig, *, check_only: bool = False) -> None:
    """配置/PG/预算预检成功才领取；只启动一个执行流，不使用隐藏后台线程池。"""
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    previous_handlers = (
        {sig: signal.getsignal(sig) for sig in (signal.SIGINT, signal.SIGTERM)}
        if not check_only
        else {}
    )
    for sig in previous_handlers:
        loop.add_signal_handler(sig, stop.set)
    try:
        async with AsyncExitStack() as stack:
            central = CentralTaskClient.from_file(
                config.tasks_endpoint,
                config.tenant_id,
                "qa-" + str(uuid4()),
                config.task_private_key,
            )
            stack.push_async_callback(central.aclose)
            probe = KnowledgeClient.from_file(
                config.knowledge_endpoint,
                config.tenant_id,
                "startup-validation",
                config.knowledge_private_key,
            )
            await probe.aclose()
            chat_config = ChatConfig.from_file(config.chat_config)
            budget = ChatBudget(chat_config)
            budget.check_available()
            async with PostgresCheckpoints.open(config.checkpoints) as storage:
                await storage.verify_ready()
            chat = ChatClient(chat_config, budget)
            stack.push_async_callback(chat.aclose)
            if check_only:
                _log("CONFIGURATION_VALID")
                return
            _log("READY")
            while not stop.is_set():
                budget.check_available()
                try:
                    binding = await central.claim()
                except TaskRuntimeError:
                    _log("CENTRAL_UNAVAILABLE")
                    binding = None
                if binding is not None and not stop.is_set():
                    knowledge = KnowledgeClient.from_file(
                        config.knowledge_endpoint,
                        binding.tenant_id,
                        binding.owner_id,
                        config.knowledge_private_key,
                    )
                    started = time.monotonic()
                    _log("EXECUTING", task=binding.task)
                    work = asyncio.create_task(
                        execute_qa_task(binding, central, config.checkpoints, knowledge, chat)
                    )
                    stopping = asyncio.create_task(stop.wait())
                    try:
                        done, _ = await asyncio.wait(
                            {work, stopping}, return_when=asyncio.FIRST_COMPLETED
                        )
                        if work in done:
                            try:
                                result = await work
                                _log(
                                    "TASK_FINISHED",
                                    task=result,
                                    duration=time.monotonic() - started,
                                )
                            except TaskRuntimeError:
                                _log("TASK_RESULT_UNCONFIRMED", task=binding.task)
                    finally:
                        work.cancel()
                        stopping.cancel()
                        await asyncio.gather(work, stopping, return_exceptions=True)
                        await knowledge.aclose()
                if not stop.is_set():
                    try:
                        await asyncio.wait_for(stop.wait(), POLL_SECONDS)
                    except TimeoutError:
                        # 空队列轮询的计时器正常到期，明确回到下一次预算/领取检查。
                        continue
    finally:
        _log("STOPPING")
        for sig, previous in previous_handlers.items():
            loop.remove_signal_handler(sig)
            signal.signal(sig, previous)
