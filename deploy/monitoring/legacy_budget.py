#!/usr/bin/env python3
"""只读旧账本的loopback Prometheus适配；不初始化资金、不发送通知或模型请求。"""

import argparse
import fcntl
import json
import os
import stat
from decimal import Decimal, InvalidOperation
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

MAX_BYTES = 262144
HOST = "127.0.0.1"
DEFAULT_PORT = 29192
LEDGER_INVALID = "LEDGER_INVALID"
KINDS = ("CHAT", "EMBEDDING")


def ratio(path: Path) -> Decimal:
    """非阻塞共享锁与原写锁兼容；不输出路径、明细或凭据。"""
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "r", encoding="utf-8") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077 or info.st_size > MAX_BYTES:
            raise ValueError(LEDGER_INVALID)
        fcntl.flock(stream, fcntl.LOCK_SH | fcntl.LOCK_NB)
        content = stream.read(MAX_BYTES + 1)
        if len(content.encode()) > MAX_BYTES:
            raise ValueError(LEDGER_INVALID)
        data = json.loads(content)
        if not isinstance(data["limitCny"], str) or not isinstance(data["reservedCny"], str):
            raise ValueError(LEDGER_INVALID)
        limit, used = Decimal(data["limitCny"]), Decimal(data["reservedCny"])
        if not limit.is_finite() or not used.is_finite() or limit <= 0 or used < 0:
            raise ValueError(LEDGER_INVALID)
        calls = data["calls"]
        if not isinstance(calls, list):
            raise ValueError(LEDGER_INVALID)
        amounts = [Decimal(call["reservedCny"]) for call in calls]
        if any(not value.is_finite() or value < 0 for value in amounts) or sum(amounts) != used:
            raise ValueError(LEDGER_INVALID)
        return used / limit


def metrics(paths: dict[str, Path]) -> bytes:
    """固定枚举标签、失败显式NaN；未知/损坏不能变为零费用。"""
    lines = []
    for kind in KINDS:
        if kind not in paths:
            continue
        try:
            value, success = str(ratio(paths[kind])), 1
        except (OSError, ValueError, TypeError, KeyError, InvalidOperation):
            value, success = "NaN", 0
        lines.extend(
            [
                f'agentic_legacy_budget_read_success{{kind="{kind}"}} {success}',
                f'agentic_legacy_budget_ratio{{kind="{kind}"}} {value}',
            ]
        )
    return ("\n".join(lines) + "\n").encode()


def serve(paths: dict[str, Path], port: int) -> None:
    """单连接串行且socket有界；仅显式本机运维启动，不修改应用部署。"""

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(2)

        def do_GET(self):
            if self.path != "/metrics":
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            body = metrics(paths)
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_):
            # 不将任意请求路径/headers写日志；健康仅由固定数值判断。
            pass

    with HTTPServer((HOST, port), Handler) as server:
        server.serve_forever(poll_interval=0.5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chat-ledger", type=Path)
    parser.add_argument("--embedding-ledger", type=Path)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    paths = {
        kind: path
        for kind, path in zip(KINDS, (args.chat_ledger, args.embedding_ledger), strict=True)
        if path
    }
    if not paths or any(not path.is_absolute() for path in paths.values()):
        parser.error("需至少一个绝对账本路径；本工具不会创建账本")
    if args.once:
        print(metrics(paths).decode(), end="")
    else:
        serve(paths, args.port)


if __name__ == "__main__":
    main()
