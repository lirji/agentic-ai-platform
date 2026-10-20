#!/usr/bin/env python3
"""以独立进程的不可达数据库验证故障响应，不停止或修改共享数据库。"""

from __future__ import annotations

import json
import socket
import subprocess
import time

import httpx
import local
import task_smoke

PORT = 28097
MANAGEMENT_PORT = 29097
START_SECONDS = 30


def run() -> None:
    """故障实例仅在测试期间存在，最终只终止本函数创建的子进程。"""
    for port in (PORT, MANAGEMENT_PORT):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    env = local.environment("test")
    env.update(
        {
            "TASK_PORT": str(PORT),
            "TASK_MANAGEMENT_PORT": str(MANAGEMENT_PORT),
            "TASK_DB_URL": "jdbc:mysql://127.0.0.1:1/agentic_tasks_test?connectTimeout=500",
            "SPRING_FLYWAY_ENABLED": "false",
        }
    )
    jar = local.ROOT / "async-task-service/target/async-task-service-0.1.0-SNAPSHOT.jar"
    with (local.LOGS / "s5a-task-fault-process.log").open("ab") as log:
        process = subprocess.Popen(
            ["java", "-Xms32m", "-Xmx256m", "-XX:ActiveProcessorCount=2", "-jar", str(jar)],
            stdout=log,
            stderr=subprocess.STDOUT,
            env=env,
            cwd=local.ROOT,
        )
        try:
            with httpx.Client(timeout=12, trust_env=False) as client:
                deadline = time.monotonic() + START_SECONDS
                last_error = "NOT_STARTED"
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise AssertionError("故障实例提前退出，详见进程日志")
                    try:
                        if (
                            client.get(f"http://127.0.0.1:{PORT}/api/v1/tasks/none").status_code
                            == 401
                        ):
                            break
                    except httpx.HTTPError as error:
                        last_error = type(error).__name__
                    time.sleep(0.2)
                else:
                    raise AssertionError(f"故障实例启动超时：{last_error}")
                headers = {
                    "X-Internal-Token": task_smoke.service_token(
                        "worker", "fault-test", "fault-worker"
                    )
                }
                response = client.post(
                    f"http://127.0.0.1:{PORT}/internal/v1/task-worker/claim",
                    headers=headers,
                    json={"runtimeVersion": task_smoke.RUNTIME, "graphVersion": task_smoke.GRAPH},
                )
                assert response.status_code == 503
                assert response.json()["code"] == "TASK_DEPENDENCY_UNAVAILABLE"
                assert "jdbc" not in response.text.lower()
                health = client.get(f"http://127.0.0.1:{MANAGEMENT_PORT}/actuator/health/readiness")
                assert health.status_code == 503 and health.json()["status"] == "DOWN"
                evidence = {
                    "status": "PASS",
                    "checks": ["claim_storage_503", "readiness_down", "safe_error_body"],
                    "sharedDatabaseModified": False,
                }
                (task_smoke.EVIDENCE / "fault-smoke.json").write_text(
                    json.dumps(evidence, indent=2) + "\n"
                )
                print("PASS: 故障实例返回 503、readiness DOWN，未影响共享数据库。")
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                # 仅终止自己创建的故障测试子进程，不按端口或模糊名称杀进程。
                process.kill()
                process.wait(timeout=5)


if __name__ == "__main__":
    run()
