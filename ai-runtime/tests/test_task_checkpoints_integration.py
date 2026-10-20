"""显式启用的真实 PG/MySQL/HTTP 验收；只保留本项目合成任务，不访问模型。"""

import asyncio
import copy
import json
import os
import sys
from contextlib import AsyncExitStack, asynccontextmanager
from pathlib import Path
from typing import TypedDict
from uuid import uuid4

import httpx
import pytest
from langgraph.graph import END, START, StateGraph
from langsmith import tracing_context
from psycopg.errors import CheckViolation, QueryCanceled
from psycopg_pool import PoolTimeout, TooManyRequests
from pydantic import BaseModel

from agentic_ai.tasks.checkpointer import FencedPostgresSaver
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.execution import run_claimed_graph
from agentic_ai.tasks.models import TaskStatus
from agentic_ai.tasks.postgres import MAX_SNAPSHOT_BYTES, CheckpointConfig, PostgresCheckpoints

pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_CHECKPOINT_IT") != "1", reason="需显式启用项目本地真实数据库验收"
)
ROOT = Path(__file__).resolve().parents[2]
CENTRAL = "http://127.0.0.1:28087"


class SampleState(TypedDict, total=False):
    """纯测试的确定性节点状态，不代替产品知识检索或聊天模型。"""

    question: str
    retrieved: str
    answer: str


def sample_graph(saver, calls, *, pause=False):
    async def retrieve(state):
        calls.append("retrieve")
        return {"retrieved": "合成资料证据"}

    async def answer(state):
        calls.append("answer")
        return {"answer": state["retrieved"] + "：恢复验证"}

    graph = StateGraph(SampleState)
    graph.add_node("retrieve", retrieve)
    graph.add_node("answer", answer)
    graph.add_edge(START, "retrieve")
    graph.add_edge("retrieve", "answer")
    graph.add_edge("answer", END)
    # 静态中断只用于模拟进程退出的位置，不暴露为产品人工恢复 API。
    return graph.compile(checkpointer=saver, interrupt_before=["answer"] if pause else None)


class RealTask:
    """隔离租户与任务编号只保存在忽略记录中，故障 SQL 限定自己的合成行。"""

    def __init__(self, stack):
        sys.path.insert(0, str(ROOT / "deploy"))
        import checkpoint_local
        import local
        import task_smoke

        self.local = local
        self.sign = task_smoke.service_token
        self.stack = stack
        self.task_id = str(uuid4())
        self.tenant = "pg-test-" + str(uuid4())
        self.owner = "checkpoint-test-owner"
        self.config = CheckpointConfig(dsn=checkpoint_local.dsn("test"))
        local.private(
            local.STATE / f"s5b-{self.task_id}.json",
            json.dumps({"taskId": self.task_id, "tenant": self.tenant, "owner": self.owner}),
        )

    async def client(self, *, transport=None):
        client = CentralTaskClient.from_file(
            CENTRAL,
            self.tenant,
            "pg-worker-" + str(uuid4()),
            ROOT / ".local/task-worker-private.pem",
            transport=transport,
        )
        self.stack.push_async_callback(client.aclose)
        return client

    async def submit(self):
        async with httpx.AsyncClient(trust_env=False, timeout=5) as http:
            response = await http.post(
                CENTRAL + "/internal/v1/tasks",
                headers={
                    "X-Internal-Token": self.sign("submit", self.tenant, self.owner),
                    "Idempotency-Key": self.task_id,
                },
                json={
                    "taskId": self.task_id,
                    "kind": "KNOWLEDGE_QA",
                    "runtimeVersion": "langgraph-v1",
                    "graphVersion": "knowledge-qa-v1",
                    "input": {"question": "仅验证检查点恢复"},
                },
            )
            assert response.status_code == 202

    async def expire(self):
        count = await asyncio.to_thread(
            self.local.mysql,
            "UPDATE agentic_tasks.ai_task SET lease_expires_at="
            "TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) "
            f"WHERE task_id='{self.task_id}' AND tenant_id='{self.tenant}' AND status='RUNNING';"
            "SELECT ROW_COUNT();",
        )
        assert count.strip() == "1"

    async def storage(self):
        return await self.stack.enter_async_context(PostgresCheckpoints.open(self.config))


@asynccontextmanager
async def real_task():
    with tracing_context(enabled=False):
        async with AsyncExitStack() as stack:
            task = RealTask(stack)
            await task.submit()
            yield task


def test_real_graph_rebuilds_from_committed_checkpoint_without_replaying_first_node():
    async def scenario():
        async with real_task() as task:
            first = await task.client()
            lease = await first.claim()
            assert lease is not None and lease.task.task_id == task.task_id
            calls = []
            store = await task.storage()
            saver = FencedPostgresSaver(lease, first, store)
            graph = sample_graph(saver, calls, pause=True)
            await graph.ainvoke(lease.input, saver.config, durability="sync")
            checkpoint = (await first.inspect(task.task_id)).checkpoint
            assert checkpoint is not None and calls == ["retrieve"]
            await task.expire()
            second = await task.client()
            resumed = await second.claim()
            assert resumed.epoch == lease.epoch + 1
            assert resumed.checkpoint == checkpoint
            result = await run_claimed_graph(
                resumed, second, task.config, lambda cp: sample_graph(cp, calls)
            )
            assert result.status == TaskStatus.SUCCEEDED
            assert result.result["answer"] == "合成资料证据：恢复验证"
            assert calls == ["retrieve", "answer"]
            current = await second.inspect(task.task_id)
            assert current.checkpoint.thread_id == resumed.physical_thread
            assert current.checkpoint.epoch == resumed.epoch

            def must_not_build(_saver):
                raise AssertionError("中央已完成时不能打开 PG 或构建图")

            # 终态先行；不可连接 PG 也不会使已经成功的任务重复执行。
            bad_pg = CheckpointConfig(dsn="postgresql://127.0.0.1:1/unreachable")
            replay = await run_claimed_graph(resumed, second, bad_pg, must_not_build)
            assert replay == result

    asyncio.run(scenario())


class FaultTransport(httpx.AsyncBaseTransport):
    """请求仍发到真实中央服务，只在指定边界暂停或丢弃已成功的响应。"""

    def __init__(self, suffix, *, drop=True):
        self.inner = httpx.AsyncHTTPTransport()
        self.suffix = suffix
        self.drop = drop
        self.armed = True
        self.observed = 0
        self.entered = asyncio.Event()
        self.release = asyncio.Event()

    async def handle_async_request(self, request):
        selected = self.armed and request.url.path.endswith(self.suffix)
        if selected:
            self.armed = False
            self.observed += 1
            self.entered.set()
            if not self.drop:
                await self.release.wait()
        response = await self.inner.handle_async_request(request)
        if selected and self.drop:
            assert response.status_code == 200
            await response.aread()
            await response.aclose()
            raise httpx.ReadError("仅测试：真实提交后响应丢失", request=request)
        return response

    async def aclose(self):
        await self.inner.aclose()


@pytest.mark.parametrize("suffix", ["/checkpoint", "/complete"])
def test_unknown_commit_response_is_reconciled_against_real_central_state(suffix):
    async def scenario():
        async with real_task() as task:
            fault = FaultTransport(suffix)
            worker = await task.client(transport=fault)
            lease = await worker.claim()
            calls = []
            result = await run_claimed_graph(
                lease, worker, task.config, lambda cp: sample_graph(cp, calls)
            )
            assert result.status == TaskStatus.SUCCEEDED
            assert fault.observed == 1 and calls == ["retrieve", "answer"]

    asyncio.run(scenario())


def test_old_prepared_snapshot_cannot_become_the_new_workers_recovery_point():
    async def scenario():
        async with real_task() as task:
            fault = FaultTransport("/checkpoint", drop=False)
            fault.armed = False
            old = await task.client(transport=fault)
            lease = await old.claim()
            store = await task.storage()
            saver = FencedPostgresSaver(lease, old, store)
            calls = []
            await sample_graph(saver, calls, pause=True).ainvoke(
                lease.input, saver.config, durability="sync"
            )
            approved = await saver.aget_tuple(saver.config)
            orphan = copy.deepcopy(approved.checkpoint)
            orphan["id"] = str(uuid4())
            version = saver.get_next_version(orphan["channel_versions"].get("answer"), None)
            orphan["channel_versions"]["answer"] = version
            orphan["channel_values"]["answer"] = "旧执行器不得恢复的结果"
            fault.armed = True
            pending = asyncio.create_task(
                saver.aput(approved.config, orphan, approved.metadata, {"answer": version})
            )
            try:
                await asyncio.wait_for(fault.entered.wait(), timeout=2)
                # 此时 PG 准备写确实存在，但中央还没有接受它。
                await store.fingerprint(lease.physical_thread, orphan["id"])
                await task.expire()
                contenders = [await task.client(), await task.client()]
                claims = await asyncio.gather(*(client.claim() for client in contenders))
                assert sum(item is not None for item in claims) == 1
                winner = next(index for index, item in enumerate(claims) if item is not None)
                current, new = claims[winner], contenders[winner]
                assert current.epoch == lease.epoch + 1
                new_saver = FencedPostgresSaver(current, new, store)
                restored = await new_saver.aget_tuple(new_saver.config)
                assert restored.checkpoint["id"] == approved.checkpoint["id"]
                assert restored.checkpoint["channel_values"].get("answer") is None
            finally:
                fault.release.set()
            with pytest.raises(TaskRuntimeError) as failed:
                await pending
            assert failed.value.code == TaskRuntimeCode.LEASE_LOST
            for operation in (
                saver.aput(approved.config, orphan, approved.metadata, {"answer": version}),
                saver.aput_writes(approved.config, [("answer", "late")], str(uuid4())),
            ):
                with pytest.raises(TaskRuntimeError) as rejected:
                    await operation
                assert rejected.value.code == TaskRuntimeCode.LEASE_LOST
            result = await run_claimed_graph(
                current, new, task.config, lambda cp: sample_graph(cp, calls)
            )
            assert result.status == TaskStatus.SUCCEEDED
            assert result.result["answer"] == "合成资料证据：恢复验证"
            assert calls == ["retrieve", "answer"]

    asyncio.run(scenario())


def test_immutable_storage_pending_write_exclusion_and_metadata_filter():
    async def scenario():
        async with real_task() as task:
            worker = await task.client()
            lease = await worker.claim()
            store = await task.storage()
            saver = FencedPostgresSaver(lease, worker, store)
            config = saver.config
            config["configurable"]["api_key"] = "test-only-secret-canary"
            config["metadata"] = {"authorization": "test-only-secret-canary"}
            await sample_graph(saver, [], pause=True).ainvoke(
                lease.input, config, durability="sync"
            )
            receipt = (await worker.inspect(task.task_id)).checkpoint
            async with store.pool.connection() as connection:
                row = await (
                    await connection.execute(
                        "SELECT metadata::text FROM checkpoints "
                        "WHERE thread_id=%s AND checkpoint_id=%s",
                        (receipt.thread_id, receipt.checkpoint_id),
                    )
                ).fetchone()
                assert "canary" not in row["metadata"]
                with pytest.raises(CheckViolation):
                    await connection.execute(
                        "UPDATE checkpoints SET metadata='{}'::jsonb "
                        "WHERE thread_id=%s AND checkpoint_id=%s",
                        (receipt.thread_id, receipt.checkpoint_id),
                    )
                with pytest.raises(CheckViolation):
                    await connection.execute(
                        "UPDATE checkpoint_blobs SET blob=%s WHERE thread_id=%s",
                        (b"changed", receipt.thread_id),
                    )
                # 旧尝试的 pending 数据即便非法，也不能进入恢复解码路径。
                await connection.execute(
                    "INSERT INTO checkpoint_writes"
                    "(thread_id,checkpoint_ns,checkpoint_id,task_id,idx,channel,type,blob,task_path)"
                    " VALUES(%s,'',%s,%s,0,'answer','pickle',%s,'')",
                    (receipt.thread_id, receipt.checkpoint_id, str(uuid4()), b"not-pickle"),
                )
            found = await saver.aget_tuple(saver.config)
            assert found.pending_writes == []
            assert (
                await store.fingerprint(receipt.thread_id, receipt.checkpoint_id) == receipt.sha256
            )
            await task.expire()
            second = await task.client()
            resumed = await second.claim()
            result = await run_claimed_graph(
                resumed, second, task.config, lambda cp: sample_graph(cp, [])
            )
            assert result.status == TaskStatus.SUCCEEDED

    asyncio.run(scenario())


def test_invalid_receipt_is_rejected_before_native_deserialization():
    async def scenario():
        async with real_task() as task:
            worker = await task.client()
            lease = await worker.claim()
            store = await task.storage()
            saver = FencedPostgresSaver(lease, worker, store)
            await sample_graph(saver, [], pause=True).ainvoke(
                lease.input, saver.config, durability="sync"
            )
            receipt = (await worker.inspect(task.task_id)).checkpoint
            # 只改变自己的测试任务凭据，模拟跨库内容/摘要不一致，不改写已保护快照。
            async with httpx.AsyncClient(trust_env=False, timeout=5) as http:
                response = await http.post(
                    CENTRAL + f"/internal/v1/task-worker/{task.task_id}/checkpoint",
                    headers={
                        "X-Internal-Token": task.sign("worker", task.tenant, worker.worker_id)
                    },
                    json={
                        "epoch": lease.epoch,
                        "operationKey": "test-invalid-digest",
                        "checkpointId": receipt.checkpoint_id,
                        "checkpointNamespace": "",
                        "sha256": "0" * 64,
                    },
                )
                assert response.status_code == 200

            async def must_not_deserialize(_config):
                raise AssertionError("摘要校验失败前不能反序列化")

            store.saver.aget_tuple = must_not_deserialize
            with pytest.raises(TaskRuntimeError) as rejected:
                await saver.aget_tuple(saver.config)
            assert rejected.value.code == TaskRuntimeCode.CHECKPOINT_INVALID

    asyncio.run(scenario())


def test_central_success_survives_later_graph_checkpoint_failure():
    async def scenario():
        async with real_task() as task:
            worker = await task.client()
            lease = await worker.claim()
            calls = []

            def build(saver):
                async def final_node(state):
                    calls.append("executed")
                    # 模拟中央已提交而图还尝试收尾；后续 PG 租约检查必然失败。
                    await worker.complete(task.task_id, lease.epoch, {"answer": "中央已提交"})
                    return {"answer": "图收尾"}

                graph = StateGraph(SampleState)
                graph.add_node("final", final_node)
                graph.add_edge(START, "final")
                graph.add_edge("final", END)
                return graph.compile(checkpointer=saver)

            result = await run_claimed_graph(lease, worker, task.config, build)
            assert result.status == TaskStatus.SUCCEEDED
            assert result.result == {"answer": "中央已提交"} and calls == ["executed"]
            replay = await run_claimed_graph(lease, worker, task.config, build)
            assert replay == result and calls == ["executed"]

    asyncio.run(scenario())


def test_completed_graph_checkpoint_resumes_without_any_node_reexecution():
    async def scenario():
        async with real_task() as task:
            worker = await task.client()
            lease = await worker.claim()
            store = await task.storage()
            saver = FencedPostgresSaver(lease, worker, store)
            calls = []
            await sample_graph(saver, calls).ainvoke(lease.input, saver.config, durability="sync")
            assert (await worker.inspect(task.task_id)).task.status == TaskStatus.RUNNING
            await task.expire()
            next_worker = await task.client()
            resumed = await next_worker.claim()
            result = await run_claimed_graph(
                resumed, next_worker, task.config, lambda cp: sample_graph(cp, calls)
            )
            assert result.status == TaskStatus.SUCCEEDED and calls == ["retrieve", "answer"]

    asyncio.run(scenario())


class UnapprovedObject(BaseModel):
    """严格序列化器不允许将任意应用类从数据库反射构造。"""

    value: str


def test_thread_tenant_namespace_size_and_serializer_fences():
    async def scenario():
        async with real_task() as task:
            worker = await task.client()
            lease = await worker.claim()
            store = await task.storage()
            saver = FencedPostgresSaver(lease, worker, store)
            await sample_graph(saver, [], pause=True).ainvoke(
                lease.input, saver.config, durability="sync"
            )
            saved = await saver.aget_tuple(saver.config)
            bad_configs = [
                {"configurable": {"thread_id": str(uuid4()) + ":1"}},
                {"configurable": {"thread_id": lease.physical_thread, "checkpoint_ns": "other"}},
                {
                    "configurable": {
                        "thread_id": lease.physical_thread,
                        "checkpoint_id": str(uuid4()),
                    }
                },
            ]
            for bad_config in bad_configs:
                with pytest.raises(TaskRuntimeError) as rejected:
                    await saver.aget_tuple(bad_config)
                assert rejected.value.code == TaskRuntimeCode.PROTOCOL_INVALID
            with pytest.raises(TaskRuntimeError):
                FencedPostgresSaver(lease.model_copy(update={"tenant_id": "other"}), worker, store)
            forged = FencedPostgresSaver(
                lease.model_copy(update={"owner_id": "other-owner"}), worker, store
            )
            with pytest.raises(TaskRuntimeError) as rejected:
                await forged.aget_tuple(forged.config)
            assert rejected.value.code == TaskRuntimeCode.LEASE_LOST
            with pytest.raises(TaskRuntimeError) as rejected:
                await saver.adelete_thread(lease.physical_thread)
            assert rejected.value.code == TaskRuntimeCode.UNSUPPORTED
            for value in ("x" * MAX_SNAPSHOT_BYTES, UnapprovedObject(value="blocked")):
                checkpoint = copy.deepcopy(saved.checkpoint)
                checkpoint["id"] = str(uuid4())
                checkpoint["channel_values"]["answer"] = value
                with pytest.raises(TaskRuntimeError) as rejected:
                    await saver.aput(saved.config, checkpoint, saved.metadata, {})
                assert rejected.value.code == TaskRuntimeCode.CHECKPOINT_INVALID
            for writes in (
                [("answer", "small")] * 101,
                [("answer", "x" * MAX_SNAPSHOT_BYTES)],
                [("answer", UnapprovedObject(value="blocked"))],
            ):
                with pytest.raises(TaskRuntimeError) as rejected:
                    await saver.aput_writes(saved.config, writes, str(uuid4()))
                assert rejected.value.code == TaskRuntimeCode.CHECKPOINT_INVALID
            # 本项目不读取旧 JSON 或 pickle，非法编码在进入原生解码前拒绝。
            for encoding in ("pickle", "json"):
                with pytest.raises(TaskRuntimeError) as rejected:
                    store.serde.loads_typed((encoding, b"invalid"))
                assert rejected.value.code == TaskRuntimeCode.CHECKPOINT_INVALID
            assert (
                await worker.inspect(task.task_id)
            ).checkpoint.checkpoint_id == saved.checkpoint["id"]

    asyncio.run(scenario())


def test_real_pool_limits_timeouts_and_cancelled_waiters_release_capacity():
    async def scenario():
        async with real_task() as task:
            config = task.config.model_copy(update={"max_connections": 1, "acquire_seconds": 0.1})
            async with PostgresCheckpoints.open(config) as store:
                held = await store.pool.getconn()
                waiting = [asyncio.create_task(store.pool.getconn()) for _ in range(4)]
                await asyncio.sleep(0.02)
                try:
                    assert store.pool.get_stats()["requests_waiting"] == 4
                    with pytest.raises(TooManyRequests):
                        await store.pool.getconn()
                    waiting[0].cancel()
                    outcomes = await asyncio.gather(*waiting, return_exceptions=True)
                    assert isinstance(outcomes[0], asyncio.CancelledError)
                    assert all(isinstance(item, PoolTimeout) for item in outcomes[1:])
                finally:
                    await store.pool.putconn(held)
                async with store.pool.connection() as connection:
                    assert (await (await connection.execute("SELECT 1 AS ok")).fetchone())[
                        "ok"
                    ] == 1
                    with pytest.raises(QueryCanceled):
                        await connection.execute("SELECT pg_sleep(4)")
                assert store.pool.get_stats()["pool_size"] == 1

    asyncio.run(scenario())


def test_project_database_grants_comments_and_migration_replay():
    async def scenario():
        async with real_task() as task:
            import checkpoint_local

            store = await task.storage()
            async with store.pool.connection() as connection:
                cursor = await connection.execute("""
                    SELECT has_schema_privilege(current_user,'public','CREATE') AS ddl,
                    has_table_privilege(current_user,'checkpoints','DELETE') AS delete_cp,
                    has_table_privilege(current_user,'checkpoint_blobs','DELETE') AS delete_blob,
                    has_database_privilege(current_user,'agentic_checkpoints','CONNECT') AS other_db
                """)
                assert not any((await cursor.fetchone()).values())
                cursor = await connection.execute("""
                    SELECT c.relname,obj_description(c.oid,'pg_class') AS description,
                    count(*) FILTER (WHERE col_description(c.oid,a.attnum) IS NULL) AS missing
                    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    JOIN pg_attribute a ON a.attrelid=c.oid
                    WHERE n.nspname='public' AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped
                    GROUP BY c.oid,c.relname
                """)
                tables = await cursor.fetchall()
                assert len(tables) == 5
                assert all(row["description"] and row["missing"] == 0 for row in tables)
                before = await (
                    await connection.execute("SELECT * FROM runtime_schema_migrations")
                ).fetchall()
            await checkpoint_local.migrate("test")
            await checkpoint_local.migrate("test")
            async with store.pool.connection() as connection:
                after = await (
                    await connection.execute("SELECT * FROM runtime_schema_migrations")
                ).fetchall()
                assert before == after and len(after) == 1
                row = await (
                    await connection.execute("SELECT max(v) AS version FROM checkpoint_migrations")
                ).fetchone()
                assert row["version"] == 9

    asyncio.run(scenario())
