"""本地运行与配置预检入口；预检本身不发生外部模型调用。"""

import argparse
import json
import sys
from collections.abc import Sequence

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
    args = parser.parse_args(argv)
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
