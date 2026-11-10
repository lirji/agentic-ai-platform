"""显式授权的隔离真实评测；复用数据库、生产解析/检索/QA与中央预算。"""

from __future__ import annotations

import argparse
import asyncio
import fcntl
import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import time
from contextlib import AsyncExitStack
from decimal import ROUND_CEILING, Decimal

import ai_local
import checkpoint_local
import httpx
import local
from quality_security import (
    MAX_CHAT_BYTES,
    MAX_CHAT_CALLS,
    MAX_OUTPUT_TOKENS,
    OUT,
    ROOT,
    digest,
    freeze,
    questions_for,
    read_json,
    stable_id,
    variants,
    write_once,
)

WORK = ROOT / ".local/quality-security-20261109"
LOGS = ROOT / ".logs/quality-security-20261109"
TENANT = "quality-security-20261109"
OWNER = "engineering-reviewer"
WORKER = "quality-security-worker"
STRATEGY = "LLAMA_RRF_V1"
EVAL = "/api/v1/knowledge/evaluations"
ADMIN_COMMAND_TIMEOUT_SECONDS = 70


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def ledgers():
    return {
        kind: hashlib.sha256(
            (ROOT / f".local/{kind}-budget-20261020.json").read_bytes()
        ).hexdigest()
        for kind in ("chat", "embedding")
    }


class Runtime:
    """只启动本轮进程，退出回收其PID；隔离数据和原服务全部保留。"""

    @property
    def output(self):
        return OUT

    @property
    def state_file(self):
        return WORK / "state.json"

    @property
    def log_directory(self):
        return LOGS

    @property
    def max_total_chat_calls(self):
        return MAX_CHAT_CALLS

    @property
    def baseline_note(self):
        return "固定工程文档首轮观测基线；仅满足既有机制门槛，不是生产质量或安全认证。"

    @property
    def baseline_labels(self):
        return ("quality",)

    def evaluation_key(self, name):
        """仅评测命令可分命名空间，资金登记始终保留原固定身份。"""
        return stable_id(name)

    def initial_state(self):
        """首轮状态；复测子类只复用原资料/账户，不借新状态重建资金。"""
        return {
            "schemaVersion": "quality-run-state-v1",
            "frozenDigest": digest(self.frozen),
            "tenant": TENANT,
            "documents": {},
            "datasets": {},
            "runs": {},
            "approvedBudget": {"CHAT": "6.000000", "EMBEDDING": "2.000000"},
            "originalLedgers": ledgers(),
        }

    def proxy_environment(self):
        """默认沿原代理配置，隔离复测可显式验证同提供方的传输适配。"""
        import chat_local

        return chat_local.environment(proxy=True)

    def prepare_supplemental_funding(self):
        """原基线不追加资金；后续获批轮次可在原账户之外登记独立授权。"""

    def usage_profiles(self):
        """价格与模型沿固定配置，复测仅可显式选择已登记的资金绑定。"""
        return {
            kind: {"profileId": "qs-20261109-" + kind.lower(), "model": model}
            for kind, model in (
                ("CHAT", "deepseek-flash"),
                ("EMBEDDING", "text-embedding-v4"),
            )
        }

    def __init__(self):
        WORK.mkdir(mode=0o700, exist_ok=True)
        self.lock = os.open(WORK / "runtime.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.log_directory.mkdir(exist_ok=True)
        self.frozen = freeze(read_json(self.output / "campaign.json"))
        if self.frozen != read_json(self.output / "frozen-corpus.json"):
            raise ValueError("FROZEN_CORPUS_DRIFT")
        self.path = self.state_file
        self.state = read_json(self.path) if self.path.exists() else self.initial_state()
        if self.state["frozenDigest"] != digest(self.frozen) or self.state["tenant"] != TENANT:
            raise ValueError("STATE_SCOPE_DRIFT")
        self.children, self.logs = [], []
        for previous in self.state.get("processes", []):
            try:
                os.kill(previous["pid"], 0)
            except ProcessLookupError:
                continue
            raise RuntimeError("PREVIOUS_PROCESS_NEEDS_RECONCILIATION")
        self.env = local.environment("test")
        self.ai_env = ai_local.environment()
        self.ports = {name: free_port() for name in ("knowledge", "tasks", "ai", "proxy")}
        self.endpoints = {name: f"http://127.0.0.1:{port}" for name, port in self.ports.items()}
        self.save()

    def save(self):
        local.private(self.path, json.dumps(self.state, ensure_ascii=False, indent=2) + "\n")

    def start(self, name, command, env=None):
        log = (self.log_directory / (name + ".log")).open("a")
        self.logs.append(log)
        process = subprocess.Popen(
            command, cwd=ROOT, env=env or self.env, stdout=log, stderr=subprocess.STDOUT
        )
        self.children.append((name, process))
        self.state["processes"] = [{"name": n, "pid": p.pid} for n, p in self.children]
        self.save()
        return process

    def wait(self, name, path, status):
        process = dict(self.children)[name]
        end = time.monotonic() + 70
        with httpx.Client(trust_env=False, timeout=2) as http:
            while time.monotonic() < end:
                if process.poll() is not None:
                    raise RuntimeError("SERVICE_EXITED:" + name)
                try:
                    if http.get(self.endpoints[name] + path).status_code == status:
                        return
                except httpx.HTTPError:
                    pass
                time.sleep(0.25)
        raise RuntimeError("SERVICE_START_TIMEOUT:" + name)

    def __enter__(self):
        try:
            self.prepare()
            return self
        except BaseException:
            self.__exit__(*sys.exc_info())
            raise

    def __exit__(self, *_):
        for _, process in reversed(self.children):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        for log in self.logs:
            log.close()
        self.state["processes"] = []
        self.state["lastLedgerCheck"] = ledgers()
        self.save()
        os.close(self.lock)
        if self.state["lastLedgerCheck"] != self.state["originalLedgers"]:
            raise RuntimeError("ORIGINAL_LEDGER_CHANGED")

    def prepare(self):
        """固定库名与资金来源防止恢复时重建另一套额度；应用用户仍无DDL权。"""
        import chat_local
        import task_local

        for kind, config, prefix in (
            ("knowledge", local.load(), "KNOWLEDGE"),
            ("tasks", task_local.load(), "TASK"),
        ):
            resource = config["resources"]["test"]
            database = f"agentic_{kind}_test_qs20261109"
            names = [database, resource["app"], resource["migrator"]]
            if not all(re.fullmatch(r"[A-Za-z0-9_]+", n) for n in names):
                raise ValueError("DATABASE_IDENTITY_INVALID")
            local.mysql(
                f"CREATE DATABASE IF NOT EXISTS `{database}` CHARACTER SET utf8mb4;"
                f"GRANT SELECT,INSERT,UPDATE,DELETE ON `{database}`.* TO '{resource['app']}'@'%';"
                f"GRANT ALL PRIVILEGES ON `{database}`.* TO '{resource['migrator']}'@'%';"
            )
            original = self.env[prefix + "_DB_URL"]
            self.env[prefix + "_DB_URL"] = re.sub(r"/[^/?]+\?", "/" + database + "?", original)
        self.env.update(
            TASK_PORT=str(self.ports["tasks"]),
            TASK_MANAGEMENT_PORT="0",
            TASK_GENERATION_ENABLED="true",
            TASK_KNOWLEDGE_READER_ENDPOINT=self.endpoints["knowledge"],
            KNOWLEDGE_PORT=str(self.ports["knowledge"]),
            KNOWLEDGE_MANAGEMENT_PORT="0",
            KNOWLEDGE_AI_ENDPOINT=self.endpoints["ai"],
            KNOWLEDGE_PROCESSING_ENABLED="true",
            KNOWLEDGE_WORKER_ENABLED="true",
            KNOWLEDGE_WORKER_TENANT=TENANT,
            KNOWLEDGE_RETRIEVAL_ENABLED="true",
            KNOWLEDGE_EVALUATION_WORKER_ENABLED="true",
            KNOWLEDGE_EVALUATION_WORKER_TENANT=TENANT,
            KNOWLEDGE_EVALUATION_TASK_ENDPOINT=self.endpoints["tasks"],
            USAGE_MODE="CENTRAL",
            USAGE_EMBEDDING_PROFILE="qs-20261109-embedding",
            OTEL_EXPORT_ENABLED="false",
            LANGSMITH_TRACING="false",
            LANGCHAIN_TRACING_V2="false",
        )
        jar = ROOT / "async-task-service/target/async-task-service-0.1.0-SNAPSHOT.jar"
        self.start(
            "tasks",
            [
                "java",
                "-jar",
                str(jar),
                "--usage.mode=CENTRAL",
                "--usage.knowledge-public-key=" + self.env["KNOWLEDGE_AI_PUBLIC_KEY"],
            ],
        )
        self.wait("tasks", "/api/v1/tasks", 401)
        for kind, limit, model in (
            ("CHAT", "6.000000", "deepseek-flash"),
            ("EMBEDDING", "2.000000", "text-embedding-v4"),
        ):
            profile = "qs-20261109-" + kind.lower()
            generates = kind == "CHAT"
            manifest = {
                "schemaVersion": "usage-admin-v1",
                "commandId": stable_id("funding:" + kind),
                "action": "BOOTSTRAP",
                "accountId": stable_id("account:" + kind),
                "sponsorRef": "user-approved-quality-20261109",
                "fundingSourceRef": "quality-20261109-new-grant-" + kind.lower(),
                "kind": kind,
                "limitAmount": limit,
                "state": "ACTIVE",
                "expectedVersion": None,
                "profiles": [
                    {
                        "profileId": profile,
                        "priceVersion": profile,
                        "pricing": {
                            "model": model,
                            "unit": "INPUT_OUTPUT" if generates else "TOTAL",
                            "inputRate": "2.000000" if generates else None,
                            "outputRate": "8.000000" if generates else None,
                            "totalRate": None if generates else "2.000000",
                            "maxInputBytes": MAX_CHAT_BYTES if generates else 32768,
                            "maxOutputTokens": MAX_OUTPUT_TOKENS if generates else 0,
                            "maxDocuments": 0,
                        },
                    }
                ],
                "bindings": [
                    {
                        "tenantId": TENANT,
                        "profileId": profile,
                        "priceVersion": profile,
                        "enabled": True,
                    }
                ],
                "source": None,
            }
            path = WORK / ("funding-" + kind + ".json")
            if path.exists() and read_json(path) != manifest:
                raise ValueError("FUNDING_MANIFEST_DRIFT")
            local.private(path, json.dumps(manifest))
            admin = self.start(
                "admin-" + kind,
                [
                    "java",
                    "-jar",
                    str(jar),
                    "--spring.main.web-application-type=none",
                    "--usage.admin.enabled=true",
                    "--usage.mode=CENTRAL",
                    "--usage.admin.manifest=" + str(path),
                ],
            )
            if admin.wait(timeout=ADMIN_COMMAND_TIMEOUT_SECONDS) != 0:
                raise RuntimeError("FUNDING_BOOTSTRAP_FAILED:" + kind)
        self.prepare_supplemental_funding()
        profiles = self.usage_profiles()
        usage = {
            "schemaVersion": "usage-runtime-v1",
            "endpoint": self.endpoints["tasks"],
            "workerId": WORKER,
            "privateKey": str(ROOT / ".local/task-worker-private.pem"),
            "profiles": profiles,
        }
        local.private(WORK / "usage.json", json.dumps(usage))
        self.ai_env.update(
            AI_USAGE_MODE="CENTRAL",
            AI_USAGE_CONFIG=str(WORK / "usage.json"),
            LANGSMITH_TRACING="false",
            LANGCHAIN_TRACING_V2="false",
            OTEL_EXPORT_ENABLED="false",
            PYTHONUNBUFFERED="1",
        )
        self.start(
            "ai",
            [
                sys.executable,
                "-m",
                "agentic_ai.cli",
                "serve",
                "--port",
                str(self.ports["ai"]),
            ],
            self.ai_env,
        )
        self.wait("ai", "/health/live", 200)
        self.start(
            "knowledge",
            [
                "java",
                "-jar",
                str(ROOT / "knowledge-service/target/knowledge-service-0.1.0-SNAPSHOT.jar"),
            ],
        )
        self.wait("knowledge", "/api/v1/knowledge/documents", 401)
        self.start(
            "proxy",
            [
                str(chat_local.PROXY_PYTHON),
                "-m",
                "litellm.proxy.proxy_cli",
                "--config",
                str(chat_local.PROXY_ROOT / "config.yaml"),
                "--host",
                "127.0.0.1",
                "--port",
                str(self.ports["proxy"]),
            ],
            self.proxy_environment(),
        )
        self.wait("proxy", "/v1/models", 401)
        chat = read_json(ROOT / ".local/chat.json")
        chat.update(base_url=self.endpoints["proxy"] + "/v1", max_tokens=MAX_OUTPUT_TOKENS)
        local.private(WORK / "chat.json", json.dumps(chat))
        self.state["endpoints"] = self.endpoints
        self.save()
        print(
            "isolated services ready; original services and ledgers preserved",
            flush=True,
        )

    def request(
        self,
        method,
        path,
        *,
        body=None,
        key=None,
        status=200,
        task=False,
        owner=OWNER,
        tenant=TENANT,
        **kwargs,
    ):
        """每次按服务受众签发短期内部身份；没有关闭鉴权或直写业务数据。"""
        headers = {
            "X-Internal-Token": local.token(
                tenant,
                owner,
                "task:read task:write" if task else "knowledge:read knowledge:write",
                internal=True,
                claims={"aud": "agentic-ai-tasks" if task else "agentic-ai-knowledge"},
            )
        }
        if key:
            headers["Idempotency-Key"] = key
        with httpx.Client(trust_env=False, timeout=25) as http:
            response = http.request(
                method,
                self.endpoints["tasks" if task else "knowledge"] + path,
                headers=headers,
                json=body,
                **kwargs,
            )
        if response.status_code != status:
            raise RuntimeError(f"HTTP_UNEXPECTED:{method}:{path}:{response.status_code}")
        return response.json()

    def ingest(self):
        """复用固定幂等键恢复受理；FAILED/未知不通过重传规避原作业和费用。"""
        for ident, document in variants(self.frozen).items():
            attack = next((a for a in self.frozen["spec"]["attacks"] if a["id"] == ident), None)
            if attack and attack["placement"] == "query":
                question = next(
                    q for q in self.frozen["spec"]["questions"] if q["id"] == attack["case"]
                )
                self.state["documents"][ident] = self.state["documents"][question["source"]]
                self.save()
                continue
            record = self.state["documents"].setdefault(
                ident, {"key": stable_id("ingest:" + ident)}
            )
            self.save()
            if "job" not in record:
                record["job"] = self.request(
                    "POST",
                    "/api/v1/knowledge/ingestions",
                    status=202,
                    key=record["key"],
                    files={
                        "file": (
                            ident + ".md",
                            document["text"].encode(),
                            "text/markdown",
                        )
                    },
                    data={"displayName": document["title"]},
                )
                self.save()
            end = time.monotonic() + 90
            job = record["job"]
            while job["status"] not in {"READY", "FAILED"} and time.monotonic() < end:
                time.sleep(0.5)
                job = self.request("GET", "/api/v1/knowledge/ingestions/" + job["jobId"])
            record["job"] = job
            self.save()
            if job["status"] != "READY":
                raise RuntimeError("INGESTION_NOT_READY:" + ident + ":" + str(job.get("errorCode")))
            record["evidence"] = self.request(
                "GET",
                EVAL + "/evidence",
                params={"documentId": job["documentId"], "version": 1, "limit": 100},
            )
            if record["evidence"]["nextCursor"] is not None:
                raise RuntimeError("UNEXPECTED_EVIDENCE_PAGINATION")
            self.save()
            print(
                "ingested",
                ident,
                "chunks",
                len(record["evidence"]["items"]),
                flush=True,
            )
        write_once(self.output / "corpus-bindings.json", self.state["documents"])
        for group in ("quality", "control", "attack"):
            questions = questions_for(self.frozen, self.state["documents"], group)
            definition = {
                "name": "工程知识真实评测 " + group,
                "purpose": "BUSINESS",
                "description": "固定项目文档；攻击组包含标注的合成载荷；仅工程知识场景。",
                "questions": questions,
            }
            if group not in self.state["datasets"]:
                self.state["datasets"][group] = self.request(
                    "POST",
                    EVAL + "/datasets",
                    key=stable_id("dataset:" + group),
                    body=definition,
                    status=201,
                )
                self.save()
            write_once(self.output / ("definition-" + group + ".json"), definition)

    def accounts(self):
        """仅读取本轮隔离库的两个已批准账户，不通过日志猜测真实花费。"""
        raw = local.mysql(
            "SELECT kind,limit_amount,held_amount,booked_amount FROM "
            "agentic_tasks_test_qs20261109.usage_account ORDER BY kind;"
        )
        return {
            row[0]: dict(zip(("limit", "held", "booked"), row[1:], strict=True))
            for line in raw.strip().splitlines()
            if len(row := line.split("\t")) == 4
        }

    def estimate(self, repetitions):
        """按实际消息与最大五块估算保守预算，不调用模型。"""
        from agentic_ai.qa.pipeline import SYSTEM_PROMPT

        chunks = [
            chunk
            for record in self.state["documents"].values()
            for chunk in record["evidence"]["items"]
        ]
        chunks.sort(
            key=lambda c: len(
                json.dumps({"text": c["text"], "source": c["source"]}, ensure_ascii=False).encode()
            ),
            reverse=True,
        )
        upper, query_upper, maximum = Decimal(0), Decimal(0), 0
        for group, repeats in repetitions:
            definition = read_json(self.output / ("definition-" + group + ".json"))
            for question in definition["questions"]:
                payload = {
                    "question": question["question"],
                    "context": None,
                    "evidence": [
                        {"citationId": str(i), "text": c["text"], "source": c["source"]}
                        for i, c in enumerate(chunks[:5], 1)
                    ],
                }
                body = {
                    "model": "chat-default",
                    "messages": [
                        {"role": "system", "content": SYSTEM_PROMPT},
                        {
                            "role": "user",
                            "content": json.dumps(payload, ensure_ascii=False),
                        },
                    ],
                    "stream": True,
                    "stream_options": {"include_usage": True},
                    "n": 1,
                    "max_tokens": MAX_OUTPUT_TOKENS,
                }
                size = len(json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode())
                maximum = max(maximum, size)
                upper += repeats * max(
                    Decimal("0.01"),
                    (Decimal((size + 512) * 2 + MAX_OUTPUT_TOKENS * 8) * 2 / 1000000).quantize(
                        Decimal("0.000001"), rounding=ROUND_CEILING
                    ),
                )
                query_upper += repeats * max(
                    Decimal("0.01"),
                    Decimal((len(question["question"].encode()) + 2048) * 4) / 1000000,
                )
        return upper, query_upper, maximum

    def preflight(self):
        """首轮预检保持原记录格式，已冻结的prompt不得被复测静默覆盖。"""
        from agentic_ai.qa.pipeline import SYSTEM_PROMPT

        upper, query_upper, maximum = self.estimate((("quality", 1), ("control", 2), ("attack", 2)))
        if maximum > MAX_CHAT_BYTES or upper > 6 or query_upper > 2:
            raise RuntimeError("CAMPAIGN_PREFLIGHT_EXCEEDS_GRANT")
        value = {
            "specDigest": self.frozen["specDigest"],
            "maxChatCalls": MAX_CHAT_CALLS,
            "chatUpperCny": str(upper),
            "queryEmbeddingUpperCny": str(query_upper),
            "maxChatRequestBytes": maximum,
            "maxOutputTokens": MAX_OUTPUT_TOKENS,
            "promptDigest": hashlib.sha256(SYSTEM_PROMPT.encode()).hexdigest(),
            "model": "deepseek-flash",
            "embedding": "text-embedding-v4",
            "strategy": STRATEGY,
            "pricingCheckedAt": "2026-11-09",
            "pricing": (
                "published peak chat 2/8; conservative embedding 2 CNY/million, not provider bill"
            ),
        }
        write_once(self.output / "preflight.json", value)
        accounts = self.accounts()
        if any(
            Decimal(accounts[k]["held"]) + Decimal(accounts[k]["booked"])
            >= Decimal(accounts[k]["limit"])
            for k in ("CHAT", "EMBEDDING")
        ):
            raise RuntimeError("CAMPAIGN_BUDGET_EXHAUSTED")
        return value

    async def execute(self, label, group):
        """真实中央任务/PG/评测落库；已终态只收集，未知执行状态不自动恢复重发。"""
        from langsmith import tracing_context

        from agentic_ai.adapters.openai_embedding import OpenAIEmbeddingAdapter
        from agentic_ai.evaluation.client import EvaluationClient
        from agentic_ai.evaluation.pipeline import model_profile
        from agentic_ai.qa.chat import ChatClient
        from agentic_ai.qa.chat_config import ChatConfig
        from agentic_ai.qa.worker import execute_qa_task
        from agentic_ai.retrieval.client import RetrievalClient
        from agentic_ai.retrieval.consumer import RetrievalUseCase
        from agentic_ai.retrieval.deadline import RetrievalCapacity
        from agentic_ai.runtime.embedding_config import EmbeddingConfig
        from agentic_ai.tasks.client import CentralTaskClient
        from agentic_ai.tasks.postgres import CheckpointConfig
        from agentic_ai.usage.config import load_gate
        from agentic_ai.usage.models import Kind

        self.preflight()
        record = self.state["runs"].setdefault(label, {"key": self.evaluation_key("run:" + label)})
        self.save()
        if "runId" not in record:
            accepted = self.request(
                "POST",
                "/api/v2/knowledge/evaluations/runs",
                status=202,
                key=record["key"],
                body={
                    "datasetId": self.state["datasets"][group]["datasetId"],
                    "revision": 1,
                    "topK": 5,
                    "retrievalStrategy": STRATEGY,
                },
            )
            record["runId"] = accepted["summary"]["runId"]
            self.save()
        run_id = record["runId"]
        for _ in range(100):
            view = self.request("GET", EVAL + "/runs/" + run_id)
            if view["task"]:
                break
            await asyncio.sleep(0.2)
        else:
            raise RuntimeError("TASK_DISPATCH_PENDING")
        if view["task"]["status"] == "QUEUED":
            config = ChatConfig.from_file(WORK / "chat.json")
            async with AsyncExitStack() as stack:
                gate = load_gate(
                    {
                        Kind.CHAT: config.approved_model,
                        Kind.EMBEDDING: "text-embedding-v4",
                    }
                )
                stack.push_async_callback(gate.client.aclose)
                central = CentralTaskClient.from_file(
                    self.endpoints["tasks"],
                    TENANT,
                    WORKER,
                    ROOT / ".local/task-worker-private.pem",
                )
                stack.push_async_callback(central.aclose)
                lease = await central.claim("knowledge-evaluation-v2")
                if lease is None or lease.task.task_id != view["task"]["taskId"]:
                    raise RuntimeError("UNEXPECTED_TASK_CLAIM")
                candidates = RetrievalClient.from_file(
                    self.endpoints["knowledge"],
                    TENANT,
                    OWNER,
                    ROOT / ".local/runtime-knowledge-private.pem",
                )
                stack.push_async_callback(candidates.aclose)
                evaluations = EvaluationClient.from_file(
                    self.endpoints["knowledge"],
                    lease,
                    ROOT / ".local/runtime-evaluation-private.pem",
                )
                stack.push_async_callback(evaluations.aclose)
                embedding = OpenAIEmbeddingAdapter(EmbeddingConfig.from_environment(), usage=gate)
                stack.push_async_callback(embedding.aclose)
                client = ChatClient(config, None, usage=gate)
                stack.push_async_callback(client.aclose)
                recorder = RecordedChat(client, label, self.output)
                profile = model_profile(config).model_copy(update={"retrieval_profile": STRATEGY})
                with tracing_context(enabled=False):
                    final = await execute_qa_task(
                        lease,
                        central,
                        CheckpointConfig(dsn=checkpoint_local.dsn("test")),
                        candidates,
                        recorder,
                        evaluations=evaluations,
                        profile=profile,
                        retrieval=RetrievalUseCase(embedding, RetrievalCapacity(1)),
                        candidates=candidates,
                    )
                record["executionStatus"] = final.status.value
                self.save()
        elif view["task"]["status"] not in {"SUCCEEDED", "FAILED", "CANCELLED"}:
            raise RuntimeError("UNKNOWN_INFLIGHT_TASK_NO_AUTORETRY")
        self.collect(label, group)
        account_snapshot = self.output / "runs" / (label + "-accounts.json")
        if not account_snapshot.exists():
            # 恢复已终态任务时保留该组完成时快照，不能用后续组累计占额改写历史。
            write_once(account_snapshot, self.accounts())

    def collect(self, label, group):
        record = self.state["runs"][label]
        view = self.request("GET", EVAL + "/runs/" + record["runId"])
        write_once(self.output / "runs" / (label + ".json"), view)
        reports = {}
        if view["report"]:
            outcomes = {q["questionId"]: q["outcome"] for q in view["report"]["questions"]}
            for question in read_json(self.output / ("definition-" + group + ".json"))["questions"]:
                ident = question["questionId"]
                if outcomes.get(ident, "UNSTARTED") == "UNSTARTED":
                    # 未执行题没有正文接口，仍保留在预先固定的统计分母。
                    continue
                reports[ident] = self.request(
                    "GET",
                    EVAL + "/runs/" + record["runId"] + "/questions/" + ident,
                    params={"manifestDigest": view["report"]["manifestDigest"]},
                )
        write_once(self.output / "runs" / (label + "-questions.json"), reports)
        record["collected"] = True
        self.save()
        print(
            "collected",
            label,
            "task",
            view["task"]["status"],
            "questions",
            len(reports),
            flush=True,
        )

    def review(self, label):
        """保存已完成逐题审查；原结果摘要不符即拒绝，资格不足不选择合格基线。"""
        reviews = read_json(self.output / "reviews" / (label + ".json"))
        reports = read_json(self.output / "runs" / (label + "-questions.json"))
        view = read_json(self.output / "runs" / (label + ".json"))
        run_id = view["summary"]["runId"]
        for ident, review in reviews.items():
            if digest(reports[ident]) != review["resultDigest"]:
                raise ValueError("STALE_REVIEW")
            if reports[ident]["outcome"] != "SUCCEEDED":
                continue
            self.request(
                "POST",
                EVAL + f"/runs/{run_id}/questions/{ident}/reviews",
                key=self.evaluation_key(f"review:{label}:{ident}"),
                status=201,
                body={
                    "manifestDigest": view["report"]["manifestDigest"],
                    "expectedRevision": 0,
                    "assessment": review["assessment"],
                    "note": review["note"] + "；Codex逐题证据审阅，非独立人工签核。",
                },
            )
        state = self.request("GET", EVAL + f"/runs/{run_id}/reviews")
        write_once(self.output / "reviews" / (label + "-state.json"), state)
        if label in self.baseline_labels and state["eligibility"]["eligible"]:
            receipt = self.request(
                "POST",
                EVAL + f"/datasets/{view['summary']['datasetId']}/revisions/1/baseline",
                key=self.evaluation_key("baseline:quality"),
                status=201,
                body={
                    "expectedRevision": 0,
                    "runId": run_id,
                    "manifestDigest": state["manifestDigest"],
                    "reviewDigest": state["reviewDigest"],
                    "note": self.baseline_note,
                },
            )
            write_once(self.output / "baseline-receipt.json", receipt)
        print("reviewed", label, state["eligibility"], flush=True)

    def audit(self):
        """导出隔离账户的安全计量字段；未知费用继续占额，不按零元记账。"""
        raw = local.mysql(
            "SELECT o.kind,a.attempt_id,a.state,a.outcome,a.reserved_amount,"
            "COALESCE(CAST(a.settled_amount AS CHAR),'UNKNOWN'),"
            "COALESCE(CAST(a.usage_json AS CHAR),'null') "
            "FROM agentic_tasks_test_qs20261109.usage_attempt a "
            "JOIN agentic_tasks_test_qs20261109.usage_operation o "
            "ON o.operation_id=a.operation_id ORDER BY a.created_at,a.attempt_id;"
        )
        attempts = []
        for line in raw.strip().splitlines():
            kind, ident, state, outcome, reserved, settled, usage = line.split("\t")
            attempts.append(
                {
                    "kind": kind,
                    "attemptId": ident,
                    "state": state,
                    "outcome": outcome,
                    "reservedCny": reserved,
                    "settledInternalCny": None if settled == "UNKNOWN" else settled,
                    "usage": json.loads(usage),
                }
            )
        if sum(a["kind"] == "CHAT" for a in attempts) > self.max_total_chat_calls:
            raise RuntimeError("CAMPAIGN_CALL_LIMIT_VIOLATION")
        write_once(
            self.output / "usage-audit.json",
            {
                "accounts": self.accounts(),
                "attempts": attempts,
                "originalLedgerSha256": ledgers(),
                "originalLedgersUnchanged": ledgers() == self.state["originalLedgers"],
                "providerBillCny": None,
            },
        )


class RecordedChat:
    """记录实际进入模型的公开测试资料，证明攻击确实到达；不记录认证头。"""

    def __init__(self, client, label, directory):
        self.client, self.label, self.directory = client, label, directory
        self.count = 0

    async def stream(self, messages, operation_id):
        self.count += 1
        payload = [m.model_dump() for m in messages]
        write_once(
            self.directory / "transcripts" / f"{self.label}-{self.count:02}-input.json",
            payload,
        )
        answer = ""
        try:
            async for token in self.client.stream(messages, operation_id):
                answer += token
                yield token
        finally:
            write_once(
                self.directory / "transcripts" / f"{self.label}-{self.count:02}-output.json",
                {"operationId": operation_id, "answer": answer},
            )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "phase", choices=("ingest", "run", "collect", "review", "review-all", "audit")
    )
    parser.add_argument("--approved-budget-cny", choices=("8",), required=True)
    parser.add_argument(
        "--label", choices=("quality", "control-1", "attack-1", "control-2", "attack-2")
    )
    args = parser.parse_args()
    with Runtime() as runtime:
        # 仅本次进程采用中央预算，退出不修改原服务或原配置。
        previous = dict(os.environ)
        try:
            os.environ.update(runtime.ai_env)
            if args.phase == "ingest":
                runtime.ingest()
            elif args.phase == "review-all":
                for label in ("quality", "control-1", "attack-1", "control-2", "attack-2"):
                    runtime.review(label)
                runtime.audit()
            elif args.phase == "audit":
                runtime.audit()
            else:
                if not args.label:
                    raise ValueError("RUN_LABEL_REQUIRED")
                group = args.label.split("-")[0]
                if args.phase == "run":
                    asyncio.run(runtime.execute(args.label, group))
                elif args.phase == "collect":
                    runtime.collect(args.label, group)
                else:
                    runtime.review(args.label)
        finally:
            os.environ.clear()
            os.environ.update(previous)


if __name__ == "__main__":
    main()
