"""中央终态先行的执行入口；图完成和中央结果提交之间允许安全恢复。"""

import asyncio
from collections.abc import Awaitable, Callable
from typing import Any, cast

from langgraph.graph.state import CompiledStateGraph
from langsmith import tracing_context
from pydantic import JsonValue, TypeAdapter, ValidationError

from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import TaskLease, TaskStatus, TaskView
from agentic_ai.tasks.postgres import CheckpointConfig, PostgresCheckpoints

MAX_EXECUTION_SECONDS = 45
MAX_GRAPH_STEPS = 32
RESULT_SCHEMA = TypeAdapter(dict[str, JsonValue])
GraphFactory = Callable[[FencedPostgresSaver], CompiledStateGraph[Any, Any, Any, Any]]
ResultValidator = Callable[[dict[str, object]], Awaitable[None]]


def _same_task(current: TaskLease, original: TaskLease, central: CentralTaskClient) -> None:
    if (
        current.task.task_id != original.task.task_id
        or current.tenant_id != original.tenant_id
        or current.tenant_id != central.tenant_id
        or current.owner_id != original.owner_id
        or current.input != original.input
    ):
        raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID)


async def run_claimed_graph(
    binding: TaskLease,
    central: CentralTaskClient,
    checkpoints: CheckpointConfig,
    build_graph: GraphFactory,
    *,
    before_complete: ResultValidator | None = None,
) -> TaskView:
    """先查中央再开 PG；已完成图从完整快照取输出，不再次执行业务节点。"""
    current = await central.inspect(binding.task.task_id)
    _same_task(current, binding, central)
    if current.task.status.terminal:
        return current.task
    if (
        current.task.status != TaskStatus.RUNNING
        or current.epoch != binding.epoch
        or current.worker_id != central.worker_id
    ):
        raise TaskRuntimeError(TaskRuntimeCode.LEASE_LOST)
    try:
        async with PostgresCheckpoints.open(checkpoints) as storage:
            saver = FencedPostgresSaver(current, central, storage)
            graph = build_graph(saver)
            config = saver.config
            config["recursion_limit"] = MAX_GRAPH_STEPS
            # 同步 durability 保证上一完整检查点提交后才开始下一步；本片无外部追踪。
            with tracing_context(enabled=False):
                async with asyncio.timeout(MAX_EXECUTION_SECONDS):
                    value = await graph.ainvoke(
                        current.input if current.checkpoint is None else None,
                        config,
                        durability="sync",
                    )
                    snapshot = await graph.aget_state(config)
                    if snapshot.next or snapshot.interrupts:
                        raise TaskRuntimeError(TaskRuntimeCode.UNSUPPORTED)
                    try:
                        result = cast(
                            dict[str, object], RESULT_SCHEMA.validate_python(value, strict=True)
                        )
                    except ValidationError:
                        raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID) from None
                    # 已完成快照恢复可能不再执行任何节点，最终权限校验不能只放在节点中。
                    if before_complete is not None:
                        await before_complete(result)
                    return await central.complete(current.task.task_id, current.epoch, result)
    except (TaskRuntimeError, TimeoutError) as error:
        # 取消、接管或提交响应丢失时，以中央最新事实结束；未提交则交给后续领取恢复。
        observed = await central.inspect(binding.task.task_id)
        _same_task(observed, binding, central)
        if observed.task.status.terminal:
            return observed.task
        if isinstance(error, TimeoutError):
            raise TaskRuntimeError(TaskRuntimeCode.UNAVAILABLE) from None
        raise
