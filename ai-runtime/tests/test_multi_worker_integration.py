"""MW02–MW04：独立Python进程竞争与退出后接管，MySQL/PG均为原登记test库。"""

import asyncio
import copy
import json
import os
import subprocess
import sys
from contextlib import AsyncExitStack
from pathlib import Path
from uuid import uuid4

import pytest
from langsmith import tracing_context
from test_generation_execution_integration import services as services  # noqa: F401
from test_staged_research_integration import StagedCase

from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import TaskLease, TaskStatus
from agentic_ai.tasks.postgres import PostgresCheckpoints

pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_MULTI_WORKER_IT") != "1", reason="需显式启用隔离多进程验收"
)
PRIVATE_KEY = Path(__file__).resolve().parents[2] / ".local/task-worker-private.pem"


class ProcessGroup:
    """只管理本夹具创建的子进程；保留所有私密manifest和日志以便失败恢复。"""

    def __init__(self, task):
        self.task = task
        self.children = []
        self.directory = task.services.directory / ("workers-" + str(uuid4()))
        self.directory.mkdir(mode=0o700)
        self.go = self.directory / "go"

    def spawn(self, action):
        name = str(uuid4())
        manifest, result, ready = (
            self.directory / (name + suffix) for suffix in (".json", ".result.json", ".ready")
        )
        data = {
            "endpoint": self.task.services.endpoint,
            "tenant": self.task.tenant,
            "worker": "process-" + name,
            "taskId": self.task.task_id,
            "graph": self.task.graph,
            "checkpoints": {
                "dsn": self.task.services.checkpoints.dsn.get_secret_value(),
                "max_connections": 2,
            },
            "result": str(result),
            "ready": str(ready),
            "go": str(self.go),
            "action": action,
        }
        self.task.services.local.private(manifest, json.dumps(data))
        log = (self.directory / (name + ".log")).open("w")
        process = subprocess.Popen(
            [
                sys.executable,
                str(Path(__file__).with_name("worker_process_probe.py")),
                str(manifest),
            ],
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        child = (process, log, result, ready)
        self.children.append(child)
        return child

    async def wait_file(self, child, index):
        async with asyncio.timeout(40):
            while not child[index].exists():
                assert child[0].poll() is None, "子进程失败，见本任务workers目录日志"
                await asyncio.sleep(0.03)
        if index == 2:
            # 文件创建与写入不是原子事件，允许本次进程尚未写完的小窗口。
            async with asyncio.timeout(2):
                while True:
                    try:
                        return json.loads(child[index].read_text())
                    except json.JSONDecodeError:
                        await asyncio.sleep(0.01)
        return None

    async def stop(self, child):
        if child[0].poll() is None:
            child[0].terminate()
        await asyncio.to_thread(child[0].wait, 10)
        child[1].close()

    async def close(self):
        for child in self.children:
            await self.stop(child)


@pytest.mark.parametrize("finish", ["finish", "finish_lost", "cancel"])
def test_process_competition_exit_takeover_and_unknown_terminal_preserve_fences(services, finish):
    async def scenario():
        with tracing_context(enabled=False):
            async with AsyncExitStack() as stack:
                task = StagedCase(services, stack)
                await task.submit()
                group = ProcessGroup(task)
                stack.push_async_callback(group.close)
                first, second = group.spawn("pause_hold"), group.spawn("pause_hold")
                await asyncio.gather(group.wait_file(first, 3), group.wait_file(second, 3))
                group.go.write_text("go")
                outcomes = await asyncio.gather(
                    group.wait_file(first, 2), group.wait_file(second, 2)
                )
                assert sum(result["claimed"] for result in outcomes) == 1
                winner = next(result for result in outcomes if result["claimed"])
                assert winner["modelCalls"] == winner["planCalls"] == 1
                old = TaskLease.model_validate_json(json.dumps(winner["lease"]))
                await group.stop(first)
                await group.stop(second)
                original = CentralTaskClient.from_file(
                    services.endpoint,
                    task.tenant,
                    old.worker_id,
                    PRIVATE_KEY,
                )
                stack.push_async_callback(original.aclose)
                if finish == "cancel":
                    await task.cancel(old.task.revision)
                else:
                    await task.expire()
                successor = group.spawn("finish" if finish == "cancel" else finish)
                resumed = await group.wait_file(successor, 2)
                await asyncio.to_thread(successor[0].wait, 10)
                if finish == "cancel":
                    assert not resumed["claimed"] and resumed["modelCalls"] == 0
                    assert (
                        await original.inspect(task.task_id)
                    ).task.status == TaskStatus.CANCELLED
                else:
                    assert resumed["claimed"] and resumed["epoch"] == old.epoch + 1
                    assert resumed["result"]["status"] == TaskStatus.SUCCEEDED.value
                    assert (
                        resumed["planCalls"] == 0
                        and resumed["analysisCalls"] == resumed["modelCalls"] == 1
                    )
                    assert resumed["result"]["result"]["content"]["usage"]["modelCalls"] == 2
                    assert resumed["completionReplyDropped"] == (finish == "finish_lost")
                    with pytest.raises(TaskRuntimeError) as rejected:
                        await original.complete(
                            task.task_id, old.epoch, resumed["result"]["result"]
                        )
                    assert rejected.value.code == TaskRuntimeCode.CONFLICT
                with pytest.raises(TaskRuntimeError) as rejected:
                    await original.heartbeat(task.task_id, old.epoch)
                assert rejected.value.code == TaskRuntimeCode.LEASE_LOST
                # 即便旧执行者仍持有已确认快照，也不能把迟到PG写入升级为可恢复状态。
                async with PostgresCheckpoints.open(services.checkpoints) as storage:
                    saver = FencedPostgresSaver(old, original, storage)
                    approved = await storage.saver.aget_tuple(
                        {
                            "configurable": {
                                "thread_id": old.physical_thread,
                                "checkpoint_ns": "",
                                "checkpoint_id": old.checkpoint.checkpoint_id,
                            }
                        }
                    )
                    assert approved is not None
                    orphan = copy.deepcopy(approved.checkpoint)
                    orphan["id"] = str(uuid4())
                    for operation in (
                        saver.aput(approved.config, orphan, approved.metadata, {}),
                        saver.aput_writes(approved.config, [("late", "synthetic")], str(uuid4())),
                    ):
                        with pytest.raises(TaskRuntimeError) as rejected:
                            await operation
                        assert rejected.value.code == TaskRuntimeCode.LEASE_LOST
                    assert (
                        await storage.saver.aget_tuple(
                            {
                                "configurable": {
                                    "thread_id": old.physical_thread,
                                    "checkpoint_ns": "",
                                    "checkpoint_id": orphan["id"],
                                }
                            }
                        )
                        is None
                    )

    asyncio.run(scenario())
