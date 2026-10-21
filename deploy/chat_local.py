"""已获准 deepseek-flash 的本机出口及 worker；账本只初始化一次，不隐式启动。"""

from __future__ import annotations

import json
import os
import secrets
import signal
import socket
import stat
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

import checkpoint_local
import local

MODEL = "deepseek-flash"
VERSION = "1.106.0"
PORT = 28089
PROXY_ROOT = local.ROOT / "deploy/model-proxy"
PROXY_PYTHON = PROXY_ROOT / ".venv/bin/python"
AI_PYTHON = local.ROOT / "ai-runtime/.venv/bin/python"
SECRETS = local.STATE / "model-proxy.json"
CHAT = local.STATE / "chat.json"
WORKER = local.STATE / "qa-worker.json"
LEDGER = local.STATE / "chat-budget-20261020.json"
PRICING_DATE = "2026-10-21"
PRICING_REFERENCE = "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/"
FILES = (LEDGER, SECRETS, CHAT, WORKER)
SERVICES = {"model-proxy": PROXY_PYTHON, "qa-worker": AI_PYTHON}


def read_private(path: Path) -> dict:
    """拒绝符号链接、宽权限和异常内容，不将秘密放入错误消息。"""
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(descriptor, "r") as source:
            meta = os.fstat(source.fileno())
            if not stat.S_ISREG(meta.st_mode) or meta.st_mode & 0o077:
                raise ValueError
            value = json.loads(source.read(256 * 1024 + 1))
            if not isinstance(value, dict):
                raise ValueError
            return value
    except (OSError, ValueError):
        raise RuntimeError("聊天私密配置缺失、损坏或权限错误，未重建或输出内容") from None


def _create(path: Path, value: dict) -> None:
    """排他创建并 fsync；任何已存在文件均不覆盖，防止重跑重置已消费额度。"""
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as target:
        json.dump(value, target, ensure_ascii=False, indent=2)
        target.flush()
        os.fsync(target.fileno())


def init() -> None:
    """仅首次迁入显式环境中的账号；配置、已发生的预留和恢复记录都保留。"""
    if any(path.exists() or path.is_symlink() for path in FILES):
        for path in FILES:
            read_private(path)
        check_worker()
        print("聊天配置已存在并通过预检；凭据、价格和累计账本未改写。")
        return
    key = os.environ.get("DEEPSEEK_API_KEY", "")
    if not key or len(key) > 4096 or any(not 33 <= ord(char) <= 126 for char in key):
        raise RuntimeError("init-chat 需要获授权的 DEEPSEEK_API_KEY 环境变量")
    for name in ("task-worker-private.pem", "runtime-knowledge-private.pem"):
        path = local.STATE / name
        if not path.is_file() or path.is_symlink() or path.stat().st_mode & 0o077:
            raise RuntimeError("问答服务身份缺失或权限错误，未创建聊天账本")
    dsn = checkpoint_local.dsn("dev")
    proxy_key = "sk-" + secrets.token_hex(32)
    local.STATE.mkdir(mode=0o700, exist_ok=True)
    _create(
        LEDGER,
        {
            "version": 1,
            "scope": "chat-integration-20261020",
            "currency": "CNY",
            "limitCny": "1",
            "reservedCny": "0",
            "calls": [],
        },
    )
    _create(
        SECRETS, {"version": 1, "approvedModel": MODEL, "providerKey": key, "proxyKey": proxy_key}
    )
    _create(
        CHAT,
        {
            "base_url": f"http://127.0.0.1:{PORT}/v1",
            "api_key": proxy_key,
            "approved_model": MODEL,
            "budget_file": str(LEDGER),
            "budget_limit_cny": "1",
            "input_price_cny_per_million": "2",
            "output_price_cny_per_million": "8",
            "pricing_checked_at": PRICING_DATE,
            "pricing_reference": PRICING_REFERENCE,
        },
    )
    _create(
        WORKER,
        {
            "tenant_id": "local-dev",
            "tasks_endpoint": "http://127.0.0.1:28087",
            "knowledge_endpoint": "http://127.0.0.1:28084",
            "task_private_key": str(local.STATE / "task-worker-private.pem"),
            "knowledge_private_key": str(local.STATE / "runtime-knowledge-private.pem"),
            "chat_config": str(CHAT),
            "checkpoints": {"dsn": dsn},
        },
    )
    check_worker()
    print("deepseek-flash 私密配置与独立 1 元累计账本已建立；尚未调用模型。")


def environment(*, proxy: bool) -> dict[str, str]:
    """不继承其他模型密钥、追踪、代理或云账号；两个进程仅获得各自必需配置。"""
    env = {key: os.environ[key] for key in ("PATH", "HOME", "TMPDIR", "LANG") if key in os.environ}
    env.update(
        LANGSMITH_TRACING="false",
        LANGCHAIN_TRACING_V2="false",
        OTEL_SDK_DISABLED="true",
        DO_NOT_TRACK="1",
        PYTHONUNBUFFERED="1",
    )
    if proxy:
        value = read_private(SECRETS)
        if value.get("version") != 1 or value.get("approvedModel") != MODEL:
            raise RuntimeError("代理配置归属不一致，未启动")
        env.update(
            DEEPSEEK_API_KEY=value["providerKey"],
            LITELLM_MASTER_KEY=value["proxyKey"],
            LITELLM_LOG="ERROR",
            LITELLM_LOCAL_MODEL_COST_MAP="true",
            DISABLE_ADMIN_UI="true",
            PYTHONPATH=str(PROXY_ROOT),
        )
    return env


def check_worker() -> None:
    """真实 PG/身份/累计账本预检不领取任务，也不调用付费模型。"""
    local.run(
        [str(AI_PYTHON), "-m", "agentic_ai.cli", "check-qa-worker", "--config", str(WORKER)],
        env=environment(proxy=False),
    )


def alive(service: str) -> int | None:
    """同时核对项目专属解释器和命令，防止 PID 复用后影响其他服务。"""
    path = local.STATE / f"{service}.pid"
    if not path.exists():
        return None
    pid = int(path.read_text())
    process = subprocess.run(
        ["ps", "-p", str(pid), "-o", "command="], capture_output=True, text=True
    )
    marker = "litellm.proxy.proxy_cli" if service == "model-proxy" else "agentic_ai.cli qa-worker"
    return (
        pid
        if (
            process.returncode == 0
            and str(SERVICES[service]) in process.stdout
            and marker in process.stdout
        )
        else None
    )


def ready() -> None:
    """仅查询受保护的模型清单；不使用会真实生成内容的 /health 端点。"""
    value = read_private(SECRETS)
    request = urllib.request.Request(
        f"http://127.0.0.1:{PORT}/v1/models",
        headers={"Authorization": "Bearer " + value["proxyKey"]},
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=3) as response:
        data = json.load(response)
    if {model["id"] for model in data["data"]} != {"chat-default"}:
        raise RuntimeError("聊天出口路由与已批准配置不符")


def start(service: str) -> None:
    """启动已锁定制品；模型路由先就绪，显式启动 worker 才能消费 local-dev 问题。"""
    if alive(service):
        print(f"{service} 已运行")
        return
    if service == "model-proxy":
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(("127.0.0.1", PORT))
        version = local.run(
            [
                str(PROXY_PYTHON),
                "-c",
                "import importlib.metadata; print(importlib.metadata.version('litellm'))",
            ]
        )
        if version.decode().strip() != VERSION:
            raise RuntimeError("LiteLLM 版本与锁定制品不一致")
        command = [
            str(PROXY_PYTHON),
            "-m",
            "litellm.proxy.proxy_cli",
            "--config",
            str(PROXY_ROOT / "config.yaml"),
            "--host",
            "127.0.0.1",
            "--port",
            str(PORT),
            "--log_config",
            str(PROXY_ROOT / "logging.json"),
        ]
    else:
        check_worker()
        ready()
        command = [str(AI_PYTHON), "-m", "agentic_ai.cli", "qa-worker", "--config", str(WORKER)]
    local.LOGS.mkdir(exist_ok=True)
    descriptor = os.open(
        local.LOGS / f"{service}.log", os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600
    )
    with os.fdopen(descriptor, "ab") as log:
        process = subprocess.Popen(
            command,
            cwd=PROXY_ROOT if service == "model-proxy" else local.ROOT,
            env=environment(proxy=service == "model-proxy"),
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    local.private(local.STATE / f"{service}.pid", str(process.pid))
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"{service} 启动退出，请核对本项目日志；未重启或重建账本")
        if service == "qa-worker":
            time.sleep(1)
            if process.poll() is None:
                print("问答 worker 已启动；固定 local-dev，累计额度仍受独立账本约束。")
                return
        else:
            try:
                ready()
                print(f"独立 LiteLLM 已就绪：127.0.0.1:{PORT}；尚未以此证明模型可用。")
                return
            except (OSError, urllib.error.URLError):
                time.sleep(0.2)
    raise RuntimeError(f"{service} 就绪超时，保留 PID 和日志，未强杀")


def stop(service: str) -> None:
    """仅正常停止本项目进程，保留任务、检查点、凭据和累计预算。"""
    if service == "model-proxy" and alive("qa-worker"):
        raise RuntimeError("请先 stop-qa，再停止模型出口，避免中断在途任务")
    pid = alive(service)
    if pid:
        os.kill(pid, signal.SIGTERM)
        deadline = time.monotonic() + 30
        while alive(service) and time.monotonic() < deadline:
            time.sleep(0.2)
        if alive(service):
            raise RuntimeError(f"{service} 尚未退出，未强制终止")
    print(f"{service} 已停止，累计费用与恢复记录保留。")
