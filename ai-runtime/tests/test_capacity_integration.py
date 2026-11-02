"""S16C：明确启用的隔离容量实验；样本/阈值是实验条件，不是生产SLO。"""

import asyncio
import json
import math
import os
import re
import socket
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from uuid import uuid4

import httpx
import jwt
import pytest

from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError

ROOT = Path(__file__).resolve().parents[2]
pytestmark = pytest.mark.skipif(
    not os.environ.get("AGENTIC_CAPACITY_IT"), reason="仅deploy/capacity_validation.py显式隔离运行"
)
OWNER = "capacity-owner"
QUERY = "授权候选检索资料"
STRATEGY = "LLAMA_RRF_V1"


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def percentiles(values):
    ordered = sorted(values)
    return {
        "samples": len(ordered),
        **{
            label: round(ordered[max(0, math.ceil(len(ordered) * q) - 1)], 3) if ordered else None
            for label, q in (("p50Ms", 0.5), ("p95Ms", 0.95), ("p99Ms", 0.99))
        },
    }


class Lab:
    """只管理本轮创建的进程/数据库；故障不指向原Docker或共享组件的管理接口。"""

    def __init__(self):
        sys.path.insert(0, str(ROOT / "deploy"))
        import local

        self.local = local
        self.record = json.loads(Path(os.environ["AGENTIC_CAPACITY_IT"]).read_text())
        self.directory = Path(self.record["directory"])
        self.databases = self.record["databases"]
        assert all(
            re.fullmatch(r"agentic_(knowledge|tasks)_test_s16c_[a-f0-9]{8}", d)
            for d in self.databases.values()
        )
        self.tenants = ["s16c-" + self.record["runId"] + suffix for suffix in ("-a", "-b")]
        self.children, self.workers, self.logs = [], {}, []
        self.samples, self.resources, self.accepted, self.faults = [], [], [], {}
        self.phase = "startup"
        self.environment = dict(os.environ)
        k_port, t_port, r_port, k_metrics, t_metrics = (free_port() for _ in range(5))
        self.config = {
            "directory": str(self.directory),
            "runtimePort": r_port,
            "knowledgeEndpoint": f"http://127.0.0.1:{k_port}",
            "taskEndpoint": f"http://127.0.0.1:{t_port}",
            "indexes": {
                "qdrant": self.environment.get(
                    "KNOWLEDGE_QDRANT_ENDPOINT", "http://127.0.0.1:26333"
                ),
                "elastic": self.environment.get(
                    "KNOWLEDGE_ELASTIC_ENDPOINT", "http://127.0.0.1:29200"
                ),
            },
        }
        assert all(
            re.match(r"http://(127\.0\.0\.1|localhost):\d+$", url)
            for url in self.config["indexes"].values()
        )
        self.manifest = self.directory / "manifest.json"
        self.local.private(self.manifest, json.dumps(self.config))
        self.metrics = {
            "knowledge": f"http://127.0.0.1:{k_metrics}/actuator/prometheus",
            "tasks": f"http://127.0.0.1:{t_metrics}/actuator/prometheus",
        }
        self.environment.update(
            KNOWLEDGE_PORT=str(k_port),
            TASK_PORT=str(t_port),
            KNOWLEDGE_MANAGEMENT_PORT=str(k_metrics),
            TASK_MANAGEMENT_PORT=str(t_metrics),
            KNOWLEDGE_AI_ENDPOINT=f"http://127.0.0.1:{r_port}",
            KNOWLEDGE_QDRANT_ENDPOINT=f"http://127.0.0.1:{r_port}",
            KNOWLEDGE_ELASTIC_ENDPOINT=f"http://127.0.0.1:{r_port}",
            KNOWLEDGE_PROCESSING_ENABLED="true",
            KNOWLEDGE_WORKER_ENABLED="true",
            KNOWLEDGE_WORKER_TENANT="",
            KNOWLEDGE_POLL_SECONDS="1",
            KNOWLEDGE_AI_SECONDS="4",
            KNOWLEDGE_INDEX_SECONDS="2",
            KNOWLEDGE_LIFECYCLE_GENERATION_ENABLED="true",
            KNOWLEDGE_RETRIEVAL_ENABLED="true",
            KNOWLEDGE_RUNTIME_ENABLED="true",
            KNOWLEDGE_EVALUATION_WORKER_ENABLED="false",
            TASK_GENERATION_ENABLED="true",
            TASK_RESEARCH_ENABLED="true",
            TASK_RESEARCH_STAGED_ENABLED="true",
            TASK_LEASE_SECONDS="15",
            TASK_KNOWLEDGE_READER_ENABLED="true",
            TASK_KNOWLEDGE_READER_ENDPOINT=self.config["knowledgeEndpoint"],
            KNOWLEDGE_DB_POOL_SIZE="6",
            TASK_DB_POOL_SIZE="6",
        )
        policy = {
            "knowledge.capacity.enabled": True,
            "knowledge.capacity.max-ingestions": 2,
            "knowledge.capacity.max-searches": 2,
            "knowledge.capacity.max-candidates": 4,
            "task.capacity.enabled": True,
            "task.capacity.max-interactive": 2,
            "task.capacity.max-background": 2,
        }
        for tenant in self.tenants:
            for key, value in {
                "ingestions": 1,
                "searches": 1,
                "candidates": 2,
                "queued-ingestions": 6,
            }.items():
                policy[f"knowledge.capacity.tenants.{tenant}.{key}"] = value
            for key, value in {"interactive": 1, "background": 1, "queued-per-lane": 4}.items():
                policy[f"task.capacity.tenants.{tenant}.{key}"] = value
        self.environment["SPRING_APPLICATION_JSON"] = json.dumps(policy)
        self.documents = {tenant: [] for tenant in self.tenants}

    def spawn(self, name, args, env=None):
        log = (self.directory / (name + ".log")).open("w")
        self.logs.append(log)
        process = subprocess.Popen(
            args, cwd=ROOT, env=env or self.environment, stdout=log, stderr=subprocess.STDOUT
        )
        self.children.append((name, process))
        self.local.private(
            self.directory / "processes.json",
            json.dumps([{"name": n, "pid": p.pid, "exitCode": p.poll()} for n, p in self.children]),
        )
        return process

    async def start(self):
        probe = ROOT / "ai-runtime/tests/capacity_probe.py"
        # 模型代理配置和外部API密钥不传给替身进程。
        safe_env = {
            k: self.environment[k] for k in ("PATH", "LANGSMITH_TRACING", "LANGCHAIN_TRACING_V2")
        }
        self.spawn("runtime", [sys.executable, str(probe), str(self.manifest)], safe_env)
        await self.ready(f"http://127.0.0.1:{self.config['runtimePort']}/openapi.json", 200)
        for name, module in (("knowledge", "knowledge-service"), ("tasks", "async-task-service")):
            self.spawn(
                name,
                [
                    "java",
                    "-Xmx384m",
                    "-jar",
                    str(ROOT / module / "target" / (module + "-0.1.0-SNAPSHOT.jar")),
                ],
            )
        await self.ready(self.config["knowledgeEndpoint"] + "/api/v1/knowledge/documents", 401)
        await self.ready(self.config["taskEndpoint"] + "/api/v1/tasks", 401)
        for tenant in self.tenants:
            self.start_worker(tenant)

    def start_worker(self, tenant):
        safe_env = {
            k: self.environment[k] for k in ("PATH", "LANGSMITH_TRACING", "LANGCHAIN_TRACING_V2")
        }
        self.workers[tenant] = self.spawn(
            tenant + "-worker-" + uuid4().hex[:4],
            [
                sys.executable,
                str(ROOT / "ai-runtime/tests/capacity_probe.py"),
                str(self.manifest),
                "--tenant",
                tenant,
            ],
            safe_env,
        )

    async def ready(self, url, status):
        async with asyncio.timeout(60), httpx.AsyncClient(trust_env=False, timeout=1) as http:
            while True:
                assert all(p.poll() is None for _, p in self.children), (
                    "本轮进程退出，见processes/log"
                )
                try:
                    if (await http.get(url)).status_code == status:
                        return
                except httpx.HTTPError:
                    pass
                await asyncio.sleep(0.2)

    def token(self, tenant, task=False):
        now = int(time.time())
        return jwt.encode(
            {
                "iss": "urn:agentic-ai:edge",
                "aud": "agentic-ai-tasks" if task else "agentic-ai-knowledge",
                "sub": OWNER,
                "tenant_id": tenant,
                "scope": "task:read task:write" if task else "knowledge:read knowledge:write",
                "iat": now,
                "nbf": now,
                "exp": now + 60,
            },
            (ROOT / ".local/internal-private.pem").read_bytes(),
            algorithm="RS256",
        )

    async def request(self, tenant, method, route, *, task=False, **kwargs):
        headers = kwargs.pop("headers", {})
        headers.setdefault("X-Internal-Token", self.token(tenant, task))
        return await self.http.request(
            method,
            self.config["taskEndpoint" if task else "knowledgeEndpoint"] + route,
            headers=headers,
            **kwargs,
        )

    async def submit(self, tenant, kind, size=128):
        import task_smoke
        from test_qa_pipeline import question

        start, key = time.time(), str(uuid4())
        if kind == "upload":
            response = await self.request(
                tenant,
                "POST",
                "/api/v1/knowledge/ingestions",
                headers={"Idempotency-Key": key},
                files={
                    "file": (
                        "capacity.txt",
                        (QUERY + key + "。原文资料。" * size).encode(),
                        "text/plain",
                    )
                },
            )
        elif kind == "qa":
            response = await self.request(
                tenant,
                "POST",
                "/internal/v1/tasks",
                task=True,
                headers={
                    "X-Internal-Token": task_smoke.service_token("submit", tenant, OWNER),
                    "Idempotency-Key": key,
                },
                json={
                    "taskId": key,
                    "kind": "KNOWLEDGE_QA",
                    "runtimeVersion": "langgraph-v1",
                    "graphVersion": "knowledge-qa-v2",
                    "input": question(
                        schemaVersion="knowledge-qa-input-v2",
                        question=QUERY,
                        documentIds=self.documents[tenant],
                        retrievalStrategy=STRATEGY,
                    ),
                },
            )
        else:
            response = await self.request(
                tenant,
                "POST",
                "/api/v4/tasks",
                task=True,
                headers={"Idempotency-Key": key},
                json={
                    "taskId": key,
                    "kind": "RESEARCH",
                    "mode": "REPORT",
                    "question": QUERY,
                    "documentIds": self.documents[tenant],
                    "retrievalStrategy": STRATEGY,
                },
            )
        self.samples.append(
            {
                "phase": self.phase,
                "tenant": tenant,
                "kind": kind,
                "stage": "admission",
                "status": response.status_code,
                "start": start,
                "ms": (time.time() - start) * 1000,
            }
        )
        assert response.status_code in (202, 429), (kind, response.status_code, response.text)
        if response.status_code == 429:
            return None
        data = response.json()
        item = {
            "tenant": tenant,
            "kind": kind,
            "id": data["jobId"] if kind == "upload" else key,
            "start": start,
            "phase": self.phase,
            "data": data,
        }
        self.accepted.append(item)
        return item

    async def search(self, tenant, *, expected=(200, 429)):
        start = time.time()
        response = await self.request(
            tenant,
            "POST",
            "/api/v2/knowledge/search",
            json={
                "schemaVersion": "knowledge-search-input-v2",
                "query": QUERY,
                "documentIds": self.documents[tenant],
                "topK": 5,
                "strategy": STRATEGY,
            },
        )
        self.samples.append(
            {
                "phase": self.phase,
                "tenant": tenant,
                "kind": "search",
                "stage": "completion",
                "status": response.status_code,
                "start": start,
                "ms": (time.time() - start) * 1000,
            }
        )
        assert response.status_code in expected, (response.status_code, response.text)
        if response.status_code == 200:
            body = response.json()
            assert body["mode"] == "RRF" and len(body["hits"]) == 2
            assert {h["identity"]["documentId"] for h in body["hits"]} == set(
                self.documents[tenant]
            )
        return response

    async def finished(self, item, expected="SUCCEEDED"):
        async with asyncio.timeout(90):
            while True:
                task = item["kind"] != "upload"
                route = ("/api/v1/tasks/" if task else "/api/v1/knowledge/ingestions/") + item["id"]
                response = await self.request(item["tenant"], "GET", route, task=task)
                assert response.status_code == 200, response.text
                value = response.json()
                if value["status"] in ("SUCCEEDED", "FAILED", "READY", "CANCELLED"):
                    assert value["status"] == expected, value
                    item["final"] = value
                    self.samples.append(
                        {
                            "phase": item["phase"],
                            "tenant": item["tenant"],
                            "kind": item["kind"],
                            "stage": "completion",
                            "status": expected,
                            "start": item["start"],
                            "ms": (time.time() - item["start"]) * 1000,
                        }
                    )
                    if task and expected == "SUCCEEDED":
                        assert value["result"]["references"]
                        assert all(
                            ref["documentId"] in self.documents[item["tenant"]]
                            for ref in value["result"]["references"]
                        )
                    return value
                await asyncio.sleep(0.1)

    async def drain(self, items):
        await asyncio.gather(
            *(
                self.finished(x, "READY" if x["kind"] == "upload" else "SUCCEEDED")
                for x in items
                if x
            )
        )

    async def observe(self):
        """采样不触及正文；真实指标和租约计数用于证明资源边界而非估算吞吐。"""
        while True:
            sample = {"at": time.time(), "phase": self.phase, "metrics": {}, "processes": []}
            for name, url in self.metrics.items():
                response = await self.http.get(url)
                assert response.status_code == 200
                chosen = {}
                for line in response.text.splitlines():
                    if line.startswith(
                        (
                            "hikaricp_connections_",
                            "jvm_threads_live_threads ",
                            "jvm_memory_used_bytes{",
                            "process_cpu_usage ",
                        )
                    ):
                        key, value = line.rsplit(" ", 1)
                        chosen[key] = float(value)
                sample["metrics"][name] = chosen
            pids = [str(p.pid) for _, p in self.children if p.poll() is None]
            output = await asyncio.to_thread(
                subprocess.check_output,
                ["ps", "-o", "pid=,rss=,%cpu=", "-p", ",".join(pids)],
                text=True,
            )
            sample["processes"] = [line.split() for line in output.splitlines()]
            k, t = self.databases["knowledge"], self.databases["tasks"]
            counts = await asyncio.to_thread(
                self.local.mysql,
                f"SELECT 'ingestion',tenant_id,COUNT(*) FROM {k}.knowledge_ingestion "
                "WHERE status='PROCESSING' AND lease_until>UTC_TIMESTAMP(6) GROUP BY tenant_id;"
                f"SELECT kind,tenant_id,COUNT(*) FROM {t}.ai_task "
                "WHERE status='RUNNING' AND lease_expires_at>UTC_TIMESTAMP(6) "
                "GROUP BY kind,tenant_id;",
            )
            sample["activeLeases"] = [line.split("\t") for line in counts.splitlines()]
            assert all(int(row[2]) <= 1 for row in sample["activeLeases"]), sample["activeLeases"]
            self.resources.append(sample)
            await asyncio.sleep(0.4)

    async def mixed(self, phase, rounds):
        self.phase = phase
        for _ in range(rounds):
            items = await asyncio.gather(
                *(
                    self.submit(tenant, kind, size=32 + 32 * (_ % 3))
                    for tenant in self.tenants
                    for kind in ("upload", "qa", "research")
                ),
                *(self.search(tenant, expected=(200,)) for tenant in self.tenants),
            )
            assert all(item is not None for item in items), "常规及恢复负载不得意外拒绝"
            await self.drain([i for i in items if isinstance(i, dict)])

    async def retry(self, item):
        response = await self.request(
            item["tenant"],
            "POST",
            f"/api/v1/knowledge/ingestions/{item['id']}/retry",
            json={"expectedRevision": item["final"]["revision"]},
        )
        assert response.status_code == 202, response.text
        await self.finished(item, "READY")

    async def faults_run(self):
        a, b = self.tenants
        self.phase = "database-slow"
        k = self.databases["knowledge"]
        lock_name = "s16c-" + self.record["runId"]
        slow = asyncio.create_task(
            asyncio.to_thread(
                self.local.mysql,
                "START TRANSACTION; "
                f"SELECT policy_key FROM {k}.knowledge_capacity_policy FOR UPDATE;"
                f"SELECT GET_LOCK('{lock_name}',0); SELECT SLEEP(2); COMMIT; "
                f"SELECT RELEASE_LOCK('{lock_name}');",
            )
        )
        async with asyncio.timeout(5):
            while (  # noqa: ASYNC110 -- 轮询另一个真实数据库连接的闸门
                await asyncio.to_thread(self.local.mysql, f"SELECT IS_USED_LOCK('{lock_name}')")
            ).strip() == "NULL":
                await asyncio.sleep(0.03)
        start = time.monotonic()
        item, _ = await asyncio.gather(self.submit(a, "upload"), self.search(b))
        delay = time.monotonic() - start
        await slow
        assert delay >= 0.5, "必须实测锁等待，不能只声明注入"
        await self.drain([item])
        self.faults["databaseSlow"] = {"PASS": True, "observedMs": delay * 1000}
        for marker, phase in (
            ("index-offline", "index-unavailable"),
            ("model-timeout", "model-timeout"),
        ):
            self.phase = phase
            flag = self.directory / marker
            flag.write_text("enabled")
            try:
                response = await self.search(a, expected=(503, 504))
                item = await self.submit(a, "upload")
                value = await self.finished(item, "FAILED")
                assert value["retryable"]
                document = await self.request(
                    a, "GET", "/api/v2/knowledge/documents/" + value["documentId"]
                )
                assert (
                    document.status_code == 200 and document.json()["publishedGeneration"] is None
                )
                self.faults[phase] = {
                    "PASS": True,
                    "searchStatus": response.status_code,
                    "errorCode": value["errorCode"],
                }
            finally:
                flag.unlink()
            await self.retry(item)
            await self.search(a, expected=(200,))
        self.phase = "worker-exit"
        pause = self.directory / (a + "-pause")
        pause.write_text("enabled")
        item = await self.submit(a, "research")
        paused = self.directory / (a + "-paused.json")
        async with asyncio.timeout(30):
            while not paused.exists():
                assert self.workers[a].poll() is None
                await asyncio.sleep(0.05)
        old = json.loads(paused.read_text())
        assert old["taskId"] == item["id"]
        self.workers[a].terminate()
        await asyncio.to_thread(self.workers[a].wait, 10)
        pause.unlink()
        start = time.monotonic()
        self.start_worker(a)
        # 接管等待期间，另一个租户仍能运行四类业务。
        self.phase = "during-recovery"
        jobs = await asyncio.gather(
            *(self.submit(b, kind) for kind in ("upload", "qa", "research"))
        )
        await self.search(b, expected=(200,))
        await self.drain(jobs)
        result = await self.finished(item)
        assert result["attemptCount"] == 2
        assert result["result"]["content"]["usage"]["modelCalls"] == 2
        old_client = CentralTaskClient.from_file(
            self.config["taskEndpoint"], a, old["worker"], ROOT / ".local/task-worker-private.pem"
        )
        try:
            with pytest.raises(TaskRuntimeError) as failure:
                await old_client.heartbeat(item["id"], old["epoch"])
            assert failure.value.code == TaskRuntimeCode.LEASE_LOST
        finally:
            await old_client.aclose()
        self.faults["workerExit"] = {
            "PASS": True,
            "recoveryMs": (time.monotonic() - start) * 1000,
            "attempts": result["attemptCount"],
            "modelCalls": 2,
        }

    def save(self, passed):
        self.local.private(
            self.directory / "measurements.json",
            json.dumps(
                {
                    "passed": passed,
                    "samples": self.samples,
                    "resources": self.resources,
                    "accepted": self.accepted,
                    "faults": self.faults,
                },
                ensure_ascii=False,
                indent=2,
            ),
        )

    async def close(self):
        (self.directory / "workers-stop").write_text("stop")
        for _, process in reversed(self.children):
            if process.poll() is None:
                process.terminate()
                await asyncio.to_thread(process.wait, 15)
        for log in self.logs:
            log.close()
        self.local.private(
            self.directory / "processes.json",
            json.dumps([{"name": n, "pid": p.pid, "exitCode": p.poll()} for n, p in self.children]),
        )


def test_mixed_load_and_fault_recovery_have_measured_bounded_outcomes():
    async def scenario():
        lab, observer, passed = Lab(), None, False
        try:
            await lab.start()
            async with httpx.AsyncClient(
                trust_env=False, timeout=40, limits=httpx.Limits(max_connections=32)
            ) as lab.http:
                lab.phase = "seed"
                for tenant in lab.tenants:
                    for _ in range(2):
                        item = await lab.submit(tenant, "upload", size=8)
                        await lab.finished(item, "READY")
                        lab.documents[tenant].append(item["data"]["documentId"])
                        lab.documents[tenant].sort()
                observer = asyncio.create_task(lab.observe())
                await lab.mixed("warmup", 1)
                await lab.mixed("steady", 4)
                lab.phase = "burst"
                items = await asyncio.gather(
                    *(
                        lab.submit(tenant, kind, size=96)
                        for tenant, number in ((lab.tenants[0], 12), (lab.tenants[1], 2))
                        for _ in range(number)
                        for kind in ("upload", "qa", "research")
                    ),
                    *(lab.search(lab.tenants[i % 2]) for i in range(12)),
                )
                assert any(s["phase"] == "burst" and s["status"] == 429 for s in lab.samples)
                await lab.drain([i for i in items if isinstance(i, dict)])
                await lab.faults_run()
                await lab.mixed("recovered", 2)
                assert not observer.done(), "资源采样提前失败"
                observer.cancel()
                await asyncio.gather(observer, return_exceptions=True)
                assert len(lab.resources) >= 10
                assert all(
                    "final" in item and item["final"]["status"] in ("READY", "SUCCEEDED")
                    for item in lab.accepted
                )
                # 从持久表核对全量本轮受理数量，而非只凭HTTP回执判断没有丢失。
                for domain, table, kind, status in (
                    ("knowledge", "knowledge_ingestion", "upload", "READY"),
                    ("tasks", "ai_task", "task", "SUCCEEDED"),
                ):
                    count = int(
                        await asyncio.to_thread(
                            lab.local.mysql,
                            f"SELECT COUNT(*) FROM {lab.databases[domain]}.{table} "
                            f"WHERE status='{status}'",
                        )
                    )
                    expected = sum(
                        (x["kind"] == "upload") == (kind == "upload") for x in lab.accepted
                    )
                    assert count == expected
                    total = int(
                        await asyncio.to_thread(
                            lab.local.mysql, f"SELECT COUNT(*) FROM {lab.databases[domain]}.{table}"
                        )
                    )
                    assert total == expected, "拒绝不能产生额外任务，不能留下未核对状态"
                slots = int(
                    await asyncio.to_thread(
                        lab.local.mysql,
                        f"SELECT COUNT(*) FROM {lab.databases['knowledge']}."
                        "knowledge_capacity_slot "
                        "WHERE lease_token IS NOT NULL",
                    )
                )
                assert slots == 0, "恢复后在线许可必须全部释放"
                for sample in lab.resources:
                    for metrics in sample["metrics"].values():
                        assert all(
                            value <= 6
                            for key, value in metrics.items()
                            if key.startswith("hikaricp_connections_active{")
                        )
                events = [
                    json.loads(line)
                    for path in lab.directory.glob("worker-*.jsonl")
                    for line in path.read_text().splitlines()
                ]
                queues = [
                    (e["at"] - datetime.fromisoformat(e["createdAt"]).timestamp()) * 1000
                    for e in events
                    if e["type"] == "claimed" and e["epoch"] == 1
                ]
                summary = {
                    "status": "PASS",
                    "faults": lab.faults,
                    "queue": percentiles(queues),
                    "resourceSamples": len(lab.resources),
                    "accepted": len(lab.accepted),
                    "paidCalls": 0,
                    "measurements": str(lab.directory / "measurements.json"),
                }
                lab.local.private(lab.directory / "summary.json", json.dumps(summary, indent=2))
                passed = True
        finally:
            if observer:
                observer.cancel()
                await asyncio.gather(observer, return_exceptions=True)
            lab.save(passed)
            await lab.close()

    asyncio.run(scenario())
