"""浏览器验收辅助：真实 E02/PG 存储，确定性 QA 端口，不调用付费模型。"""

import argparse
import asyncio
import json
from contextlib import AsyncExitStack
from pathlib import Path

from langsmith import tracing_context
from test_evaluation_execution_integration import RealEvaluation, real_evaluation
from test_evaluation_pipeline import EvaluationChat


class EmptyRecall:
    """明确的零召回验收输入，保留正常完成和未知费用口径。"""

    async def search(self, question, documents):
        assert question and documents
        return ()

    async def citation(self, value):
        raise AssertionError("空召回不应读取引用")


class DecisionRecall:
    """S8D界面夹具：无答案题确实零召回，引用仍通过真实授权端口。"""

    def __init__(self, delegate):
        self.delegate = delegate

    async def search(self, question, documents):
        if "餐补" in question:
            return ()
        return await self.delegate.search(question, documents)

    async def citation(self, value):
        return await self.delegate.citation(value)


async def main(action, record):
    """生成夹具或消费浏览器已请求的恢复，不自动替用户恢复任务。"""
    if action == "candidate":
        async with AsyncExitStack() as stack:
            parent = json.loads(record.read_text())
            harness = RealEvaluation(stack)
            harness.state.update(datasetId=parent["datasetId"], definition=parent["definition"])
            harness.save()
            view = await harness.request(
                "POST",
                "/evaluations/runs",
                body={"datasetId": parent["datasetId"], "revision": 1, "topK": 5},
                key=harness.key,
                status=202,
            )
            harness.run_id = view["summary"]["runId"]
            harness.state.update(runId=harness.run_id, status="ACCEPTED")
            harness.save()
            view = await harness.wait_status("QUEUED")
            harness.state.update(taskId=view["task"]["taskId"])
            harness.save()
            await harness.attach()
            harness.knowledge = EmptyRecall()
            central, binding, reports = await harness.claim()
            result = await harness.finish(central, binding, reports, EvaluationChat())
            assert result.status.value == "SUCCEEDED"
            await harness.request("GET", f"/evaluations/runs/{harness.run_id}")
            print(json.dumps({"record": str(harness.record), **harness.state}))
    elif action == "resume":
        async with AsyncExitStack() as stack:
            harness = RealEvaluation(stack, record)
            await harness.attach()
            await harness.wait_status("QUEUED")
            central, binding, reports = await harness.claim()
            result = await harness.finish(central, binding, reports, EvaluationChat())
            assert result.status.value == "SUCCEEDED"
    else:
        async with real_evaluation() as harness:
            central, binding, reports = await harness.claim()
            if action == "empty":
                harness.knowledge = EmptyRecall()
            elif action == "success":
                harness.knowledge = DecisionRecall(harness.knowledge)
            result = await harness.finish(
                central,
                binding,
                reports,
                EvaluationChat(fail_at=2 if action == "failed" else None),
            )
            assert result.status.value == ("FAILED" if action == "failed" else "SUCCEEDED")
            await harness.request("GET", f"/evaluations/runs/{harness.run_id}")
            print(json.dumps({"record": str(harness.record), **harness.state}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["failed", "empty", "resume", "success", "candidate"])
    parser.add_argument("--record", type=Path)
    args = parser.parse_args()
    if (args.action in {"resume", "candidate"}) != bool(args.record):
        parser.error("resume/candidate 必须提供原 record，其他动作不能指定 record")
    with tracing_context(enabled=False):
        asyncio.run(main(args.action, args.record))
