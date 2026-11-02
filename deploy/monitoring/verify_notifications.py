#!/usr/bin/env python3
"""隔离Alertmanager实际接收/去重/恢复演练，仅向本机合成HTTP接收器发送。"""

import argparse
import hashlib
import json
import socket
import subprocess
import threading
import time
import uuid
from datetime import UTC, datetime, timedelta
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen


def run(binary: Path, evidence: Path) -> None:
    """只管理本次启动的本机子进程；既有监控和应用配置不变。"""
    evidence.mkdir(parents=True, exist_ok=True)
    received = []

    class Receiver(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            if length > 65536:
                self.send_error(HTTPStatus.REQUEST_ENTITY_TOO_LARGE)
                return
            received.append(json.loads(self.rfile.read(length)))
            self.send_response(HTTPStatus.OK)
            self.end_headers()

        def log_message(self, *_):
            pass

    server = HTTPServer(("127.0.0.1", 0), Receiver)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    config = dict(
        route=dict(
            receiver="loopback-test",
            group_by=["project", "alertname", "service", "kind"],
            group_wait="1s",
            group_interval="1s",
            repeat_interval="1h",
        ),
        receivers=[
            dict(
                name="loopback-test",
                webhook_configs=[
                    dict(
                        url=f"http://127.0.0.1:{server.server_port}/",
                        send_resolved=True,
                    )
                ],
            )
        ],
    )
    path = (evidence / "alertmanager-test.json").resolve()
    path.write_text(json.dumps(config))
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        port = reserved.getsockname()[1]
    storage = (evidence / ("state-" + uuid.uuid4().hex[:8])).resolve()
    log = (evidence / "alertmanager.log").open("w")
    process = subprocess.Popen(
        [
            str(binary.resolve()),
            "--config.file=" + str(path),
            "--web.listen-address=127.0.0.1:" + str(port),
            "--cluster.listen-address=",
            "--storage.path=" + str(storage),
            "--log.level=warn",
        ],
        stdout=log,
        stderr=subprocess.STDOUT,
    )
    try:
        base = "http://127.0.0.1:" + str(port)
        deadline = time.monotonic() + 30
        while True:
            try:
                with urlopen(base + "/-/ready", timeout=2) as response:
                    assert response.status == HTTPStatus.OK
                break
            except URLError:
                if time.monotonic() > deadline:
                    raise RuntimeError("ALERTMANAGER_NOT_READY") from None
                time.sleep(0.2)
        now = datetime.now(UTC)
        names = (
            "AgenticQueueBacklog",
            "AgenticCentralBudgetNearLimit",
            "AgenticDependencyErrors",
        )
        alerts = [
            dict(
                labels=dict(
                    project="agentic-ai",
                    alertname=name,
                    service="tasks",
                    severity="warning",
                    threshold="experimental",
                ),
                annotations=dict(summary="本机合成告警"),
                startsAt=now.isoformat(),
                endsAt=(now + timedelta(minutes=10)).isoformat(),
            )
            for name in names
        ]

        def send():
            with urlopen(
                Request(
                    base + "/api/v2/alerts",
                    data=json.dumps(alerts).encode(),
                    headers={"Content-Type": "application/json"},
                ),
                timeout=3,
            ) as response:
                assert response.status == HTTPStatus.OK

        def wait_count(count):
            deadline = time.monotonic() + 30
            while len(received) < count and time.monotonic() < deadline:
                time.sleep(0.1)
            assert len(received) == count, f"NOTIFICATION_COUNT_{len(received)}_EXPECTED_{count}"

        send()
        wait_count(3)
        for _ in range(4):
            send()
        time.sleep(3)
        assert len(received) == 3, "DUPLICATE_NOTIFICATION"
        for alert in alerts:
            alert["endsAt"] = datetime.now(UTC).isoformat()
        send()
        wait_count(6)
        assert [r["status"] for r in received].count("firing") == 3
        assert [r["status"] for r in received].count("resolved") == 3
        observed = {r["alerts"][0]["labels"]["alertname"] for r in received}
        assert observed == set(names)
        (evidence / "notification-result.json").write_text(
            json.dumps(
                dict(
                    status="PASS",
                    binarySha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
                    firing=3,
                    duplicateSubmissions=4,
                    duplicateNotifications=0,
                    resolved=3,
                    externalMessages=0,
                    alerts=sorted(observed),
                ),
                indent=2,
            )
            + "\n"
        )
        print("PASS: 3 firing, 0 duplicate, 3 resolved; loopback only")
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        log.close()
        server.shutdown()
        server.server_close()
        thread.join(3)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    run(args.binary, args.evidence)
