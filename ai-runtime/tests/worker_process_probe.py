"""S16A仅测试子进程：真实Tasks/PG，确定性模型端口；不用于运行环境。"""

import asyncio
import json
import sys
import time
from pathlib import Path

from test_qa_pipeline import Knowledge
from test_research_coverage import setup
from test_staged_research import StagedChat

from agentic_ai.agents.staged_research_pipeline import StagedResearchPipeline
from agentic_ai.qa.worker import execute_qa_task
from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.postgres import CheckpointConfig, PostgresCheckpoints

PRIVATE_KEY = Path(__file__).resolve().parents[2] / ".local/task-worker-private.pem"


def wait_start(go: Path) -> None:
    """跨进程文件屏障有固定超时，放在线程中避免阻塞异步心跳。"""
    deadline = time.monotonic() + 30
    while not go.exists():
        if time.monotonic() > deadline:
            raise TimeoutError("process start barrier expired")
        time.sleep(0.02)


async def run(path: Path) -> None:
    data = json.loads(await asyncio.to_thread(path.read_text))
    result_path, ready, go = (Path(data[key]) for key in ("result", "ready", "go"))
    central = CentralTaskClient.from_file(
        data["endpoint"], data["tenant"], data["worker"], PRIVATE_KEY
    )
    ready.write_text("ready")
    try:
        await asyncio.to_thread(wait_start, go)
        lease = await central.claim(data["graph"])
        if lease is None:
            result_path.write_text(json.dumps({"claimed": False, "modelCalls": 0}))
            return
        assert lease.task.task_id == data["taskId"]
        _, _, _, candidates, _, use_case = setup()
        chat = StagedChat()
        config = CheckpointConfig.model_validate(data["checkpoints"])
        if data["action"] == "pause_hold":
            async with PostgresCheckpoints.open(config) as storage:
                saver = FencedPostgresSaver(lease, central, storage)
                pipeline = StagedResearchPipeline(lease, central, candidates, chat, use_case)
                await pipeline.build(saver, interrupt_before=["analyze"]).ainvoke(
                    lease.input, saver.config, durability="sync"
                )
                confirmed = await central.inspect(lease.task.task_id)
                assert confirmed.checkpoint is not None
                result_path.write_text(
                    json.dumps(
                        {
                            "claimed": True,
                            "lease": confirmed.model_dump(mode="json", by_alias=True),
                            "modelCalls": chat.calls,
                            "planCalls": chat.plan_calls,
                        }
                    )
                )
                # 父进程在看到中央确认凭据后终止当前进程，模拟进程级退出而非只取消协程。
                await asyncio.Event().wait()
        else:
            dropped = False
            complete = central.complete

            async def uncertain(*args):
                nonlocal dropped
                result = await complete(*args)
                if data["action"] == "finish_lost" and not dropped:
                    dropped = True
                    raise TaskRuntimeError(TaskRuntimeCode.UNAVAILABLE)
                return result

            central.complete = uncertain
            result = await execute_qa_task(
                lease,
                central,
                config,
                Knowledge(),
                chat,
                tool_chat=chat,
                retrieval=use_case,
                candidates=candidates,
            )
            result_path.write_text(
                json.dumps(
                    {
                        "claimed": True,
                        "result": result.model_dump(mode="json", by_alias=True),
                        "epoch": lease.epoch,
                        "modelCalls": chat.calls,
                        "planCalls": chat.plan_calls,
                        "analysisCalls": chat.analysis_calls,
                        "completionReplyDropped": dropped,
                    }
                )
            )
    finally:
        await central.aclose()


if __name__ == "__main__":
    asyncio.run(run(Path(sys.argv[1])))
