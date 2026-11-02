#!/usr/bin/env python3
"""S14B真实Knowledge/Tasks/React验证；只用隔离租户与确定性报告，不调用模型。"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import time
from pathlib import Path
from uuid import uuid4

import candidate_browser_bridge
import httpx
import jwt
import local
import task_smoke


def browser(fixture: Path) -> None:
    """经真实worker/报告协议完成一次评测，再由浏览器关联当前报告和修正稿。"""
    value = json.loads(fixture.read_text())
    tenant, owner = value["tenant"], value["owner"]
    worker_id = "candidate-browser-worker"

    def token(role: str) -> str:
        now = int(time.time())
        payload = {
            "iss": "urn:agentic-ai:edge",
            "aud": "agentic-ai-knowledge",
            "sub": owner,
            "tenant_id": tenant,
            "scope": "knowledge:read knowledge:write",
            "iat": now,
            "nbf": now,
            "exp": now + 60,
        }
        key = "internal-private.pem"
        if role == "evaluation":
            payload.update(
                iss="urn:agentic-ai:evaluation-worker",
                azp="ai-runtime-evaluation",
                scope="evaluation:worker",
                worker_id=worker_id,
            )
            key = "runtime-evaluation-private.pem"
        return jwt.encode(payload, (local.STATE / key).read_bytes(), algorithm="RS256")

    with httpx.Client(timeout=10, trust_env=False) as client:

        def request(method, path, body=None, role="user", task=False):
            credential = (
                task_smoke.service_token("worker", tenant, worker_id)
                if task
                else token(role)
            )
            response = client.request(
                method,
                value["taskEndpoint" if task else "endpoint"] + path,
                json=body,
                headers={
                    "X-Internal-Token": credential,
                    "Idempotency-Key": str(uuid4()),
                },
            )
            assert response.status_code in (200, 201, 202), (
                f"{method} {path}: {response.status_code} {response.text[:300]}"
            )
            return response.json()

        claim = request(
            "POST",
            "/internal/v1/task-worker/claim",
            {
                "runtimeVersion": "langgraph-v1",
                "graphVersion": "knowledge-evaluation-v1",
            },
            task=True,
        )["task"]
        assert claim["task"]["taskId"] == value["taskId"]
        epoch = claim["epoch"]
        base = "/internal/v1/evaluations/runs/" + value["runId"]
        opened = request(
            "POST",
            base + "/open",
            {
                "taskId": value["taskId"],
                "epoch": epoch,
                "profile": {
                    "model": "candidate-fixture",
                    "route": "chat-default",
                    "maxOutputTokens": 512,
                    "promptDigest": "a" * 64,
                    "retrievalProfile": "java-rrf60-v1",
                },
            },
            role="evaluation",
        )
        evidence = value["evidence"]
        citation = {
            key: evidence[key] for key in ("documentId", "version", "chunkId", "source")
        }
        report = {
            "questionId": value["question"]["questionId"],
            "outcome": "SUCCEEDED",
            "result": {
                "schemaVersion": "knowledge-qa-result-v1",
                "answerability": "ANSWERED",
                "answer": "完整修正内容[1]。",
                "citations": [dict(citation, citationId="1")],
            },
            "retrieved": [dict(citation, textHash=evidence["textHash"], score=1.0)],
            "metrics": {"recall": 1.0, "mrr": 1.0, "ndcg": 1.0},
            "timing": {
                "retrievalMs": 1,
                "generationMs": 1,
                "verificationMs": 1,
                "totalMs": 3,
            },
            "cost": {
                "chatAttempts": 1,
                "providerUsage": None,
                "providerAmountCny": None,
                "chatReservedCny": "0",
                "embeddingReservedCny": "0",
            },
            "errorCode": None,
        }
        accepted = request(
            "PUT",
            base + "/questions/" + report["questionId"],
            {"taskId": value["taskId"], "epoch": epoch, "report": report},
            role="evaluation",
        )
        manifest = request(
            "PUT",
            base + "/manifests/" + str(epoch),
            {
                "taskId": value["taskId"],
                "profileDigest": opened["profileDigest"],
                "complete": True,
                "questions": [accepted],
            },
            role="evaluation",
        )
        request(
            "POST",
            "/internal/v1/task-worker/" + value["taskId"] + "/complete",
            {
                "epoch": epoch,
                "operationKey": "candidate-complete",
                "result": {
                    "schemaVersion": "evaluation-completion-v1",
                    "runId": value["runId"],
                    "epoch": epoch,
                    "manifestDigest": manifest["manifestDigest"],
                },
            },
            task=True,
        )
        request(
            "POST",
            "/api/v1/knowledge/evaluations/runs/"
            + value["runId"]
            + "/questions/"
            + report["questionId"]
            + "/reviews",
            {
                "manifestDigest": manifest["manifestDigest"],
                "expectedRevision": 0,
                "assessment": {
                    "citationCorrect": "PASS",
                    "supported": "PASS",
                    "coverage": "PASS",
                    "refusal": None,
                },
                "note": "确定性联合机制验收，不宣称真实模型质量",
            },
        )
    web = local.ROOT.parent / "agentic-ai-platform-web"
    env = dict(
        os.environ,
        CANDIDATE_FIXTURE=str(fixture),
        PLAYWRIGHT_BASE_URL="http://127.0.0.1:28226",
    )
    env["PATH"] = (
        str(web / ".local/toolchains/node-v24.22.0-darwin-arm64/bin")
        + os.pathsep
        + env["PATH"]
    )
    with (
        candidate_browser_bridge.serve(value) as origin,
        (fixture.parent / "browser.log").open("w") as log,
    ):
        env["PLAYWRIGHT_BASE_URL"] = origin
        result = subprocess.run(
            [
                "node",
                str(web / "node_modules/@playwright/test/cli.js"),
                "test",
                "tests/candidates-real.spec.ts",
                "--reporter=list",
                "--output=.logs/s14b-browser-artifacts",
            ],
            cwd=web,
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
            timeout=210,
            check=False,
        )
    assert result.returncode == 0, "浏览器失败，见browser.log"
    print(
        "PASS: actual Tasks terminal, Knowledge quality binding, browser workflow; paid calls=0"
    )


def verify() -> None:
    """动态端口启动本轮测试Tasks，结束只终止所启动进程，保留测试数据/日志。"""
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    env = local.environment("test")
    endpoint = f"http://127.0.0.1:{port}"
    env.update(
        TASK_PORT=str(port),
        TASK_MANAGEMENT_PORT="0",
        KNOWLEDGE_EVALUATION_TASK_ENDPOINT=endpoint,
        AGENTIC_CANDIDATE_BROWSER="1",
    )
    with (local.LOGS / "s14b-joint-tasks.log").open("w") as log:
        process = subprocess.Popen(
            [
                "java",
                "-Xmx384m",
                "-jar",
                str(
                    local.ROOT
                    / "async-task-service/target/async-task-service-0.1.0-SNAPSHOT.jar"
                ),
            ],
            cwd=local.ROOT,
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        try:
            with httpx.Client(timeout=2, trust_env=False) as client:
                deadline = time.monotonic() + 40
                last_failure = "尚未就绪"
                while time.monotonic() < deadline:
                    assert process.poll() is None, "Tasks启动失败"
                    try:
                        if client.get(endpoint + "/api/v1/tasks").status_code == 401:
                            break
                    except httpx.HTTPError as failure:
                        last_failure = type(failure).__name__
                    time.sleep(0.1)
                else:
                    raise AssertionError("Tasks启动超时：" + last_failure)
            result = subprocess.run(
                [
                    "mvn",
                    "-B",
                    "-ntp",
                    "-pl",
                    "knowledge-service",
                    "-am",
                    "-Pintegration",
                    "-Dit.test=KnowledgeCandidateIT#realTasksAndBrowserCandidateWorkflow",
                    "-Dfailsafe.failIfNoSpecifiedTests=false",
                    "verify",
                ],
                env=env,
                cwd=local.ROOT,
                check=False,
            )
            assert result.returncode == 0, "候选联合验收失败"
        finally:
            process.terminate()
            process.wait(timeout=15)


if __name__ == "__main__":
    if sys.argv[1] == "browser":
        browser(Path(sys.argv[2]))
    elif sys.argv[1] == "verify":
        verify()
    else:
        raise ValueError("需要browser或verify")
