"""本地运行与配置预检入口；预检本身不发生外部模型调用。"""

import argparse
import asyncio
import json
import logging
import sys
from collections.abc import Sequence
from pathlib import Path

import uvicorn

from agentic_ai.rag.errors import EmbeddingError
from agentic_ai.runtime.embedding_config import EmbeddingConfig

EXIT_CONFIGURATION_ERROR = 2


def main(argv: Sequence[str] | None = None) -> int:
    """让开发者先核对配置，再按明确命令启动当前实现的 HTTP 服务。"""
    parser = argparse.ArgumentParser(description="独立知识平台 AI 运行时")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check-config", help="检查真实 Embedding 配置，不调用模型")
    server = commands.add_parser("serve", help="启动当前已实现的 HTTP 端点")
    server.add_argument("--host", default="127.0.0.1", help="监听地址，默认仅本机")
    server.add_argument("--port", default=8000, type=int, help="监听端口，0 表示由系统分配")
    for name in ("qa-worker", "check-qa-worker"):
        command = commands.add_parser(name, help="显式启动或预检知识问答 worker")
        command.add_argument("--config", required=True, type=Path, help="本机 0600 私密配置路径")
    args = parser.parse_args(argv)
    if args.command in {"qa-worker", "check-qa-worker"}:
        from agentic_ai.qa.errors import QaError
        from agentic_ai.qa.worker import WorkerConfig, run_worker
        from agentic_ai.tasks.errors import TaskRuntimeError

        logging.basicConfig(level=logging.INFO, format="%(message)s")
        try:
            asyncio.run(
                run_worker(
                    WorkerConfig.from_file(args.config),
                    check_only=args.command == "check-qa-worker",
                )
            )
        except (QaError, TaskRuntimeError) as error:
            print(json.dumps({"code": error.code.value}), file=sys.stderr)
            return EXIT_CONFIGURATION_ERROR
        return 0
    if args.command == "check-config":
        try:
            config = EmbeddingConfig.from_environment()
        except EmbeddingError as error:
            print(
                json.dumps({"code": error.code, "message": error.message}, ensure_ascii=False),
                file=sys.stderr,
            )
            return EXIT_CONFIGURATION_ERROR
        print(json.dumps(config.safe_summary(), ensure_ascii=False))
        return 0
    if not 0 <= args.port <= 65535:
        parser.error("端口必须在 0 到 65535 之间")
    uvicorn.run("agentic_ai.api.app:create_app", factory=True, host=args.host, port=args.port)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
