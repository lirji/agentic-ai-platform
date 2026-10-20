#!/usr/bin/env python3
"""隔离进程使用测试库和无权限桶，验证真实存储故障的 HTTP 与 readiness 表现。"""

from __future__ import annotations

import json
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from http import HTTPStatus

import local

API_PORT = 28184
MANAGEMENT_PORT = 29184


def read(url: str, data: bytes | None = None, headers: dict | None = None) -> tuple:
    """网络和 HTTP 错误不自动重试写请求。"""
    request = urllib.request.Request(url, data=data, headers=headers or {})
    try:
        response = urllib.request.urlopen(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, json.load(response)


def main() -> None:
    """只启动与关闭此函数持有的子进程，不改变共享服务及正常应用配置。"""
    for port in (API_PORT, MANAGEMENT_PORT):
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(("127.0.0.1", port))
    env = local.environment("test")
    env.update(
        {
            "KNOWLEDGE_PORT": str(API_PORT),
            "KNOWLEDGE_MANAGEMENT_PORT": str(MANAGEMENT_PORT),
            "KNOWLEDGE_S3_BUCKET": "not-agentic-authorized",
        }
    )
    jar = local.ROOT / "knowledge-service/target/knowledge-service-0.1.0-SNAPSHOT.jar"
    with (local.LOGS / "fault-knowledge.log").open("ab") as log:
        process = subprocess.Popen(
            ["java", "-Xms64m", "-Xmx384m", "-XX:ActiveProcessorCount=4", "-jar", str(jar)],
            cwd=local.ROOT,
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        local.private(local.STATE / "fault-knowledge.pid", str(process.pid))
        try:
            deadline = time.monotonic() + 45
            last_failure = "NOT_READY"
            while time.monotonic() < deadline:
                assert process.poll() is None, "fault process exited before health"
                try:
                    if read(f"http://127.0.0.1:{MANAGEMENT_PORT}/actuator/health/liveness") == (
                        200,
                        {"status": "UP"},
                    ):
                        break
                except (OSError, urllib.error.URLError) as unavailable:
                    last_failure = type(unavailable).__name__
                time.sleep(0.3)
            else:
                raise AssertionError(f"fault process liveness timeout: {last_failure}")
            status, body = read(f"http://127.0.0.1:{MANAGEMENT_PORT}/actuator/health/readiness")
            assert status == HTTPStatus.SERVICE_UNAVAILABLE and body == {"status": "DOWN"}, (
                "readiness hid storage denial"
            )
            tenant = "fault-" + uuid.uuid4().hex
            identity = local.token(tenant, "alice", "knowledge:read knowledge:write", internal=True)
            boundary = "agentic-fault"
            content = (
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                f'filename="fault.txt"\r\nContent-Type: text/plain\r\n\r\n'
                f"should not be accepted\r\n--{boundary}--\r\n"
            ).encode()
            headers = {
                "X-Internal-Token": identity,
                "Idempotency-Key": uuid.uuid4().hex,
                "Content-Type": f"multipart/form-data; boundary={boundary}",
            }
            status, body = read(
                f"http://127.0.0.1:{API_PORT}/api/v1/knowledge/ingestions", content, headers
            )
            assert status == HTTPStatus.SERVICE_UNAVAILABLE
            assert body["code"] == "KNOWLEDGE_STORAGE_UNAVAILABLE"
            assert set(body) == {"code", "message", "traceId"}
            status, body = read(
                f"http://127.0.0.1:{API_PORT}/api/v1/knowledge/documents",
                headers={"X-Internal-Token": identity},
            )
            assert status == HTTPStatus.OK and body == {"items": [], "nextCursor": None}
            resource = local.load()["resources"]["test"]
            count = local.mysql(
                f"SELECT COUNT(*) FROM `{resource['database']}`.knowledge_ingestion "
                f"WHERE tenant_id='{tenant}';"
            ).strip()
            assert count == "0"
            evidence = {
                "status": "PASS",
                "checks": [
                    "liveness stays UP",
                    "readiness returns safe 503",
                    "upload returns storage unavailable",
                    "no document",
                    "no task",
                ],
                "tenant": tenant,
                "sharedServicesChanged": False,
            }
            (local.LOGS / "s1-fault-smoke.json").write_text(json.dumps(evidence, indent=2) + "\n")
            print("PASS: 5 real dependency-failure checks")
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=20)


if __name__ == "__main__":
    main()
